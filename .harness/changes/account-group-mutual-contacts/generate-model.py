"""离线读取本功能 Flyway DDL，复用 gen_datamodel 生成两表段落；保留其他会话文档。"""
from pathlib import Path
import csv
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
ddl = root / "armada-api/src/main/resources/db/migration/V202__account_mutual_contacts.sql"
wiki = root / ".harness/wiki/数据模型.md"
columns, indexes, tables = [], [], []
for table, body, comment in re.findall(r"CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\) COMMENT='([^']+)';", ddl.read_text(), re.S):
    tables.append(["armada", table, comment])
    position = 0
    for line in body.splitlines():
        line = line.strip().rstrip(",")
        col = re.fullmatch(r"(\w+) ([A-Z]+(?:\(\d+\))?) (.*?) COMMENT(?:=| )'([^']+)'", line)
        if col:
            name, typ, options, description = col.groups()
            position += 1
            default = re.search(r"DEFAULT (\S+)", options)
            columns.append(["armada", table, position, name, typ.lower(), "NO" if "NOT NULL" in options else "YES",
                default[1] if default else "__NULL__", "", "auto_increment" if "AUTO_INCREMENT" in options else "", description])
        elif line:
            idx = re.fullmatch(r"(PRIMARY KEY|UNIQUE KEY|KEY)(?: (\w+))? \(([^)]+)\)", line)
            if not idx:
                raise ValueError(f"Unrecognized DDL: {line}")
            kind, name, fields = idx.groups()
            for seq, field in enumerate(fields.split(","), 1):
                indexes.append(["armada", table, name or "PRIMARY", "1" if kind == "KEY" else "0", seq, field.strip(), "BTREE"])
if len(tables) != 2:
    raise ValueError("Expected exactly two mutual contact tables")
with tempfile.TemporaryDirectory(prefix="armada-mutual-model-") as temp:
    env = os.environ.copy()
    for key, rows in [("COLS", columns), ("IDX", indexes), ("TBL", tables)]:
        file = Path(temp) / (key + ".tsv")
        with file.open("w") as stream:
            csv.writer(stream, delimiter="\t", lineterminator="\n").writerows(rows)
        env["ARMADA_MODEL_" + key] = str(file)
    output = Path(temp) / "model.md"
    env["ARMADA_MODEL_OUT"] = str(output)
    subprocess.run(["python3", str(root / ".harness/wiki/gen_datamodel.py")], env=env, check=True)
    generated = output.read_text()
    content = wiki.read_text()
    sections = []
    for _, table, _ in tables:
        pattern = rf"### {table}（.*?(?=\n### |\Z)"
        section = re.search(pattern, generated, re.S).group().rstrip() + "\n\n"
        content = re.sub(pattern, "", content, flags=re.S)
        sections.append(section)
    anchor = "### · 群组 / 群链接池"
    if content.count(anchor) != 1:
        raise ValueError("Data model anchor changed")
    wiki.write_text(content.replace(anchor, "".join(sections) + anchor))
