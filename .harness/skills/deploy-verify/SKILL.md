# 部署验证技能

把改动部署到测试服并验证真的生效。

## 部署
- 脚本：`wheel-deploy/deploy-test.sh`，flag：`--be` / `--fe` / `--prebuilt`（`--fe --prebuilt` 实测最快）。
- **部署前必做** `mvn -pl wheel-api-app clean`：杜绝 worktree stale 迁移打进 jar → Flyway 撞号 → crash-loop 全站挂。
- 后台部署须 `-y` 免 `/dev/tty` 交互。
- quirk：`--fe` 可能连带 recreate backend 且不验后端；全栈省略 scope 或分两次跑。

## 验证（部署 ≠ 生效）
1. 后端无 crash-loop（容器没在反复重启）。
2. 真库确认新数据 / 新列真的落库（用 `query-testdb.sh`）。
3. 关键接口实测返回正确。

## 部署拓扑
- 协议层 laqunxitong：`13.234.217.33`（`xieyi.pem`，systemd）。
- wheel 测试服：`65.2.123.53`（`dev-1.pem`，docker）。
