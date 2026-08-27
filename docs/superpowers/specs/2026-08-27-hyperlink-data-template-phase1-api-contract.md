# 超链数据包与超链营销模板一期 API 合同

> **冻结版本：v1，2026-08-27。** 本文是前后端并行开发的字段级合同。
> 业务语义和数据库结构分别见
> `2026-08-27-hyperlink-data-template-phase1-design.md` 与
> `../../business/hyperlink-marketing-data-model.md`。如果示例与本文冲突，以本文的 HTTP 路径、
> 参数名、JSON 字段、空值和枚举定义为准；任何合同变更必须先修改本文并通知四个开发分支。

一期只实现数据包、模板和模板图片兼容接口。不得创建任务、策略、点击、分析、导出或
`resource_asset` 的空接口。

## 1. 快速索引

| 业务 | Method | Path | 返回 `data` |
|---|---|---|---|
| 数据包列表/任务候选 | GET | `/api/data-packages` | `PageResult<DataPackageListItem>` |
| 数据包详情 | GET | `/api/data-packages/{id}` | `DataPackageDetail` |
| 创建数据包 | POST | `/api/data-packages` | `DataPackageDetail` |
| 编辑数据包 | PUT | `/api/data-packages/{id}` | `DataPackageDetail` |
| 导入号码 | POST | `/api/data-packages/{id}/import` | `DataPackageImportResult` |
| 号码明细 | GET | `/api/data-packages/{id}/phones` | `PageResult<DataPackagePhoneItem>` |
| 国家候选 | GET | `/api/data-packages/countries` | `DataPackageCountryOption[]` |
| 删除数据包 | DELETE | `/api/data-packages/{id}` | `null` |
| 模板列表 | GET | `/api/hyperlink-templates` | `PageResult<HyperlinkTemplateListItem>` |
| 模板详情 | GET | `/api/hyperlink-templates/{id}` | `HyperlinkTemplateDetail` |
| 模板候选 | GET | `/api/hyperlink-templates/options` | `HyperlinkTemplateOption[]` |
| 创建模板 | POST | `/api/hyperlink-templates` | `HyperlinkTemplateDetail` |
| 编辑模板 | PUT | `/api/hyperlink-templates/{id}` | `HyperlinkTemplateDetail` |
| 复制模板 | POST | `/api/hyperlink-templates/{id}/copy` | `HyperlinkTemplateDetail` |
| 删除模板 | DELETE | `/api/hyperlink-templates/{id}` | `null` |
| 上传模板图片 | POST | `/api/marketing-template-files` | `MarketingTemplateFileUploadResult` |
| 读取模板图片 | GET | `/api/marketing-template-files/{id}/content` | JPEG 二进制 |

一期**不实现**竞品兼容路径 `/api/admin/**`，统一沿用 Armada 租户接口 `/api/**`。

## 2. 通用约定

### 2.1 响应信封

除图片内容接口外，所有接口返回现有 `ApiResponse<T>`：

```typescript
interface ApiResponse<T> {
  code: number;
  message: string;
  data: T | null;
}
```

成功：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

可恢复业务错误沿用当前 `GlobalExceptionHandler`：HTTP 状态为 **200**，`data=null`，前端以
`code !== 0` 判断失败。禁止新增一套超链专用错误信封。

| HTTP | `code` | 含义 |
|---:|---:|---|
| 200 | `40001` | 参数、文件、URL、消息内容或状态校验失败 |
| 200 | `40401` | 当前租户下资源不存在或已软删 |
| 200 | `40901` | 名称、版本、并发导入或引用冲突 |
| 200 | `50000` | 未预期异常；只由全局兜底产生，业务代码不得主动返回 |
| 401 | `40104` | 登录失效 |
| 403 | `40302` | 没有接口权限 |
| 503 | `50301` | 认证基础设施不可用 |

前端继续使用 `armadaRequest` 拆包并展示服务端 `message`。一期不修改全局响应模型。

### 2.2 分页、时间与空值

