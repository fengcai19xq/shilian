"""核对 Java 测试里的每个期望串是否与 Python 实现一致。"""
import sys

sys.path.insert(0, "/Users/qianxu/Documents/git/github/shilian/services/retrieval-proxy/src")

from retrieval_proxy.kb_encoding import slug, kb_name, project_tag

checks = {
    "slug('融资部')": (slug("融资部"), "878d8d4490e8"),
    "slug('风控部')": (slug("风控部"), "98ce63a790e8"),
    "slug('default')": (slug("default"), "default"),
    "slug('Risk_Control')": (slug("Risk_Control"), "risk_control"),
    "slug('财务 共享')": (slug("财务 共享"), "8d2252a12051714eab"),
    "kb(acme,L1,融资部)": (kb_name("acme", "L1", "融资部"), "sl-acme-L1-878d8d4490e8"),
    "kb(acme,L4,风控部)": (kb_name("acme", "L4", "风控部"), "sl-acme-L4-98ce63a790e8"),
    "kb(default,L2,融资部)": (kb_name("default", "L2", "融资部"), "sl-default-L2-878d8d4490e8"),
    "tag(None)": (project_tag(None), "project::__none__"),
    "tag(blank)": (project_tag("   "), "project::__none__"),
    "tag(PRJ-DEMO-A)": (project_tag("PRJ-DEMO-A"),
                        "project::p00500052004a002d00440045004d004f002d0041"),
    "tag(PRJ-2026-CITIC)": (project_tag("PRJ-2026-CITIC"),
                            "project::p00500052004a002d0032003000320036002d00430049005400490043"),
    "tag(宁波-授信)": (project_tag("宁波-授信"), "project::p5b816ce2002d63884fe1"),
}

bad = 0
for label, (actual, expected) in checks.items():
    ok = actual == expected
    if not ok:
        bad += 1
        print(f"MISMATCH {label}\n   java_expects: {expected}\n   python_actual: {actual}")
print("ALL MATCH" if bad == 0 else f"{bad} mismatches to fix")
