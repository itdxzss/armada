# -*- coding: utf-8 -*-
"""从 information_schema TSV 转储生成 数据模型.md（图片同款 per-table 格式）。
结构 100% 取自真库；描述优先级：库内列注释 > 业务映射(D2) > 通用列 > 名称启发 > '-'。"""
import csv, collections

COLS = "/tmp/wheel_columns.tsv"
IDX = "/tmp/wheel_indexes.tsv"
TBL = "/tmp/wheel_tables.tsv"
OUT = "/tmp/datamodel_tables.md"

# ---------------- 通用列（精确名命中） ----------------
COMMON = {
    "id": "主键",
    "tenant_id": "租户 ID（NULL 仅平台池资源使用）",
    "created_at": "创建时间",
    "updated_at": "更新时间",
    "created_by": "创建人 user_id",
    "updated_by": "更新人 user_id",
    "deleted_at": "软删除时间；NULL=未删",
    "granted_at": "授予时间",
    "granted_by": "授予人 user_id",
    "occurred_at": "发生时间",
    "started_at": "开始时间",
    "finished_at": "完成时间",
    "status": "状态",
    "remark": "备注",
    "name": "名称",
    "code": "编码（业务唯一）",
    "sort": "排序权重",
    "enabled": "是否启用",
    "visible": "是否可见",
    "is_builtin": "是否内置（内置不可删）",
    "description": "描述",
    "expire_at": "到期时间；NULL=永久",
    "last_check_at": "上次检查时间",
    "ownership": "归属：OWNED 永久 / LEASED 租借 / PLATFORM_POOL 平台池",
    "lease_until": "租借到期时间；仅 LEASED 填",
    "source": "来源",
    "perm_keys": "权限码数组（JSON）",
    "username": "登录名",
    "password_hash": "BCrypt 密码哈希（cost=10）",
    "display_name": "显示名",
    "email": "邮箱",
    "phone": "手机号",
    "avatar_url": "头像 URL",
    "last_login_at": "上次登录时间",
    "last_login_ip": "上次登录 IP",
    "must_change_pwd": "首次登录强制改密",
    "module_key": "模块编码",
    "menu_key": "菜单稳定 key",
    "parent_key": "父菜单 key",
    "route": "前端路由",
    "icon": "图标",
    "perm_key": "访问所需权限码",
    "package_code": "套餐编码",
    "meter_key": "用量计量项 key",
    "actor_user_id": "操作人 user_id",
    "actor_username": "操作人用户名（冗余便于审计）",
    "action": "操作动作",
    "target_type": "目标对象类型",
    "target_id": "目标对象 ID",
    "ip": "来源 IP",
    "user_agent": "User-Agent",
    "request_id": "traceId 关联",
    "payload": "操作参数（JSON，脱敏）",
    "result": "结果：OK / FAIL",
    "error_msg": "错误信息",
    "error_message": "错误信息",
    "upload_id": "预签上传 ID",
    "object_key": "对象存储 key",
    "filename": "文件名",
    "mime_type": "MIME 类型",
    "size_bytes": "文件大小（字节）",
    "uploader_id": "上传人 user_id",
    "s3_key": "对象存储 key",
    "bucket": "存储桶",
    "category": "分类",
    "file_id": "关联文件 ID",
    "batch_id": "所属批次 ID",
    "account_id": "关联账号 ID",
    "group_link_id": "关联老群链接 ID",
    "row_id": "关联任务行 ID",
    "seq": "序号",
}

