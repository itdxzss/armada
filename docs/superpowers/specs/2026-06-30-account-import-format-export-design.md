# 账号导入原格式导出设计 — armada

> 状态:**已 brainstorm 定稿,待用户审 → 转 writing-plans**(2026-06-30)
> 范围:账号导入批次的文件导出从 CSV 改为按导入容器导出。只保证本设计上线后的新增批次;历史批次不做补偿。
> 关联模块:`com.armada.account` 后端导入/导出链路,`wheel-saas-pure-web` 账号导入页下载链路。

---

## 1. 目标与非目标

**目标**:账号导入页保留现有「导出全部 / 导出成功 / 导出失败 / 导出异常」入口,但导出的文件格式跟导入时一致:
- 导入 ZIP → 导出 ZIP,包内为符合范围的 JSON 条目。
- 导入 TXT 或粘贴文本 → 导出 TXT,内容为符合范围的原始文本条目。

**非目标:**
- 不追溯历史批次。历史批次没有原始明细 payload,导出新格式时可返回业务错误「历史批次缺少原始导出材料」。
- 不改变导入格式枚举含义。`import_format=1/2/3` 仍表示六段/JSON/全参。
- 不新增异步导出任务。当前批次导出仍为同步 HTTP 文件下载。
- 不在列表、明细接口暴露敏感原始凭据内容。

---

## 2. 数据模型

新增字段只服务于文件还原,不参与列表展示。

### `account_import_batch`

新增 `source_file_type VARCHAR(16) DEFAULT NULL COMMENT '原始导入容器:ZIP/TXT'`。

写入规则:
- 上传 `.zip` 时写 `ZIP`。
- 上传 `.txt` 或前端粘贴文本时写 `TXT`。
- 历史行保持 `NULL`。

`source_file_type` 和现有 `import_format` 分工:
- `import_format`:凭据语义,例如 JSON号/全参账号。
- `source_file_type`:文件容器,决定导出响应是 zip 还是 txt。

### `account_import_detail`

新增:
- `raw_payload MEDIUMTEXT DEFAULT NULL COMMENT '单条原始导入内容,敏感,不得进入日志或列表响应'`
- `source_entry_name VARCHAR(512) DEFAULT NULL COMMENT '原始条目名:zip内路径或line-N'`

写入规则:
- ZIP:每个 `.json` entry 解析出的明细写入该 entry 原始 JSON 文本和 zip 内 entry 名。
- TXT/粘贴:每条明细写入对应原始文本;`source_entry_name` 写 `line-<lineNo>`。
- 解析失败但能定位到条目的行也必须写 `raw_payload`,确保「导出失败」能拿回原始内容。

敏感约束:
- `raw_payload` 等同凭据材料,日志只允许输出长度和脱敏手机号。
- 明细分页 VO 不增加 `rawPayload` 字段。
- Mapper 查询导出专用投影时才读取 `raw_payload`。

---

## 3. 导入链路

`ParsedEntry` 增加两个字段:`rawPayload`、`sourceEntryName`。

JSON ZIP:
- `AccountImportParser.parseJsonZip` 读取每个 `.json` entry 的原始 UTF-8 文本。
- 对单对象 entry 产出一条明细,`rawPayload=entry文本`,`sourceEntryName=zip entry name`。
- 对数组 entry 产出多条明细时,每条 `rawPayload` 写数组元素序列化后的 JSON 文本,`sourceEntryName` 写 `entryName + "#" + index`。这样导出 ZIP 时一条明细对应一个文件,范围过滤不会产生半个数组文件。
- 非 `.json` entry 继续忽略。

TXT/粘贴:
- JSON号非 zip 文件、全参账号文本、前端粘贴内容都归为 `TXT` 容器。
- 如果输入是 JSON 数组,每个数组元素作为一条导出文本记录;如果输入是单对象,只有一条记录。
- 导出 TXT 时按 `line_no` 升序拼接 `raw_payload`,条目之间用换行分隔。

批次写入:
- `AccountImportDTO` 增加 `sourceFileType` 或由 Controller 根据 multipart 文件名/是否 zip 字节判定后传给 Service。
- `AccountImportServiceImpl.buildBatch` 写入 `source_file_type`。
- `buildDetail` 写入 `rawPayload` 和 `sourceEntryName`。