分页接口统一返回：

```typescript
interface PageResult<T> {
  list: T[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
}
```

- `page` 从 1 开始，缺省或小于 1 时为 1。
- `pageSize` 缺省或非正数时为 10，最大 1000；页面列表显式传 20，号码抽屉显式传 50。
- 时间字段和查询参数均为 epoch 毫秒 `number`，语义是 UTC 时刻，前端按 Asia/Shanghai 展示。
- 响应对象中定义的字段必须存在；无值用 `null`，数组无值用 `[]`，计数无值用 `0`。
- 请求中的可空字符串先 trim；trim 后空串与 `null` 等价。
- JSON 字段使用 camelCase，数据库列使用 snake_case；不得把数据库列名直接暴露给前端。
- 新表使用自增 `BIGINT`，一期前端 ID 类型沿用项目现状定义为 `number`。

### 2.3 固定枚举

| 类型 | API 值 | 数据库存储 | 说明 |
|---|---|---:|---|
| `DataPackageImportMode` | `APPEND` | 1 | 追加当前代 |
|  | `OVERWRITE` | 2 | 写下一代并原子切换 |
| `DataPackagePoolStatus` | `UNUSED` | 1 | 未使用 |
|  | `CLAIMED` | 2 | 已领取 |
|  | `SENT` | 3 | 当前停留在单钩 |
|  | `DELIVERED` | 4 | 已送达 |
|  | `RETRYABLE_FAILED` | 5 | 可重试失败 |
|  | `UNREGISTERED` | 6 | 确认未注册 |
| `HyperlinkMessageType` | `1` | 1 | `SINGLE_LINK_PREVIEW`，一期开放 |
|  | `2` | 2 | `DOUBLE_IMAGE_TEXT`，一期明确拒绝 |
|  | `3` | 3 | `NORMAL_BUTTON`，一期开放 |
|  | `4` | 4 | `CARD_BUTTON`，一期开放 |
| `HyperlinkButtonType` | `CTA_URL` | JSON 字符串 | 一期唯一开放按钮类型 |

国家未知筛选值固定为字符串 `UNKNOWN`；真实国家使用大写 ISO2，例如 `PH`。`UNKNOWN` 只用于
查询参数和下拉 `value`，数据库 `country_iso2` 仍保存 `NULL`。

## 3. 共享类型

前端两个 API 文件应直接按以下形状定义类型，不自行创建 snake_case 适配层。

