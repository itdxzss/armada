"""基于现有群创建者聚合元数据与 V207，离线重生成单表文档，不读取远程库。"""
from pathlib import Path
import csv
import os
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
wiki = root / '.harness/wiki/数据模型.md'
content = wiki.read_text()
pattern = r'### group_link_preview（.*?(?=\n### |\Z)'
section = re.search(pattern, content, re.S).group()
columns, indexes = [], []
for line in section.splitlines():
    fields = [s.strip() for s in line.split('|')[1:-1]]
    if len(fields) == 5 and fields[0] not in ('Column', 'creator_phone_source') and not fields[0].startswith('-'):
        name, typ, nullable, default, description = fields
        columns.append(['armada', 'group_link_preview', len(columns) + 1, name, typ, nullable,
                        '__NULL__' if default in ('-', 'NULL', 'AUTO_INCREMENT') else default,
                        '', 'auto_increment' if default == 'AUTO_INCREMENT' else '', description])
        if name == 'owner_phone':
            columns.append(['armada', 'group_link_preview', len(columns) + 1, 'creator_phone_source',
                            'tinyint', 'NO', '2', '', '', '创建者号码来源:0未知 1老格式群JID推导 2协议确认或既有号码'])
    elif len(fields) == 3 and fields[2] in ('INDEX', 'UNIQUE', 'PRIMARY'):
        name, names, kind = fields
        for seq, col in enumerate(names.split(','), 1):
            indexes.append(['armada', 'group_link_preview', name, '1' if kind == 'INDEX' else '0', seq, col.strip(), 'BTREE'])
assert columns and indexes
with tempfile.TemporaryDirectory(prefix='armada-creator-model-') as temp:
    env = os.environ.copy()
    for key, rows in [('COLS', columns), ('IDX', indexes), ('TBL', [['armada', 'group_link_preview', '群链接协议预览元数据']])]:
        path = Path(temp) / (key + '.tsv')
        with path.open('w') as stream:
            csv.writer(stream, delimiter='\t', lineterminator='\n').writerows(rows)
        env['ARMADA_MODEL_' + key] = str(path)
    output = Path(temp) / 'model.md'
    env['ARMADA_MODEL_OUT'] = str(output)
    subprocess.run(['python3', str(root / '.harness/wiki/gen_datamodel.py')], env=env, check=True)
    generated = re.search(pattern, output.read_text(), re.S).group().rstrip() + '\n\n'
    wiki.write_text(re.sub(pattern, lambda _: generated, content, flags=re.S))
