# 变更记录：IP 数据统计国家主数据口径

- 日期 / 分支: 2026-07-01 / 当前工作区
- 需求来源: `0701IP管理、IP数据统计需求页面.html` 与口径确认
- 状态: 已完成

## 目标

把 IP 数据统计从“已有 IP 的 region 聚合”调整为“真实支持国家主数据 LEFT JOIN IP 聚合”。

## 统计口径

- `totalIpCount / inUseIpCount / idleIpCount / unavailableIpCount`: 统计所有活跃 IP,包含 `混合（不限国家）`。
- `coveredRegionCount`: 有活跃 IP 的真实支持国家数,不包含混合池。
- `supportedCountryCount`: `country` 表中启用且支持 IP 管理展示的真实国家数。
- `noIpCountryCount`: 支持国家中 IP 总数为 0 的国家数。
- 国家维度列表只展示真实支持国家,不展示 `混合（不限国家）`。
- 资源风险新增 `no_ip`,优先级高于 `no_idle / low_available / high_unavailable / normal`。
- 代理协议码 `2` 的对外展示名为 `SOCKETS`；底层协议枚举/协议层常量仍沿用 SOCKS5 语义。

## 影响模块

- `resource` 域:
  - `IpProxyResourceRisk`
  - `IpProxyStatsSummaryVO`
  - `IpProxyMapper.xml`
  - stats service / controller / mapper 测试
- DB: 无新增迁移,复用既有 `country` 主数据表。

## 验证

```bash
xmllint --noout armada-api/src/main/resources/mapper/resource/IpProxyMapper.xml
mvn -q -Dtest=IpProxyStatsServiceImplTest,IpProxyStatsControllerTest test
./armada-api/dbtest.sh IpProxyStatsMapperDbTest
```

结果: 以上 focused 校验通过。
