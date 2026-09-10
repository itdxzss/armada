# 养群增量 API

统一沿用 ApiResponse 和 PageResult。权限前缀 tenant:script_marketing:；读取 view，创建 create，编辑 edit，任务操作 operate。本人任务/剧本定义与租户边界在服务端校验。

| 方法和路径 | 行为 |
|---|---|
| GET /api/script-materials | 公共完整消息素材分页；keyword / linkMode / page / pageSize |
| POST /api/script-materials | 新建共享消息素材，MarketingTemplateDTO |
| PUT /api/script-materials/{id} | 更新共享消息素材 |
| POST /api/script-materials/{id}/clone | 复制素材 |
| GET /api/script-definitions | 本人剧本摘要分页；keyword / status(0停用,1启用) / page / pageSize |
| GET /api/script-definitions/{id} | 完整剧本定义 |
| POST /api/script-definitions | 保存新定义；name / enabled / steps |
| PUT /api/script-definitions/{id} | 更新定义；不追改任务 |
| DELETE /api/script-definitions/{id} | 软删本人定义 |
| GET /api/script-marketing-tasks/options/account-groups | 当前租户推手分组 |
| GET /api/script-marketing-tasks/options/groups | 必填 accountGroupId，另支持 keyword / page / pageSize |
| POST /api/script-marketing-tasks/check | 完整任务草稿入参，只读检查 |
| GET /api/script-marketing-tasks/{id}/check | 重新检查本人任务；启动后复核原绑定 |
| POST /api/script-marketing-tasks/{id}/groups/{groupId}/pause | 暂停单群 |
| POST /api/script-marketing-tasks/{id}/groups/{groupId}/resume | 检查原绑定后继续单群 |

既有任务增改接口的 ScriptMarketingSaveDTO 增加必填 accountGroupId。steps 的每项增加 roleKey / waitMinSeconds / waitMaxSeconds，保留 role / accountId / message；推手 accountId 必须为空，任务内管理员 accountId 必填，剧本定义的所有 accountId 为空。

任务列表/详情补充 accountGroupId、pauseReason；群详情补充 bindingsJson、paused、pauseReason。发送记录的 submittedAt 保留原提交时间；记录结果通过原 commandId 关联。

检查报告字段：ready、accountCount、requiredPromoters、poolReason、checkedAt、groups。每群字段为 groupLinkId、groupJid、groupName、ready、required、available、shortage、offline、noPermission、unconfirmed、reasons。

检查接口正常返回 code=0，data.ready 表示资格；启动/恢复因资格失败返回非零业务码，并把完整同型报告放在 data。业务失败不能当启动成功。前端读取 ArmadaApiError.data 展示明细；重新检查不触发启动。

约束：2–100 项、至少一种管理员和一种推手角色；1–100 个不同群；roleKey 1–32 字且首尾不空白，同名角色类型/管理员账号一致；逐项范围 0–86400 秒，min≤max；任务默认间隔 1–86400 秒；可选 endAt 晚于当前及 startAt。
