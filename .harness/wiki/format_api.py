# -*- coding: utf-8 -*-
"""把 wheel_endpoints.json 渲染成 接口协议.md「模块 API」部分（图片同款 ### METHOD /path）。"""
import json, re

res = json.load(open('/tmp/wheel_endpoints.json', encoding='utf-8'))

# 控制器 -> (realm, 中文模块名)
ADMIN, TENANT, EDGE = "admin", "tenant", "edge"
CTRL = {
 "AdminAuthController": (ADMIN, "平台登录鉴权"),
 "AdminIamController": (ADMIN, "平台 IAM（用户 / 角色 / 菜单）"),
 "AdminTenantPackageController": (ADMIN, "租户与套餐管理"),
 "AdminMenuTemplateController": (ADMIN, "租户菜单模板"),
 "AdminUsageQuotaController": (ADMIN, "用量与配额"),
 "AdminBiController": (ADMIN, "平台 BI 业务数据"),
 "AdminResellerUsageController": (ADMIN, "代理商用量"),
 "AdminDailyStatController": (ADMIN, "平台每日统计"),
 "AdminDashboardController": (ADMIN, "平台首页看板"),
 "AdminMonitorController": (ADMIN, "跨租户监控"),
 "AdminDictController": (ADMIN, "字典管理"),
 "AdminResourcePoolController": (ADMIN, "平台资源池"),
 "AdminAccountPoolController": (ADMIN, "平台账号池"),
 "AdminDispatchController": (ADMIN, "资源派发"),
 "AdminFileController": (ADMIN, "平台文件（预签上传）"),
 "TenantAuthController": (TENANT, "租户登录鉴权"),
 "TenantIamController": (TENANT, "租户 IAM（用户 / 角色 / 菜单）"),
 "TenantAccountController": (TENANT, "账号管理"),
 "TenantAccountGroupController": (TENANT, "账号分组"),
 "TenantAccountImportController": (TENANT, "账号导入"),
 "TenantAccountStatsController": (TENANT, "账号统计卡"),
 "TenantBusinessBasicsController": (TENANT, "业务基础（标签 / 老群链接 / 素材 / 营销 / 群营销）"),
 "TenantManualTaskController": (TENANT, "拉群任务（批次 / 群行 / 日志）"),
 "TenantJoinTaskController": (TENANT, "进群任务"),
 "TenantWsGroupController": (TENANT, "群组列表（WhatsApp 群）"),
 "TenantPromotionController": (TENANT, "推广 / 落地页 / 渠道统计"),
 "TenantBuyerChannelController": (TENANT, "买号上量 · 渠道"),
 "TenantBuyerTrendController": (TENANT, "买号上量 · 趋势"),
 "TenantProtocolController": (TENANT, "协议管理"),
 "TenantProtocolExportController": (TENANT, "协议号导出"),
 "TenantTaskTemplateController": (TENANT, "拉群任务模板"),
 "TenantPullFieldDocController": (TENANT, "拉群字段说明"),
 "TenantResourceController": (TENANT, "租户资源池（IP / 数据包 / 导出）"),
 "TenantAlarmController": (TENANT, "告警"),
 "TenantDailyStatController": (TENANT, "租户每日统计"),
 "TenantDashboardController": (TENANT, "租户首页看板"),
 "TenantUsageController": (TENANT, "租户用量"),
 "TenantFileController": (TENANT, "租户文件（预签上传）"),
 "PublicHealthController": (EDGE, "健康检查（公开）"),
 "PublicLandingController": (EDGE, "公开落地页 / H5 扫号"),
 "TenantPublicAuthController": (EDGE, "租户公开登录"),
 "InternalDispatchEngineController": (EDGE, "内部调度引擎"),
 "GroupLinkHealthController": (EDGE, "群链接健康回报（内部）"),
 "H5WorkerEventController": (EDGE, "H5 Worker 事件回调（内部）"),
 "HeartbeatController": (EDGE, "心跳上报（内部）"),
}
REALM_TITLE = {
 ADMIN: ("一、平台端 API（admin-web · 前缀 `/api/admin`）", "JWT：admin 访问令牌（`Authorization: Bearer`）。"),
 TENANT: ("二、租户端 API（saas-web · 前缀 `/api/tenant`）", "JWT：tenant 访问令牌；`tenant_id` 由令牌解析，拦截器自动注入。"),
 EDGE: ("三、公开 / 内部 / Worker API", "公开 `/api/public/**` 免鉴权；内部 `/api/internal/**` 走 `X-Api-Key`，不对外。"),
}
REALM_ORDER = [ADMIN, TENANT, EDGE]

