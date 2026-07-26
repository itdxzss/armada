# Version Independent Backend Artifact Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Armada 测试部署和生产打包在 Maven 项目版本变化后仍能自动找到后端可执行 JAR。

**Architecture:** Maven 构建后由共享 Shell 函数扫描 `target` 并要求唯一 `*.jar`；源文件动态识别，传输和 Docker 上下文使用固定暂存名 `armada-api-deploy.jar`。零候选或多候选直接失败。

**Tech Stack:** Bash、Maven、Docker、现有 Shell 测试脚本。

---

### Task 1: 用测试锁定动态产物解析

**Files:**
- Create: `armada-deploy/lib/artifact.sh`
- Modify: `armada-deploy/deploy-test.test.sh`
- Modify: `armada-deploy/package-prod.test.sh`

- [x] **Step 1: 写失败测试**

在 `deploy-test.test.sh` 创建临时 `target`，分别断言单个 `armada-api-1.0.2-SNAPSHOT.jar` 可解析，零候选和两个
JAR 均失败。在两个脚本测试中断言部署资产使用 `armada-api-deploy.jar`，且不包含版本化 SNAPSHOT 文件名。

- [x] **Step 2: 运行测试确认红灯**

Run:

```bash
bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
```

Expected: FAIL，原因是共享解析函数和稳定暂存名尚不存在。

- [x] **Step 3: 实现共享解析函数**

实现 `armada_resolve_backend_jar`，用 POSIX 兼容的 `target/*.jar` glob 收集普通文件候选，只有一个候选时输出路径。

### Task 2: 接入所有部署入口

**Files:**
- Modify: `armada-deploy/deploy-test.sh`
- Modify: `armada-deploy/lib/armada.sh`
- Modify: `armada-deploy/deploy-test-win.sh`
- Modify: `armada-deploy/package-prod.sh`
- Modify: `armada-deploy/backend.prebuilt.Dockerfile`

- [x] **Step 1: 改测试部署**

测试部署统一设置 `JAR_NAME=armada-api-deploy.jar`。Maven 构建后调用共享函数设置实际 `JAR_PATH`，再把该文件
同步到远端稳定路径。

- [x] **Step 2: 改 Windows 和生产打包**

Windows 部署及生产离线打包复用同一解析函数；准备 Docker 上下文时把实际产物复制为稳定暂存名。

- [x] **Step 3: 修改 Dockerfile**

将后端复制路径改为：

```dockerfile
COPY armada-api/target/armada-api-deploy.jar /app/app.jar
```

- [x] **Step 4: 运行测试确认转绿**

Run:

```bash
bash -n armada-deploy/deploy-test.sh armada-deploy/deploy-test-win.sh \
  armada-deploy/package-prod.sh armada-deploy/lib/artifact.sh armada-deploy/lib/armada.sh
bash armada-deploy/deploy-test.test.sh
bash armada-deploy/package-prod.test.sh
```

Expected: 全部退出码为 0。

### Task 3: 实际构建、提交和部署 test1 后端

**Files:**
- Verify: `armada-api/target/*.jar`
- Deploy: `armada-deploy/deploy-test.sh`

- [x] **Step 1: 实际构建并验证解析**

Run:

```bash
cd armada-api && mvn -q -DskipTests clean package
cd .. && bash -c '. armada-deploy/lib/artifact.sh; armada_resolve_backend_jar armada-api/target'
```

Expected: 输出 `armada-api-1.0.2-SNAPSHOT.jar` 的实际路径。

- [ ] **Step 2: 提交并推送**

只暂存本计划、设计和部署脚本相关文件，提交信息：

```bash
git commit -m "fix(deploy): 动态识别后端构建产物"
git push origin 1.0.2-snapshot
```

- [ ] **Step 3: 按远端分支部署第一套环境后端**

Run:

```bash
./armada-deploy/deploy-test.sh --env test1 --be --branch 1.0.2-snapshot -y
```

Expected: 构建、同步、容器重建及脚本健康检查均成功。

- [ ] **Step 4: 部署后只读验证**

检查 `armada-backend` 为 running、无 crash-loop，健康接口返回预期业务码，日志无启动/Flyway/数据库连接错误，
并核对远端稳定暂存 JAR 和当前分支 commit。
