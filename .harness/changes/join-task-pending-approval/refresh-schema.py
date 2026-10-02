"""通过正式模型生成器渲染 V206，仅更新本次审核恢复子记录文档。"""
from pathlib import Path
import csv
import os
import re
import runpy
import tempfile

root = Path(__file__).resolve().parents[3]
migration = root / 'armada-api/src/main/resources/db/migration/V206__join_task_pending_approval.sql'
table = 'join_task_approval'
body = re.search(r'CREATE TABLE IF NOT EXISTS join_task_approval \((.*?)\) ENGINE=', migration.read_text(), re.S)[1]
columns, indexes = [], []
for line in body.splitlines():
    line = line.strip().rstrip(',')
    col = re.fullmatch(r"(\w+) ([A-Z]+(?:\(\d+\))?) (.*?) COMMENT '([^']+)'", line)
    if col:
        name, typ, flags, comment = col.groups()
        default = re.search(r'DEFAULT (\S+)', flags)
        columns.append(['armada', table, len(columns) + 1, name, typ.lower(),
                        'NO' if 'NOT NULL' in flags else 'YES',
                        default[1] if default else '__NULL__', '', '', comment])
    elif line:
        kind, name, fields = re.fullmatch(r'(PRIMARY KEY|KEY)(?: (\w+))? \(([^)]+)\)', line).groups()
        for seq, field in enumerate(fields.split(','), 1):
            indexes.append(['armada', table, name or 'PRIMARY', '1' if kind == 'KEY' else '0', seq, field.strip(), 'BTREE'])

with tempfile.TemporaryDirectory(prefix='join-approval-schema-') as directory:
    temp = Path(directory)
    for key, rows in [('COLS', columns), ('IDX', indexes), ('TBL', [['armada', table, '进群待审核自动处理进度']])]:
        path = temp / (key + '.tsv')
        with path.open('w') as stream:
            csv.writer(stream, delimiter='\t').writerows(rows)
        os.environ['ARMADA_MODEL_' + key] = str(path)
    os.environ['ARMADA_MODEL_OUT'] = str(temp / 'generated.md')
    generator = runpy.run_path(str(root / '.harness/wiki/gen_datamodel.py'))
    heading, body = generator['render_table']('armada', table).split('\n', 1)
    section = heading + '\n\n> 本节基于 V206 离线迁移元数据生成；未表示远程数据库已迁移。\n' + body + '\n'
    wiki = root / '.harness/wiki/数据模型.md'
    text = wiki.read_text()
    pattern = rf'^### {table}（[^\n]+）\n.*?(?=^### |\Z)'
    if re.search(pattern, text, re.M | re.S):
        text = re.sub(pattern, lambda _: section, text, flags=re.M | re.S)
    else:
        anchor = '### · 群组营销 / 素材'
        assert text.count(anchor) == 1
        text = text.replace(anchor, section + anchor)
    wiki.write_text(text)
