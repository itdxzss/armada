# iOS 原生失败回报修复（2026-09-17）

范围：用户授权修复失败检测与回报、补齐 Armada FAILED 接口，并于后续明确要求提交部署；本次准备第一套 test1 后端发布，不采购、不实现自动补购循环。

设计：沿用注册明细聚合与租约。首次原生失败仅在 WAITING_CODE / REGISTERING 接受，进入 FAILED；重复 FAILED 返回首次结果，成功终态不受迟到失败覆盖。失败不取消或完成短信供应商订单。

数据：明细现有 failure_code 只保存原因标识，无法表达类别和可读详情；在同一聚合新增 failure_kind（1 NUMBER、2 RATE_LIMIT、3 UNKNOWN）及 failure_detail（最多 256 UTF-16 单元），不复制号码或订单字段。V200 幂等迁移；无 Redis 变更。

API：FAILED result 必须包含 requestId、phoneNumber、outcome、failureKind、failureCode、failureDetail；REGISTERED/STOPPED 继续使用原三字段。固定分类、原因标识和长度校验，DTO 日志脱敏；ACK 返回当前号码、FAILED 状态和已持久化首次失败信息。

iOS：移除视图 setter 误报；校验当前控制器、会话、号码及注册模式；补手机号页失败入口；主线程收敛；保存失败回滚并保留待写事件；busy 期间延后上报并忽略旧 status；ACK 写入失败继续重试。

验证：后端注册相关 103 项通过（0 failures/errors/skipped）；接口拒绝回归先复现 HTTP 400，再转绿。最终定向复跑安全过滤器 13 项、服务/H2 37 项通过。iOS 模拟器 124/124 通过；arm64 dylib 构建、宿主签名核对、verify-project.sh、Mapper XML、API 文档测试及 diff 检查通过。证据见 test-results.json 和扩展仓库 PersonalRegistration/evidence/simulator-results-failure-fixes-20260917.json。MySQL PREPARE 在 H2 不可执行，测试提取迁移中原始 DDL 执行，并校验幂等守卫；不连接远程库。模拟器替身不证明真实手机弹窗一定触发指定方法。

回滚：仅回退本任务补丁，保留已有在途工作；先停用新客户端/回退后端，再按 rollback.sql 移除新增列（删除列会丢失失败类别及详情）。

持久化边界：Keychain 暂时不可写时，客户端保留仅内存的待写终态，恢复可写后先落盘再发送；若在持续不可写期间进程被杀，该内存事件无法保证跨进程恢复。服务端 ACK 仅在真实落库后返回，原生失败不代表短信订单已退款或取消。

部署/业务验收：未提交、未推送、未部署、未执行 V200 到远程数据库，未签名安装 IPA，未进行真实取号/收码/注册。以后发布须先让后端应用 V200 并支持新字段，再交付新客户端。

提交准备：修改和提交均在 armada 主目录 1.0.3-snapshot；注册前置模块尚未纳入 Git，因此包含运行所需的注册源码、V196—V200 和注册网关配置。凭据解析、六段转换、其他工作区及无关在途修改不纳入提交。

发布前检查：暂存 Git tree 导出到独立临时目录，在 JDK 17 下重跑注册相关 103 项通过，证明提交内容不依赖其他脏文件；deploy-test.sh 测试通过。生产离线包测试因既有缺失 armada-deploy/prod/scripts/inspect-production-host.sh 失败，不属于本次 test1 后端部署路径。
