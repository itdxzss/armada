# IP 代理 TXT 导入格式门禁设计

## 1. 背景

IP 管理的 TXT 批量导入已经有两个连续动作:

1. 用户选择分配方式、国家、代理类型、来源和 TXT 文件。
2. 前端先做格式校验,通过后调用 `POST /api/ip-proxies/import/sample-check` 随机抽检最多 5 条 IP。

当前前端已经有轻量格式校验,能够在发现空行等格式问题时阻断抽检并在导入弹框下方展示错误。但后端抽检入口仍会通过 `LineImporter` 跳过空行,并过滤格式失败行后继续抽检剩余合法行。绕过前端或前后端口径不一致时,坏 TXT 仍可能进入抽检流程。

本次需求要求:上传 TXT 后先做格式检测;只要第一次碰到格式错误,就按现有异常/错误展示方式停止;格式无误后才打开抽检弹框并抽检 5 条 IP。

## 2. 目标

- TXT 文件格式校验采用 fail-fast:从第一行开始扫描,遇到第一条格式错误立即停止。
- 格式错误包括空行、字段数量不是 4、host/username/password 为空、端口不是正整数。
- 格式错误时不调用代理检测器,不打开抽检结果弹框,不写入 IP。
- 格式错误提示沿用前端现有导入弹框下方错误区域,标题为“上传的文件中存在格式错误数据”。
- 格式完全通过后,再沿用现有抽检逻辑:过滤批内重复和库内已有行,随机抽最多 5 条新增候选检测。
- 后端抽检入口增加同等门禁,正式导入入口也复用同一门禁作为兜底。

## 3. 非目标

- 不改变 TXT 支持格式,仍只接受 `host:port:username:password`。
- 不新增导入任务表、检测 token 或新的抽检会话状态。
- 不改变抽检成功/失败结果结构。
- 不改变 IP 分配方式码值,继续使用 `smart` 和 `mixed`。
- 不改 IP 代理检测器的真实出口与 WhatsApp 连通性语义。
- 不新增数据库迁移。

## 4. 当前实现与缺口

### 后端

- `IpProxyImportDTO` 已有 `allocationMode`,支持 `smart` 和 `mixed`。
- `IpProxyServiceImpl#importProxies` 和 `sampleCheckImport` 复用 `importOutcomes(...)` 解析 TXT。
- `LineImporter` 会跳过空行,因此不能作为“空行不允许”的唯一校验器。
- `sampleCheckImport` 当前会从 `Kind.PERSISTED` 行生成候选,格式失败行不会阻断抽检。

### 前端

- `wheel-saas-pure-web/src/views/resource/ip/ip-import-format.ts` 已有 `validateIpImportTextFormat(...)`。
- `useResourceIpPage#sampleCheckImport` 读取 TXT 后先调用本地校验,失败时设置:
  - `importCheckErrorTitle = "上传的文件中存在格式错误数据"`
  - `importCheckErrors = [第一条错误]`
  - `showImportSampleCheckDialog = false`
- 前端正常路径已经符合需求,但后端兜底异常目前仍主要走 toast,需要把格式类异常映射回导入弹框下方错误区域。

## 5. 设计

### 5.1 格式门禁

在后端 `IpProxyServiceImpl` 中新增一个专用的原文格式门禁,在进入 `LineImporter` 前执行。

规则:

1. 使用 `text.split("\\R", -1)` 保留空行位置。
2. 从第 1 行开始逐行 trim。
3. 如果 trim 后为空,抛 `BusinessException(ErrorCode.VALIDATION, "上传的文件中存在格式错误数据：" + 第一条行级错误)`。
4. 如果 `split(":", -1)` 后字段数不是 4,抛同类异常。
5. 如果 host、username、password 为空,抛同类异常。
6. 如果 port 不是正整数,抛同类异常。
7. 全部通过后才允许进入批内去重、库内去重和抽检。

行级错误文案与前端保持一致:

- `第 N 行：格式错误，空行不允许`
- `第 N 行：格式错误，应为 代理地址:端口:用户名:密码`
- `第 N 行：格式错误，存在空字段`
- `第 N 行：格式错误，端口必须为正整数`

为避免改变统一异常结构,后端只使用现有 `BusinessException` 的 `message` 承载格式错误。message 固定为:

```text
上传的文件中存在格式错误数据：第 N 行：具体原因
```

前端 catch 到该前缀时,把前缀放入 `importCheckErrorTitle`,把冒号后的行级错误放入 `importCheckErrors[0]`。这样仍是现有 `{code,message,data}` 业务异常结构,页面展示也保持在导入弹框下方。

### 5.2 抽检流程

`sampleCheckImport` 的顺序调整为:

1. `normalizeImport(dto)`
2. `validateImport(normalized)` 校验协议、来源、内容、分配方式。
3. `validateImportTextFormatOrThrow(normalized.text())` 做 fail-fast 格式门禁。
4. `importOutcomes(normalized)` 解析并批内去重。
5. `importCandidates(...)` 取本批唯一候选。
6. `filterNewCandidates(...)` 过滤库内已有行。
7. `randomImportSamples(...)` 抽最多 5 条。
8. `detector.check(...)` 执行真实检测并返回现有 `IpProxyImportSampleCheckVO`。

格式门禁失败时,流程停在第 3 步,不查询库、不调用 `detector.check(...)`。