```typescript
export type DataPackageImportMode = "APPEND" | "OVERWRITE";
export type DataPackagePoolStatus =
  | "UNUSED"
  | "CLAIMED"
  | "SENT"
  | "DELIVERED"
  | "RETRYABLE_FAILED"
  | "UNREGISTERED";

export type HyperlinkMessageType = 1 | 2 | 3 | 4;
export type HyperlinkButtonType = "CTA_URL";

export interface DataPackageMetrics {
  totalCount: number;
  unusedCount: number;
  usedCount: number;
  sentCount: number;
  deliveredCount: number;
  failedCount: number;
  unregisteredCount: number;
  clickUvCount: number;
}

export interface DataPackageListItem {
  id: number;
  name: string;
  remark: string | null;
  countries: Array<string | null>;
  metrics: DataPackageMetrics;
  version: number;
  createdAt: number;
  updatedAt: number;
}

export interface DataPackageDetail extends DataPackageListItem {
  currentGeneration: number;
}

export interface DataPackagePhoneItem {
  id: number;
  generation: number;
  phone: string;
  countryIso2: string | null;
  poolStatus: DataPackagePoolStatus;
  sourceImportId: number;
  createdAt: number;
}

export interface DataPackageCountryOption {
  value: string;
  countryIso2: string | null;
  nameZh: string;
}

export interface DataPackageImportResult {
  importId: number;
  mode: DataPackageImportMode;
  generation: number;
  totalRows: number;
  acceptedRows: number;
  invalidRows: number;
  duplicatedRows: number;
  phoneCountAfterImport: number;
}

export interface HyperlinkButton {
  type: HyperlinkButtonType;
  displayText: string;
  targetValue: string;
  useShortLink: boolean;
  sort: number;
}

export interface HyperlinkMessageContent {
  schemaVersion: 1;
  messageType: HyperlinkMessageType;
  title: string;
  content: string | null;
  linkDescription: string | null;
  promotionLink: string | null;
  buttons: HyperlinkButton[];
  cardText: string | null;
  linkPreviewAssetId: number | null;
  bodyMainAssetId: number | null;
}

export interface HyperlinkTemplateListItem {
  id: number;
  name: string;
  messageType: HyperlinkMessageType;
  title: string;
  linkPreviewAssetId: number | null;
  linkPreviewAssetUrl: string | null;
  bodyMainAssetId: number | null;
  bodyMainAssetUrl: string | null;
  taskRefCount: number;
  version: number;
  createdBy: number | null;
  createdAt: number;
  updatedAt: number;
}

export interface HyperlinkTemplateDetail extends HyperlinkMessageContent {
  id: number;
  name: string;
  remark: string | null;
  linkPreviewAssetUrl: string | null;
  bodyMainAssetUrl: string | null;
  taskRefCount: number;
  version: number;
  createdBy: number | null;
  createdAt: number;
  updatedAt: number;
}

export interface HyperlinkTemplateOption {
  id: number;
  name: string;
  messageType: HyperlinkMessageType;
  title: string;
  version: number;
}

export interface MarketingTemplateFileUploadResult {
  id: number;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  url: string;
}
```

两个素材 URL 的生成规则固定为 `/api/marketing-template-files/{assetId}/content`；素材 ID 为
`null` 时对应 URL 也必须为 `null`。

## 4. 使用数据包接口

### 4.1 查询列表和未来任务候选

```http
GET /api/data-packages?page=1&pageSize=20&name=菲律宾&createdFrom=1787846400000&createdTo=1787932799999&countryIso2s=PH,ID&forTask=false
```

| 参数 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `page` | int | 否 | 默认 1 |
| `pageSize` | int | 否 | 默认 10，最大 1000 |
| `name` | string | 否 | trim 后按包名模糊匹配，最大 128 |
| `createdFrom` | long | 否 | 创建时间下界，包含 |
| `createdTo` | long | 否 | 创建时间上界，包含；不得小于 `createdFrom` |
| `countryIso2s` | string | 否 | 逗号分隔，例 `PH,ID,UNKNOWN`；去重并转大写 |
| `forTask` | boolean | 否 | 默认 false；true 时只返回 `unusedCount>0` 的有效包 |

