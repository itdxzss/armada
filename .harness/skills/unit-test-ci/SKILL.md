# CI 与质量门禁技能

armada 的质量门禁：哪些机器挡、哪些人挡。

## 已机械化（`mvn verify` 会挡）
**ArchUnit**（真字节码规则,替代 wheel 手写 grep 的 archtest）强制：
- 依赖方向：`shared←platform←业务域←boot` 不反转；`shared` 不依赖 `platform`/业务域；`platform` 不依赖业务域。
- 跨业务域只调对方 `Service`，禁碰其 controller/mapper/entity。
- `Controller→Service→Mapper`：controller 禁直连 mapper(无 Repository 层)。
- 跨租户豁免 `@IgnoreTenant` 只能在 `admin` 域，且必须声明非空 reason。

## 尚未机械化（全靠纪律,见 `rules/编码规范.md`、`rules/数据模型规范.md`）
内存分页、生产 mock、`FOR UPDATE`+`LIMIT` 的 `@InterceptorIgnore`、mapper XML 裸 `<>`、`account_type` 改写、
**数据模型反屎山(加列先看全局 / 禁三镜像分歧 / 禁死列 / 宽表拆分)**、
部署前 `mvn clean`、弹框 z-index、deploy 脚本 `${VAR}`、DbTest 必须真跑(禁缺 env 静默跳过)。

## 熵管理
同类 review 问题反复出现，就把它翻成 ArchUnit / Checkstyle / CI 检查，逐步把"靠纪律"变成"机器挡"。一次只加一条规则，避免改一个错触发另一个错的循环。
