# 历史群群主国家严格识别设计

## 背景

第一套测试环境的历史群管理中，账号组“混合劫持”有 10 个账号。页面当前展示的四个可管理历史群分别关联三次秘鲁号码 `51943333070` 和一次肯尼亚号码 `254713151300`，但国家均显示为加拿大。

只读排查确认，这四行的国家不是根据“关联账号”计算，而是根据群预览中的创建者字段 `group_link_preview.owner_phone` 计算。对应原始值为 `193088878297313` 和 `12306742263892`。它们是 Android 群列表 `creator` 经现有代码截取后保存的数字身份，形态符合 LID/内部身份，不能作为已确认的手机号。现有国家服务只做数字最长区号前缀匹配，因此两个值都以 `1` 开头并命中共享 `+1` 区号；国家主数据中加拿大排在美国之前，最终错误显示为加拿大。

## 已确认产品口径

- 历史群列表的“国家”表示群主/创建者号码所属国家，不表示关联账号国家。
- 只有已确认的真实群主手机号可以参与国家识别。
- creator 是 LID 时不能直接当手机号；但同一份协议响应若存在
  `participants[].jid == creator LID` 且该成员携带明确的 `phone_number`，允许把这组
  服务端同时返回的身份对解析为真实 PN。无法精确匹配、手机号格式无效、身份类型未知时，
  国家返回空，前端继续显示 `--`。
- 不允许回退到关联账号国家，也不允许仅凭数字开头猜测国家。
- 前端列名、列顺序和空值展示保持不变。

## 方案比较

### 方案一：只在历史群查询时校验

列表组装阶段使用严格手机号解析替代最长区号匹配。该方案能立即阻止当前两个异常值命中加拿大，改动较小，但协议身份仍在进入后端时丢失，后续其他消费者仍可能把 LID 当手机号。

### 方案二：只在协议映射时处理

Android mapper 根据 `addressing_mode` 区分 PN 和 LID，只为 PN 输出群主手机号。该方案能阻止新增错误值，但历史数据库中的异常值仍可能继续显示，其他写入入口也缺少防御。

### 方案三：协议身份归一化和后端严格校验

协议防腐层保留 creator 的身份类型，只显式产出已确认 PN；后端持久化不再从任意 `ownerJid` 截取数字，同时列表查询使用严格手机号解析兜底。该方案同时覆盖数据入口、持久化和展示出口，采用此方案。

## 总体设计

数据链路调整为：

```text
WhatsApp creator
  -> 协议 backend 识别 PN / LID / UNKNOWN
  -> LID creator 在同一响应内精确匹配 participant JID 与 phone_number
  -> 稳定结果分别携带 ownerJid、ownerPhone、ownerIdentityKind
  -> 群预览按观察态更新、清空或保留 owner_phone
  -> 历史群查询严格校验 owner_phone 并解析 ISO2
  -> 国家主数据补齐中文名和国旗
  -> 无法确认时返回 null，前端显示 --
```

本次不依赖前端判断身份，也不让历史群业务层出现 Android/Web 分支。协议差异只收敛在 backend mapper 和稳定协议结果中。

## creator 身份归一化

### Android

Android 当前群响应已经包含 `creator` 和 `addressing_mode`。`AndroidAccountParticipatingGroupMapper` 先分别从 addressing mode 与 JID 后缀推导身份，再按以下顺序收敛：

1. creator 为空时返回 UNKNOWN。
2. `addressing_mode=pn/lid` 分别得到 PN/LID；`@s.whatsapp.net`、`@lid` 后缀分别得到 PN/LID。
3. addressing mode 与后缀同时存在但结论冲突时返回 UNKNOWN，不任选其一。
4. 只有一方能确认身份时采用该结论；两方都不能确认时返回 UNKNOWN。
5. 最终身份为 LID 时，先在同一群的 `participants` 中按规范化 LID 精确匹配 `jid`；
   只在身份相等且该项存在合法 `phone_number` 时，把结果提升为已确认 PN。