# 88 个缺 javadoc 端点的中文说明（key = "METHOD path"）
CURATED = {
 # TenantBusinessBasicsController
 "GET /api/tenant/tags":"标签列表","POST /api/tenant/tags":"新建标签","PATCH /api/tenant/tags/{id}":"编辑标签","DELETE /api/tenant/tags/{id}":"删除标签",
 "GET /api/tenant/group-links":"老群链接列表（分页）","POST /api/tenant/group-links":"新建老群链接","POST /api/tenant/group-links/import":"批量导入老群链接",
 "PATCH /api/tenant/group-links/{id}":"编辑老群链接","DELETE /api/tenant/group-links/{id}":"删除老群链接",
 "GET /api/tenant/link-labels":"链接标签列表","POST /api/tenant/link-labels":"新建链接标签",
 "GET /api/tenant/group-link-import-batches":"群链接导入批次列表（分页）","POST /api/tenant/group-link-import-batches":"创建群链接导入批次",
 "GET /api/tenant/group-link-import-batches/{id}":"群链接导入批次详情","GET /api/tenant/group-link-import-batches/{id}/export-failures":"导出导入失败行（CSV）",
 "GET /api/tenant/material-templates":"素材模板列表（分页）","POST /api/tenant/material-templates":"新建素材模板","PATCH /api/tenant/material-templates/{id}":"编辑素材模板",
 "POST /api/tenant/material-templates/{id}/audit-submit":"提交素材模板审核","DELETE /api/tenant/material-templates/{id}":"删除素材模板",
 "GET /api/tenant/group-material-templates":"群素材模板列表（分页）","POST /api/tenant/group-material-templates":"新建群素材模板","PATCH /api/tenant/group-material-templates/{id}":"编辑群素材模板",
 "POST /api/tenant/group-material-templates/{id}/toggle":"启用 / 停用群素材模板","DELETE /api/tenant/group-material-templates/{id}":"删除群素材模板",
 "GET /api/tenant/marketing-templates":"营销模板列表（分页）","POST /api/tenant/marketing-templates":"新建营销模板","PATCH /api/tenant/marketing-templates/{id}":"编辑营销模板","DELETE /api/tenant/marketing-templates/{id}":"删除营销模板",
 "GET /api/tenant/group-marketing-tasks":"群组营销任务列表（分页）","POST /api/tenant/group-marketing-tasks":"新建群组营销任务","GET /api/tenant/group-marketing-tasks/{id}":"群组营销任务详情",
 "POST /api/tenant/group-marketing-tasks/{id}/start":"启动群组营销任务","POST /api/tenant/group-marketing-tasks/{id}/stop":"停止群组营销任务","PATCH /api/tenant/group-marketing-tasks/{id}/materials":"更新群组营销任务素材",
 # TenantManualTaskController
 "POST /api/tenant/task-batches":"创建拉群任务（批次）","POST /api/tenant/task-batches/estimate":"预估自动拆分群数 / 资源","POST /api/tenant/task-batches/preview":"预览任务拆分结果",
 "GET /api/tenant/task-batches":"拉群任务列表（分页）","GET /api/tenant/task-batches/list":"拉群任务列表（别名）","GET /api/tenant/task-batches/{id}":"拉群任务详情","GET /api/tenant/task-batches/{id}/row-summary":"任务行汇总统计",
 "POST /api/tenant/task-batches/{id}/rows/submit":"标记所选群组为已交单","POST /api/tenant/task-batches/{id}/rows/unsubmit":"取消所选群组交单标记",
 "GET /api/tenant/task-batches/{id}/export-report":"导出任务报表","GET /api/tenant/task-batches/{id}/export-links":"导出群链接","GET /api/tenant/task-batches/{id}/export-resources":"导出任务资源（料子号）",
 "POST /api/tenant/task-batches/{id}/group-operations":"批量群组操作（检测状态 / 设管理员 / 补拉手等）",
 "POST /api/tenant/task-batches/{id}/start":"启动任务","POST /api/tenant/task-batches/{id}/pause":"暂停任务","POST /api/tenant/task-batches/{id}/resume":"恢复任务","POST /api/tenant/task-batches/{id}/stop":"中断任务",
 "POST /api/tenant/task-batches/batch-start":"批量启动任务","POST /api/tenant/task-batches/batch-pause":"批量暂停任务","POST /api/tenant/task-batches/batch-resume":"批量恢复任务","POST /api/tenant/task-batches/batch-stop":"批量中断任务","POST /api/tenant/task-batches/batch-delete":"批量删除任务",
 "GET /api/tenant/task-batches/{id}/rows":"任务群组（行）列表（分页）","GET /api/tenant/task-batches/{batchId}/rows/{rowId}/group-detail":"群组详情（明细抽屉）",
 "GET /api/tenant/task-rows/list":"任务行列表（别名）","POST /api/tenant/task-rows/batch-replay":"批量重跑群行","POST /api/tenant/task-rows/batch-stop":"批量中断群行","POST /api/tenant/task-rows/batch-pause":"批量暂停群行","POST /api/tenant/task-rows/batch-resume":"批量恢复群行",
 "GET /api/tenant/task-rows/{id}":"任务行详情","POST /api/tenant/task-rows/{id}/pause":"暂停单个群行","POST /api/tenant/task-rows/{id}/resume":"恢复单个群行","POST /api/tenant/task-rows/{id}/stop":"中断单个群行","POST /api/tenant/task-rows/{id}/replay":"重跑单个群行",
 "POST /api/tenant/task-rows/{id}/force-replace":"强制换号","POST /api/tenant/task-rows/{id}/manual-supplement":"手动补充拉手 / 管理员","PATCH /api/tenant/task-rows/{id}/throttle":"调整群行节流参数","POST /api/tenant/task-rows/{id}/add-admin":"追加管理员",
 "GET /api/tenant/task-logs":"任务事件流（分页）","GET /api/tenant/task-logs/list":"任务事件流（别名）","GET /api/tenant/task-logs/export":"导出任务日志",
 # 其余
 "POST /api/internal/grouplink/health/report":"群链接健康巡检回报（内部）","POST /api/internal/h5/worker-event":"H5 Worker 事件回调（内部）",
 "POST /api/tenant/accounts/batch-avatar":"批量设置账号头像",
 "GET /api/tenant/account-imports":"账号导入批次列表（分页）","POST /api/tenant/account-imports":"创建账号导入批次","GET /api/tenant/account-imports/{id}":"账号导入批次详情","GET /api/tenant/account-imports/{id}/export":"导出账号导入结果",
 "GET /api/tenant/protocol-exports":"协议号导出批次列表（分页）","GET /api/tenant/protocol-exports/estimate":"预估可导出协议号数","POST /api/tenant/protocol-exports":"创建协议号导出批次","GET /api/tenant/protocol-exports/{id}":"协议号导出批次详情","GET /api/tenant/protocol-exports/{id}/accounts.csv":"下载导出协议号 CSV",
}

