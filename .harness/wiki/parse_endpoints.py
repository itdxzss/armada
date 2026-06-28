# -*- coding: utf-8 -*-
"""解析 armada-api 的 @RestController,抽取端点契约为 JSON。"""
import argparse
import json
import re
from pathlib import Path


VERB = {"Get": "GET", "Post": "POST", "Put": "PUT", "Delete": "DELETE", "Patch": "PATCH"}
MAP_RE = re.compile(r"@(Get|Post|Put|Delete|Patch|Request)Mapping\b")
ANNOTATION_RE = re.compile(r"@[A-Za-z_][\w.]*\s*(?:\([^)]*\))?")
INFRA_TYPES = {
    "TenantContext",
    "AuthContext",
    "HttpServletRequest",
    "HttpServletResponse",
    "HttpHeaders",
    "Principal",
    "Authentication",
    "BindingResult",
    "UriComponentsBuilder",
    "Locale",
    "Pageable",
}


def grab_parens(s, start):
    """start 指向 '(';返回 (内部内容,结束后位置)。"""
    if start >= len(s) or s[start] != "(":
        return "", start
    depth = 0
    i = start
    while i < len(s):
        c = s[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return s[start + 1:i], i + 1
        i += 1
    return s[start + 1:], len(s)


def split_top(s, seps=","):
    """按顶层分隔符切分,忽略泛型、括号和字符串。"""
    out = []
    depth = 0
    buf = ""
    instr = None
    for c in s:
        if instr:
            buf += c
            if c == instr:
                instr = None
            continue
        if c in "\"'":
            instr = c
            buf += c
            continue
        if c in "<({[":
            depth += 1
        elif c in ">)}]":
            depth -= 1
        if c in seps and depth == 0:
            if buf.strip():
                out.append(buf)
            buf = ""
        else:
            buf += c
    if buf.strip():
        out.append(buf)
    return out


def str_lit(s):
    m = re.search(r'"([^"]*)"', s)
    return m.group(1) if m else None


def mapping_path(args):
    if not args:
        return ""
    for key in ("value", "path"):
        m = re.search(rf"\b{key}\s*=\s*\"([^\"]*)\"", args)
        if m:
            return m.group(1)
    return str_lit(args) or ""


def join_path(base, p):
    base = base or ""
    p = p or ""
    if not p:
        full = base
    elif not base:
        full = p
    else:
        full = base.rstrip("/") + "/" + p.lstrip("/")
    if not full.startswith("/"):
        full = "/" + full
    return re.sub(r"//+", "/", full)


def parse_javadoc(block):
    """返回 (summary, {param: desc}, return_desc)。"""
    if not block:
        return "", {}, ""
    clean = []
    for ln in block.split("\n"):
        ln = ln.strip()
        ln = re.sub(r"^/\*\*?", "", ln)
        ln = re.sub(r"\*/$", "", ln)
        ln = re.sub(r"^\*\s?", "", ln)
        clean.append(ln)

    summ = []
    params = {}
    ret = ""
    cur = None
    summ_done = False
    for ln in clean:
        st = ln.strip()
        m = re.match(r"@param\s+(\w+)\s*(.*)", st)
        mr = re.match(r"@return\s*(.*)", st)
        if m:
            cur = ("p", m.group(1))
            params[m.group(1)] = m.group(2).strip()
            summ_done = True
            continue
        if mr:
            cur = ("r", None)
            ret = mr.group(1).strip()
            summ_done = True
            continue
        if st.startswith("@"):
            cur = "other"
            summ_done = True
            continue
        if cur is None:
            if st == "":
                if summ:
                    summ_done = True
                continue
            if not summ_done:
                s2 = re.sub(r"<[^>]+>", "", st).strip()
                if s2:
                    summ.append(s2)
        elif cur and cur[0] == "p":
            params[cur[1]] = (params[cur[1]] + " " + st).strip()
        elif cur and cur[0] == "r":
            ret = (ret + " " + st).strip()

    def clean_tags(s):
        s = re.sub(r"\{@(?:code|link|linkplain|literal|value)\s+([^}]*)\}", r"\1", s)
        return re.sub(r"\s+", " ", s).strip()

    return clean_tags(" ".join(summ)), {k: clean_tags(v) for k, v in params.items()}, clean_tags(ret)


def nearest_javadoc_before(text, pos):
    """取 mapping 注解前最近的 Javadoc 块,避免被 {@code /api/public/**} 误判。"""
    pre = text[:pos]
    blocks = list(re.finditer(r"(?ms)^[ \t]*/\*\*.*?\*/", pre))
    if not blocks:
        return ""
    last = blocks[-1]
    gap = pre[last.end():]
    if "}" in gap or ";" in gap:
        return ""
    return last.group(0)


def annotation_args(ann, name):
    m = re.search(rf"@{name}\(([^)]*)\)", ann)
    return m.group(1) if m else ""


def annotation_name(ann, annotation, fallback):
    args = annotation_args(ann, annotation)
    m = re.search(r'(?:name|value)\s*=\s*"([^"]+)"', args)
    if m:
        return m.group(1)
    return str_lit(args) or fallback


def classify_param(param, pdoc):
    param = param.strip()
    if not param:
        return None
    annotations = " ".join(ANNOTATION_RE.findall(param))
    rest = ANNOTATION_RE.sub("", param).strip()
    toks = [t for t in rest.split() if t != "final"]
    if len(toks) < 2:
        return None
    typ = " ".join(toks[:-1])
    var = toks[-1].replace("...", "[]")
    desc = pdoc.get(var, "")

    if "@PathVariable" in annotations:
        return {"kind": "path", "name": annotation_name(annotations, "PathVariable", var), "type": typ, "desc": desc}
    if "@RequestParam" in annotations:
        args = annotation_args(annotations, "RequestParam")
        required = not re.search(r"required\s*=\s*false", args) and "defaultValue" not in args
        dv = re.search(r'defaultValue\s*=\s*"([^"]*)"', args)
        if typ.endswith("MultipartFile"):
            return {
                "kind": "part",
                "name": annotation_name(annotations, "RequestParam", var),
                "type": typ,
                "required": required,
                "default": dv.group(1) if dv else None,
                "desc": desc,
            }
        return {
            "kind": "query",
            "name": annotation_name(annotations, "RequestParam", var),
            "type": typ,
            "required": required,
            "default": dv.group(1) if dv else None,
            "desc": desc,
        }
    if "@RequestBody" in annotations:
        return {"kind": "body", "name": var, "type": typ, "desc": desc}
    if "@RequestPart" in annotations:
        return {"kind": "part", "name": annotation_name(annotations, "RequestPart", var), "type": typ, "desc": desc}

    bt = typ.split("<")[0].split(".")[-1]
    if bt in INFRA_TYPES:
        return None
    if bt[:1].isupper() or "@ModelAttribute" in annotations:
        return {"kind": "queryobj", "name": var, "type": typ, "desc": desc}
    return None


def resp_type(ret):
    ret = ret.strip()
    m = re.match(r"ApiResponse<(.+)>$", ret)
    if m:
        inner = m.group(1).strip()
        mp = re.match(r"PageResult<(.+)>$", inner) or re.match(r"PageResponse<(.+)>$", inner)
        if mp:
            return f"分页（{mp.group(1)}）"
        if inner in ("Void", "java.lang.Void"):
            return "无"
        return inner
    return ret or "void"


def discover_controller_files(root):
    src = Path(root) / "armada-api" / "src" / "main" / "java"
    return sorted(p for p in src.rglob("*.java") if "@RestController" in p.read_text(encoding="utf-8"))


def package_name(text):
    m = re.search(r"\bpackage\s+([\w.]+);", text)
    return m.group(1) if m else ""


def module_name(pkg):
    parts = pkg.split(".")
    return parts[2] if len(parts) > 2 and parts[:2] == ["com", "armada"] else ""


def parse_controller(path, root):
    text = path.read_text(encoding="utf-8")
    cls_m = re.search(r"\b(?:public\s+)?(?:final\s+)?class\s+(\w+)", text)
    cname = cls_m.group(1) if cls_m else path.stem
    cls_pos = cls_m.start() if cls_m else 0
    pkg = package_name(text)

    head = text[max(0, cls_pos - 1000):cls_pos]
    cb = ""
    rm = re.search(r"@RequestMapping\s*(?:\(([^)]*)\))?", head)
    if rm:
        cb = mapping_path(rm.group(1) or "")
    cperm_m = re.search(r'@RequirePerm\("([^"]+)"\)', head)
    cperm = cperm_m.group(1) if cperm_m else None

    endpoints = []
    for m in MAP_RE.finditer(text):
        if m.start() < cls_pos:
            continue
        kind = m.group(1)
        pstart = m.end()
        while pstart < len(text) and text[pstart] in " \t":
            pstart += 1
        args, after = grab_parens(text, pstart) if pstart < len(text) and text[pstart] == "(" else ("", m.end())
        verb = VERB.get(kind)
        if verb is None:
            mv = re.search(r"RequestMethod\.(\w+)", args)
            verb = mv.group(1) if mv else "ANY"
        full = join_path(cb, mapping_path(args))
        body_start = text.find("{", after)
        if body_start < 0:
            continue
        headspan = text[after:body_start]
        perm_m = re.search(r'@RequirePerm\("([^"]+)"\)', headspan)
        perm = perm_m.group(1) if perm_m else cperm

        javadoc = nearest_javadoc_before(text, m.start())
        summary, pdoc, retdoc = parse_javadoc(javadoc)

        sig = headspan
        while True:
            sig = sig.lstrip()
            if not sig.startswith("@"):
                break
            am = re.match(r"@[A-Za-z_][\w.]*", sig)
            sig = sig[am.end():].lstrip()
            if sig.startswith("("):
                _, end = grab_parens(sig, 0)
                sig = sig[end:]

        ret_type = ""
        params = []
        method_name = ""
        po = sig.find("(")
        if po > 0:
            left = sig[:po].split()
            mods = {"public", "protected", "private", "static", "final", "synchronized", "default", "abstract"}
            if left:
                method_name = left[-1]
                ret_type = " ".join(t for t in left[:-1] if t not in mods)
            pinner, _ = grab_parens(sig, po)
            for raw_param in split_top(pinner):
                cp = classify_param(raw_param, pdoc)
                if cp:
                    params.append(cp)

        endpoints.append({
            "method": verb,
            "path": full,
            "name": method_name,
            "summary": summary,
            "perm": perm,
            "params": params,
            "response": resp_type(ret_type),
            "ret_raw": ret_type,
            "retdoc": retdoc,
        })

    if not endpoints:
        return None
    rel = path.relative_to(root).as_posix()
    return {
        "controller": cname,
        "module": module_name(pkg),
        "package": pkg,
        "source": rel,
        "base": cb,
        "class_perm": cperm,
        "endpoints": sorted(endpoints, key=lambda e: (e["path"], e["method"])),
    }


def parse_all(root):
    root = Path(root).resolve()
    controllers = []
    for path in discover_controller_files(root):
        parsed = parse_controller(path, root)
        if parsed:
            controllers.append(parsed)
    return sorted(controllers, key=lambda c: (c["module"], c["controller"]))


def main():
    default_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="解析 armada-api Controller 端点契约")
    parser.add_argument("--root", default=str(default_root), help="armada 仓库根目录")
    parser.add_argument("--output", default=None, help="输出 JSON 文件路径")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    output = Path(args.output).resolve() if args.output else root / ".harness" / "wiki" / "armada_endpoints.json"
    result = parse_all(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    total = sum(len(c["endpoints"]) for c in result)
    print(f"controllers={len(result)} endpoints={total} output={output}")


if __name__ == "__main__":
    main()