6. LID 无匹配、匹配项无合法 `phone_number` 时，`ownerJid` 保留为完整 LID JID，
   `ownerPhone=null`。
7. 最终身份为 PN 或由精确 LID/PN 身份对确认时，`ownerJid` 规范为完整 PN JID，
   `ownerPhone` 只保留规范化的纯数字 user 部分。

UNKNOWN 始终令 `ownerPhone=null`，不猜测身份。

第一套环境只读核对确认，当前三个在线群均为 LID 寻址；每个 creator 都能与同一
`GroupInfos[].participants[].jid` 精确匹配，且匹配项均带 `phone_number`。因此优先在
Armada Android backend mapper 完成防腐，不要求新增 Zhuan HTTP 接口，也不依赖关联账号国家。

### Web/Baileys

Web 响应中的 owner 若带 `@s.whatsapp.net`，映射为 PN；若带 `@lid`，映射为 LID；空值或裸数字按 UNKNOWN 处理。当前轻量群列表未返回 owner 时保持 UNKNOWN，不为补国家而增加逐群 metadata 请求。

### 稳定结果模型

`AccountParticipatingGroupResult.Group` 增加明确的 `ownerPhone` 和 `ownerIdentityKind`，避免业务服务通过字符串后缀反推协议身份。`ownerIdentityKind` 取值为 `PN`、`LID`、`UNKNOWN`；
LID 经同响应中的精确身份对解析成功后，稳定结果按 PN 输出。

Kafka 账号群快照若没有 creator 身份信息，继续映射为 UNKNOWN；不扩展与本次问题无关的事件载荷。后续协议事件显式提供 `ownerPhone` 时可以自然映射为 PN。

## 群预览持久化

现有 `AccountGroupMembershipSnapshotServiceImpl.ownerPhone(...)` 会在 `ownerPhone` 为空时截取 `ownerJid` 的 `@` 前部分，这是 LID 进入 `group_link_preview.owner_phone` 的直接原因。该回退删除，持久化只接受稳定结果中显式确认的 `ownerPhone`。

预览更新需要区分三种观察语义：

| 身份观察 | `owner_phone` 行为 |
|---|---|
| PN | 写入规范化并通过基础格式校验的手机号 |
| LID | 清空现有 `owner_phone`，避免旧 LID 数字继续残留 |
| UNKNOWN | 保留现有值，避免轻量响应缺字段时抹掉以前已确认的 PN |

为表达该三态，在账号群同步的预览写入参数中增加非持久化的“群主身份已观察”语义，Mapper 使用 `CASE` 完成“写入、清空、保留”。不新增数据库列、索引或 Flyway 迁移。

首次插入预览时，只有 PN 写入 `owner_phone`；LID 和 UNKNOWN 都写空。邀请链接预览、账号群同步及其他可能写入 `owner_phone` 的入口统一复用同一身份规范化器，不能保留各自的“截取 `@` 前数字”实现。

## 国家严格解析

现有 IP 分配使用的 `resolveIpRegionByPhonePrefix` 和 `resolveIpRegionsByPhonePrefix` 保持最长前缀语义，不在本次修改，避免改变账号上线选代理逻辑。

历史群国家新增独立的严格解析方法，使用 libphonenumber 完成：

1. 只接受纯数字国际号码或明确的 `@s.whatsapp.net` user；
2. 按国际号码解析并校验号码有效性；
3. 获取号码对应的 ISO2，而不是只取共享国际区号；
4. 只返回国家主数据中启用且未删除的 ISO2；
5. 解析失败、号码无效、区域不确定或国家未启用时返回空；
6. 不回退到现有最长前缀算法。

这可以正确区分共享 `+1` 的加拿大和美国，也能避免无效的 `1...` 内部身份因国家排序命中加拿大。秘鲁 `+51`、肯尼亚 `+254` 等有效号码仍按号码本身解析。

`HistoricalGroupAccountGroupQueryService` 改用严格方法批量解析当前页的群主号码；国家主数据仍批量读取，避免逐行查询。

## 存量数据

