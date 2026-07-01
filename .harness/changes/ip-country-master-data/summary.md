# 变更记录：IP 管理国家主数据

- 日期 / 分支 / worktree: 2026-07-01 / main / armada
- 需求来源: IP 管理国家下拉从前端写死改为管理员国家主数据
- 状态: 已实现,未提交

## 目标（一句话）

新增平台级国家/地区主数据,供 IP 管理国家下拉动态读取。

## 缺口拆解 / 任务清单
- [x] 建 `country` 表并 seed 248 个国家/地区
- [x] 国家选项接口返回 `MIXED + 248` 行
- [x] IP 管理查询/导入兼容 `countryValue`
- [x] 更新数据模型和接口文档

## 关键设计决策

- `country` 无 `tenant_id`,加入 MyBatis 租户忽略表。
- 国家主数据代码归到 `com.armada.platform.country`,避免 `resource` 域依赖 `admin` 域;`admin` 只保留 `/api/admin/countries` controller。
- `ip_proxy.tenant_id` 保留,本次不迁平台池。
- `混合（不限国家）` 不入表,由接口虚拟返回。
- 时间字段使用 epoch 毫秒 BIGINT。
- `V023__country_name_en_seed.sql` 只补已上线 `country.name_en`,不改已执行的 `V021`。
- 英文地区名采用 Unicode CLDR/ICU `en` territory display names。

## 验证（evidence-before-done）

- `xmllint --noout armada-api/src/main/resources/mapper/platform/country/CountryMapper.xml` => 0
- `python3 .harness/wiki/test_api_docs.py` => OK, controllers=12 endpoints=61
- `rg -n "DATETIME|CURRENT_TIMESTAMP|ON UPDATE" armada-api/src/main/resources/db/migration/V021__country_master_data.sql .harness/changes/ip-country-master-data/db-migrations.sql docs/superpowers/specs/2026-07-01-admin-ip-country-master-data-design.md` => no matches
- `rg -c "^\\(" armada-api/src/main/resources/db/migration/V021__country_master_data.sql .harness/changes/ip-country-master-data/db-migrations.sql` => 248 / 248
- `mvn -q -Dtest='CountryServiceImplTest,IpProxyServiceImplTest' test` => 通过
- `armada-api/dbtest.sh 'CountryMapperDbTest,CountryControllerDbTest,IpProxyMapperDbTest'` => 通过
- `armada-api/dbtest.sh CountryMapperDbTest` => 通过;Flyway validated 23 migrations,current schema 023
- MySQL 验证:活跃 `country` 248 行,`name_en` 缺失 0;抽查 `AF=Afghanistan`,`IN=India`,`CI=Côte d’Ivoire`,`XK=Kosovo`,`TR=Türkiye`,`AC=Ascension Island`
- Surefire 汇总:
  - `com.armada.platform.country.mapper.CountryMapperDbTest`: tests=3 errors=0 skipped=0 failures=0
  - `CountryControllerDbTest`: tests=1 errors=0 skipped=0 failures=0
  - `IpProxyMapperDbTest`: tests=8 errors=0 skipped=0 failures=0
  - `com.armada.platform.country.service.CountryServiceImplTest`: tests=3 errors=0 skipped=0 failures=0
  - `IpProxyServiceImplTest`: tests=23 errors=0 skipped=0 failures=0

## 部署

- 本次仅本地实现和验证,未提交、未部署。

## 遗留 / 跟进

- 角色菜单权限研发后,再补 IP 管理和国家主数据维护菜单权限。
