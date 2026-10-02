# 官方商家字段复核（只读）

日期：2026-09-16。用户要求重新核对 https://grizzlysms.com/cn/docs 。本次通过真实浏览器展开 getPricesV3、getNumberV2、getActiveActivations 文档；没有按执行按钮，没有采购。

## 已确认

- getPricesV3 响应按国家、服务分组，providers 中每个供应商有 provider_id、price 数组、count。
- getNumber/getNumberV2 的 providerIds 是采购供应商白名单，逗号分隔；exceptProviderIds 是排除名单。maxPrice 是最高价，不是精确固定成交价。
- getNumberV2 文档成功示例返回 activationId、phoneNumber、activationCost、currency、countryCode、时间等，没有 provider_id；getActiveActivations 示例也没有供应商字段。不能将未列出的字段视作接口保证。
- Armada 采购解析对象没有实际供应商字段；任务 provider_id 是用户选定的请求约束，页面展示的是该约束，不是供应商成交回执。
- 复核同日保存的网页历史组件：显示 provider_id，但历史购买按钮只传国家和服务，没有将历史 provider_id 带入请求。
- 网页价格购买弹窗从当前价格列表取 providerId，作为 providerIds 参数发出；允许同一价档多个供应商。不存在已观察到的 ID 转换代码。
- 同日只读网页公开报价与 test1 API 的美国187/WhatsApp wa/0.88 档均出现196、62、65、66。网页历史222与当前候选不同，不能推导成网页/API的两套编码。
- getPricesV3 中196的 count=222 是库存数，不能与历史订单 provider_id=222 混为一谈。

## 未确认与纠正

- 没有官方依据证明222与196之间存在编号映射，也未查明222退出当前报价名单的原因。
- 196、62的一次NO_NUMBERS只证明各次没有分配号码，不代表供应商永久不可用，也不足以推导必须涨价。
- 网页当前登录账户与后端API账户是否完全一致，尚未通过账户身份对账证明；未读取或复制密钥做跨环境操作。
- 后续对账应分列请求供应商、订单实际供应商（若可取得）、报价时刻、国家/服务、价格与币种。未取得实际供方证据时标为未返回，不能用请求值冒充成交回执。
