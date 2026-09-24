#!/usr/bin/env python3
"""由 tasks.json 生成单文件进度看板 docs/progress.html。

用法：python3 scripts/build_board.py
看板内的状态改动保存在浏览器 localStorage，可点「导出 tasks.json」回写仓库。
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = json.loads((ROOT / "tasks.json").read_text(encoding="utf-8"))

TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>__PROJECT__ · 开发进度看板</title>
<style>
*{box-sizing:border-box}
body{margin:0;font:14px/1.7 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;color:#1b2330;background:#f4f6fa}
.wrap{max-width:1180px;margin:0 auto;padding:28px 20px 60px}
header{background:linear-gradient(120deg,#1b2a6b,#3d6fff);color:#fff;border-radius:14px;padding:26px 28px}
header h1{margin:0 0 6px;font-size:24px}
header p{margin:0;opacity:.88;font-size:13px}
.kpis{display:flex;gap:14px;flex-wrap:wrap;margin:18px 0 6px}
.kpi{flex:1;min-width:150px;background:#fff;border:1px solid #e6ebf2;border-radius:12px;padding:14px 16px}
.kpi b{display:block;font-size:26px;line-height:1.2}
.kpi span{color:#6b7a8d;font-size:12px}
h2{font-size:17px;margin:30px 0 10px;padding-bottom:8px;border-bottom:2px solid #3d6fff}
.phase{background:#fff;border:1px solid #e6ebf2;border-radius:12px;margin-bottom:14px;overflow:hidden}
.ph-head{display:flex;align-items:center;gap:12px;padding:12px 16px;background:#fafbfe;border-bottom:1px solid #eef1f6}
.ph-head b{font-size:15px}
.ph-head small{color:#6b7a8d}
.bar{flex:1;height:8px;background:#eef1f6;border-radius:6px;overflow:hidden;min-width:100px}
.bar i{display:block;height:100%;background:#12a150;width:0}
table{width:100%;border-collapse:collapse;font-size:13px}
th,td{padding:9px 12px;border-bottom:1px solid #eef1f6;text-align:left;vertical-align:top}
th{background:#fafbfe;color:#6b7a8d;font-weight:600;font-size:12px}
td.id{font-family:ui-monospace,Menlo,monospace;color:#6b7a8d;white-space:nowrap}
.mod{display:inline-block;padding:1px 7px;border-radius:5px;background:#eef3ff;color:#2450d8;font-size:11px}
.dep{color:#6b7a8d;font-size:12px}
.acc{color:#6b7a8d;font-size:12px}
select{font:inherit;font-size:12px;padding:3px 6px;border:1px solid #dfe5ee;border-radius:6px;background:#fff}
tr[data-st="done"]{background:#f4fbf6}
tr[data-st="blocked"]{background:#fdf5f3}
tr[data-st="doing"]{background:#fffaf2}
.legend{color:#6b7a8d;font-size:12px;margin:6px 0 0}
.tools{display:flex;gap:10px;margin:14px 0}
button{font:inherit;font-size:13px;padding:7px 14px;border:1px solid #dfe5ee;background:#fff;border-radius:8px;cursor:pointer}
button.pri{background:#3d6fff;color:#fff;border-color:#3d6fff}
.budget{background:#fff8ec;border:1px solid #f4dcae;border-radius:12px;padding:16px 18px}
.budget b{color:#8a5a06}
pre{background:#0f1729;color:#d7e3ff;padding:14px;border-radius:10px;overflow:auto;font-size:12px}
footer{margin-top:34px;color:#6b7a8d;font-size:12px;text-align:center}
@media(max-width:760px){.acc,.dep{display:none}}
</style>
</head>
<body>
<div class="wrap">
<header>
  <h1>__PROJECT__ · 开发进度看板</h1>
  <p>数据源 <code style="color:#cfe0ff">tasks.json</code> ｜ 状态改动实时存本地，可导出回写仓库 ｜ 基线日期 __UPDATED__</p>
</header>

<div class="kpis" id="kpis"></div>
<p class="legend">状态：未开始 / 进行中 / 阻塞 / 已完成 —— 直接在表格里改，自动重算进度并保存。</p>

<div class="tools">
  <button class="pri" id="exp">导出 tasks.json</button>
  <button id="rst">重置为基线</button>
</div>

<div id="phases"></div>

<h2>Token 与成本控制</h2>
<div class="budget">
  <p style="margin:0 0 8px"><b>月度上限 __CAP__ 元，达 __ALERT__% 告警。</b>__BUDGET_NOTE__</p>
  <p style="margin:0;color:#6b7a8d;font-size:13px">控量的四个动作：① 路由分级——判定类走 Jev / 本地小模型，只有生成答案才用大模型；② 检索先探测后精排，top_k 小；③ 相同问题与相同文档打标结果做缓存；④ 网关按部门出账，超额自动降级而不是静默超支。</p>
</div>

<h2>子会话并行开发的模块边界</h2>
<pre>services/retrieval-proxy   权限过滤 + RAGFlow 检索转发（T04 T06 T16）
services/matcher           清单解析与三态匹配（T10）
services/packager          成册执行器，纯函数（T11）
services/gateway           模型网关 / 企微 / NL2SQL（T05 T09 T12 T15）
services/decide            decide() 路由与 Jev 接入（T08 T14）
web                        PC 三栏工作台（T07 T13）
contracts                  跨模块接口契约，改动需全员评审</pre>
<p class="legend">并行开发约定：每个子会话只读写自己模块目录 + contracts（只读），接口以 contracts 为准，不跨模块改代码。这样既避免冲突，也把每个会话的上下文压到最小。</p>

<footer>__PROJECT__ · 进度看板由 scripts/build_board.py 生成</footer>
</div>

<script>
var BASE = __DATA__;
var LABEL = {todo:"未开始", doing:"进行中", blocked:"阻塞", done:"已完成"};
var KEY = "shilian-board-v1";

function load(){
  var o = {};
  try { o = JSON.parse(localStorage.getItem(KEY) || "{}"); } catch(e){ o = {}; }
  return BASE.tasks.map(function(t){
    return Object.assign({}, t, {status: o[t.id] || t.status});
  });
}
function save(id, st){
  var o = {};
  try { o = JSON.parse(localStorage.getItem(KEY) || "{}"); } catch(e){ o = {}; }
  o[id] = st;
  localStorage.setItem(KEY, JSON.stringify(o));
}

function render(){
  var tasks = load();
  var done = tasks.filter(function(t){return t.status === "done"}).length;
  var doing = tasks.filter(function(t){return t.status === "doing"}).length;
  var blocked = tasks.filter(function(t){return t.status === "blocked"}).length;
  var days = tasks.reduce(function(a,t){return a + t.days}, 0);
  var leftDays = tasks.filter(function(t){return t.status !== "done"})
                      .reduce(function(a,t){return a + t.days}, 0);
  document.getElementById("kpis").innerHTML =
    kpi(Math.round(done / tasks.length * 100) + "%", "整体完成度") +
    kpi(done + " / " + tasks.length, "已完成任务") +
    kpi(doing, "进行中") +
    kpi(blocked, "阻塞") +
    kpi(leftDays + " / " + days, "剩余人日 / 总人日");

  var byPhase = {};
  tasks.forEach(function(t){ (byPhase[t.phase] = byPhase[t.phase] || []).push(t); });

  var html = "";
  BASE.phases.forEach(function(p){
    var list = byPhase[p.id] || [];
    var d = list.filter(function(t){return t.status === "done"}).length;
    var pct = list.length ? Math.round(d / list.length * 100) : 0;
    html += '<div class="phase"><div class="ph-head"><b>' + esc(p.name) + '</b>' +
            '<small>' + esc(p.goal) + '</small>' +
            '<span class="bar"><i style="width:' + pct + '%"></i></span>' +
            '<small>' + d + "/" + list.length + '</small></div>' +
            '<table><tr><th>ID</th><th>任务</th><th>模块</th><th>负责</th><th>人日</th>' +
            '<th class="dep">依赖</th><th>状态</th><th class="acc">验收</th></tr>';
    list.forEach(function(t){
      var ready = t.deps.every(function(d2){
        var dep = tasks.filter(function(x){return x.id === d2})[0];
        return !dep || dep.status === "done";
      });
      html += '<tr data-st="' + t.status + '"><td class="id">' + t.id + '</td>' +
        "<td><b>" + esc(t.title) + "</b></td>" +
        '<td><span class="mod">' + esc(t.module) + "</span></td>" +
        "<td>" + esc(t.owner) + "</td><td>" + t.days + "</td>" +
        '<td class="dep">' + (t.deps.length ? t.deps.join(" ") + (ready ? "" : " ⛔") : "—") + "</td>" +
        "<td>" + sel(t) + "</td>" +
        '<td class="acc">' + esc(t.acceptance) + "</td></tr>";
    });
    html += "</table></div>";
  });
  document.getElementById("phases").innerHTML = html;

  Array.prototype.forEach.call(document.querySelectorAll("select[data-id]"), function(s){
    s.addEventListener("change", function(){ save(s.dataset.id, s.value); render(); });
  });
}
function sel(t){
  var h = '<select data-id="' + t.id + '">';
  ["todo","doing","blocked","done"].forEach(function(k){
    h += '<option value="' + k + '"' + (t.status === k ? " selected" : "") + ">" + LABEL[k] + "</option>";
  });
  return h + "</select>";
}
function kpi(v, l){ return '<div class="kpi"><b>' + v + "</b><span>" + l + "</span></div>"; }
function esc(s){ return String(s).replace(/[&<>]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;"}[c]; }); }

document.getElementById("exp").addEventListener("click", function(){
  var out = Object.assign({}, BASE, {tasks: load()});
  var b = new Blob([JSON.stringify(out, null, 2)], {type:"application/json"});
  var a = document.createElement("a");
  a.href = URL.createObjectURL(b); a.download = "tasks.json"; a.click();
});
document.getElementById("rst").addEventListener("click", function(){
  localStorage.removeItem(KEY); render();
});
render();
</script>
</body>
</html>
"""


def main() -> None:
    html = (
        TEMPLATE.replace("__PROJECT__", DATA["project"])
        .replace("__UPDATED__", DATA["updated_at"])
        .replace("__CAP__", f'{DATA["token_budget"]["monthly_cap_cny"]:,}')
        .replace("__ALERT__", str(DATA["token_budget"]["alert_at_pct"]))
        .replace("__BUDGET_NOTE__", DATA["token_budget"]["note"])
        .replace("__DATA__", json.dumps(DATA, ensure_ascii=False))
    )
    out = ROOT / "docs" / "progress.html"
    out.write_text(html, encoding="utf-8")
    print(f"wrote {out} ({len(html)} bytes)")


if __name__ == "__main__":
    main()