禁止把国家数组交给 `qs` 自由序列化；前端必须显式执行 `countryIso2s.join(",")`。

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "list": [
      {
        "id": 101,
        "name": "菲律宾新客",
        "remark": "8 月活动",
        "countries": ["PH"],
        "metrics": {
          "totalCount": 4800,
          "unusedCount": 4800,
          "usedCount": 0,
          "sentCount": 0,
          "deliveredCount": 0,
          "failedCount": 0,
          "unregisteredCount": 0,
          "clickUvCount": 0
        },
        "version": 1,
        "createdAt": 1787881200000,
        "updatedAt": 1787881200000
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1,
    "totalPages": 1
  }
}
```

列表约束：

- `countries` 来自当前页包、当前 generation 的 DISTINCT 国家；按 ISO2 升序，`null` 放最后。
- `failedCount = retryableFailedCount + unregisteredCount`，与 `unregisteredCount` 有意重叠。
- `sentCount` 是当前停留在单钩的数量，不是历史累计发送数。
- 一期 `clickUvCount` 恒为 0，不落数据库列。
- 列表只能 JOIN `data_package_stat`，不得对号码表做分页级 GROUP BY。
- `forTask=true` 只改变过滤条件，不改变响应类型。

### 4.2 创建、查看、编辑和删除

创建：

```http
POST /api/data-packages
Content-Type: application/json
```

```json
{
  "name": "菲律宾新客",
  "remark": "8 月活动"
}
```

校验：`name` trim 后 1～128 字符，同租户有效名称唯一；`remark` 可空，最长 255。
成功返回完整 `DataPackageDetail`，新包 `currentGeneration=1`，所有指标为 0，`version=1`。

详情：

```http
GET /api/data-packages/101
```

`data` 为 `DataPackageDetail`，在列表字段基础上增加 `currentGeneration`。

编辑采用完整元数据更新，不是 PATCH：

```http
PUT /api/data-packages/101
Content-Type: application/json
```

```json
{
  "name": "菲律宾新客第二批",
  "remark": "已复核",
  "version": 1
}
```

`version` 必填且大于 0。成功返回更新后的 `DataPackageDetail`，`version+1`；旧版本返回
`code=40901` 和“数据包已被其他人修改，请刷新后重试”。一期不实现额外的 `/name` 兼容端点。

删除：

```http
DELETE /api/data-packages/101
```

成功响应固定为：

```json
{ "code": 0, "message": "ok", "data": null }
```

删除为软删除。不存在或已删除统一返回 `40401`，不泄露跨租户资源是否存在。

### 4.3 导入 TXT

```http
POST /api/data-packages/101/import
Content-Type: multipart/form-data
```

multipart 字段：

| 字段 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `mode` | string | 是 | `APPEND` 或 `OVERWRITE`，大小写敏感 |
| `file` | file | 是 | UTF-8 `.txt`，允许 BOM；文件名最长 255 |

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "importId": 9001,
    "mode": "APPEND",
    "generation": 1,
    "totalRows": 1200,
    "acceptedRows": 1160,
    "invalidRows": 10,
    "duplicatedRows": 30,
    "phoneCountAfterImport": 5960
  }
}
```

计数口径：

- `totalRows` 是非空行数；空行不计入。
- `invalidRows` 是 trim 后不满足 6～20 位纯数字的行数。
- `duplicatedRows` 包含文件内合法重复；追加时还包含与当前代重复的号码。
- 覆盖时不把旧代同号算重复。
- `acceptedRows` 是本次实际插入数。
- 对合法非空行，`acceptedRows + invalidRows + duplicatedRows = totalRows`。
- 空文件或覆盖清洗后 0 条合法唯一号码返回 `40001`，不切代。
- 追加全部重复允许成功，`acceptedRows=0`，generation、总数和统计不变。
- 超过 5000 个非空行返回 `40001`；单包超过配置阈值时整体拒绝。
- 同一包有并发导入时串行等待或返回 `40901`，不得出现两个 current generation。

### 4.4 查询号码明细

```http
GET /api/data-packages/101/phones?page=1&pageSize=50&phone=639&poolStatus=UNUSED&countryIso2=PH
```

| 参数 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `page` / `pageSize` | int | 否 | 通用分页；页面显式传 50 |
| `phone` | string | 否 | trim 后包含匹配；只能输入数字，最长 20 |
| `poolStatus` | enum | 否 | §2.3 的字符串枚举 |
| `countryIso2` | string | 否 | 大写 ISO2 或 `UNKNOWN` |

成功返回 `PageResult<DataPackagePhoneItem>`。接口必须先校验父包未删除，并且只查询父包的
`current_generation`；URL 中传入其他租户包 ID 时按不存在处理。

### 4.5 查询国家候选

```http
GET /api/data-packages/countries
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": [
    { "value": "PH", "countryIso2": "PH", "nameZh": "菲律宾" },
    { "value": "ID", "countryIso2": "ID", "nameZh": "印度尼西亚" },
    { "value": "UNKNOWN", "countryIso2": null, "nameZh": "未识别" }
  ]
}
```

真实国家读取启用的 `country` 主数据并按 `sort_order,id` 排序，`UNKNOWN` 固定追加在末尾。
接口不统计当前租户包数或号码数，不扫描 `data_package_phone`。

