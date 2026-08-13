# PROXY_FAILED 旧代理恢复为不可用口径

## 需求

- 协议上报有效 `PROXY_FAILED` 后，Armada 不再把本次失败的旧代理释放回空闲池。
- 旧代理精确置为 `UNAVAILABLE` 并解绑，然后排除旧 `proxyId` 分配新 IP、重新上线。
- 现有不可用 IP 定时任务继续每 15 分钟重检；检测成功恢复为 `IDLE`，失败保持 `UNAVAILABLE`。

## 实现

- `ProxyFailedRecoveryCoordinator` 在换 IP 重上线前调用 resource 域标记失败代理不可用。
- Mapper SQL 同时匹配 `tenant_id + proxyId + accountId + IN_USE`，避免迟到事件误伤其它绑定。
- 写入代理检测失败状态和 `PROXY_FAILED` 原因，使旧代理进入现有不可用重检候选。
- 保留现有 5 秒 `PROXY_FAILED` 恢复补偿、旧代理排除和 outbox 上线流程。

## 验证

- `xmllint --noout src/main/resources/mapper/resource/IpProxyMapper.xml`
- Java 17 聚焦测试：104 个通过，0 失败、0 错误、0 跳过。
- `IpProxyMapperH2Test` 使用 H2 MySQL 模式、生产 MyBatis-Plus 租户插件和真实 Mapper XML，覆盖精确置不可用、解绑、租户隔离及重检成功恢复 `IDLE`。

## 数据与部署影响

- 无 schema、HTTP API、协议契约和配置变更。
- 不涉及远程环境、真库数据修改或部署。