# ---------------- 业务列描述（table.col）：取自 D2 蓝图与代码语义 ----------------
D = {
# master_user
"master_user.totp_secret":"TOTP 密钥（开启 2FA 时填）","master_user.totp_enabled":"是否启用 2FA",
"master_user.is_super":"超管标记；超管绕过权限校验",
# master_role
"master_role.is_builtin":"内置角色不可删",
# tenant
"tenant.contact_name":"联系人","tenant.contact_email":"联系邮箱","tenant.contact_phone":"联系电话",
"tenant.billing_policy":"配额超额策略：OFF / ALERT_ONLY / SOFT_STOP / HARD_STOP",
"tenant.max_users":"租户内用户上限","tenant.max_concurrent_rows":"拉群任务行级并发上限",
"tenant.retention_days":"任务日志保留天数",
# package
"package.module_keys":"开通的模块编码数组（JSON）","package.default_quotas":"默认配额配置（JSON）",
"package.max_users":"用户上限","package.max_concurrent_rows":"并发行上限","package.retention_days":"日志保留天数",
# tenant_module
"tenant_module.source":"PACKAGE=随套餐 / GRANTED=Admin 单独授权 / MANDATORY=强制",
"tenant_module.granted_by":"授权人 master_user_id","tenant_module.granted_at":"授权时间",
# tenant_role_template
"tenant_role_template.is_default":"租户开通时是否默认复制此角色",
# tenant_menu_template
"tenant_menu_template.module_key":"所属模块",
# module_definition
"module_definition.scope":"作用域：TENANT / ADMIN","module_definition.category":"业务分类：BUSINESS / RESOURCE / SYSTEM",
"module_definition.is_mandatory":"是否租户强制开通",
# quota
"quota.tenant_id":"0=平台默认模板，其它=租户特化","quota.meter_key":"计量项：pull.group.success 等",
"quota.period":"周期：DAILY / MONTHLY / TOTAL","quota.hard_limit":"硬上限；NULL=无上限",
"quota.soft_limit_ratio":"预警比例（0.8=达 80% 预警）","quota.policy":"OFF / ALERT_ONLY / SOFT_STOP / HARD_STOP",
"quota.reset_at":"下次重置时间（月配额每月 1 号）",
# usage_meter
"usage_meter_daily.stat_date":"统计日","usage_meter_daily.delta":"当日累计值（5min Job 从 Redis 落库覆盖）",
"usage_meter_daily.last_synced_at":"最近同步时间",
"usage_meter_monthly.stat_month":"统计月份（2026-05）","usage_meter_monthly.total":"当月累计值",
"usage_meter_monthly.last_synced_at":"最近同步时间",
# op_log
"op_log_tenant.impersonated_by":"master_user.id；非 NULL 表示 admin 代操作","op_log_tenant.impersonator_name":"代操作的 admin 用户名",
# dispatch_record
"dispatch_record.resource_type":"ACCOUNT / IP / PROTOCOL / DATA_PACK","dispatch_record.resource_ids":"资源 ID 数组（JSON）",
"dispatch_record.count_total":"派发数量","dispatch_record.ownership":"OWNED 出售 / LEASED 租借",
"dispatch_record.from_tenant_id":"来源租户；NULL=从平台池派发","dispatch_record.to_tenant_id":"目标租户",
"dispatch_record.operator_id":"操作人 master_user.id","dispatch_record.status":"DONE / REVOKED / EXPIRED",
# system_config
"system_config.config_key":"配置键（主键）","system_config.config_value":"配置值",
# dict
"dict_type.type_code":"字典类型编码","dict_item.type_code":"所属字典类型","dict_item.item_code":"字典项编码","dict_item.item_value":"字典项值",
# file_meta / session
"file_meta.uploader_id":"上传人 master_user.id",
"file_upload_session.expires_at":"预签过期时间",
"file_upload_session_tenant.uploader_id":"上传人 tenant_user.id","file_upload_session_tenant.expires_at":"预签过期时间",
# tenant_user
"tenant_user.is_tenant_admin":"租户管理员（开通时第一个用户）",
# tenant_role
"tenant_role.source_template":"从哪个 tenant_role_template 复制来",
# tenant_menu_override
"tenant_menu_override.visible":"NULL=用模板默认","tenant_menu_override.name_override":"NULL=用模板默认","tenant_menu_override.sort_override":"NULL=用模板默认",
# tenant_settings
"tenant_settings.timezone":"时区","tenant_settings.default_lang":"默认语言","tenant_settings.contact_info":"联系信息（JSON）","tenant_settings.business_type":"业务类型","tenant_settings.logo_url":"Logo URL",
# tenant_webhook
"tenant_webhook.channel":"DINGTALK / FEISHU / GENERIC","tenant_webhook.url":"回调地址","tenant_webhook.secret":"签名密钥","tenant_webhook.subscribed_events":"订阅事件数组（JSON）",
# tag
"tag.tag_name":"标签名","tag.role":"角色：PULLER / ADMIN / OPERATOR / STAGE","tag.tag_color":"标签颜色","tag.max_join_count":"仅 role=OPERATOR 用","tag.risk_cooldown_minutes":"风控冷却分钟","tag.daily_call_quota":"每日调用配额",
# account
"account.ws_phone":"WhatsApp 手机号","account.protocol_address":"协议层地址","account.ws_version":"WhatsApp 版本","account.ip_group_name":"IP 分组名","account.truth_ip":"真实出口 IP",
"account.account_type":"账号类型：PERSONAL_ANDROID / BUSINESS（导入即冻结）","account.number_source":"号码来源：PURCHASED / SCANNED / SCANNED_H5","account.channel_name":"采买=文件名，扫号=渠道名",
"account.dispatched_at":"上次派发时间","account.dispatched_by":"派发人",
"account.account_state":"账号状态：1=新增 2=正常 3=解绑（+扩展态）","account.login_state":"登录状态：1=在线 2=离线","account.risk_status":"风控状态：1=未风控 2=风控中",
"account.risk_start_time":"风控开始时间","account.risk_end_time":"风控结束时间","account.cooldown_until":"冷却截止时间",
"account.last_state_sync_time":"状态最近同步时间","account.state_source":"状态来源：HEARTBEAT / MANUAL_REFRESH / TASK_REPORT / STALE / NEED_REAUTH",
"account.tag_id":"单标签","account.role":"由 tag.role 派生，只读冗余",
"account.friends_num":"好友数","account.groups_num":"群数","account.successful_interactions":"成功互动数","account.first_login_time":"首次登录/入库时间",
"account.avatar_s3key":"头像对象存储 key","account.nickname":"昵称",
"account.worker_id":"承载该号的 worker 实例 ID","account.priority":"调度优先级",
"account.proxy_id":"协议层 proxy session ID","account.proxy_country":"proxy 出口国家码","account.proxy_asn":"proxy 出口 ASN",
"account.proxy_failure_count":"proxy 连续失败计数；切换时清零","account.country":"国家码","account.ip_type":"IP 类型","account.mute_status":"禁言状态",
"account.protocol_account_id":"协议层账号 ID（acc_ 句柄）","account.active_phone_key":"生成列：未删时=ws_phone，用于唯一活跃号约束",
"account.banned_reason":"封号原因码","account.pull_count":"作为拉手累计拉人数","account.hyperlink_count":"超链点击数（占位）","account.unbind_time":"解绑/失效时间","account.risk_end":"风控结束时间",
# account_history
"account_history.event_type":"事件类型：STATE_CHANGE / RISK / BANNED / LEASE_* / DISPATCH / TAG_CHANGE / H5_LOGIN 等","account_history.detail":"事件明细（JSON）",
# account_group
"account_group.group_name":"分组名","account_group.account_count":"分组内账号数（冗余/实时聚合）","account_group.color":"分组颜色",
# account_tag
"account_tag.account_id":"账号 ID","account_tag.tag_id":"标签 ID",
# account_import_batch
"account_import_batch.filename":"导入文件名","account_import_batch.group_name":"目标分组名","account_import_batch.device":"设备型号","account_import_batch.account_type":"账号类型","account_import_batch.service":"服务/运营商","account_import_batch.ip_mode":"IP 分配方式","account_import_batch.total_count":"总条数","account_import_batch.success_count":"成功数","account_import_batch.fail_count":"失败数","account_import_batch.parse_status":"解析状态：DONE / PARTIAL","account_import_batch.login_progress":"登录完成进度",
# account_import_detail
"account_import_detail.batch_id":"所属导入批次","account_import_detail.raw_line":"原始行文本","account_import_detail.ws_phone":"解析出的手机号","account_import_detail.parse_result":"解析结果","account_import_detail.fail_reason":"失败原因","account_import_detail.login_status":"登录状态",
# account_credential
"account_credential.wid":"WhatsApp wid","account_credential.protocol_account_id":"协议层账号 ID","account_credential.format":"凭证格式（六段/JSON）","account_credential.creds_json":"creds 全文（托管）","account_credential.session_token":"代理 session token","account_credential.proxy_line":"代理线路",
# ip_proxy
"ip_proxy.ip_address":"代理 IP","ip_proxy.port":"端口","ip_proxy.protocol":"协议：SOCKS5 等","ip_proxy.username":"代理认证用户名","ip_proxy.password":"代理认证密码/凭证串","ip_proxy.region":"地区","ip_proxy.group_name":"分组名","ip_proxy.bound_account_id":"绑定的账号 ID","ip_proxy.session_token":"住宅代理 session（轮换 IP 用）",
# protocol_account
"protocol_account.protocol_url":"协议层地址","protocol_account.protocol_type":"协议类型","protocol_account.auth_token":"鉴权 token","protocol_account.max_concurrent":"最大并发","protocol_account.last_heartbeat_at":"最近心跳时间",
# protocol
"protocol.protocol_name":"协议名","protocol.protocol_type":"协议类型",
# data_pack
"data_pack.pack_name":"数据包名","data_pack.pack_version":"版本","data_pack.pack_type":"类型：PROFILE / DEVICE / FINGERPRINT","data_pack.file_id":"关联文件 ID","data_pack.file_scope":"文件归属：TENANT / ADMIN","data_pack.item_count":"明细条数（实时聚合）",
"data_pack_item.pack_id":"所属数据包","data_pack_item.row_no":"行号",
# export_batch
"export_batch.export_type":"导出类型：ACCOUNT / TASK_LOG / STAT","export_batch.filter_json":"导出筛选条件（JSON）","export_batch.total_count":"导出总条数",
# protocol_export
"protocol_export_batch.export_type":"导出类型","protocol_export_batch.total_count":"导出总数","protocol_export_detail.batch_id":"所属导出批次","protocol_export_detail.protocol_account_id":"协议账号 ID",
# task_template
"task_template.template_name":"模板名","task_template.task_role":"0=拉手主导 1=管理主导","task_template.puller_num":"拉手数量","task_template.puller_tag_id":"拉手标签","task_template.admin_num":"管理员数量","task_template.admin_tag_id":"管理员标签","task_template.operator_num":"操作员数量","task_template.operator_tag_id":"操作员标签","task_template.stage_num":"站台号数量","task_template.stage_tag_id":"站台号标签","task_template.stage2_num":"二级站台号数量","task_template.stage2_tag_id":"二级站台号标签","task_template.use_admin":"是否使用管理员","task_template.review_mode":"审核模式：OFF / UNIFIED / PER_GROUP","task_template.ignore_group_entry_review":"忽略进群审核","task_template.material_template_id":"群信息素材模板","task_template.confirm_modify_group_info":"是否确认修改群信息","task_template.human_interval_sec":"拟人操作间隔(秒)","task_template.once_num_min":"单次最少拉人","task_template.once_num_max":"单次最多拉人","task_template.first_num":"首次拉人数","task_template.handle_thread_num":"处理线程数","task_template.handle_maximum_num":"单号处理上限","task_template.handle_min":"单号最少处理","task_template.admin_min":"管理员最少处理","task_template.same_num":"同号并发","task_template.sync_mode":"0=单个 1=批量","task_template.handle_quit_mode":"拉手退群方式：KEEP 等","task_template.admin_quit_mode":"管理员退群方式","task_template.lock_group":"是否锁群","task_template.not_release":"是否不放人","task_template.failed_move_tag":"失败移入标签","task_template.success_move_tag":"成功移入标签","task_template.hand_in_system_tag":"交单系统标签","task_template.marketing_template_id":"营销模板","task_template.marketing_interval_sec":"营销发送间隔(秒)","task_template.supplement_policy_json":"4 角色自动补号策略（JSON）","task_template.task_model":"保留老字段（含义待澄清）","task_template.forward_handle":"是否前置处理","task_template.wait_sec":"等待秒数",
# task_batch
"task_batch.batch_name":"任务名（业务可见的总任务 ID）","task_batch.template_id":"引用模板 ID","task_batch.mode":"AUTO / MANUAL","task_batch.material_file_ids":"素材文件 ID 数组（JSON）","task_batch.material_total_count":"素材总号数","task_batch.per_group_capacity":"每群目标人数","task_batch.auto_split_count":"自动拆分群数","task_batch.link_label_id":"群链接标签","task_batch.group_link_ids":"手选群链接 ID 数组（JSON）","task_batch.link_pick_strategy":"AUTO / MANUAL_PASTE / MANUAL_PICK","task_batch.config_snapshot":"建任务抽屉参数快照（JSON）","task_batch.template_override":"模板覆盖参数（JSON）","task_batch.task_type":"任务类型：拉群 / 老群链接进群","task_batch.submitted_at":"导出报表交单时间；NULL=未交单",
# task_row
"task_row.batch_id":"所属批次","task_row.seq":"批次内序号 1..N","task_row.target_count":"目标人数","task_row.done_count":"已完成数","task_row.failed_count":"失败数","task_row.group_link_url":"群链接 URL（冗余，链接删后仍可追溯）","task_row.occupied_pullers":"占用拉手 account_id 数组（JSON）","task_row.occupied_admins":"占用管理员（JSON）","task_row.occupied_operators":"占用操作员（JSON）","task_row.occupied_stages":"占用站台号（JSON）","task_row.occupied_stages2":"占用二级站台号（JSON）","task_row.health_status":"群健康度：NORMAL / ABNORMAL / RISK / BANNED","task_row.last_event_at":"最近事件时间","task_row.last_heartbeat_at":"最近心跳时间","task_row.block_reason":"阻塞/失败原因码","task_row.supplement_attempts":"补号尝试次数","task_row.current_count":"当前群人数（探测回写）","task_row.member_size":"群成员规模",
# task_log
"task_log.event":"事件：JOIN_SUCCESS / JOIN_FAIL / BANNED / RESUME / COMPLETED 等","task_log.target_no":"涉及的目标号","task_log.error_code":"错误码",
# task_water_plan
"task_water_plan.batch_id":"所属批次","task_water_plan.plan_json":"水军分配计划（JSON）","task_water_plan.water_account_id":"水军账号 ID","task_water_plan.group_link_id":"目标群链接",
# group_link
"group_link.group_url":"群链接 URL","group_link.group_name":"群名称","group_link.wa_subject":"WA 群主题（探测回写）","group_link.health_status":"健康状态","group_link.last_check_at":"上次巡检时间","group_link.last_health_error":"上次巡检错误","group_link.health_failure_count":"巡检连续失败数","group_link.is_banned":"是否被封","group_link.announce_only":"是否仅管理员可发言（禁言）","group_link.member_count":"群人数","group_link.owner_phone":"群主手机号","group_link.avatar_url":"群头像 URL","group_link.last_state_source":"状态来源","group_link.origin":"来源：TARGET 进群目标 / 自建 等","group_link.membership_state":"我方成员状态：JOINED 等","group_link.label_id":"链接标签 ID","group_link.country":"国家码","group_link.import_batch_id":"导入批次 ID",
# group_link_history
"group_link_history.group_link_id":"关联群链接","group_link_history.event_type":"事件类型","group_link_history.detail":"明细（JSON）",
# group_link_import
"group_link_import_batch.filename":"导入文件名","group_link_import_batch.total_count":"总条数","group_link_import_batch.success_count":"成功数","group_link_import_batch.fail_count":"失败数","group_link_import_batch.parse_status":"解析状态","group_link_import_detail.batch_id":"所属导入批次","group_link_import_detail.raw_line":"原始行","group_link_import_detail.group_url":"解析出的群链接","group_link_import_detail.parse_result":"解析结果","group_link_import_detail.fail_reason":"失败原因",
# link_label
"link_label.label_name":"标签名","link_label.color":"颜色","link_label.country":"国家码","link_label.link_count":"标签下链接数（聚合）",
# join_task
"join_task.task_name":"任务名","join_task.task_type":"任务类型","join_task.account_ids":"参与账号 ID 数组（JSON）","join_task.group_link_ids":"目标群链接 ID 数组（JSON）","join_task.use_admin":"是否用管理员模式","join_task.group_info_timing":"修改群信息时机","join_task.manager_group":"管理群","join_task.total_count":"总进群数","join_task.success_count":"成功数","join_task.fail_count":"失败数",
# join_task_result
"join_task_result.task_id":"所属进群任务","join_task_result.account_id":"账号 ID","join_task_result.group_link_id":"目标群链接","join_task_result.result":"结果","join_task_result.fail_reason":"失败原因","join_task_result.is_admin":"是否已提权为管理员","join_task_result.promoted_at":"提权时间",
# marketing_template
"marketing_template.template_name":"模板名","marketing_template.content":"正文内容","marketing_template.hyperlink_title":"超链标题","marketing_template.hyperlink_url":"超链 URL","marketing_template.button_items":"按钮项（JSON）","marketing_template.media_file_id":"媒体文件 ID",
# material_template
"material_template.template_name":"模板名","material_template.category":"分类","material_template.content":"内容","material_template.avatar_file_id":"头像文件 ID",
# material_audit
"material_audit.template_id":"关联素材模板","material_audit.audit_status":"审核状态","material_audit.auditor_id":"审核人","material_audit.audit_remark":"审核意见",
# material_phone
"material_phone.batch_id":"所属拉群批次","material_phone.ws_phone":"预分配手机号","material_phone.row_id":"分配到的任务行","material_phone.is_private":"是否隐私号（料子第2列=1）","material_phone.state":"使用状态：PENDING / JOINED 等",
# group_material_template
"group_material_template.template_name":"模板名","group_material_template.group_name_text":"群名文案","group_material_template.announcement":"群公告","group_material_template.avatar_file_id":"群头像文件 ID",
# group_marketing_task
"group_marketing_task.task_name":"任务名","group_marketing_task.template_id":"营销模板","group_marketing_task.group_link_ids":"目标群（JSON）","group_marketing_task.account_ids":"发言账号（JSON）","group_marketing_task.interval_sec":"发送间隔(秒)","group_marketing_task.total_count":"目标发送数","group_marketing_task.sent_count":"已发送数","group_marketing_task.fail_count":"失败数",
"group_marketing_task_detail.task_id":"所属营销任务","group_marketing_task_detail.group_link_id":"目标群","group_marketing_task_detail.account_id":"发言账号","group_marketing_task_detail.send_status":"发送状态","group_marketing_task_detail.sent_at":"发送时间",
# buyer_channel / daily_stat
"buyer_channel.channel_name":"渠道名","buyer_channel.channel_code":"渠道编码","buyer_daily_stat.stat_date":"统计日","buyer_daily_stat.channel_id":"渠道 ID","buyer_daily_stat.uploaded":"上量数","buyer_daily_stat.valid":"有效数","buyer_daily_stat.abnormal":"异常数",
# promotion / landing / visit
"promotion_channel.channel_name":"渠道名","promotion_channel.channel_code":"渠道编码","promotion_template.template_name":"模板名","landing_page.page_name":"落地页名","landing_page.page_kind":"类型：PROMO / WA_LOGIN","landing_page.content":"页面内容/模板","visit_log.visitor_id":"访客 ID","visit_log.channel_id":"渠道 ID","visit_log.landing_page_id":"落地页 ID","visit_log.client_ip_hash":"IP 的 SHA256（脱敏）","visit_log.ua_brief":"UA 截断",
# stat dailies
"channel_stat_daily.stat_date":"统计日","channel_ad_data.stat_date":"统计日","marketing_stat_daily.stat_date":"统计日","account_stat_daily.stat_date":"统计日",
# alarm
"alarm_rule.rule_name":"规则名","alarm_rule.metric":"监控指标","alarm_rule.threshold":"阈值","alarm_rule.level":"级别","alarm_rule.channel":"通知渠道","alarm_event.rule_id":"触发规则","alarm_event.level":"级别","alarm_event.title":"标题","alarm_event.content":"内容","alarm_event.acked":"是否已确认","alarm_event.acked_by":"确认人","alarm_event.acked_at":"确认时间",
# pull_field_doc
"pull_field_doc.field_key":"字段 key","pull_field_doc.field_name":"字段名","pull_field_doc.field_desc":"字段说明","pull_field_doc.field_group":"字段分组",
# wa_login_session (高频)
"wa_login_session.channel_id":"promotion_channel.id；NULL=直链","wa_login_session.landing_page_id":"使用的落地页","wa_login_session.ws_phone":"用户输入手机号（E.164 不带 +）","wa_login_session.country_code":"区号","wa_login_session.visitor_id":"H5 visitor_id","wa_login_session.client_ip_hash":"IP 的 SHA256","wa_login_session.ua_brief":"UA 截断","wa_login_session.geo_country":"GeoIP 国家","wa_login_session.pairing_code":"WA 8 位配对码；登录成功后清空","wa_login_session.pairing_code_expire":"配对码过期时间","wa_login_session.pairing_retry_count":"重新申请配对码次数","wa_login_session.fail_reason":"失败原因","wa_login_session.worker_id":"承载会话的 worker 实例","wa_login_session.account_id":"登录成功绑定的 account.id","wa_login_session.code_issued_at":"配对码下发时间","wa_login_session.linked_at":"关联设备时间","wa_login_session.ready_at":"会话稳定可用时间","wa_login_session.closed_at":"终态写入时间","wa_login_session.last_heartbeat_at":"最近心跳时间",
}

