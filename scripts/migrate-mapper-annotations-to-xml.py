#!/usr/bin/env python3
"""将 Mapper 接口上的 @Select/@Insert/@Update/@Delete 迁到 resources/mapper/*.xml。"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANN_NAMES = ("Select", "Insert", "Update", "Delete")


def find_matching(s: str, open_idx: int, open_ch: str, close_ch: str) -> int:
    depth = 0
    i = open_idx
    while i < len(s):
        c = s[i]
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i
        elif c == '"' and open_ch != '"':
            i += 1
            while i < len(s):
                if s[i] == "\\" :
                    i += 2
                    continue
                if s[i] == '"':
                    break
                i += 1
        i += 1
    raise ValueError(f"unbalanced {open_ch}{close_ch} at {open_idx}")


def parse_sql_literal(s: str, start: int) -> tuple[str, int]:
    """start points at first quote of \"\"\" or \"."""
    if s.startswith('"""', start):
        end = s.find('"""', start + 3)
        if end < 0:
            raise ValueError("unterminated text block")
        return s[start + 3 : end].strip("\n"), end + 3
    if s[start] == '"':
        i = start + 1
        out = []
        while i < len(s):
            if s[i] == "\\":
                nxt = s[i + 1]
                out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(nxt, nxt))
                i += 2
                continue
            if s[i] == '"':
                return "".join(out), i + 1
            out.append(s[i])
            i += 1
        raise ValueError("unterminated string")
    raise ValueError(f"expected string at {start}")


def result_type(ret: str) -> str | None:
    r = " ".join(ret.split())
    if r in ("void", "int", "long", "boolean", "Integer", "Long", "Boolean"):
        return None
    if "List<" in r:
        inner = r[r.find("<") + 1 : r.rfind(">")]
        return inner.strip()
    return r


def extract_methods(text: str) -> list[dict]:
    methods = []
    i = 0
    while i < len(text):
        m = re.search(r"@(Select|Insert|Update|Delete)\s*\(", text[i:])
        if not m:
            break
        ann = m.group(1)
        abs_ann = i + m.start()
        open_paren = i + m.end() - 1
        # annotation args: ("sql") or ("""sql""")
        close_ann = find_matching(text, open_paren, "(", ")")
        inner = text[open_paren + 1 : close_ann].strip()
        # first string literal in annotation args
        qs = inner.find('"')
        if qs < 0:
            raise ValueError(f"no sql string in @{ann}")
        sql, _ = parse_sql_literal(inner, qs)

        # skip whitespace/javadoc to method signature
        j = close_ann + 1
        while j < len(text) and text[j].isspace():
            j += 1
        # optional modifiers
        sig_m = re.match(
            r"(?:public\s+)?([\w.<>,\s\[\]]+?)\s+(\w+)\s*\(",
            text[j:],
        )
        if not sig_m:
            raise ValueError(f"no method after @{ann} at {abs_ann}: {text[j:j+80]!r}")
        ret = sig_m.group(1).strip()
        name = sig_m.group(2)
        params_open = j + sig_m.end() - 1
        params_close = find_matching(text, params_open, "(", ")")
        params = text[params_open + 1 : params_close]
        # expect ;
        k = params_close + 1
        while k < len(text) and text[k].isspace():
            k += 1
        if k >= len(text) or text[k] != ";":
            raise ValueError(f"expected ; after {name}")
        end = k + 1
        methods.append(
            {
                "ann": ann,
                "sql": sql,
                "ret": ret,
                "name": name,
                "params": params,
                "start": abs_ann,
                "end": end,
            }
        )
        i = end
    return methods


def process(path: Path) -> bool:
    text = path.read_text()
    if not any(f"@{a}" in text for a in ANN_NAMES):
        return False
    pkg_m = re.search(r"package\s+([\w.]+);", text)
    iface_m = re.search(r"public\s+interface\s+(\w+)", text)
    if not pkg_m or not iface_m:
        raise SystemExit(f"bad file: {path}")
    fqcn = f"{pkg_m.group(1)}.{iface_m.group(1)}"
    methods = extract_methods(text)
    if not methods:
        print(f"WARN: no methods extracted: {path}", file=sys.stderr)
        return False

    statements = []
    for meth in methods:
        tag = {"Select": "select", "Insert": "insert", "Update": "update", "Delete": "delete"}[
            meth["ann"]
        ]
        attrs = [f'id="{meth["name"]}"']
        rt = result_type(meth["ret"])
        if tag == "select" and rt:
            attrs.append(f'resultType="{rt}"')
        sql = meth["sql"]
        if "]]>" in sql:
            raise ValueError(f"{path} {meth['name']}: SQL contains ]]>")
        statements.append(
            f"    <{tag} {' '.join(attrs)}>\n"
            f"        <![CDATA[\n{sql}\n]]>\n"
            f"    </{tag}>"
        )

    # rebuild Java: remove annotation blocks, keep signatures
    out = []
    cursor = 0
    for meth in methods:
        out.append(text[cursor : meth["start"]])
        params = meth["params"].strip()
        sig = f"    {meth['ret']} {meth['name']}({params});"
        out.append(sig)
        cursor = meth["end"]
    out.append(text[cursor:])
    cleaned = "".join(out)

    # drop unused annotation imports (keep Param, Mapper)
    for a in ANN_NAMES:
        cleaned = re.sub(
            rf"^import\s+org\.apache\.ibatis\.annotations\.{a};\s*\n",
            "",
            cleaned,
            flags=re.M,
        )
    # wildcard import org.apache.ibatis.annotations.* — replace with Mapper + Param if needed
    if "import org.apache.ibatis.annotations.*;" in cleaned:
        needs_param = "@Param" in cleaned or "Param(" in cleaned
        repl = "import org.apache.ibatis.annotations.Mapper;\n"
        if needs_param:
            repl += "import org.apache.ibatis.annotations.Param;\n"
        cleaned = cleaned.replace("import org.apache.ibatis.annotations.*;\n", repl)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)

    res_dir = Path(str(path).split("/src/main/java/")[0]) / "src/main/resources/mapper"
    res_dir.mkdir(parents=True, exist_ok=True)
    xml_path = res_dir / f"{iface_m.group(1)}.xml"
    xml = (
        '<?xml version="1.0" encoding="UTF-8" ?>\n'
        '<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"\n'
        '        "https://mybatis.org/dtd/mybatis-3-mapper.dtd">\n'
        f'<mapper namespace="{fqcn}">\n\n'
        + "\n\n".join(statements)
        + "\n\n</mapper>\n"
    )
    xml_path.write_text(xml)
    path.write_text(cleaned)
    print(f"OK {path.relative_to(ROOT)} → {xml_path.relative_to(ROOT)} ({len(methods)} stmts)")
    return True


def main() -> None:
    if len(sys.argv) > 1:
        paths = [Path(p).resolve() for p in sys.argv[1:]]
    else:
        paths = [
            p
            for p in (ROOT / "tioka-modules").rglob("*Mapper.java")
            if any(f"@{a}" in p.read_text() for a in ANN_NAMES)
        ]
    n = 0
    for p in sorted(paths):
        if process(p):
            n += 1
    print(f"done: {n} files")


if __name__ == "__main__":
    main()
