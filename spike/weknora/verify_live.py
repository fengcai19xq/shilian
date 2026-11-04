"""H1/H2/H3 现场验证脚本 —— 需要一个真实运行的 WeKnora 实例。

用法：
    export WEKNORA_BASE_URL=http://<weknora-host>:8080
    export WEKNORA_API_KEY=<空间级 API Key，需 retrieve + ingest + chat capability>
    export WEKNORA_TENANT_ID=<可选，多空间时指定>
    python3 spike/weknora/verify_live.py

本脚本**只读为主**：H1 会创建一个临时 KB 与两个临时文档用于验证过滤语义，
验证结束后自动清理（--keep 可保留现场排障）。不触碰任何已有知识库。

三个假设对应 DESIGN-REVIEW.md §10 的前三项风险：
    H1  tag_ids 在真实检索引擎上是 SQL 级下推，而非取回后应用层筛选
    H2  流式（SSE）问答可用，且 shim 的 501 是唯一阻塞点
    H3  外部用户主体令牌（X-External-User-Token）可承载 shilian 的会话身份
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid
from typing import Any

import httpx

BASE = os.environ.get("WEKNORA_BASE_URL", "http://localhost:8080").rstrip("/")
API_KEY = os.environ.get("WEKNORA_API_KEY", "")
TENANT_ID = os.environ.get("WEKNORA_TENANT_ID", "")

PASS = "\033[32m✓ PASS\033[0m"
FAIL = "\033[31m✗ FAIL\033[0m"
SKIP = "\033[33m- SKIP\033[0m"
INFO = "\033[36m·\033[0m"

results: list[tuple[str, str, str]] = []


def record(hypothesis: str, ok: bool | None, detail: str) -> None:
    mark = PASS if ok else FAIL if ok is False else SKIP
    results.append((hypothesis, mark, detail))
    print(f"{mark} {hypothesis}\n     {detail}")


def headers(extra: dict[str, str] | None = None) -> dict[str, str]:
    h = {"X-API-Key": API_KEY}
    if TENANT_ID:
        h["X-Tenant-ID"] = TENANT_ID
    if extra:
        h.update(extra)
    return h


def unwrap(resp: httpx.Response) -> Any:
    if resp.status_code >= 400:
        raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:400]}")
    data = resp.json()
    return data.get("data", data) if isinstance(data, dict) else data


def api(method: str, path: str, **kw: Any) -> Any:
    resp = httpx.request(method, BASE + path, headers=headers(), timeout=120.0, **kw)
    return unwrap(resp)


def resolve_embedding_model() -> str:
    """取空间内已配通的 Embedding 模型 id。

    建库时必须显式绑定向量模型，否则解析阶段直接失败：
    `failed to get embedding model: model ID cannot be empty`（2026-11-04 现场实测）。
    注意不要走 /initialization/initialize —— 该接口字段校验严格，
    且会把已有配置覆盖成空，实测反而更容易踩坑。
    """
    rows = api("GET", "/api/v1/models")
    rows = rows if isinstance(rows, list) else rows.get("data", [])
    for m in rows or []:
        if m.get("type") == "Embedding" and m.get("status") == "active":
            return str(m["id"])
    raise RuntimeError(
        "空间内没有 status=active 的 Embedding 模型。"
        "请先在 WeKnora 里配好 embedding 模型（TEI / OpenAI 兼容均可）再重跑。"
    )


# ---------- 测试数据：同一句查询词，靠 tag 区分可见性 ----------

SECRET_MARKER = f"MARKER-{uuid.uuid4().hex[:8]}"
NEEDLE = f"年度授信额度审批流程说明 {SECRET_MARKER}"


def make_doc(tag_name: str) -> bytes:
    """生成一个极小的 txt 文档，内容含唯一标记，便于确认召回来源。"""
    body = (
        f"# {tag_name}\n\n"
        f"{NEEDLE}\n\n"
        f"本文档用于验证 tag_ids 过滤是否真的下推到检索引擎。标记：{tag_name}\n"
    )
    return body.encode("utf-8")


# ---------- H1 ----------


def verify_h1(keep: bool) -> None:
    """tag_ids 是否为引擎级下推过滤。

    判定方法（关键）：两个文档内容几乎相同、都含同一查询词，只有 tag 不同。
    若 tag_ids 是引擎级下推 -> 只召回被授权 tag 的那一篇；
    若是取回后应用层筛选 -> 两篇都会先被召回，但返回体里仍只有一篇，
    **无法区分**。因此必须加一步：请求一个「两篇都不该命中」的 tag，
    观察是否返回空。若过滤是取回后再筛，rerank 的 candidate_count 会暴露真实召回量。
    """
    print(f"\n{INFO} H1：验证 tag_ids 过滤下推\n")
    kb_id = tag_a = tag_b = doc_a = doc_b = None
    try:
        emb_id = resolve_embedding_model()
        kb = api("POST", "/api/v1/knowledge-bases", json={
            "name": f"spike-h1-{uuid.uuid4().hex[:8]}",
            "description": "shilian 融合 spike：tag 过滤下推验证（临时库，自动清理）",
            "type": "document",
            "embedding_model_id": emb_id,
        })
        kb_id = kb["id"] if isinstance(kb, dict) else kb
        print(f"{INFO}   临时 KB: {kb_id}  embedding={emb_id[:8]}")

        # 建两个标签：一个模拟"授权项目"，一个模拟"越权项目"
        tag_a = api("POST", f"/api/v1/knowledge-bases/{kb_id}/tags",
                    json={"name": "project::prj-authorized"})
        tag_b = api("POST", f"/api/v1/knowledge-bases/{kb_id}/tags",
                    json={"name": "project::prj-forbidden"})
        tag_a_id = tag_a["id"] if isinstance(tag_a, dict) else tag_a
        tag_b_id = tag_b["id"] if isinstance(tag_b, dict) else tag_b
        print(f"{INFO}   标签: authorized={tag_a_id} forbidden={tag_b_id}")

        # 上传两篇内容相同、标签不同的文档
        # tag_ids 必须是**裸 id 字符串**：传 JSON 数组字符串会被当成一个不存在的
        # 标签名，服务端直接 HTTP 400（2026-11-04 现场实测）。多标签用重复字段。
        doc_a = api("POST", f"/api/v1/knowledge-bases/{kb_id}/knowledge/file",
                    files={"file": ("authorized.txt", make_doc("授权项目"), "text/plain")},
                    data={"tag_ids": tag_a_id})
        doc_b = api("POST", f"/api/v1/knowledge-bases/{kb_id}/knowledge/file",
                    files={"file": ("forbidden.txt", make_doc("越权项目"), "text/plain")},
                    data={"tag_ids": tag_b_id})
        doc_a_id = doc_a["id"] if isinstance(doc_a, dict) else doc_a
        doc_b_id = doc_b["id"] if isinstance(doc_b, dict) else doc_b

        # 等解析完成（最多 180s）；任一文档 failed 立刻带上失败原因退出，不白等
        deadline = time.time() + 180
        states: dict[str, Any] = {}
        errors: dict[str, Any] = {}
        while time.time() < deadline:
            items = api("GET", f"/api/v1/knowledge-bases/{kb_id}/knowledge")
            rows = items if isinstance(items, list) else items.get("knowledge", [])
            states = {r.get("id"): r.get("parse_status") for r in rows}
            errors = {r.get("id"): r.get("error_message") for r in rows}
            if states.get(doc_a_id) == "failed" or states.get(doc_b_id) == "failed":
                reason = errors.get(doc_a_id) or errors.get(doc_b_id)
                record("H1", None, f"文档解析失败：{reason}（多为 KB 未绑定向量模型或 docreader 异常）")
                return
            if states.get(doc_a_id) == "completed" and states.get(doc_b_id) == "completed":
                break
            time.sleep(5)
        else:
            record("H1", None, "文档解析未在 180s 内完成，无法判定。请检查 docreader 日志后重跑。")
            return

        query = {"query": "年度授信额度审批流程", "knowledge_base_ids": [kb_id]}

        # 基线：不带 tag 过滤，两篇都应召回
        base = api("POST", "/api/v1/knowledge-search", json={**query, "match_count": 10})
        base_ids = {r.get("knowledge_id") for r in (base or [])}
        print(f"{INFO}   基线（无 tag 过滤）召回 {len(base_ids)} 个文档")
        if not {doc_a_id, doc_b_id} <= base_ids:
            record("H1", None, f"基线未同时召回两篇（召回={base_ids}），"
                               "可能是 embedding 未配置或解析产物为空，无法判定过滤语义。")
            return

        # 关键：只授权 tag_a
        filtered = api("POST", "/api/v1/knowledge-search",
                       json={**query, "tag_ids": [tag_a_id], "match_count": 10})
        got = {r.get("knowledge_id") for r in (filtered or [])}
        meta = (filtered or {}).get("meta", {}) if isinstance(filtered, dict) else {}

        # 关键：授权一个谁都没有的 tag，应返回空
        empty_tag = api("POST", f"/api/v1/knowledge-bases/{kb_id}/tags",
                        json={"name": "project::nonexistent"})
        empty_tag_id = empty_tag["id"] if isinstance(empty_tag, dict) else empty_tag
        none_hits = api("POST", "/api/v1/knowledge-search",
                        json={**query, "tag_ids": [empty_tag_id], "match_count": 10})

        ok = got == {doc_a_id} and not none_hits
        detail = (
            f"授权 tag 后召回={sorted(got)}（期望仅 authorized 一篇）；"
            f"无匹配 tag 时召回 {len(none_hits or [])} 篇（期望 0）；"
            f"meta.rerank={json.dumps(meta.get('rerank', {}), ensure_ascii=False)[:200]}"
        )
        record("H1 tag_ids 是引擎级下推过滤", ok, detail)
        if not ok:
            print(f"\n{INFO}   ⚠ H1 未通过 → 按 DESIGN-REVIEW.md §10 走备选："
                  "dept 也升格为 KB 维度，或向上游提 PR 增加过滤能力。")

    except Exception as exc:  # noqa: BLE001 - spike 脚本，需要看到任何异常
        record("H1", None, f"执行异常：{type(exc).__name__}: {exc}")
    finally:
        if not keep and kb_id:
            try:
                httpx.delete(f"{BASE}/api/v1/knowledge-bases/{kb_id}",
                             headers=headers(), timeout=60.0)
                print(f"{INFO}   已清理临时 KB {kb_id}")
            except Exception as exc:  # noqa: BLE001
                print(f"{INFO}   清理失败（请手动删除 KB {kb_id}）：{exc}")


# ---------- H2 ----------


def verify_h2() -> None:
    """流式问答是否可用。若可用，shim 的流式支持就是唯一待补项。"""
    print(f"\n{INFO} H2：验证 SSE 流式问答\n")
    session_id = None
    try:
        kbs = api("GET", "/api/v1/knowledge-bases")
        rows = kbs if isinstance(kbs, list) else kbs.get("knowledge_bases", kbs.get("data", []))
        if not rows:
            record("H2", None, "空间内没有知识库，跳过。请先建库并上传至少一篇文档。")
            return
        # 挑一个确实有已解析文档的库：盲选 rows[0] 会撞上空库或解析失败的库，
        # 那种情况下问答必然无引用，属于验证脚本自身的假阴性。
        kb_id = None
        for cand in rows:
            cid = cand["id"]
            docs = api("GET", f"/api/v1/knowledge-bases/{cid}/knowledge")
            docs = docs if isinstance(docs, list) else (docs or {}).get("knowledge", [])
            if any(d.get("parse_status") == "completed" for d in docs):
                kb_id = cid
                break
        if not kb_id:
            record("H2", None, "空间内没有任何含已解析文档的知识库，跳过。先跑 H1 或手动上传文档。")
            return

        session = api("POST", "/api/v1/sessions", json={"title": f"spike-h2-{uuid.uuid4().hex[:6]}"})
        session_id = session["id"] if isinstance(session, dict) else session

        events: list[str] = []
        answer_chars = 0
        with httpx.stream(
            "POST",
            f"{BASE}/api/v1/knowledge-chat/{session_id}",
            headers=headers(),
            json={"query": "这份文档讲了什么？", "knowledge_base_ids": [kb_id]},
            timeout=180.0,
        ) as resp:
            if resp.status_code >= 400:
                record("H2", False, f"HTTP {resp.status_code}：{resp.read()[:300].decode('utf-8', 'ignore')}")
                return
            for line in resp.iter_lines():
                if not line or not line.startswith("data:"):
                    continue
                try:
                    payload = json.loads(line[5:].strip())
                except json.JSONDecodeError:
                    continue
                rtype = payload.get("response_type")
                if rtype:
                    events.append(rtype)
                if rtype == "answer":
                    answer_chars += len(payload.get("content") or "")
                if rtype in ("complete", "error"):
                    break

        uniq = list(dict.fromkeys(events))
        ok = "answer" in uniq and "complete" in uniq and answer_chars > 0
        record(
            "H2 SSE 流式问答可用",
            ok,
            f"事件序列={uniq}；answer 累计 {answer_chars} 字符。"
            + ("" if ok else " → 若失败，shim 的 501 就不是唯一阻塞点，需重排 P1。"),
        )
    except Exception as exc:  # noqa: BLE001
        record("H2", None, f"执行异常：{type(exc).__name__}: {exc}")
    finally:
        if session_id:
            try:
                httpx.delete(f"{BASE}/api/v1/sessions/{session_id}", headers=headers(), timeout=30.0)
            except Exception:  # noqa: BLE001
                pass


# ---------- H3 ----------


def make_external_token(secret: str, tenant_id: str, user_id: str, ttl: int = 600) -> str:
    """按上游约定签一个 HS256 外部主体令牌：aud=weknora、exp、tenant_id、sub。"""
    now = int(time.time())
    header = base64.urlsafe_b64encode(json.dumps(
        {"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode()).rstrip(b"=")
    claims = {
        "aud": "weknora",
        "sub": user_id,
        "tenant_id": int(tenant_id) if str(tenant_id).isdigit() else tenant_id,
        "iat": now,
        "exp": now + ttl,
    }
    body = base64.urlsafe_b64encode(json.dumps(claims, separators=(",", ":")).encode()).rstrip(b"=")
    signing_input = header + b"." + body
    sig = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), signing_input, hashlib.sha256).digest()
    ).rstrip(b"=")
    return (signing_input + b"." + sig).decode()


def _unsigned_token(tenant_id: str, user_id: str) -> str:
    """alg=none 的裸令牌：签名段留空。合规实现必须拒绝（HTTP 401）。"""
    body = base64.urlsafe_b64encode(json.dumps({
        "alg": "none", "typ": "JWT"}, separators=(",", ":")).encode()).rstrip(b"=")
    claims = base64.urlsafe_b64encode(json.dumps({
        "aud": "weknora", "sub": user_id,
        "tenant_id": int(tenant_id) if str(tenant_id).isdigit() else tenant_id,
        "iat": int(time.time()), "exp": int(time.time()) + 600,
    }, separators=(",", ":")).encode()).rstrip(b"=")
    return f"{body.decode()}.{claims.decode()}."


def verify_h3() -> None:
    """外部用户主体令牌能否承载 shilian 的会话身份（替代 X-User-Id 注入）。

    需要空间侧先开启 api-principal-config 的 signed_token 模式并配置共享密钥。
    未配置时本项 SKIP，不视为失败。

    **判据必须含反向对照**（2026-11-04 现场实测教训）：
    `X-External-User-Token` 在 mode=tenant 下被整个忽略——伪造签名也返回 200。
    因此「合法令牌拿到 200」本身不构成通过，只有伪造/过期/跨租户全部拿到 401
    才说明服务端真的在做密码学校验。
    """
    print(f"\n{INFO} H3：验证外部用户主体令牌\n")
    secret = os.environ.get("WEKNORA_EXTERNAL_USER_SECRET", "")
    if not secret or not TENANT_ID:
        record("H3", None,
               "未配置 WEKNORA_EXTERNAL_USER_SECRET / WEKNORA_TENANT_ID。"
               "需先在空间设置里开启 api-principal-config 的 signed_token 模式，"
               "再带这两个环境变量重跑。")
        return
    try:
        cases = {
            "合法令牌": make_external_token(secret, TENANT_ID, "u_10231"),
            "伪造签名": make_external_token("definitely-wrong-secret", TENANT_ID, "u_10231"),
            "过期令牌": make_external_token(secret, TENANT_ID, "u_10231", ttl=-1200),
            "跨租户": make_external_token(secret, "999999", "u_10231"),
            "alg=none": _unsigned_token(TENANT_ID, "u_10231"),
            "垃圾串": "not.a.jwt",
        }
        seen: dict[str, int] = {}
        for label, tok in cases.items():
            resp = httpx.get(
                f"{BASE}/api/v1/knowledge-bases",
                headers=headers({"X-External-User-Token": tok}),
                timeout=30.0,
            )
            seen[label] = resp.status_code
            print(f"{INFO}   {label:8s} -> HTTP {resp.status_code}")

        accepted = seen.get("合法令牌") == 200
        rejected = all(seen[k] in (401, 403) for k in
                       ("伪造签名", "过期令牌", "跨租户", "alg=none", "垃圾串"))
        if accepted and rejected:
            record("H3 外部主体令牌可承载会话身份", True,
                   "合法令牌 200 且五种伪造全部 401/403，说明服务端在密码学校验该令牌。"
                   "shilian 可用它把 user_id 透传给 WeKnora，替代 X-User-Id 注入，审计口径可对齐。"
                   "注意：WeKnora 只验签不做用户级授权（不同 sub 看到的库相同），"
                   "权限判定仍必须由 shilian 负责。")
        elif not accepted:
            record("H3", False,
                   f"合法令牌被拒 HTTP {seen.get('合法令牌')} → 检查空间是否开启 signed_token 模式、"
                   f"密钥是否一致、tenant_id claim 是否匹配。")
        else:
            leak = [k for k, v in seen.items()
                    if k != "合法令牌" and v not in (401, 403)]
            record("H3", False,
                   f"合法令牌 200，但 {leak} 未被拒绝（状态码 "
                   f"{[seen[k] for k in leak]}）→ header 被服务端忽略或校验形同虚设，"
                   "不能用于承载身份。")
    except Exception as exc:  # noqa: BLE001
        record("H3", None, f"执行异常：{type(exc).__name__}: {exc}")


def main() -> int:
    parser = argparse.ArgumentParser(description="WeKnora 融合现场验证")
    parser.add_argument("--keep", action="store_true", help="保留 H1 的临时 KB 以便排障")
    parser.add_argument("--only", choices=["h1", "h2", "h3"], help="只跑指定假设")
    args = parser.parse_args()

    if not API_KEY:
        print("缺少 WEKNORA_API_KEY。请在空间设置里创建 API Key（需 retrieve+ingest+chat）。")
        return 2
    if not BASE.startswith("http"):
        print("WEKNORA_BASE_URL 非法。")
        return 2

    print(f"目标：{BASE}")
    try:
        health = httpx.get(f"{BASE}/health", timeout=10.0)
        print(f"健康检查：HTTP {health.status_code} {health.text[:80]}")
    except Exception as exc:  # noqa: BLE001
        print(f"无法连接 WeKnora 实例：{exc}")
        return 2

    if args.only in (None, "h1"):
        verify_h1(args.keep)
    if args.only in (None, "h2"):
        verify_h2()
    if args.only in (None, "h3"):
        verify_h3()

    print("\n" + "=" * 72)
    print("汇总")
    print("=" * 72)
    for hypothesis, mark, _ in results:
        print(f"{mark} {hypothesis}")
    failed = [r for r in results if "FAIL" in r[1]]
    skipped = [r for r in results if "SKIP" in r[1]]
    print(f"\n通过 {len(results) - len(failed) - len(skipped)} / 失败 {len(failed)} / 跳过 {len(skipped)}")
    if failed:
        print("\n存在 FAIL：按 DESIGN-REVIEW.md §10 的缓解路径处理后再启动 P1。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
