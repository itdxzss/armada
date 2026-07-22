# 变更记录：推广模板与渠道管理

- 日期 / 分支：2026-07-20 / `1.0.1-snapshot-wyfBranch`
- 需求来源：买号上量系统模板管理、渠道管理和渠道统计 V1.1 文档及渠道页面截图
- 状态：渠道新增、分页、详情、编辑、删除和 Facebook CAPI 探测接口已实现；渠道统计延期

## 已确认业务口径

- 页面和接口不传租户参数，但保留 Armada MyBatis 自动租户隔离。
- 新增表单的“归属用户”保存为 `promotion_channel.owner_user_id`。
- 页面“创建人”筛选实际按 `owner_user_id` 精确筛选。
- “上级用户”暂不建用户层级字段；前端把选择结果展开为 `ownerUserIds`，后端使用 `owner_user_id IN (...)`。
- Access Token 禁止明文落库和回显，使用 AES-256-GCM 密文、密钥版本及 SHA-256 指纹。
- V061 只创建模板、域名、渠道、追踪配置四张表；渠道统计表和 `promotion_operation_log` 不创建。

## 实现

- `POST /api/promotion-channels`：新增渠道、校验模板/国家、规范化并绑定域名、生成渠道码、加密追踪配置。
- `GET /api/promotion-channels`：按目标国家、混合国家、模板、创建人、上级用户展开集合分页查询。
- `PUT /api/promotion-channels/{id}`：编辑渠道可变字段，保持渠道码和创建信息不变；仅平台、追踪 ID 未变且密文完整时允许空 Token 复用。
- `DELETE /api/promotion-channels/{id}`：在同一事务软删渠道和追踪配置，不级联域名及历史账号引用。
- `POST /api/promotion-channels/probe/{id}`：使用 Meta 测试事件码发送合成 `PageView`，返回并保存脱敏探测结果。
- 国家展示信息通过 `CountryService` 批量读取，未跨业务域直接依赖 CountryMapper。
- 分页 `count` 和 `select` 复用同一 MyBatis 筛选 SQL，分页在数据库完成。

## 数据模型修正

- 删除最新页面不存在的 `theme_color`、`status_reason`。
- 不增加当前无更新接口需求的 `revision`，不增加当前无软删复活唯一需求的 `is_active`。
- 新增 `is_marketing_allowed` 和三个 CAPI 事件映射字段。
- `preselected_country_id` 改为必填。
- 补充模板编码、域名、渠道码、每渠道追踪配置的数据库唯一约束。
- 渠道主表只保留唯一键和默认分页索引，避免无查询证据的过度索引。
- 渠道编辑和删除复用现有列，本次不增加主题色字段、不创建新 Flyway 迁移。

## 验证记录

- TDD RED：首次定向测试因渠道接口类不存在而按预期编译失败。
- TDD GREEN：渠道 Controller、Service、域名规范化、Token 加密、Mapper SQL、V061 SQL 合同和 CountryService 定向测试退出码为 0。
- 真库 DbTest：本地不存在 `armada-api/.env`，未连接真实 MySQL，不声称真库迁移通过。

## 文件约定

- 推广迁移执行链：`V061__promotion_template_channel_statistics.sql`、`V062__promotion_channel_country_values.sql`、`V063__promotion_template_visibility_and_seed.sql`。
- `.harness/changes/.../db-migrations.sql` 仅为历史评审副本，不是 Flyway 执行入口。
- 按用户要求只暂存文件，不 commit、不 push。

## Flyway 版本冲突修复（2026-07-21）

- 合并后推广 V058/V059 分别与营销 V058/V059 重复，Flyway 在解析阶段终止应用启动。
- 推广迁移链整体顺延为 V061/V062/V063，业务 SQL 顺序保持不变；V060 不复用，避免与曾发布的推广 V060 产生历史歧义。
- 新增全目录 Flyway 版本唯一性契约测试，按 Flyway 语义识别前导零、点/下划线以及末尾零段等价版本。
- 上线前必须只读核查目标库 `flyway_schema_history` 的 58–63 版本；如果旧推广 V058/V059/V060 已经执行，不得直接 `repair` 或常规部署，需先完成专项 history 与物理 schema 对账。
- V061 首次部署曾因 4 处独占行注释写成 `--正文` 而在首条 DDL 前失败；已统一改为 `-- 正文`，并增加全迁移独占行注释语法契约测试。
- 只有在确认 V061 失败前未执行 DDL、目标库无预存 `promotion_*` 对象且没有其他迁移实例运行时，才可 repair 失败记录后重新迁移。

