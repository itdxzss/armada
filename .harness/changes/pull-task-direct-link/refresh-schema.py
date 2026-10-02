"""读取 V208 变更字段，调用正式模型生成器刷新本次增量说明；不连接数据库。"""
from pathlib import Path
import csv
import os
import re
import runpy
import tempfile

root = Path(__file__).resolve().parents[3]
ddl = (root / "armada-api/src/main/resources/db/migration/V208__pull_task_direct_link.sql").read_text()
wiki = root / ".harness/wiki/数据模型.md"
columns, indexes = [], []
tables = [
    ["armada", "pull_task", "拉群任务公共主表"],
    ["armada", "pull_task_standard_setting", "普通拉群冻结执行配置"],
]

def column(table, name, typ, options, comment):
    default = re.search(r"DEFAULT (\S+)", options)
    position = sum(row[1] == table for row in columns) + 1
    columns.append([
        "armada", table, position, name, typ.lower(),
        "NO" if "NOT NULL" in options else "YES",
        default[1] if default else "__NULL__", "", "", comment,
    ])

request = re.search(r"ADD COLUMN (creation_request_id) (VARCHAR\(36\)) (.*?) COMMENT ''([^']+)''", ddl)
assert request, "Missing direct creation idempotency column"
column("pull_task", *request.groups())
for name in ("manager_group_id", "manager_group_name"):
    match = re.search(rf"MODIFY COLUMN ({name}) (\w+(?:\(\d+\))?) (.*?) COMMENT '([^']+)'", ddl)
    assert match, name
    column("pull_task_standard_setting", *match.groups())
index = re.search(r"ADD UNIQUE KEY (uq_pull_task_creation_request) \(([^)]+)\)", ddl)
assert index, "Missing tenant/user scoped idempotency index"
for seq, name in enumerate(index[2].split(","), 1):
    indexes.append(["armada", "pull_task", index[1], "0", seq, name.strip(), "BTREE"])

with tempfile.TemporaryDirectory(prefix="direct-link-model-") as directory:
    temp = Path(directory)
    for key, rows in [("COLS", columns), ("IDX", indexes), ("TBL", tables)]:
        path = temp / (key + ".tsv")
        with path.open("w") as stream:
            csv.writer(stream, delimiter="\t").writerows(rows)
        os.environ["ARMADA_MODEL_" + key] = str(path)
    os.environ["ARMADA_MODEL_OUT"] = str(temp / "generated.md")
    generator = runpy.run_path(str(root / ".harness/wiki/gen_datamodel.py"))
    section = "<!-- direct-link-schema-start -->\n"
    section += "\n> V208 离线迁移增量：以下仅列本次变更字段和索引，不是完整表结构；不代表远程数据库已迁移。\n\n"
    section += "创建模式新增 DIRECT_LINK，执行阶段新增 DIRECT_PULLER_JOIN(10)；旧值及默认行为保留。\n\n"
    for _, table, _ in tables:
        section += generator["render_table"]("armada", table) + "\n\n"
    section += "<!-- direct-link-schema-end -->\n\n"
    text = wiki.read_text()
    pattern = r"<!-- direct-link-schema-start -->.*?<!-- direct-link-schema-end -->\n*"
    if re.search(pattern, text, re.S):
        text = re.sub(pattern, lambda _: section, text, flags=re.S)
    else:
        anchor = "### · 拉群任务族\n\n"
        assert text.count(anchor) == 1, "Pull task model anchor changed"
        text = text.replace(anchor, anchor + section)
    wiki.write_text(text)
