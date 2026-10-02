"""以当前文档和 V201/V203 离线元数据调用正式生成器，仅刷新进群任务相关段落。"""
from pathlib import Path
import csv
import os
import re
import runpy
import tempfile

root = Path(__file__).resolve().parents[3]
wiki = root / '.harness/wiki/数据模型.md'
text = wiki.read_text()
migrations = root / 'armada-api/src/main/resources/db/migration'
ddl = (migrations / 'V201__join_task_set_admin.sql').read_text() + '\n' + (
    migrations / 'V203__join_task_clear_admins_and_leave.sql').read_text()
columns, indexes, tables = [], [], []
for table in ('join_task', 'join_task_result'):
    match = re.search(rf'^### {table}（([^\n]+)）\n.*?(?=^### |\Z)', text, re.M | re.S)
    assert match, table
    tables.append(['armada', table, match[1]])
    cols = []
    for line in match[0].splitlines():
        parts = [p.strip() for p in line.strip('|').split('|')]
        if len(parts) == 5 and parts[2] in ('YES', 'NO'):
            name, typ, nullable, default, comment = parts
            cols.append(['armada', table, len(cols)+1, name, typ, nullable, default, '',
                         'auto_increment' if default == 'AUTO_INCREMENT' else '', comment])
        elif len(parts) == 3 and parts[2] in ('INDEX', 'PRIMARY', 'UNIQUE'):
            for seq, field in enumerate(parts[1].split(','), 1):
                indexes.append(['armada', table, parts[0], '1' if parts[2] == 'INDEX' else '0', seq, field.strip(), 'BTREE'])
    for name, typ, flags, comment in re.findall(
            rf"ALTER TABLE {table} ADD COLUMN (\w+) (\w+(?:\(\d+\))?) (.*?) COMMENT ''([^']+)''", ddl):
        old = next((row for row in cols if row[3] == name), None)
        default = re.search(r"DEFAULT (\w+|'{4})", flags)
        row = ['armada', table, old[2] if old else len(cols)+1, name, typ.lower(),
               'NO' if 'NOT NULL' in flags else 'YES',
               default[1].replace("''''", "''") if default else '__NULL__', '', '', comment]
        if old: old[:] = row
        else: cols.append(row)
    columns.extend(cols)

table = 'join_task_cleanup'
tables.append(['armada', table, '进群后清理管理员并退群的执行子记录'])
body = re.search(r'CREATE TABLE IF NOT EXISTS join_task_cleanup \((.*?)\) ENGINE=', ddl, re.S)[1]
position = 0
for line in body.splitlines():
    line = line.strip().rstrip(',')
    col = re.fullmatch(r"(\w+) ([A-Z]+(?:\(\d+\))?) (.*?) COMMENT '([^']+)'", line)
    if col:
        name, typ, flags, comment = col.groups(); position += 1
        default = re.search(r"DEFAULT (\S+)", flags)
        columns.append(['armada', table, position, name, typ.lower(), 'NO' if 'NOT NULL' in flags else 'YES',
                        default[1] if default else '__NULL__', '', '', comment])
    elif line:
        idx = re.fullmatch(r'(PRIMARY KEY|UNIQUE KEY|KEY)(?: (\w+))? \(([^)]+)\)', line)
        assert idx, line
        kind, name, fields = idx.groups()
        for seq, field in enumerate(fields.split(','), 1):
            indexes.append(['armada', table, name or 'PRIMARY', '1' if kind == 'KEY' else '0', seq, field.strip(), 'BTREE'])

with tempfile.TemporaryDirectory(prefix='join-cleanup-schema-') as directory:
    temp = Path(directory)
    for key, rows in [('COLS', columns), ('IDX', indexes), ('TBL', tables)]:
        file = temp / (key + '.tsv')
        with file.open('w') as stream: csv.writer(stream, delimiter='\t').writerows(rows)
        os.environ['ARMADA_MODEL_' + key] = str(file)
    os.environ['ARMADA_MODEL_OUT'] = str(temp / 'generated.md')
    generator = runpy.run_path(str(root / '.harness/wiki/gen_datamodel.py'))
    for _, table, _ in tables:
        heading, body = generator['render_table']('armada', table).split('\n', 1)
        section = heading + '\n\n> 本节基于 V201/V203 离线迁移元数据生成；未表示远程数据库已迁移。\n' + body + '\n'
        pattern = rf'^### {table}（[^\n]+）\n.*?(?=^### |\Z)'
        if re.search(pattern, text, re.M | re.S):
            text = re.sub(pattern, lambda _: section, text, flags=re.M | re.S)
        else:
            anchor = '### · 群组营销 / 素材'
            assert text.count(anchor) == 1
            text = text.replace(anchor, section + anchor)
wiki.write_text(text)