## 5. 使用模板接口

### 5.1 查询列表、详情和候选

模板列表：

```http
GET /api/hyperlink-templates?page=1&pageSize=20&name=福利&messageType=3&createdFrom=1787846400000&createdTo=1787932799999
```

| 参数 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `page` / `pageSize` | int | 否 | 通用分页；页面显式传 20 |
| `name` | string | 否 | trim 后模糊匹配，最大 128 |
| `messageType` | int | 否 | 允许 1、3、4；查询时传 2 返回空列表，不报错 |
| `createdFrom` / `createdTo` | long | 否 | 创建时间闭区间；结束不得小于开始 |

成功返回 `PageResult<HyperlinkTemplateListItem>`：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "list": [
      {
        "id": 301,
        "name": "菲律宾新客普通按钮",
        "messageType": 3,
        "title": "新人福利",
        "linkPreviewAssetId": null,
        "linkPreviewAssetUrl": null,
        "bodyMainAssetId": 123,
        "bodyMainAssetUrl": "/api/marketing-template-files/123/content",
        "taskRefCount": 0,
        "version": 1,
        "createdBy": 7,
        "createdAt": 1787881200000,
        "updatedAt": 1787881200000
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1,
    "totalPages": 1
  }
}
```

详情：

```http
GET /api/hyperlink-templates/301
```

返回完整 `HyperlinkTemplateDetail`。一期 `taskRefCount` 恒为 0；未来按任务
`source_template_id` 实时查询，不在模板表落计数列。

候选：

```http
GET /api/hyperlink-templates/options?messageType=3&keyword=福利&limit=50
```

| 参数 | 类型 | 必填 | 规则 |
|---|---|---|---|
| `messageType` | int | 否 | 1、3、4 |
| `keyword` | string | 否 | 同时模糊匹配名称和标题，最大 128 |
| `limit` | int | 否 | 默认 50，范围 1～100 |

返回 `HyperlinkTemplateOption[]`，按 `updated_at DESC,id DESC`，不返回长正文和按钮 JSON。

### 5.2 创建和编辑模板

POST 和 PUT 都使用完整对象，不支持局部 PATCH。前端即使字段不适用于当前消息类型，也必须发送
`null` 或 `[]`；后端仍需再次归一化，不能信任前端清理结果。

创建：

```http
POST /api/hyperlink-templates
Content-Type: application/json
```

```json
{
  "name": "菲律宾新客普通按钮",
  "schemaVersion": 1,
  "messageType": 3,
  "title": "新人福利",
  "content": "活动数量有限",
  "linkDescription": null,
  "promotionLink": null,
  "buttons": [
    {
      "type": "CTA_URL",
      "displayText": "立即查看",
      "targetValue": "https://example.com/promo",
      "useShortLink": true,
      "sort": 1
    }
  ],
  "cardText": null,
  "linkPreviewAssetId": null,
  "bodyMainAssetId": 123,
  "remark": "默认模板"
}
```

成功返回 `HyperlinkTemplateDetail`，初始 `version=1`。

编辑只比创建多一个必填 `version`：

```http
PUT /api/hyperlink-templates/301
Content-Type: application/json
```

```json
{
  "version": 1,
  "name": "菲律宾新客普通按钮",
  "schemaVersion": 1,
  "messageType": 3,
  "title": "新人福利",
  "content": "活动数量有限",
  "linkDescription": null,
  "promotionLink": null,
  "buttons": [
    {
      "type": "CTA_URL",
      "displayText": "立即查看",
      "targetValue": "https://example.com/promo",
      "useShortLink": true,
      "sort": 1
    }
  ],
  "cardText": null,
  "linkPreviewAssetId": null,
  "bodyMainAssetId": 123,
  "remark": "默认模板"
}
```

成功返回更新后的详情且 `version+1`；版本冲突返回 `40901` 和
“模板已被其他人修改，请刷新后重试”。

字段矩阵：

| 字段 | 单图文 1 | 普通按钮 3 | 卡片按钮 4 | 约束 |
|---|---|---|---|---|
| `name` | 必填 | 必填 | 必填 | trim 后 1～128，同租户有效名称唯一 |
| `schemaVersion` | 1 | 1 | 1 | 一期只接受 1 |
| `title` | 必填 | 必填 | 必填 | trim 后 1～512 |
| `content` | 必填 | 可空 | 可空 | 类型 1 最长 2000；类型 3/4 最长 200 |
| `linkDescription` | 必填 | 保存为 null | 保存为 null | 最长 512 |
| `promotionLink` | 必填 | 保存为 null | 保存为 null | 合法绝对 `http/https` URL，最长 2048 |
| `buttons` | 保存为 `[]` | 恰好 1 个 | 恰好 1 个 | 一期只允许 `CTA_URL` |
| `cardText` | 保存为 null | 保存为 null | 必填 | trim 后 1～500 |
| `linkPreviewAssetId` | 必填 | 保存为 null | 保存为 null | 同租户 JPEG，最大 500KB |
| `bodyMainAssetId` | 保存为 null | 可空 | 可空 | 同租户 JPEG，最大 500KB |
| `remark` | 可空 | 可空 | 可空 | 最长 255 |

按钮约束：

- `displayText` trim 后 1～20。
- `targetValue` 是合法绝对 `http/https` URL，最长 2048。
- `sort` 一期必须为 1。
- `useShortLink` 必须显式传 boolean；只表示模板默认意图，不在模板阶段生成短码。
- `messageType=2` 返回 `40001` 和“一期暂不支持双图文”。
- 未开放按钮类型返回 `40001`，不得保存后在任务阶段才失败。

### 5.3 复制和删除模板

复制：

```http
POST /api/hyperlink-templates/301/copy
```

无请求体。成功返回新模板详情，名称按“原名称 副本”“原名称 副本 2”递增；新记录
`version=1`，创建人和时间取当前操作，不复制源记录审计字段。

删除：

```http
DELETE /api/hyperlink-templates/301
```

成功返回 `{ "code": 0, "message": "ok", "data": null }`。一期任务表尚不存在，直接软删除；
未来存在历史或运行中任务引用时返回 `40901`。

## 6. 使用模板图片接口

上传沿用现有接口和现有返回类型：

```http
POST /api/marketing-template-files
Content-Type: multipart/form-data
```

multipart 只有必填字段 `file`。成功示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 123,
    "originalFilename": "promo.jpg",
    "contentType": "image/jpeg",
    "sizeBytes": 48213,
    "url": "/api/marketing-template-files/123/content"
  }
}
```

