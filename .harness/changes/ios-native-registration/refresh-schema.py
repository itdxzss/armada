"""复用正式生成器刷新两张注册表的待部署结构，保留其他在途文档。"""
from pathlib import Path
import csv
import re
import tempfile

root = Path(__file__).resolve().parents[3]
wiki = root / '.harness/wiki/数据模型.md'
sql = (root / 'armada-api/src/main/resources/db/migration/V196__ios_device_registration.sql').read_text()
text = wiki.read_text()
for table in ('account_registration_task', 'account_registration_item'):
    match = re.search(rf'^### {table}（([^\n]+)）\n.*?(?=^### |\Z)', text, re.M | re.S)
    assert match
    columns, indexes = [], []
    for line in match[0].splitlines():
        parts = [part.strip() for part in line.strip('|').split('|')]
        if len(parts) == 5 and parts[2] in ('YES', 'NO'):
            name, typ, nullable, default, comment = parts
            columns.append(['armada', table, str(len(columns)+1), name, typ, nullable, default, '',
                            'auto_increment' if default == 'AUTO_INCREMENT' else '', comment])
        elif len(parts) == 3 and parts[2] in ('INDEX', 'PRIMARY', 'UNIQUE'):
            for number, name in enumerate(parts[1].split(','), 1):
                indexes.append(['armada', table, parts[0], '1' if parts[2]=='INDEX' else '0', str(number), name.strip(), 'BTREE'])
    pattern = rf"ALTER TABLE {table} (?:ADD|MODIFY) COLUMN (\w+) (\w+(?:\(\d+\))?) (.*?) COMMENT ('+)([^']+)\4"
    for name, typ, flags, _, comment in re.findall(pattern, sql):
        nullable = 'NO' if 'NOT NULL' in flags else 'YES'
        default_match = re.search(r'DEFAULT (\w+)', flags)
        default = default_match[1] if default_match else ('NULL' if nullable == 'YES' else '-')
        old = next((row for row in columns if row[3] == name), None)
        row = ['armada', table, old[2] if old else str(len(columns)+1), name, typ.lower(), nullable, default, '', '', comment]
        if old: old[:] = row
        else: columns.append(row)
    with tempfile.TemporaryDirectory(prefix='ios-registration-schema-') as directory:
        temp = Path(directory)
        for file, rows in [('columns.tsv', columns), ('indexes.tsv', indexes), ('tables.tsv', [['armada', table, match[1]]])]:
            with (temp/file).open('w') as stream: csv.writer(stream, delimiter='\t').writerows(rows)
        generator = (root/'.harness/wiki/gen_datamodel.py').read_text()
        for name, file in [('COLS','columns.tsv'),('IDX','indexes.tsv'),('TBL','tables.tsv'),('OUT','output.md')]:
            generator = re.sub(rf'^{name} = .+$', f'{name} = {str(temp/file)!r}', generator, count=1, flags=re.M)
        namespace = {}
        exec(compile(generator, str(root/'.harness/wiki/gen_datamodel.py'), 'exec'), namespace)
        heading, body = namespace['render_table']('armada', table).split('\n', 1)
        note = '\n> 本节由 gen_datamodel.py 基于既有元数据与 V196 生成待部署结构；本次未连接或迁移远程数据库。\n'
        text = text[:match.start()] + heading + '\n' + note + body + '\n' + text[match.end():]
wiki.write_text(text)
