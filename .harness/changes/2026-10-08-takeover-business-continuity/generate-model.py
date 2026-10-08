"""从既有账号模型和 V215 离线 DDL 生成变更段落，不连接数据库。"""
from pathlib import Path
import csv
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
wiki = root / ".harness/wiki/数据模型.md"
migration = root / "armada-api/src/main/resources/db/migration/V215__account_takeover_breaker.sql"
content = wiki.read_text(encoding="utf-8")
sql = migration.read_text(encoding="utf-8")
state_pattern = r"### account_state（.*?(?=\n### |\Z)"
state = re.search(state_pattern, content, re.S)
if state is None:
    raise ValueError("Missing account_state section in the existing data model")

columns, indexes = [], []
tables = [["armada", "account_state", "账号生命周期状态(高频Kafka回写)"]]
offline = re.search(r"ADD COLUMN offline_since (BIGINT) NULL\s+COMMENT ''(.*?)''", sql, re.S)
if offline is None:
    raise ValueError("Missing offline_since DDL in V215")

# 只读取既有表的元数据，新增列的类型和注释以实际迁移为准。
position = 0
for line in state.group().splitlines():
    fields = [part.strip() for part in line.split("|")[1:-1]]
    if len(fields) == 5 and fields[0] != "Column" and not fields[0].startswith("-"):
        name, typ, nullable, default, description = fields
        if name == "offline_since":
            continue
        position += 1
        columns.append(["armada", "account_state", position, name, typ, nullable,
                        "__NULL__" if default in ("-", "NULL", "AUTO_INCREMENT") else default,
                        "", "auto_increment" if default == "AUTO_INCREMENT" else "", description])
        if name == "login_state":
            position += 1
            columns.append(["armada", "account_state", position, "offline_since", offline[1].lower(),
                            "YES", "__NULL__", "", "", offline[2]])
    elif len(fields) == 3 and fields[2] in ("INDEX", "UNIQUE", "PRIMARY"):
        name, names, kind = fields
        for sequence, column in enumerate(names.split(","), 1):
            indexes.append(["armada", "account_state", name, "1" if kind == "INDEX" else "0",
                            sequence, column.strip(), "BTREE"])

breaker = re.search(
    r"CREATE TABLE IF NOT EXISTS (account_takeover_breaker) \((.*?)\) ENGINE=.*?COMMENT='([^']+)';",
    sql, re.S)
if breaker is None:
    raise ValueError("Missing account_takeover_breaker DDL in V215")
table, body, comment = breaker.groups()
tables.append(["armada", table, comment])
position = 0
for line in body.splitlines():
    line = line.strip().rstrip(",")
    if not line:
        continue
    field = re.fullmatch(r"(\w+) (BIGINT|INT) (.*?) COMMENT '([^']+)'", line)
    if field:
        name, typ, attributes, description = field.groups()
        position += 1
        default = re.search(r"DEFAULT (\S+)", attributes)
        columns.append(["armada", table, position, name, typ.lower(),
                        "NO" if "NOT NULL" in attributes else "YES",
                        default[1] if default else "__NULL__", "",
                        "auto_increment" if "AUTO_INCREMENT" in attributes else "", description])
        continue
    index = re.fullmatch(r"(PRIMARY KEY|UNIQUE KEY|KEY)(?: (\w+))? \(([^)]+)\)", line)
    if index is None:
        raise ValueError(f"Unrecognized V215 DDL: {line}")
    kind, name, fields = index.groups()
    for sequence, column in enumerate(fields.split(","), 1):
        indexes.append(["armada", table, name or "PRIMARY", "1" if kind == "KEY" else "0",
                        sequence, column.strip(), "BTREE"])

with tempfile.TemporaryDirectory(prefix="armada-takeover-model-") as temporary:
    environment = os.environ.copy()
    for key, rows in [("COLS", columns), ("IDX", indexes), ("TBL", tables)]:
        path = Path(temporary) / (key + ".tsv")
        with path.open("w", encoding="utf-8") as stream:
            csv.writer(stream, delimiter="\t", lineterminator="\n").writerows(rows)
        environment["ARMADA_MODEL_" + key] = str(path)
    output = Path(temporary) / "model.md"
    environment["ARMADA_MODEL_OUT"] = str(output)
    subprocess.run(["python3", str(root / ".harness/wiki/gen_datamodel.py")],
                   env=environment, check=True)
    generated = output.read_text(encoding="utf-8")

note = "> 本节由 gen_datamodel.py 基于既有元数据与 V215 离线迁移生成；本次未连接或迁移远程数据库。"
sections = {}
for _, table, _ in tables:
    match = re.search(rf"### {table}（.*?(?=\n### |\Z)", generated, re.S)
    if match is None:
        raise ValueError(f"Generator omitted {table}")
    section = match.group().rstrip()
    heading, body = section.split("\n", 1)
    sections[table] = heading + "\n\n" + note + "\n" + body + "\n\n"

# 防止生成期间覆盖其他会话对相同段落的编辑，其余段落以最新文件为准。
latest = wiki.read_text(encoding="utf-8")
latest_state = re.search(state_pattern, latest, re.S)
if latest_state is None or latest_state.group() != state.group():
    raise ValueError("account_state documentation changed during generation; rerun on the latest content")
breaker_pattern = r"### account_takeover_breaker（.*?(?=\n### |\Z)"
latest = re.sub(breaker_pattern, "", latest, flags=re.S)
replacement = (sections["account_state"] + sections["account_takeover_breaker"]).rstrip() + "\n"
latest = re.sub(state_pattern, lambda _: replacement, latest, count=1, flags=re.S)
wiki.write_text(latest, encoding="utf-8")
