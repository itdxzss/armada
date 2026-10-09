"""以 Flyway 元数据离线刷新本次两个聚合表，保留其他会话的文档内容。"""
from pathlib import Path
import csv
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
wiki = root / '.harness/wiki/数据模型.md'
migrations = root / 'armada-api/src/main/resources/db/migration'
original = (migrations / 'V211__new_group_creator_deletion.sql').read_text()
release = (migrations / 'V217__account_creator_deletion_release.sql').read_text()
status_comment = re.search(r"MODIFY COLUMN status.*?COMMENT '([^']+)'", release, re.S).group(1)
columns, indexes, tables = [], [], []
for sql in [original, release]:
    for table, body in re.findall(r'CREATE TABLE IF NOT EXISTS (\w+) \((.*?)\) ENGINE=', sql, re.S):
        if table == 'account_creator_deletion':
            continue
        description = '建群账号注销及实时验证账本' if table == 'pull_task_creator_deletion' else '终态未提交建群账号预留释放历史'
        tables.append(['armada', table, description])
        position = 0
        for line in body.splitlines():
            field = re.match(r"\s*(\w+)\s+(BIGINT|INT|TINYINT|TEXT|(?:VAR)?CHAR\(\d+\))(.*?)COMMENT '([^']*)'", line)
            if field:
                name, typ, attrs, comment = field.groups()
                position += 1
                if table == 'pull_task_creator_deletion' and name == 'status':
                    comment = status_comment
                default = re.search(r'DEFAULT\s+(\w+)', attrs)
                columns.append(['armada', table, position, name, typ.lower(),
                                'NO' if 'NOT NULL' in attrs else 'YES',
                                default.group(1) if default else '__NULL__', '',
                                'auto_increment' if 'AUTO_INCREMENT' in attrs else '', comment])
                if 'PRIMARY KEY' in attrs:
                    indexes.append(['armada', table, 'PRIMARY', '0', 1, name, 'BTREE'])
            index = re.match(r'\s*(UNIQUE KEY|KEY)\s+(\w+)\s*\(([^)]*)\)', line)
            if index:
                kind, name, fields = index.groups()
                for seq, field_name in enumerate(fields.split(','), 1):
                    indexes.append(['armada', table, name, '0' if kind == 'UNIQUE KEY' else '1', seq, field_name.strip(), 'BTREE'])
with tempfile.TemporaryDirectory(prefix='creator-release-model-') as temp:
    env = os.environ.copy()
    for key, rows in [('COLS', columns), ('IDX', indexes), ('TBL', tables)]:
        path = Path(temp) / (key + '.tsv')
        with path.open('w') as stream:
            csv.writer(stream, delimiter='\t', lineterminator='\n').writerows(rows)
        env['ARMADA_MODEL_' + key] = str(path)
    output = Path(temp) / 'model.md'
    env['ARMADA_MODEL_OUT'] = str(output)
    subprocess.run(['python3', str(root / '.harness/wiki/gen_datamodel.py')], env=env, check=True)
    content = wiki.read_text()
    generated = output.read_text()
    for _, table, _ in tables:
        pattern = rf'### {table}（.*?(?=\n### |\Z)'
        section = re.search(pattern, generated, re.S).group().rstrip() + '\n\n'
        content = re.sub(pattern, lambda _: section, content, flags=re.S) if re.search(pattern, content, re.S) else content.rstrip() + '\n\n' + section
    wiki.write_text(content.rstrip() + '\n')