D.update({
# flyway 框架内置表
"flyway_schema_history.installed_rank":"安装顺序","flyway_schema_history.version":"迁移版本号","flyway_schema_history.type":"迁移类型（SQL）","flyway_schema_history.script":"迁移脚本文件名","flyway_schema_history.checksum":"脚本校验和","flyway_schema_history.installed_by":"执行账号","flyway_schema_history.installed_on":"执行时间","flyway_schema_history.success":"是否成功",
# 实际列名补充（D2 猜测与真库不一致处）
"group_link.capacity":"群容量上限",
"group_link_import_batch.source_file":"导入文件名","group_link_import_batch.total_rows":"总行数","group_link_import_batch.success_rows":"成功行数","group_link_import_batch.failed_rows":"失败行数",
"group_link_import_detail.line_no":"行号","group_link_import_detail.group_link":"解析出的群链接","group_link_import_detail.reason":"失败/结果原因",
"task_batch.group_source":"群来源方式（自建/老群链接）",
"group_marketing_task.send_per_round":"每轮发送数","group_marketing_task.send_interval_seconds":"发送间隔(秒)","group_marketing_task.check_account_online":"发送前检测账号在线","group_marketing_task.skip_abnormal_groups":"跳过异常群","group_marketing_task.auto_retry":"失败自动重试",
"group_marketing_task_detail.group_link":"目标群链接",
"group_material_template.group_description":"群描述/简介",
"marketing_template.link_mode":"链接形式（超链/纯文本）","marketing_template.message_body":"消息主体（结构化）","marketing_template.message_text":"消息纯文本","marketing_template.button_content":"按钮内容（JSON）","marketing_template.promotion_link":"推广链接",
"material_audit.reason":"审核意见/原因","material_audit.audited_by":"审核人",
"promotion_channel.channel_key":"渠道 key","promotion_channel.type":"渠道类型",
"landing_page.page_key":"落地页 key","landing_page.title":"标题",
"channel_stat_daily.visits":"访问量","channel_stat_daily.leads":"留资数","channel_stat_daily.cost":"花费",
"channel_ad_data.ad_account":"广告账户","channel_ad_data.impressions":"曝光量","channel_ad_data.clicks":"点击量","channel_ad_data.cost":"花费",
"alarm_rule.rule_key":"规则 key","alarm_rule.dedupe_window_min":"去重窗口(分钟)",
"alarm_event.rule_key":"触发规则 key","alarm_event.acknowledged_by":"确认人","alarm_event.closed_by":"关闭人","alarm_event.notify_result":"通知结果",
"protocol_export_detail.country":"国家码",
})