## 渠道编辑与删除（2026-07-21）

- 主题色由前端控制，不进入后端接口和数据模型。
- 编辑切换域名时只切换 `promotion_domain_id` 引用，不原地修改可能被多个渠道共享的域名记录。
- 编辑不修改 `channel_code`、`created_by`、`created_at`；归属用户继续作为当前阶段的 `updated_by`。
- 平台和追踪 ID 均未变且存在完整密文时，空 Access Token 才表示保留原值；平台或追踪 ID 变化时必须提供新 Token，清空追踪 ID 时同步清除旧凭据。
- 删除只写 `deleted_at`，追踪配置与渠道处于同一事务；域名和历史账号关联继续保留。
- 编辑和删除先通过渠道主记录 `FOR UPDATE` 串行化，再统一按主表到追踪配置的顺序写入，避免并发死锁和 Token 预检查竞态。
- 定向渠道测试 25 个全部通过；`mvn -DskipTests package` 通过并成功生成可执行 JAR。本地缺少 `.env`，未连接未知数据库执行真库 DbTest。

## 推广 Token 加密组件启动修复（2026-07-21）

- `PromotionTokenCipher` 同时存在生产构造器和包内测试构造器，Spring 无法自动选择并错误寻找无参构造器，导致应用上下文启动失败。
- 在两参数生产构造器上显式使用 `@Autowired`，保留三参数测试构造器，不增加无参构造器和不安全的默认密钥。
- 测试环境与生产环境 Compose 显式透传 `PROMOTION_TRACKING_ENCRYPTION_KEY` 和 `PROMOTION_TRACKING_ENCRYPTION_KEY_ID`。
- `.env.example` 仅提供不可用占位值；真实 Base64 AES-256 密钥只允许保存在部署机 `.env`，同一环境必须稳定复用。
- Compose、测试部署预检和生产安装器都会拒绝空密钥；部署脚本同时校验 Base64 解码后恰好 32 字节，并将 `.env` 权限收紧为 `0600`。
- `key-id` 不再使用运行时默认值；更换密钥时必须同步更新版本号，避免同一标识对应不同密钥。
- Spring 上下文回归测试先复现 `No default constructor found`，修复后 2 个定向测试通过；部署脚本语法与加密配置定向契约检查通过。

## Facebook CAPI 渠道探测（2026-07-22）

- 探测接口为 `POST /api/promotion-channels/probe/{id}`，请求体只传 Meta Events Manager 生成的 `testEventCode`；Pixel ID 和 Access Token 从渠道追踪配置读取。
- 本期只对 Facebook 发起真实 CAPI 测试事件；非 Facebook、缺少 Pixel 或 Token 时返回页面可直接展示的 `ABNORMAL` 详情，不调用外部平台。
- 出站事件固定为合成 `PageView`，只使用随机事件 ID、渠道访问地址和合成 SHA-256 `external_id`，不读取或上传真实用户信息。
- Access Token 仅在 Service 内解密并通过 Bearer Header 发送，接口、日志和错误信息不返回明文、密文、指纹或 Meta 原始响应体。
- 当前系统尚无可信 JWT/权限边界，因此探测默认关闭；仅在隔离测试环境显式设置 `ARMADA_PROMOTION_TRACKING_FACEBOOK_PROBE_ENABLED=true` 后启用，生产接入真实身份与探测权限前不得开启。
- 同一渠道完成后有 30 秒数据库冷却；生产出站地址只允许 `https://graph.facebook.com` 且没有可配置的不安全旁路，HTTP 单项超时上限 30 秒、总和不超过 45 秒，Token 解密后会常量时间校验指纹。
- 探测抢占和最终回写均校验平台、Pixel ID 与 Token 指纹，最终回写还校验本次抢占开始时间；配置发生变化或新一轮探测已接管时返回 `CONFIG_CHANGED`，旧结果不会覆盖新状态。
- 复用 V061 的最近探测字段，不新增表、列或索引；定向 Controller、Service、Mapper、密码组件和 HTTP 适配器测试共 44 个通过。
