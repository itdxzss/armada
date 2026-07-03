# 变更记录：账号导入 IP 分配方式

- 日期 / 分支 / worktree: 2026-07-03 / `main` / `/Users/daishuaishuai/IdeaProjects/armada`
- 需求来源: 账号导入优化内容：导入协议号弹窗选择 IP 改为“智能分配 / 混合国家”
- 状态: 已完成本地实现与验证

## 目标（一句话）

账号导入批次保存 IP 分配方式；上线时智能分配按账号区号匹配国家池，找不到合适国家或国家池无可用 IP 时只 fallback 到混合国家池。

## 缺口拆解 / 任务清单

- [x] 批次表增加 `ip_allocation_mode`，保留历史批次 `ip_region` 兼容。
- [x] 后端导入接口接收并返回 `ipAllocationMode`。
- [x] 上线分配从导入明细账号手机号解析国家，`smart/mixed` 模式禁止落到其它真实国家。
- [x] 账号导入弹窗 IP 选择改为“智能分配 / 混合国家”，不再拉取国家 IP 列表。
- [x] 刷新 `.harness/wiki/数据模型.md`。
- [x] 完成 DbTest、单元测试、前端类型检查验证。

## 关键设计决策

- `ip_allocation_mode` 属于 `account_import_batch` 聚合：它描述批次导入时的 IP 分配策略，不是账号运行态，也不是 IP 资源属性。
- 前端新导入默认提交 `smart`；后端未收到 `ip_allocation_mode` 时保持 NULL，继续按原 `ip_region` 逻辑并允许原有其它地区 fallback，兼容历史批次和旧调用方。
- `smart` 只按 `country.phone_prefix` 匹配 `country.name_zh`，上线时分配 `ip_proxy.region` 等于该国家的任意 IP；如果匹配不到国家或该国家池无可用 IP，只 fallback 到 `混合（不限国家）`。
- `mixed` 直接使用 `混合（不限国家）` 池；混合池也无可用 IP 时不上线，不再借其它真实国家。
- 批量上线时通过 `CountryService.resolveIpRegionsByPhonePrefix` 一次读取国家主数据并解析多个手机号，避免 `smart` 模式按账号数重复查国家表。

## 验证（evidence-before-done）

- `xmllint --noout armada-api/src/main/resources/mapper/account/AccountImportBatchMapper.xml armada-api/src/main/resources/mapper/account/AccountMapper.xml armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml`
  - 结果: 通过。
- `mvn -q -Dtest=CountryServiceImplTest,AccountOnlineCommandServiceImplTest,IpProxyServiceImplTest test`
  - 结果: 通过；覆盖区号最长前缀匹配、导入 smart/mixed 上线分配请求、IP 分配严格 fallback。
- `armada-api/dbtest.sh IpProxyMapperDbTest,AccountImportServiceImplDbTest,AccountImportControllerDbTest`
  - 结果: 通过；Flyway 将测试库从 v033 迁移到 v034，`ip_allocation_mode` 可写可读，Controller 表单字段可持久化，严格模式不会 fallback 到其它真实国家。
- `armada-api/dbtest.sh AccountImportOnlineDispatcherDbTest,AccountOnlineCommandServiceImplDbTest,IpProxyMapperDbTest,AccountImportServiceImplDbTest,AccountImportControllerDbTest`
  - 结果: 复核后通过；补充覆盖未传 `ip_allocation_mode` 时继续使用历史 `ip_region` 自动上线路径。
- `python3 .harness/wiki/gen_datamodel.py`
  - 结果: 通过；`.harness/wiki/数据模型.md` 已包含 `account_import_batch.ip_allocation_mode`。
- `node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/account-import.test.ts`
  - 结果: 通过；5 个账号导入 API 测试通过。
- `node --import ./src/api/__tests__/node-test-alias.mjs --test src/views/account/import/constants.test.ts`
  - 结果: 通过；3 个账号导入常量测试通过。
- `node --import ./src/api/__tests__/node-test-alias.mjs --test src/api/*.test.ts`
  - 结果: 通过；38 个前端 API adapter 测试通过。
- `./node_modules/.bin/tsc --noEmit`
  - 结果: 通过。
- `./node_modules/.bin/vue-tsc --noEmit --skipLibCheck`
  - 结果: 通过。
- `git diff --check`
  - 结果: 通过；后端和前端均无空白错误。

## 部署

- commit / 环境 / 部署后验证结果: 未部署。

## 遗留 / 跟进

- 无。