# ---------------- 名称启发 ----------------
def heuristic(col, typ):
    if col.endswith("_id"): return "关联 " + col[:-3] + " ID"
    if col.startswith("is_") or col.startswith("has_"): return "布尔标志"
    if col.endswith("_at") or col.endswith("_time"): return "时间"
    if col.endswith("_count") or col.endswith("_num"): return "数量"
    if col.endswith("_url"): return "URL"
    if col.endswith("_name"): return "名称"
    if col.endswith("_phone"): return "手机号"
    if col.endswith("_email"): return "邮箱"
    if col.endswith("_ip"): return "IP"
    if col.endswith("_json") or typ == "json": return "JSON 配置"
    if col.endswith("_code"): return "编码"
    if col.endswith("_status") or col.endswith("_state"): return "状态"
    if col.endswith("_type") or col.endswith("_kind"): return "类型"
    if col.endswith("_reason"): return "原因"
    return "-"

def desc_for(table, col, typ, dbcomment):
    if dbcomment.strip():
        return dbcomment.strip()
    if f"{table}.{col}" in D:
        return D[f"{table}.{col}"]
    if col in COMMON:
        return COMMON[col]
    return heuristic(col, typ)

def fmt_default(default, nullable, extra):
    extra = extra.lower()
    if "auto_increment" in extra:
        return "AUTO_INCREMENT"
    if "stored generated" in extra or "virtual generated" in extra:
        return "（生成列）"
    if default == "__NULL__":
        return "NULL" if nullable == "YES" else "-"
    base = "''" if default == "" else default
    if "on update current_timestamp" in extra:
        base = base + " ON UPDATE CURRENT_TIMESTAMP"
    return base

