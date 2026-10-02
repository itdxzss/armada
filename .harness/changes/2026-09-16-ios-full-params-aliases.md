# 变更记录：iOS 全参供应方字段兼容

- 日期 / 分支：2026-09-16 / 两仓主仓库 1.0.3-snapshot
- 需求来源：用户反馈第二套环境批次 90 仍报缺 phone；延续前一轮缺 phone 从 jid 取号及部署第二套环境的要求。
- 状态：已部署到第二套环境；未执行真实账号导入/登录

## 目标
让选择苹果的全参导入覆盖本次供应方格式，保留完整 iOS 凭据，不降级为安卓六段。

## 事实和缺口
- perf2 批次 90：device_os=2 / account_type=1，共166条；全部缺phone，未入库、未派发。
- 本地原文件167条：manufacturer=Apple，缺phone/platform/edgeRoutingInfo，jid为号码。密钥长度与正整数ID格式校验通过。
- 前轮仅修改 Android FullParamsToSixConverter，未覆盖独立 iOS 解析分支。
- Zhuan 底层 parseNativeRoutingInfo 和 TCP handshake 已支持无 routing；Armada 与命令映射仍额外强制它。

## 设计和影响
- 仅内存副本归一化：纯数字jid补标准电话域；phone缺失/null/空白时从合法电话jid提取；platform缺失/null/空白时根据导入所选个人/商业补ios/smb_ios。
- 已有非法值或显式冲突继续报错，LID不能当号码，完整原文保持原样。
- edgeRoutingInfo允许缺失/null/空白；提供非空值时仍校验Base64。协议命令允许缺失，不造路由字节。
- 两仓修改，不新增API、表列、topic或Redis键，不改变租户与账号归属。
- 先发布perf2 Zhuan兼容接收端，再发布Armada，避免新凭据被旧映射器拒绝。
- 不自动重导166条或批量登录；真实WhatsApp接受情况未验证。

## 任务
- [x] 源码与perf2记录核对
- [x] 回归测试先红后绿
- [x] 两仓构建与检查（Noise既有失败已单独核对）
- [x] 苹果个人路径原文件167条及协议映射验证
- [x] perf2部署、运行制品与接口核对

## 回滚
保留上一版JAR和协议镜像；部署失败时回退相应服务。无数据库结构变更。

## 验证结果
- Java最终发布目录：126 tests，0 failures/errors/skipped（转换器27、解析器57、写入4、上线命令38）。
- Go gofmt、go vet ./...、go build ./...完成；go test ./...仅pkg/noise 8个测试失败，未修改HEAD基线复验同样8个失败。改动包internal/armada和api/service通过。
- 最终后端JAR本地执行苹果个人导入：167/167 accepted，167/167完整原生归一化，无真实导入/登录。
- 对比前一轮部署JAR，仅AccountImportParser.class变动，迁移未变。
- 评审：未发现本次修复阻断项；仅从合法电话JID和用户所选账号类型补字段，不猜测密钥或路由信息。

## 发布进度
- perf2 Zhuan发布退出0：running/healthy、restartCount=0、启动错误0、Swagger HTTP200。
- 协议新镜像：sha256:7786d46a25b416d4e413c78dee3f971a801e0cc6ab6b314a0349ae8d6e8e76e3。
- 协议运行二进制SHA-256：3e466eba7854a36b6546cb2e3c2072440f71c0689241e19fe76237570ff22b75。
- 后端发布退出0；本地/远端/运行中JAR SHA-256一致：f59f117275876ec67fffa6825b290a916addac90ef7b689075d82a5f9b088491。
- 协议回滚镜像：whatsapp-server-feature-android-zhuan:before-ios-alias-20260916。
- 后端回滚备份：/home/app/armada-deploy/backups/ios-supplier-alias-20260916/。

- 最终后端：running，restartCount=0，启动ERROR=0，无迁移失败；目标schema=armada_perf。
- /api/account-groups返回HTTP401/code40104（未登录预期）；/platform-config.json返回HTTP200、第二套环境。
- 批次90的166条失败记录未重跑。用户需新建导入；真实登录结果尚未验证。
- 两仓主仓库修改保留，均未提交/推送；第一套环境未发布。