def humanize(name):
    s = re.sub(r'(?<=[a-z])(?=[A-Z])', ' ', name)
    return s[:1].upper() + s[1:]

def esc(s):
    return (s or '').replace('|', '\\|').replace('\n', ' ')

def desc_of(e):
    k = f"{e['method']} {e['path']}"
    return CURATED.get(k) or e['summary'] or humanize(e['name'])

def render_ep(e):
    L = [f"### {e['method']} {e['path']}", "", esc(desc_of(e)), ""]
    pathp = [p for p in e['params'] if p['kind'] == 'path']
    queryp = [p for p in e['params'] if p['kind'] in ('query', 'queryobj')]
    body = [p for p in e['params'] if p['kind'] == 'body']
    part = [p for p in e['params'] if p['kind'] == 'part']
    rows = []
    for p in pathp:
        rows.append((p['name'], 'path', p['type'], '是', '-', p.get('desc', '')))
    for p in queryp:
        if p['kind'] == 'queryobj':
            rows.append(("（查询对象）", 'query', p['type'], '否', '-', p.get('desc', '') or '对象字段平铺为查询参数'))
        else:
            rows.append((p['name'], 'query', p['type'], '是' if p.get('required') else '否',
                         p['default'] if p.get('default') not in (None, '') else '-', p.get('desc', '')))
    for p in part:
        rows.append((p['name'], 'part', p['type'], '是', '-', p.get('desc', '') or 'multipart 文件块'))
    if rows:
        L.append("| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |")
        L.append("|------|------|------|------|------|------|")
        for nm, loc, ty, req, dv, ds in rows:
            L.append(f"| {esc(nm)} | {loc} | {esc(ty)} | {req} | {esc(str(dv))} | {esc(ds)} |")
        L.append("")
    # 紧凑要点列表（无空行间隔）
    L.append(f"- 权限：`{e['perm']}`" if e['perm'] else "- 权限：无需业务权限点")
    if body:
        b = body[0]
        bd = f"（{esc(b['desc'])}）" if b.get('desc') else ""
        L.append(f"- 请求体：`{esc(b['type'])}`{bd}")
    rd = f"（{esc(e['retdoc'])}）" if e.get('retdoc') else ""
    if e['response'] == '无':
        L.append("- 响应 `data`：无（仅 `code` / `message`）")
    elif e['ret_raw'].startswith('ApiResponse'):
        L.append(f"- 响应 `data`：`{esc(e['response'])}`{rd}")
    else:
        L.append(f"- 响应：`{esc(e['ret_raw'])}`{rd}（非 ApiResponse 包装，如文件流 / CSV）")
    L.append("")
    return "\n".join(L)