def esc(s):
    return s.replace("|", "\\|").replace("\n", " ")

# ---------------- 读数据 ----------------
table_cn = {}
with open(TBL, encoding="utf-8") as f:
    for r in csv.reader(f, delimiter="\t"):
        if len(r) >= 3:
            table_cn[(r[0], r[1])] = r[2]

cols = collections.defaultdict(list)
with open(COLS, encoding="utf-8") as f:
    for r in csv.reader(f, delimiter="\t"):
        if len(r) < 10:
            r = r + [""] * (10 - len(r))
        cols[(r[0], r[1])].append(r)

idx = collections.defaultdict(list)
with open(IDX, encoding="utf-8") as f:
    for r in csv.reader(f, delimiter="\t"):
        # schema, table, index_name, non_unique, seq, column, index_type
        if len(r) >= 7:
            idx[(r[0], r[1])].append(r)

# ---------------- 分组（armada 按业务族；兼容旧 wheel_tenant 导出标识） ----------------
ADMIN_ORDER = ["master_user","master_role","master_user_role","master_menu",
    "tenant","package","tenant_module","tenant_role_template","tenant_menu_template","module_definition",
    "quota","usage_meter_daily","usage_meter_monthly","op_log_admin","dispatch_record","system_config",
    "dict_type","dict_item","file_meta","file_upload_session","flyway_schema_history"]

