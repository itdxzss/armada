"""用既有 gen_datamodel.py 生成单表的本地迁移预览，明确区分待部署结构与远程库实况。"""
from pathlib import Path
import csv
import re
import tempfile

root = Path(__file__).resolve().parents[3]
wiki = root / '.harness/wiki/数据模型.md'
table = 'script_marketing_send_record'
text = wiki.read_text()
match = re.search(rf'^### {table}（([^\n]+)）\n.*?(?=^### |\Z)', text, re.M | re.S)
assert match, '找不到发送记录表'
section = match.group(0)
columns = []
indexes = []
for line in section.splitlines():
    parts = [part.strip() for part in line.strip('|').split('|')]
    if len(parts) == 5 and parts[2] in ('YES', 'NO'):
        name, typ, nullable, default, comment = parts
        columns.append(['armada', table, str(len(columns) + 1), name, typ, nullable,
                        default, '', 'auto_increment' if default == 'AUTO_INCREMENT' else '', comment])
    elif len(parts) == 3 and parts[2] in ('INDEX', 'PRIMARY', 'UNIQUE'):
        for index, column in enumerate(parts[1].split(','), 1):
            indexes.append(['armada', table, parts[0], '1' if parts[2] == 'INDEX' else '0',
                            str(index), column.strip(), 'BTREE'])
sql = (root / 'armada-api/src/main/resources/db/migration/V188__script_quoted_reply.sql').read_text()
additions = re.findall(r"ADD COLUMN (\w+) (\w+(?:\(\d+\))?) NULL COMMENT ''([^']+)''", sql)
assert len(additions) == 2
for name, typ, comment in additions:
    row = next((row for row in columns if row[3] == name), None)
    updated = ['armada', table, str(len(columns) + 1), name, typ.lower(), 'YES', 'NULL', '', '', comment]
    if row:
        updated[2] = row[2]
        row[:] = updated
    else:
        columns.append(updated)
with tempfile.TemporaryDirectory(prefix='script-reply-schema-') as directory:
    temp = Path(directory)
    for filename, rows in [('columns.tsv', columns), ('indexes.tsv', indexes), ('tables.tsv', [['armada', table, match[1]]])]:
        with (temp / filename).open('w') as output:
            csv.writer(output, delimiter='\t').writerows(rows)
    generator = (root / '.harness/wiki/gen_datamodel.py').read_text()
    for name, filename in [('COLS', 'columns.tsv'), ('IDX', 'indexes.tsv'), ('TBL', 'tables.tsv'), ('OUT', 'generated.md')]:
        generator = re.sub(rf'^{name} = .+$', f'{name} = {str(temp / filename)!r}', generator, count=1, flags=re.M)
    namespace = {}
    exec(compile(generator, str(root / '.harness/wiki/gen_datamodel.py'), 'exec'), namespace)
    generated = namespace['render_table']('armada', table)
    heading, body = generated.split('\n', 1)
    note = '\n> 本节由 gen_datamodel.py 基于既有文档元数据与 V188 生成；结构来源与远程执行情况分别记录。\n'
    # 仅保留既有的实际部署证据；本地文档生成本身不执行或证明远程迁移。
    verification = re.search(r'^> test1 验证：.+$', section, re.M)
    if verification:
        note += '\n' + verification.group(0) + '\n'
    wiki.write_text(text[:match.start()] + heading + '\n' + note + body + '\n' + text[match.end():])
