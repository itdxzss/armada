"""从本次已提交格式的离线迁移更新数据模型说明，不连接数据库。"""
from pathlib import Path
root = Path(__file__).resolve().parents[3]
model = root / '.harness/wiki/数据模型.md'
migration = root / 'armada-api/src/main/resources/db/migration/V213__pull_task_simple_new_group.sql'
assert 'SIMPLE_NEW_GROUP' in migration.read_text()
start = '<!-- simple-new-group-schema-start -->'
end = '<!-- simple-new-group-schema-end -->'
block = f'''{start}

> V213 离线迁移：creation_mode 新增 SIMPLE_NEW_GROUP（新群模式（新）），仅更新注释，无新增表或列。
> 复用 GROUP_CREATE(9)、MANAGER_JOIN(2)、MANAGER_ADMIN(3)、CREATOR_DELETE(11)、CREATOR_DELETE_VERIFY(12)、DIRECT_PULLER_JOIN(10)、PULL_EXECUTION(6)、CLOSING(8)。
> 保留管理接管与可选注销，省略联系人和料子提权。该模式无来源链接分组，不从旧群资源池换群。
> 注销开关唯一持久化来源仍为 pull_task.is_creator_delete_after_takeover；本说明不代表远程已迁移。

{end}
'''
text = model.read_text()
if start in text:
    before, rest = text.split(start, 1)
    _, after = rest.split(end, 1)
    text = before + block + after.lstrip('\n')
else:
    text = text.replace('### · 拉群任务族\n', '### · 拉群任务族\n\n' + block, 1)
model.write_text(text)