TENANT_GROUPS = [
    ("租户 IAM / 设置 / 审计", ["tenant","tenant_user","tenant_role","tenant_user_role","tenant_menu_override","tenant_settings","tenant_webhook","op_log_tenant"]),
    ("标签", ["tag","account_tag"]),
    ("账号与归属", ["account","account_history","account_group","account_group_baseline","account_credential","account_import_batch","account_import_detail","account_stat_daily","wa_login_session"]),
    ("群组 / 群链接池", ["group_link_label","group_link","group_link_preview","group_link_health","account_group_membership","group_link_history","group_link_import_batch","group_link_import_detail"]),
    ("拉群任务族", ["task_template","task_batch","task_row","task_log","task_water_plan","material_phone"]),
    ("进群任务", ["join_task","join_task_result"]),
    ("群组营销 / 素材", ["marketing_task","marketing_task_target","marketing_task_send_attempt","group_marketing_task","group_marketing_task_detail","group_material_template","marketing_template","material_template","material_audit"]),
    ("买量 / 推广 / 落地页", ["buyer_channel","buyer_daily_stat","promotion_channel","promotion_template","landing_page","visit_log"]),
    ("统计", ["channel_stat_daily","channel_ad_data","marketing_stat_daily"]),
    ("告警", ["alarm_rule","alarm_event"]),
    ("资源池（国家 / IP / 协议 / 数据包 / 导出）", ["country","ip_proxy","protocol","protocol_account","protocol_export_batch","protocol_export_detail","data_pack","data_pack_item","export_batch"]),
    ("公共（文件 / 字段字典）", ["file_meta_tenant","file_upload_session_tenant","pull_field_doc"]),
]