- 部署后，历史群列表查询会先严格校验存量 `owner_phone`。当前两个异常值无法通过有效号码校验，因此在重新取得真实 PN 前显示 `--`。
- 用户再次点击“加载群列表”后，若 creator LID 能与同响应 participant 的 PN 精确匹配，
  预览写入真实 PN；明确 LID 但没有可用映射时清空对应旧 `owner_phone`。
- 不执行全表批量清理，因为旧数据没有身份类型字段，批量猜测可能误删真实 PN。
- 旧值即使暂时保留在数据库，只要无法通过严格解析，就不会再形成国家展示结果。

## API 与前端

历史群接口结构不变，继续返回：

- `countryIso2`
- `countryName`
- `countryFlag`

无法确认群主国家时三个字段均为空。前端现有 `row.countryName || "--"` 行为满足要求，无需修改 `wheel-saas-pure-web`。

## 失败与兼容策略

- creator 身份冲突或格式异常按 UNKNOWN 处理，不阻断群列表刷新。
- 单个群主号码解析失败只影响该行国家字段，不影响群关系、角色、邀请链接和成员操作。
- libphonenumber 不能识别的号码不回退前缀匹配。
- Web 轻量群列表没有 owner 时不新增额外协议请求，避免放大刷新时延。
- IP 分配和账号上线的国家前缀行为保持原样。
- 不修改历史群范围、分页、操作账号选择和权限判断。

## 测试与验收

### 协议映射测试

- Android PN creator 输出 `ownerPhone` 和 PN 身份。
- Android LID creator 与同群 participant JID/phone_number 精确匹配时输出 PN 身份与
  `ownerPhone`。
- Android LID creator 找不到匹配成员、匹配成员没有合法 `phone_number` 时只输出 LID
  身份，`ownerPhone=null`。
- Android 缺失或冲突的 addressing mode 输出 UNKNOWN。
- Web `@s.whatsapp.net`、`@lid`、空 owner 分别映射为 PN、LID、UNKNOWN。

### 持久化测试

- PN 观察写入或替换 `owner_phone`。
- LID 观察清空已有 `owner_phone`。
- UNKNOWN 观察保留已有 `owner_phone`。
- Mapper 测试加载真实 XML，验证三态 SQL 行为。

### 国家服务测试

- 有效加拿大和美国号码分别返回 `CA`、`US`。
- 无效的 `193088878297313`、`12306742263892` 返回空。
- 有效秘鲁和肯尼亚号码分别返回 `PE`、`KE`。
- LID JID、裸 LID、空值、未知国家均返回空。
- 现有 IP 国家最长前缀测试保持通过。

### 历史群业务测试

- 存量无效 owner 值返回空国家字段。
- 有效 PN 返回国家名称、ISO2 和国旗。
- 国家解析失败不影响该群其他展示字段。
- 第一套环境“混合劫持”当前在线响应覆盖的三行在刷新后展示真实群主国家；第四个未出现在
  在线账号实时群响应中的历史群保持 `--`，直到后续取得可确认 PN。
- 刷新后数据库不再把相关 LID 保存为 `owner_phone`：精确匹配成功写真实 PN，无法匹配则清空。

## 发布与回滚

发布顺序为 Armada 后端单体部署；本设计不要求同步发布前端，也不要求先部署 Android Zhuan。部署前运行聚焦单测、真实 Mapper XML 测试和后端构建。第一套测试环境部署后只读核对四行接口响应及刷新后的预览值。

回滚只需回退 Armada 后端版本。由于没有数据库迁移，回滚不需要数据脚本；刷新期间被确认是 LID 而清空的错误 `owner_phone` 不恢复，这是安全的数据纠正，不影响历史群范围或操作能力。

## 非目标

- 不把“国家”改为关联账号国家。
- 不增加“关联账号国家”列或第二个国家列。
- 不为 LID 强制发起额外网络请求；只消费当前群响应已经携带的 participant 身份对。
- 不批量修改第一套或其他环境的数据库数据。
- 不改变 IP 代理国家分配算法。
- 不修改历史群详情、成员管理、拉人或营销执行语义。