一期超链模板只接受可实际解码的 JPEG/JPG 且不超过 500KB；不能只信任扩展名或 MIME。
由于该上传接口还服务现有群营销，**不得全局收紧 `uploadImage` 而破坏既有图片类型**。超链前端在
上传前做 JPEG/500KB 提示与校验，最终强制校验放在 `HyperlinkTemplateService` 绑定素材 ID 时：
按当前租户重新读取文件字节、解码、检查大小，不允许跨租户绑定。

内容读取：

```http
GET /api/marketing-template-files/123/content
```

该接口返回 JPEG 二进制，不包 `ApiResponse`，响应 `Content-Type: image/jpeg`。资源不存在、已删除或
跨租户时按不存在处理。

## 7. 错误和权限合同

### 7.1 稳定错误语义

| 场景 | `code` | `message` 要求 |
|---|---:|---|
| 数据包名称重复 | 40901 | 包含“数据包名称已存在” |
| 模板名称重复 | 40901 | 包含“模板名称已存在” |
| 数据包版本冲突 | 40901 | “数据包已被其他人修改，请刷新后重试” |
| 模板版本冲突 | 40901 | “模板已被其他人修改，请刷新后重试” |
| 同包导入冲突 | 40901 | 包含“数据包正在导入” |
| 数据包不存在/跨租户 | 40401 | “数据包不存在或已删除” |
| 模板不存在/跨租户 | 40401 | “模板不存在或已删除” |
| 图片不存在/跨租户 | 40401 | “图片不存在或已删除” |
| 空覆盖 | 40001 | 包含“覆盖导入至少需要一条合法号码” |
| 超过单次 5000 行 | 40001 | 包含“单次最多导入 5000 条” |
| 超过单包阈值 | 40001 | 同时给出配置上限和当前可追加余量 |
| 双图文 | 40001 | “一期暂不支持双图文” |
| 图片格式或大小错误 | 40001 | 明确提示 JPEG 和 500KB 限制 |