def render_table(schema, t):
    cn = table_cn.get((schema, t), "")
    head = f"### {t}" + (f"（{cn}）" if cn else "")
    lines = [head, ""]
    lines.append("| Column | Type | Nullable | Default | Description |")
    lines.append("|--------|------|----------|---------|-------------|")
    for r in cols[(schema, t)]:
        _, _, pos, col, typ, nullable, default, key, extra, comment = r[:10]
        d = esc(desc_for(t, col, typ, comment))
        lines.append(f"| {col} | {typ} | {'YES' if nullable=='YES' else 'NO'} | {esc(fmt_default(default, nullable, extra))} | {d} |")
    # indexes
    rows = idx.get((schema, t), [])
    byname = collections.OrderedDict()
    for r in rows:
        _, _, iname, nonuniq, seq, cname, itype = r[:7]
        byname.setdefault(iname, {"nonuniq": nonuniq, "cols": [], "itype": itype})
        byname[iname]["cols"].append((int(seq), cname))
    if byname:
        lines.append("")
        lines.append("**Indexes:**")
        lines.append("")
        lines.append("| Name | Columns | Type |")
        lines.append("|------|---------|------|")
        for iname, info in byname.items():
            colstr = ", ".join(c for _, c in sorted(info["cols"]))
            if iname == "PRIMARY":
                ttype = "PRIMARY"
            elif info["nonuniq"] == "0":
                ttype = "UNIQUE"
            else:
                ttype = "INDEX"
            lines.append(f"| {iname} | {colstr} | {ttype} |")
    lines.append("")
    return "\n".join(lines)

