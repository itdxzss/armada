# 登录验证码临时关闭与会话时长调整

## 变更概述

- `POST /api/public/auth/login` 暂时跳过图片验证码消费校验，仅校验用户名和密码。
- 验证码生成接口、DTO 字段、Redis 验证码服务和校验代码均保留，后续可同步恢复。
- Redis 登录会话空闲失效时间由 30 分钟延长至 2 小时。

## 影响模块

- `admin` 域登录认证服务。
- `platform.auth` Redis 会话配置。

## 数据库变更

- 无。

## API 变更

- 登录请求当前允许不传 `captchaId`、`captchaCode`。
- `/api/public/auth/captcha` 保留不变。

## Redis 变更

- 新建和续期会话的默认空闲 TTL 改为 2 小时。
- 会话 24 小时绝对上限、键格式和单用户单会话语义不变。

## 关键约束

- 与前端 `1.0.2-snapshot-login` 分支配套发布。
- 恢复验证码时必须同时恢复服务端 `CaptchaService.consume` 校验、前端输入与提交及对应测试。
- 环境变量 `AUTH_SESSION_IDLE_TIMEOUT` 仍可覆盖默认值。

## 回滚方案

- 回退本次提交，恢复验证码消费校验和 30 分钟默认空闲 TTL。

## 验证

- TDD 红灯确认：原实现因验证码校验拒绝空验证码，且默认 TTL 仍为 30 分钟。
- `mvn "-Dtest=AuthenticationServiceImplTest,AuthPropertiesTest" test`：5/5 通过。
- Maven 编译通过；本轮覆盖 1271 个生产源文件和 488 个测试源文件的编译。
- 提交前 diff 复核未发现越界改动，验证码恢复条件与前后端注释一致。
