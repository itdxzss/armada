---
name: deploy-verify
description: Use when preparing, dry-running, executing, or verifying an Armada test or production deployment, including backend, frontend, Baileys protocol, Zhuan protocol, and offline production packages.
---

# 部署验证技能

部署是外部状态变更。先确认环境与范围，再使用仓库当前脚本，最后验证真实生效。

## 硬门槛

1. 部署、SSH 或远程验证前，向用户确认目标环境、分支/commit 和范围；生产环境必须再次明确确认。
2. 私钥、`.env`、token、数据库连接和代理凭据不得回显、提交或复制到变更记录。
3. 先读脚本当前帮助，不依赖历史命令或硬编码主机：

```bash
./armada-deploy/deploy-test.sh --help
./armada-deploy/deploy-test.sh --dry-run
```

4. 后端部署脚本会在 `armada-api/` 执行 JDK 17 的 `mvn -q -DskipTests clean package`；部署前仍须独立完成与改动相称的测试。

## 部署前验证

```bash
bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test.test.sh
bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
```

- 测试环境只使用脚本帮助中当前存在的 scope，例如 `--be`、`--fe`、`--protocol`、`--zhuan`、`--all`、`--full`。
- 指定分支部署先用 `--branch <name> --dry-run` 核对，不切换或污染当前 worktree。
- 生产离线包与安装流程以 `armada-deploy/prod/README-prod.md` 和 `package-prod.sh --help` 为准。

## 验证（部署 ≠ 生效）

1. 确认部署脚本退出码为 0，目标 commit/制品与确认范围一致。
2. 确认容器或进程稳定，无 crash-loop，日志没有迁移、配置、连接或启动错误。
3. 数据库变更通过获准环境的 Flyway 状态和必要查询验证；禁止为验证临时手工 ALTER。
4. 对关键接口做真实请求，核对状态码、响应体和关键副作用。
5. 涉及协议层时验证对应进程、健康检查和事件/命令链路，不以“部署命令成功”替代业务验收。
6. 记录执行命令、环境、commit、退出码和结果，但所有敏感值必须脱敏。
