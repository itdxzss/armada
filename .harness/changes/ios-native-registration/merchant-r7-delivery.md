# r7 商家选择交付

## 当前实现

- test1，后端 1.0.3-snapshot / 3493794f 当前工作区，前端 1.0.3-snapshot / ac18863a 当前工作区；未 commit/push。
- 控端新建注册可选择 provider；手机注册/取号验证页可保存单设备许可，并明确确认后执行一次采购。
- 同价档商家可由手机选择。start 带 requestId/providerId；任务创建后商家不可变。
- token 保持原设备身份，仅配置 token/tenantId/deviceId；动态许可由 V198 真实租户表保存。
- 许可保存不采购；控端已开始任务可在 r7 手机上明确接续，无重复购买。

## 验证

- 后端 177 项、前端 19 项、网关 13 项检查通过，Vue 类型与 ESLint 通过。
- 实际 Chrome 页面已完成选择 0.88、196 并保存许可；手机 HTTPS status/options 均 200/no-store，错误设备 401。
- 当前请求 `d55443ac-7307-4779-ab7e-38e753e24066`，替代旧 r6 无号码失败任务；有效期至 2026-09-16 18:21:34 +0800。
- 当前状态 NOT_STARTED，尚无新号码或采购任务；真实收费验证等待用户新确认。
- 自动审批拒绝凭据下的空 start 探测，担忧触发收费；已彻底移除该探测，只核验 status/options 和错误设备。没有绕过审批或实际发出 start。
- 正式 deploy-test 测试通过。生产打包回归受既有缺失文件 prod/scripts/inspect-production-host.sh 阻断，与 test1 本次部署无关，未声称该项通过。

## 部署

- test1 部署命令 --env test1 --all -y 成功，前后端健康检查成功。
- 运行 JAR SHA256：971929f785811f806e6a22ff67065755a35a2af46e532f6939c9f634e0e88195；容器 RestartCount=0，Flyway V198 成功。
- 网关 root 模板普通 rsync 无权同步，改用 sudo 写入相同已测试模板，再重建仅 device-ingest-nginx；配置验证与公网 options 成功。
- 旧 JAR/env/配置备份：/home/app/armada-ios-registration-backups/r7-20260916-091454。
- 真实信息架构元数据经正式 gen_datamodel.py 更新注册三张表文档。

## IPA

- 路径：../wa-biz-compat-v8-extension/dist/WA-r7.ipa，文件名按用户要求缩短。
- 未签名；Bundle ID net.whatsapp.WhatsApp.armadareg；版本 26.36.74/build 1067315676。
- SHA256：af234fcded0435e68b82444906f1efbc841e933dc291ee8548fc1ea7a30d7868。
- 2066 个 Payload 文件对比 r6，仅注册 dylib 变化，设备预配置保持一致。无接码平台密钥。
- 85 项模拟器检查通过；测试 App 已卸载，模拟器恢复关机；无真机安装/注册证明。
- 沿用本会话由用户在全能签签名、手动更新测试 App 的交付方式。
