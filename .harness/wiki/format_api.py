# -*- coding: utf-8 -*-
"""把 armada_endpoints.json 渲染成 .harness/wiki/接口协议.md。"""
import argparse
import json
import re
from pathlib import Path


REALM_ORDER = ["public", "tenant"]
REALM_TITLE = {
    "public": ("一、公开 API", "公开路径,通常不需要租户上下文。"),
    "tenant": ("二、租户业务 API", "租户侧业务接口;租户上下文由 armada 后端统一处理。"),
}

CTRL = {
    "TenantAuthController": "租户登录",
    "AccountController": "账号列表",
    "AccountGroupController": "账号分组",
    "AccountImportController": "账号导入",
    "GroupLinkController": "群组列表",
    "GroupLinkImportController": "群链接导入批次",
    "GroupLinkLabelController": "群链接分组",
    "IpProxyController": "IP 代理",
    "JoinTaskController": "进群任务",
    "MarketingTemplateController": "营销模板",
    "MarketingTaskController": "营销任务",
}

MODULE_ORDER = {
    "TenantAuthController": 10,
    "AccountGroupController": 20,
    "AccountController": 21,
    "AccountImportController": 22,
    "IpProxyController": 30,
    "GroupLinkLabelController": 40,
    "GroupLinkController": 41,
    "GroupLinkImportController": 42,
    "JoinTaskController": 50,
    "MarketingTemplateController": 60,
    "MarketingTaskController": 61,
}


def humanize(name):
    s = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name)
    return s[:1].upper() + s[1:]


def esc(s):
    return (s or "").replace("|", "\\|").replace("\n", " ")


def realm_of(controller):
    if any(ep["path"].startswith("/api/public") for ep in controller["endpoints"]):
        return "public"
    return "tenant"


def desc_of(endpoint):
    return endpoint.get("summary") or humanize(endpoint.get("name", ""))


def render_ep(endpoint):
    lines = [f"### {endpoint['method']} {endpoint['path']}", "", esc(desc_of(endpoint)), ""]
    params = endpoint.get("params", [])
    rows = []
    for p in params:
        kind = p["kind"]
        if kind == "path":
            rows.append((p["name"], "path", p["type"], "是", "-", p.get("desc", "")))
        elif kind == "query":
            rows.append((
                p["name"],
                "query",
                p["type"],
                "是" if p.get("required") else "否",
                p["default"] if p.get("default") not in (None, "") else "-",
                p.get("desc", ""),
            ))
        elif kind == "queryobj":
            rows.append((p["name"], "query", p["type"], "否", "-", p.get("desc", "") or "对象字段平铺为查询参数"))
        elif kind == "part":
            rows.append((
                p["name"],
                "part",
                p["type"],
                "是" if p.get("required") else "否",
                p.get("default") if p.get("default") not in (None, "") else "-",
                p.get("desc", "") or "multipart 文件块",
            ))

    if rows:
        lines.append("| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |")
        lines.append("|------|------|------|------|------|------|")
        for name, loc, typ, required, default, desc in rows:
            lines.append(f"| {esc(name)} | {loc} | {esc(typ)} | {required} | {esc(str(default))} | {esc(desc)} |")
        lines.append("")

    perm = endpoint.get("perm")
    lines.append(f"- 权限：`{perm}`" if perm else "- 权限：暂无接口级权限注解")

    bodies = [p for p in params if p["kind"] == "body"]
    if bodies:
        body = bodies[0]
        body_desc = f"（{esc(body.get('desc'))}）" if body.get("desc") else ""
        lines.append(f"- 请求体：`{esc(body['type'])}`{body_desc}")

    retdoc = f"（{esc(endpoint.get('retdoc'))}）" if endpoint.get("retdoc") else ""
    if endpoint.get("response") == "无":
        lines.append("- 响应 `data`：无（仅 `code` / `message`）")
    elif endpoint.get("ret_raw", "").startswith("ApiResponse"):
        lines.append(f"- 响应 `data`：`{esc(endpoint['response'])}`{retdoc}")
    else:
        lines.append(f"- 响应：`{esc(endpoint.get('ret_raw', 'void'))}`{retdoc}")

    lines.append("")
    return "\n".join(lines)


def render(controllers):
    by_realm = {realm: [] for realm in REALM_ORDER}
    for controller in controllers:
        by_realm.setdefault(realm_of(controller), []).append(controller)

    out = [
        "# 接口协议",
        "",
        "> 本文由 `.harness/wiki/parse_endpoints.py` + `.harness/wiki/format_api.py` 从 armada controller 生成。",
        "> 如接口签名或 Javadoc 变化,请重新运行生成脚本。",
        "",
    ]
    for realm in REALM_ORDER:
        controllers_in_realm = by_realm.get(realm, [])
        if not controllers_in_realm:
            continue
        title, note = REALM_TITLE[realm]
        out.append("---")
        out.append("")
        out.append(f"# {title}")
        out.append(f"> {note}")
        out.append("")
        for controller in sorted(
                controllers_in_realm,
                key=lambda c: (MODULE_ORDER.get(c["controller"], 999), CTRL.get(c["controller"], c["controller"])),
        ):
            zh = CTRL.get(controller["controller"]) or humanize(controller["controller"].removesuffix("Controller"))
            out.append(f"## {zh} API（{controller['controller']}）")
            out.append("")
            for endpoint in controller["endpoints"]:
                out.append(render_ep(endpoint))
    return "\n".join(out).rstrip() + "\n"


def main():
    default_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="渲染 armada 接口协议 Markdown")
    parser.add_argument("--input", default=None, help="parse_endpoints.py 输出的 JSON")
    parser.add_argument("--output", default=None, help="接口协议 Markdown 输出路径")
    args = parser.parse_args()

    input_path = Path(args.input).resolve() if args.input else default_root / ".harness" / "wiki" / "armada_endpoints.json"
    output_path = Path(args.output).resolve() if args.output else default_root / ".harness" / "wiki" / "接口协议.md"
    controllers = json.loads(input_path.read_text(encoding="utf-8"))
    doc = render(controllers)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(doc, encoding="utf-8")
    endpoint_count = sum(len(c["endpoints"]) for c in controllers)
    print(f"rendered controllers={len(controllers)} endpoints={endpoint_count} output={output_path}")


if __name__ == "__main__":
    main()
