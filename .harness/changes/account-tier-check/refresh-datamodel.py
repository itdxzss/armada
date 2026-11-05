"""离线从 V225 元数据调用现有生成器，只合并本功能两张表，保留共享文档其他段落。"""
from pathlib import Path
import csv
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
DDL = ROOT / 'armada-api/src/main/resources/db/migration/V225__account_tier_check.sql'
DOC = ROOT / '.harness/wiki/数据模型.md'
GEN = ROOT / '.harness/wiki/gen_datamodel.py'
columns, indexes, tables = [], [], []
for table, body, comment in re.findall(r"CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\) COMMENT='([^']+)';", DDL.read_text(), re.S):
    tables.append(['armada', table, comment])
    ordinal = 0
    for line in body.splitlines():
        line = line.strip().rstrip(',')
        column = re.match(r"(\w+) ([A-Z]+(?:\([\d,]+\))?) (.*?) COMMENT '([^']+)'$", line)
        if column and column[1] not in ('PRIMARY', 'UNIQUE', 'KEY', 'CONSTRAINT'):
            name, kind, tail, desc = column.groups()
            ordinal += 1
            default = re.search(r'DEFAULT (\S+)', tail)
            columns.append(['armada', table, ordinal, name, kind.lower(), 'NO' if 'NOT NULL' in tail else 'YES', default[1] if default else '__NULL__', '', 'auto_increment' if 'AUTO_INCREMENT' in tail else '', desc])
        index = re.match(r'(PRIMARY KEY|UNIQUE KEY (\w+)|KEY (\w+)) \(([^)]+)\)', line)
        if index:
            name = 'PRIMARY' if index[1] == 'PRIMARY KEY' else index[2] or index[3]
            for sequence, field in enumerate(index[4].split(','), 1):
                indexes.append(['armada', table, name, '0' if index[1].startswith(('PRIMARY', 'UNIQUE')) else '1', sequence, field.strip(), 'BTREE'])
with tempfile.TemporaryDirectory(prefix='account-tier-datamodel-') as temp:
    temp = Path(temp)
    for name, rows in [('columns', columns), ('indexes', indexes), ('tables', tables)]:
        with (temp / (name + '.tsv')).open('w') as f:
            csv.writer(f, delimiter='\t', lineterminator='\n').writerows(rows)
    generated = temp / 'generated.md'
    env = dict(os.environ, ARMADA_MODEL_COLS=str(temp/'columns.tsv'), ARMADA_MODEL_IDX=str(temp/'indexes.tsv'), ARMADA_MODEL_TBL=str(temp/'tables.tsv'), ARMADA_MODEL_OUT=str(generated))
    subprocess.run(['python3', str(GEN)], env=env, check=True)
    output = generated.read_text()
    sections = []
    doc = DOC.read_text()
    for table in ['account_tier_check_task', 'account_tier_check_item']:
        pattern = rf'(?m)^### {table}[^\n]*\n.*?(?=^### |\Z)'
        section = re.search(pattern, output, re.S).group(0).strip() + '\n\n'
        section = section.replace('\n\n| Column', '\n\n> 基于 V225 迁移离线生成的待部署结构；未连接或迁移真实数据库。\n\n| Column', 1)
        doc = re.sub(pattern, '', doc, flags=re.S)
        sections.append(section)
    anchor = '### · 账号与归属\n\n'
    assert anchor in doc
    doc = doc.replace(anchor, anchor + ''.join(sections), 1)
    DOC.write_text(doc)