---

## 4. 导出链路

保留接口路径:

`GET /api/account-imports/{batchId}/export?scope=all|success|fail`

现有前端「导出异常」仍映射到 `fail`,因为后端当前账号导入明细只有 `parse_result=1/2/3/4`,没有独立异常范围。

Service 返回文件对象:

```
AccountImportExportFile {
  String filename;
  String contentType;
  byte[] bytes;
}
```

导出规则:
- 查批次,校验 `source_file_type` 非空。
- 查符合 `scope` 的明细导出投影,必须包含 `lineNo/wsPhone/parseResult/rawPayload/sourceEntryName`。
- 如果没有匹配明细,返回空 ZIP 或空 TXT,前端仍可下载;这与「导出范围为空」语义一致。
- 如果任一匹配明细缺少 `raw_payload`,抛业务错误「该批次缺少原始导出材料」。

ZIP 生成:
- 使用 `ZipOutputStream` 内存生成。
- entry 名优先取 `source_entry_name`。若缺失或重名,使用 `line-<lineNo>-<wsPhone或unknown>.json` 并做去重后缀。
- entry 内容写 `raw_payload` UTF-8 字节。
- Content-Type:`application/zip`。
- filename:`account-import-<batchId>-<scope>.zip`。

TXT 生成:
- 按 `line_no` 升序写 `raw_payload`。
- 每条之间用 `\n` 分隔,不额外写 CSV 表头。
- Content-Type:`text/plain;charset=UTF-8`。
- filename:`account-import-<batchId>-<scope>.txt`。

Controller:
- 不再设置 `text/csv`。
- 使用 Service 返回的 `contentType/filename/bytes` 组装 `ResponseEntity<byte[]>`。
- 保留 attachment Content-Disposition。

---

## 5. 前端下载

`wheel-saas-pure-web` 调整点:
- `exportAccountImportTask` 改用 `responseType: "blob"` 或 `arraybuffer`。
- 从 `Content-Disposition` 解析文件名;解析不到时用后端格式兜底。
- 页面下载函数改为通用 `downloadFile(filename, blob)`。
- 删除浏览器侧 CSV BOM 拼接,避免对 ZIP/TXT 注入额外字符。

按钮和交互文案保持现状:
- 批次列表「导出」下拉仍保留。
- 明细抽屉「导出全部 / 导出失败」仍保留。
- 成功提示仍为「导出文件已生成」。

---

## 6. 错误处理

- `batchId` 为空或批次不存在:沿用 `BusinessException`。
- 历史批次或数据缺字段:`BusinessException(VALIDATION,"该批次缺少原始导出材料")`。
- ZIP 生成失败:抛运行时异常交由全局异常处理,对外提示「导出文件生成失败」。
- 导出过程不得打印 `raw_payload`、`creds_json` 明文。

---

## 7. 测试线

后端 DbTest/MockMvc:
- 新导入 ZIP 批次后,导出 `all` 返回 `application/zip`、`.zip` 文件名,zip entry 内容等于导入 JSON 原文。
- ZIP 批次 `scope=success` 只包含成功明细,`scope=fail` 只包含重复/格式错误/凭据不全明细。
- TXT/粘贴批次导出返回 `text/plain`、`.txt` 文件名,内容按 `line_no` 升序拼接原始条目。
- 历史样式批次缺 `source_file_type/raw_payload` 时返回业务错误。
- 明细分页接口不返回 `raw_payload`。

前端验证:
- API 下载响应不再按 text 解析。
- 下载函数对 zip/txt 都不加 BOM。
- 文件名来自后端响应头。

---

## 8. 决策日志

| 决策 | 结论 |
|---|---|
| 历史批次 | 不处理,缺原始材料时报业务错误 |
| 数据保存粒度 | 明细级保存 `raw_payload`,保证成功/失败范围可过滤导出 |
| 批次容器字段 | 只加 `source_file_type=ZIP/TXT`,不加语义重复的 `source_container` |
| ZIP 数组 entry | 拆成一条明细一个导出文件,避免范围过滤破坏原数组文件 |
| 前端下载 | 改 blob 下载,以后端 Content-Disposition 为准 |