out = []
seen = set()
ADMIN_SCHEMA = "armada_admin" if any(s == "armada_admin" for (s, _) in cols) else "wheel_admin"
TENANT_SCHEMA = "armada" if any(s == "armada" for (s, _) in cols) else "wheel_tenant"

if any(s == ADMIN_SCHEMA for (s, _) in cols):
    out.append("## armada admin schema（平台运营，不带 tenant_id）\n")
    for t in ADMIN_ORDER:
        if (ADMIN_SCHEMA, t) in cols:
            out.append(render_table(ADMIN_SCHEMA, t)); seen.add((ADMIN_SCHEMA, t))
    # 任何遗漏的 admin 表
    for (s, t) in cols:
        if s == ADMIN_SCHEMA and (s, t) not in seen:
            out.append(render_table(s, t)); seen.add((s, t))

out.append("## armada schema（业务数据，行级 tenant_id 隔离）\n")
for gtitle, tabs in TENANT_GROUPS:
    out.append(f"### · {gtitle}\n")
    for t in tabs:
        if (TENANT_SCHEMA, t) in cols:
            out.append(render_table(TENANT_SCHEMA, t)); seen.add((TENANT_SCHEMA, t))
# 任何遗漏的 tenant 表
missing = [t for (s, t) in cols if s == TENANT_SCHEMA and (s, t) not in seen]
if missing:
    out.append("### · 其他（未归类）\n")
    for t in sorted(missing):
        out.append(render_table(TENANT_SCHEMA, t)); seen.add((TENANT_SCHEMA, t))

with open(OUT, "w", encoding="utf-8") as f:
    f.write("\n".join(out))

# 统计
n_admin = sum(1 for (s, _) in seen if s == ADMIN_SCHEMA)
n_tenant = sum(1 for (s, _) in seen if s == TENANT_SCHEMA)
print(f"生成完成：armada_admin={n_admin} 表, armada={n_tenant} 表, 共 {len(seen)} 表")
print(f"未归类 tenant 表: {missing}")
print(f"输出: {OUT}")
