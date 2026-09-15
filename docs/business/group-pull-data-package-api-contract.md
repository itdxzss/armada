# 拉群数据包实施接口合同

日期：2026-09-15。用于本次前后端协作；以实现及测试逐项核实。资源四表，沿用当前标准拉群执行链。

## 通用

- API统一返回现有 `{code,message,data}`；分页使用 `{list,page,pageSize,total,totalPages}`，以现有PageResult序列化为准。
- 路径前缀 `/api/group-data-packages`。当前租户来自认证上下文，客户端不得指定tenantId。
- 权限前缀 `tenant:group_data_package:`，操作为view/create/edit/import/export/delete；任务选包同时要求可查看数据包和可创建拉群任务。
- 名称trim后非空，最多128字符；备注最多512字符；时间epoch毫秒；空值与现有响应规范一致。
- 状态：`UNUSED=1, CLAIMED=2, SUCCESS=3, RETRYABLE_FAILED=4, PRIVACY_REJECTED=5, UNREGISTERED=6, UNKNOWN=7`；HTTP号码status使用枚举名。

## 资源接口

| 方法/路径 | 入参 | data或下载内容 |
|---|---|---|
| GET 根路径 | page,pageSize,name,countryIso2,continent,usageBusiness,createdFrom,createdTo,forTask | PageResult<ListItem> |
| GET /countries | 无 | `{iso2,nameZh,continent}[]` |
| GET /{id} | id | ListItem |
| POST 根路径 | `{name,remark}` | ListItem |
| PUT /{id} | `{name,remark,version}` | ListItem |
| DELETE /{id} | 无 | 无；活动占用不得被清除 |
| POST /{id}/import | multipart file,mode=`append/overwrite`,privacyFilterDays=`0..365`默认60 | ImportResult |
| GET /{id}/phones | page,pageSize,phone,status | PageResult<PhoneItem> |
| GET /{id}/imports | page,pageSize | 导入审计分页 |
| POST /{id}/reset-failed | 无 | 实际重置数量 |
| GET /{id}/export | usageStatus,format | TXT/CSV下载 |
| POST /export | `{ids,usageStatus,format}` | 所选包TXT/CSV下载 |

`usageStatus=all/unused/success/failed/privacy_rejected`；`format=txt/csv`，默认txt。
CSV包含包名、手机号、管理员标记、状态等实际上下文；TXT为稳定顺序的原料子格式（需要管理员时追加A）。
导出要求权限及包归属，不要求先被任务用过；未知/占用不会被当成未使用导出。

### ListItem

`id,name,remark,generation,version,primaryCountryIso2,continent,usageBusinesses,metrics,createdAt,updatedAt`。

`usageBusinesses`仅返回实际引用业务，本次标准拉群为`STANDARD_PULL`；无引用为空数组。
`metrics={totalCount,unusedCount,claimedCount,successCount,failedCount,privacyRejectedCount,unregisteredCount,unknownCount}`。
`failedCount`包括可重试失败、隐私拒绝、已确认未注册；子类不重复加到总数。
`totalCount=unusedCount+claimedCount+successCount+failedCount+unknownCount`；已使用按`totalCount-unusedCount`派生。
主要国家按当前代号码量计算；大洲由国家元数据提供。

### PhoneItem

`id,phone,adminRequired,memberSeq,sourceLineNo,countryIso2,status,createdAt`。
号码保留首次有效出现顺序；同号重复行任一带A/a时，尚未分配的号码保留管理员要求。已冻结任务内容不可更改。

### ImportResult

`importId,mode,generation,totalRows,acceptedRows,invalidRows,duplicatedRows,privacyFilteredRows,phoneCountAfterImport`。
单次最多100,000个有效去重号码；7–15位国际号码，允许现有拉群展示字符清洗和A/a标记。
默认追加；覆盖成功才原子切换版本。失败审计保留，但失败不会清空原包。
隐私过滤只使用本租户可核实的历史拉群拒绝事实；0表示不筛。

## 标准拉群接入

- `POST /api/pull-tasks/standard/draft/data-packages`：JSON `{creationMode,packageIds:number[],groupFolderId?,linksText?}`，返回既有PullTaskStandardDraftVO；后两个可选参数沿用粘贴链接模式的原合同。
- 每个选中的包形成一份料子执行单元，多个包按用户选中顺序形成多个执行单元。展示包名与实际可用数量，避免隐式拆分或混合。
- 草稿保存来源号码快照；正式create时锁定和复核包的代次、可用状态，再原子领取。预览后发生冲突须重新选择，不能悄悄减少号码。
- 来源记录可空，原TXT路径继续有效。换群重试沿用来源和分配版本，不二次领取或扣数。
- 暂停保留占用；结束仅归还确定没有提交的号码；已提交未确认保持UNKNOWN或占用，等待原有执行链收敛。

## 功能依赖

用户已明确选择点击趋势后续接入，本次交付菜单及拉群取用/回写闭环。后续趋势需要真实群消息/短链与包关联，本次不返回伪造PV/UV或固定空曲线。
资源统计表不预留点击列；不为本菜单增加通用消费框架、领取流水、独立对账调度器。


## 已落实的容量与口径

- 单次TXT最多10MB、100000个有效去重号码；包内当前号码容量500000。
- 选包一次1–50个，保持选择顺序；每包的全部未使用号码构成一个料子执行单元。超过100000个未使用号码明确拒绝，不截断、不自动拆群。
- 大洲使用国家主数据长枚举：ASIA、EUROPE、AFRICA、NORTH_AMERICA、SOUTH_AMERICA、OCEANIA、ANTARCTICA。
- `totalRows` 是原始物理行数，空行不计入invalidRows；sourceLineNo保留物理行号。文件内及包内重复A标记合并。
- 列表摘要和列表CSV明确是当前页；号码状态导出按所选数据包在服务器执行，总导出最多500000号码、最多100包。
- 资源GET直接读号码和统计，不触发整包任务历史回写。正式快照、任务结果和生命周期写入维护实际状态。
