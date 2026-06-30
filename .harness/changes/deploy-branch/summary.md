# 部署脚本按分支部署能力

## 变更
- `armada-deploy/deploy-test.sh` 增加 `--branch <name>` / `ARMADA_DEPLOY_BRANCH`。
- 指定分支时,脚本从 `origin/<branch>` 创建临时 detached worktree 构建 armada 后端与部署编排文件。
- 不切换当前工作区分支,不要求当前工作区干净;脚本退出时清理临时 worktree。
- `--dry-run` 只打印分支部署计划,不 fetch、不创建 worktree。

## 验证
- `bash -n armada-deploy/deploy-test.sh`
- `./armada-deploy/deploy-test.sh --branch main --be --dry-run`
- `ARMADA_DEPLOY_KEY=/tmp/armada-no-such-key ./armada-deploy/deploy-test.sh --branch main --be -y`
