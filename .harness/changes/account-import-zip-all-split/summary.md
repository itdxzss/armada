# 变更记录：账号导入 ZIP 全量导出分包

- 需求来源：账号导入 JSON/ZIP 导出全部需要在一个 ZIP 内分成功包和失败包。
- 状态：已实现后端导出结构调整与 DbTest 覆盖。

## 变更概述

`GET /api/account-imports/{batchId}/export?scope=all` 对 ZIP 导入批次仍返回一个 ZIP 文件，但包内条目从根目录平铺调整为：

- `成功/*.json`：导入成功且未出现登录失败/异常的 JSON 条目。
- `失败/*.json`：解析失败、重复、凭据不全，或登录失败/密钥异常/封号的 JSON 条目。

`scope=success` 和 `scope=fail` 的 ZIP 导出保持原有根目录 entry，不改变单独导出成功/失败入口。

## 影响模块

- `account` 账号导入批次导出。

## 数据库变更

- 无。

## API 变更

- 路径、参数、Content-Type、文件名不变。
- ZIP 批次 `scope=all` 的 ZIP 内部 entry 路径变更为成功/失败目录分包。

## 关键约束

- TXT 导出本次不改。
- 导出仍复用 `raw_payload` 原始材料，不新增明文日志。

## 回滚方案

- 回退 `AccountImportServiceImpl` 中 ZIP all entry 前缀逻辑，并回退相关测试与文档。