前端不得解析中文消息决定业务分支；一期只把消息展示给用户。需要程序分支时以后新增稳定细分业务码，
不能让四个 Agent 各自创造魔法码。

### 7.2 权限矩阵

| 接口 | 权限 |
|---|---|
| 数据包列表、详情、号码、国家 | `tenant:hyperlink_data:view` |
| 创建数据包 | `tenant:hyperlink_data:create` |
| 编辑数据包 | `tenant:hyperlink_data:edit` |
| 导入号码 | `tenant:hyperlink_data:import` |
| 删除数据包 | `tenant:hyperlink_data:delete` |
| 模板列表、详情、options | `tenant:hyperlink_template:view` |
| 创建模板 | `tenant:hyperlink_template:create` |
| 编辑模板 | `tenant:hyperlink_template:edit` |
| 复制模板 | `tenant:hyperlink_template:copy` |
| 删除模板 | `tenant:hyperlink_template:delete` |
| 上传图片 | 现有营销模板 view，或超链模板 create/edit |
| 读取图片 | 保留当前方法全部权限，再增加超链模板 view/create/edit |

本轮四个并行分支不创建菜单/RBAC Flyway；权限注解按上表写入 Controller，菜单记录和角色授权由
后续集成分支统一创建，避免两个后端分支修改同一个迁移文件。

## 8. 并行实现边界

| 分支 | 独占范围 | 不得修改 |
|---|---|---|
| 数据包后端 | 数据包四表、`com.armada.hyperlink.data`、导入/统计/清理及测试 | 模板、图片 Controller、菜单/RBAC |
| 模板后端 | `hyperlink_template`、`com.armada.hyperlink.template`、共享消息内容类型、图片方法权限及测试 | 数据包、菜单/RBAC |
| 数据包前端 | `src/api/hyperlink-data-package.ts`、`src/views/hyperlink/data/**` | 模板目录、公共路由/菜单 |
| 模板前端 | `src/api/hyperlink-template.ts`、`src/views/hyperlink/templates/**` | 数据包目录、公共路由/菜单 |

前端允许在 API 单元测试中使用项目现有 request test double，但生产代码不得增加 mock 数据或假接口。

本轮并行开发最初以远端最高 `V140` 为基线；集成时发现测试环境已经执行用户隔离
`V141`～`V152`，为保持 Flyway 历史校验一致，超链迁移最终调整如下：

| 版本 | 所有者 | 内容 |
|---|---|---|
| `V153` | 数据包后端 | 数据包四表和索引 |
| `V154` | 模板后端 | `hyperlink_template` 和索引 |
| `V155` | 后续集成分支 | 菜单与 RBAC，四个开发分支不得创建 |

`V141`～`V152` 必须保留隔离版本已经执行的原始 SQL 和校验和；禁止复用、修改或执行
`flyway repair` 将其改写为超链迁移。

合同冻结后：

1. 后端可以增加字段但不能删除、改名或改变本文类型；新增字段必须可空且不能要求前端立即使用。
2. 前端不得根据页面方便自行更名、改变枚举或增加后端没有的数据来源。
3. 发现合同无法实现时先报告阻塞，由集成负责人统一修改本文；不得只在自己的分支偷偷偏离。
4. 四个分支不更新本文、总体数据模型和 change summary，最终由集成分支统一回写实现证据。