# 组装
by_realm = {ADMIN: [], TENANT: [], EDGE: []}
unknown = []
for c in res:
    meta = CTRL.get(c['controller'])
    if not meta:
        unknown.append(c['controller']); realm, zh = EDGE, c['controller']
    else:
        realm, zh = meta
    by_realm[realm].append((zh, c))

out = []
for realm in REALM_ORDER:
    title, note = REALM_TITLE[realm]
    out.append("---\n")
    out.append(f"# {title}\n")
    out.append(f"> {note}\n")
    # 控制器按中文名排序，保持稳定
    for zh, c in sorted(by_realm[realm], key=lambda x: x[0]):
        out.append(f"## {zh} API（{c['controller']}）\n")
        # 端点：按 path 再 method 排序，稳定可读
        for e in sorted(c['endpoints'], key=lambda e: (e['path'], e['method'])):
            out.append(render_ep(e))

open('/tmp/api_modules.md', 'w', encoding='utf-8').write("\n".join(out))
tot = sum(len(c['endpoints']) for c in res)
print(f"渲染端点={tot} 控制器={len(res)} 未映射控制器={unknown}")
# 自检：所有端点是否都有非空描述
missing=[(c['controller'],e['method'],e['path']) for c in res for e in c['endpoints'] if not desc_of(e)]
print("空描述:", len(missing), missing[:5])