### 5.3 正式导入流程

`importProxies` 也在 `importOutcomes(...)` 前调用同一门禁。

理由:

- 前端正常路径要求抽检通过后才能导入,因此正式导入通常不会再遇到格式错误。
- 如果有人绕过前端直接调用导入接口,坏文件不应部分入库。
- 这能让“导入文件先格式校验”成为后端业务规则,而不是仅靠页面约束。

门禁通过后,正式导入仍沿用现有统计口径:

- `insertedRows`: 实际新增行数。
- `skippedRows`: 批内重复 + 库内已存在。
- `failedRows`: 格式门禁通过后应为 0。
- `errors`: 格式门禁通过后应为空。

### 5.4 前端展示

前端保留现有本地校验作为第一道门。

当本地校验失败:

- 不请求 `/api/ip-proxies/import/sample-check`。
- 不打开抽检结果弹框。
- 在 TXT 导入弹框下方展示错误。
- toast 仍提示“上传的文件中存在格式错误数据”。

当后端兜底返回格式类校验异常:

- `sampleCheckImport` 的 catch 分支识别该类错误。
- 设置 `importCheckErrorTitle = "上传的文件中存在格式错误数据"`。
- 设置 `importCheckErrors` 为后端 message 中的第一条行级错误。
- 保持 `showImportSampleCheckDialog = false`。
- 不点亮 `importCheckPassed`。

### 5.5 分配方式

IP 管理 TXT 导入保留现有分配方式选项:

- `smart`: 智能分配,需要选择真实国家,入库到对应国家池。
- `mixed`: 混合国家,不需要选择真实国家,统一提交 `countryValue=MIXED`,入库到 `混合（不限国家）` 池。

本次只收紧导入前格式门禁,不改变后续账号上线分配策略。

## 6. 数据流

成功路径:

1. 用户选择 TXT。
2. 前端读取文件文本。
3. 前端格式校验通过。
4. 前端调用 `/api/ip-proxies/import/sample-check`。
5. 后端再次格式校验通过。
6. 后端从实际会新增的候选中抽最多 5 条。
7. 后端返回抽检结果。
8. 抽检全部通过后,前端允许点击“开始导入”。
9. 正式导入再次通过格式门禁后入库。

失败路径:

1. 用户选择 TXT。
2. 前端或后端发现第一条格式错误。
3. 立即停止后续流程。
4. 弹框下方展示“上传的文件中存在格式错误数据”和第一条行级错误。
5. 抽检弹框不打开,导入按钮保持不可用。

## 7. 错误处理

- 批次级字段错误继续使用现有提示,例如“请选择国家”“请输入来源”“代理类型不能为空”。
- TXT 格式错误统一归为可恢复业务异常,后端使用 `BusinessException(ErrorCode.VALIDATION, ...)`。
- 格式错误不落导入统计,因为本次口径是文件级门禁,不是逐行部分成功。
- 检测失败仍返回 `IpProxyImportSampleCheckVO.passed=false`,这是抽检业务失败,不是格式异常。

## 8. 测试

### 后端单测

在 `IpProxyServiceImplTest` 覆盖:

- `sampleCheckImport` 遇到空行时抛校验异常,不调用 mapper 去重,不调用 detector。
- `sampleCheckImport` 遇到字段数错误时抛校验异常。
- `sampleCheckImport` 遇到空字段时抛校验异常。
- `sampleCheckImport` 遇到非法端口时抛校验异常。
- 格式全部合法时继续抽最多 5 条,保持现有抽检结果。
- `importProxies` 遇到格式错误时整体拒绝,不调用 insert。

### 前端单测

在 `useResourceIpPage.test.ts` 覆盖:

- 空行阻断抽检并展示下方错误。
- 字段数错误阻断抽检。
- 端口非法阻断抽检。
- 格式通过后才请求 `/api/ip-proxies/import/sample-check`。
- 后端返回格式校验异常时,错误进入导入弹框下方错误区域。

### 验证命令

后端:

```bash
mvn -q -Dtest=IpProxyServiceImplTest,IpProxyControllerTest test
```

前端:

```bash
node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/resource/ip/composables/useResourceIpPage.test.ts
node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/resource-ip.test.ts
```

## 9. 风险与约束

- `LineImporter` 当前设计会跳空行,不能直接改成“不允许空行”,否则可能影响群链接等其它导入功能。本次只在 IP TXT 导入前加专用门禁。
- 前端和后端需要共享同一组格式错误口径。实现时应避免出现前端认为合法、后端认为非法的情况。
- 正式导入改为格式错误整体拒绝后,历史“部分成功 + failedRows”口径对格式错误不再适用;这是本次需求的有意收紧。
- 不打印代理用户名、密码或完整代理 URL。

## 10. 验收标准

- TXT 中第一个错误是空行时,页面下方展示“第 N 行：格式错误，空行不允许”,不打开抽检弹框。
- TXT 中第一个错误是字段数错误、空字段或端口非法时,行为同上。
- 格式错误时后端抽检接口不调用真实代理检测器。
- 格式全部合法时,前端打开抽检结果弹框,后端最多抽检 5 条新增候选 IP。
- 抽检通过后才能正式导入。
- 绕过前端直接调用正式导入接口提交坏 TXT 时,后端整体拒绝且不新增 IP。
