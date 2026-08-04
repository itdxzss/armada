const appState = {
  currentGroup: 'Test Group 01-02',
  historyFilter: {
    continent: '不限',
    country: '不限',
    days: '不限制',
    members: '不限制',
  },
  groupProjects: [
    { id: 1, name: '7-31建普群4人群组', available: 4 },
    { id: 2, name: '7-31建普群90人群组', available: 90 },
    { id: 3, name: '7-30建普群', available: 37 },
    { id: 4, name: 'Test Group 07-12', available: 12 },
  ],
  tasks: {
    GM20260729010: { id: '#GM20260729010', name: 'Philippines Resource Check', type: '拉群营销', source: '历史老群', status: '执行失败', stage: '设置管理员中', reason: '管理员接管失败', groupProgress: '39 / 56 · 70%', createdAt: '2026-07-29 09:18' },
    GM20260729009: { id: '#GM20260729009', name: 'Malaysia Client Ops', type: '拉群营销', source: '混合来源', status: '已完成', stage: '全部完成', reason: '全部任务已完成', groupProgress: '35 / 52 · 67%', createdAt: '2026-07-29 08:42' },
    GM20260729008: { id: '#GM20260729008', name: 'Indonesia Group Reuse', type: '拉群营销', source: '自收群', status: '已停止', stage: '永久停止', reason: '用户手动停止', groupProgress: '31 / 48 · 65%', createdAt: '2026-07-28 17:30' },
    GM20260729007: { id: '#GM20260729007', name: 'Brazil Member Activate', type: '拉群营销', source: '历史老群', status: '已暂停', stage: '暂停中', reason: '用户手动暂停', groupProgress: '27 / 44 · 61%', createdAt: '2026-07-27 21:06' },
    GM20260729006: { id: '#GM20260729006', name: 'India Product Promo', type: '拉群营销', source: '混合来源', status: '部分完成', stage: '异常处理中', reason: '部分群组待人工处理', groupProgress: '23 / 40 · 57%', createdAt: '2026-07-27 14:35' },
  },
  groups: {
    101: {
      name: 'Test Group 01-02',
      jid: '120363427376403530@g.us',
      members: 100,
      createdAt: '2026-05-18 14:22',
      country: '🇺🇸 美国',
      continent: '北美洲',
      ownerId: '317415908',
      ownerName: 'station us 623',
      ownerPhone: '14156208891',
      identity: '群主',
      availability: '正常可用',
      status: '正常',
      policy: '全员加入',
      inviteLink: 'wa.me/group/US623A',
      relatedTask: '任务_731092918',
    },
    102: {
      name: 'Grupo Colombia 07-12',
      jid: '120363427049576136@g.us',
      members: 42,
      createdAt: '2026-06-07 09:36',
      country: '🇨🇴 哥伦比亚',
      continent: '南美洲',
      ownerId: '317416212',
      ownerName: 'colombia node',
      ownerPhone: '573008124501',
      identity: '管理员',
      availability: '正常可用',
      status: '正常',
      policy: '管理员审核',
      inviteLink: 'wa.me/group/CO712B',
      relatedTask: '未占用',
    },
    103: {
      name: 'Mixed Source Pending',
      jid: '-',
      members: '-',
      createdAt: '2026-07-12 18:05',
      country: '🇧🇷 巴西',
      continent: '南美洲',
      ownerId: '317417005',
      ownerName: 'mixed source',
      ownerPhone: '5521940027788',
      identity: '群主',
      availability: '暂不可用',
      status: '封禁',
      policy: '暂停加入',
      inviteLink: 'wa.me/group/MIX103',
      relatedTask: '未占用',
    },
  },
};

const workbenchState = {
  activeTaskId: 'GM20260729010',
  groups: {
    g1: {
      suffix: '群组 A', groupId: 'G-PH-001', members: 168, link: 'wa.me/group/PH-A001', stage: '设置管理员', progress: '39 / 56', issue: '管理员接管失败', action: '补充管理员',
      target: { total: 56, assigned: 56, success: 39, failed: 3, privacy: 6, retry: 4, pending: 4 },
      timeline: [['群组锁定', '已完成'], ['修改群组资料', '已完成'], ['拉手进入群组', '已完成'], ['添加水军', '已完成'], ['添加目标用户', '39 / 56'], ['补充管理员', '执行失败'], ['原管理员退出', '未开始'], ['营销执行', '未开始']],
      records: [['11:40', '设置管理员', '+639123****', '管理员账号', '失败', '管理员接管失败'], ['11:32', '添加目标用户', '+639333****', '目标用户批次 06', '成功', '--'], ['11:10', '添加水军', '+639222****', '水军批次 02', '成功', '--']],
    },
    g2: {
      suffix: '群组 B', groupId: 'G-PH-002', members: 126, link: 'wa.me/group/PH-B002', stage: '添加成员', progress: '22 / 50', issue: '拉手不足', action: '补充拉手',
      target: { total: 50, assigned: 36, success: 22, failed: 2, privacy: 4, retry: 3, pending: 14 },
      timeline: [['群组锁定', '已完成'], ['修改群组资料', '已完成'], ['拉手进入群组', '等待补充'], ['添加水军', '已完成'], ['添加目标用户', '22 / 50'], ['设置管理员', '未开始'], ['营销执行', '未开始']],
      records: [['11:32', '拉手资源校验', '系统', '拉手分组', '失败', '可用拉手为 0'], ['11:05', '添加目标用户', '+639444****', '目标用户批次 03', '成功', '--']],
    },
    g3: {
      suffix: '群组 C', groupId: 'G-PH-003', members: 205, link: 'wa.me/group/PH-C003', stage: '营销执行', progress: '50 / 50', issue: '营销账号离线', action: '切换营销账号',
      target: { total: 50, assigned: 50, success: 50, failed: 0, privacy: 0, retry: 0, pending: 0 },
      timeline: [['群组锁定', '已完成'], ['拉手进入群组', '已完成'], ['添加目标用户', '50 / 50'], ['设置管理员', '已完成'], ['营销执行', '第 3 / 8 轮'], ['群组封控', '未开始']],
      records: [['11:18', '营销发送', '+639555****', '第 3 轮消息', '失败', '营销账号离线'], ['10:50', '营销发送', '+639555****', '第 2 轮消息', '成功', '--']],
    },
    g4: {
      suffix: '群组 D', groupId: 'G-PH-004', members: 142, link: 'wa.me/group/PH-D004', stage: '添加水军', progress: '32 / 60', issue: '水军资源不足', action: '补充水军',
      target: { total: 60, assigned: 44, success: 32, failed: 2, privacy: 5, retry: 3, pending: 16 },
      timeline: [['群组锁定', '已完成'], ['拉手进入群组', '已完成'], ['添加水军', '执行失败'], ['添加目标用户', '32 / 60'], ['设置管理员', '未开始'], ['营销执行', '未开始']],
      records: [['10:56', '添加水军', '+639666****', '水军批次 04', '失败', '可用水军不足'], ['10:30', '添加目标用户', '+639777****', '目标用户批次 02', '成功', '--']],
    },
    g5: {
      suffix: '群组 E', groupId: 'G-PH-005', members: 188, link: 'wa.me/group/PH-E005', stage: '已完成', progress: '56 / 56', issue: '无', action: '查看详情',
      target: { total: 56, assigned: 56, success: 56, failed: 0, privacy: 0, retry: 0, pending: 0 },
      timeline: [['群组锁定', '已完成'], ['修改群组资料', '已完成'], ['拉手进入群组', '已完成'], ['添加水军', '已完成'], ['添加目标用户', '56 / 56'], ['设置管理员', '已完成'], ['营销执行', '已完成'], ['群组封控', '已完成']],
      records: [['10:30', '任务收口', '系统', '群组 E', '成功', '--'], ['10:12', '营销发送', '+639888****', '第 8 轮消息', '成功', '--']],
    },
  },
};

const details = {
  historyFilter: {
    title: '历史群组筛选',
    body: `
      <section class="detail-section">
        <h3>群所属地区</h3>
        <div class="history-grid">
          <label><span>群所属大洲</span><select id="continentSelect"><option>不限</option><option>🌐 亚洲</option><option>🌐 欧洲</option><option>🌐 北美洲</option><option>🌐 南美洲</option><option>🌐 非洲</option><option>🌐 大洋洲</option></select></label>
          <label><span>群所属国家</span><select id="countrySelect"><option>不限</option><option>🇺🇸 美国</option><option>🇨🇴 哥伦比亚</option><option>🇧🇷 巴西</option><option>🇮🇳 印度</option><option>🇵🇭 菲律宾</option></select></label>
        </div>
      </section>
      <section class="detail-section">
        <h3>这个群最少/最多满多少天？</h3>
        <div class="range-summary"><span><small>最少满</small><b id="daysMin">不限</b></span><span><small>最多满</small><b id="daysMax">不限</b></span></div>
        <input class="wide-range" type="range" min="0" max="365" value="365" />
        <div class="quick-tags" data-filter-key="days"><button class="active">不限制</button><button>7 天以上</button><button>7-30 天</button><button>30 天以上</button><button>30-90 天</button><button>3 天以上</button><button>14-60 天</button><button>90 天以上</button></div>
        <p class="hint">拖到最右边表示「最多满」不限制</p>
      </section>
      <section class="detail-section">
        <h3>群里现在大概有多少人？</h3>
        <div class="range-summary"><span><small>最少</small><b id="membersMin">不限</b></span><span><small>最多</small><b id="membersMax">不限</b></span></div>
        <input class="wide-range" type="range" min="0" max="500" value="500" />
        <div class="quick-tags" data-filter-key="members"><button class="active">不限制</button><button>最少 5 人</button><button>最少 10 人</button><button>10-20 人</button><button>20-50 人</button><button>50-100 人</button><button>100-200 人</button><button>200 人以上</button></div>
        <p class="hint">拖到最右边表示「最多」不限制</p>
      </section>
      <footer class="drawer-actions"><button id="clearHistoryFilter">清空</button><button id="applyHistoryFilter">应用筛选</button><button class="primary" id="queryHistoryFilter">查询</button></footer>
    `,
  },
  groupProjectList: {
    title: '群组项目列表',
    body: `
      <section class="project-list-panel">
        <button class="primary project-add" id="addGroupProject">＋ 添加群组分组</button>
        <table class="project-table">
          <thead>
            <tr>
              <th>分组名称</th>
              <th>可用群</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody id="projectRows"></tbody>
        </table>
        <footer class="project-pager"><span id="projectTotal"></span><button disabled>‹</button><button class="primary">1</button><button disabled>›</button></footer>
      </section>
    `,
  },
  taskDetail: {
    title: '拉群任务明细',
    body: (task) => `
      <section class="detail-section">
        <h3>任务基础信息</h3>
        <dl class="detail-grid">
          <div><dt>任务名称</dt><dd>${task.name}</dd></div>
          <div><dt>任务 ID</dt><dd>${task.id}</dd></div>
          <div><dt>任务类型</dt><dd>${task.type}</dd></div>
          <div><dt>任务状态</dt><dd>${task.status}</dd></div>
        </dl>
      </section>
      <section class="detail-section">
        <h3>任务统计摘要</h3>
        <dl class="detail-grid">
          <div><dt>群组来源</dt><dd>${task.source}</dd></div>
          <div><dt>当前阶段</dt><dd>${task.stage}</dd></div>
          <div><dt>完成 / 停止原因</dt><dd>${task.reason}</dd></div>
          <div><dt>拉群进度</dt><dd>${task.groupProgress}</dd></div>
          <div><dt>创建时间</dt><dd>${task.createdAt}</dd></div>
          <div><dt>新增截图指标</dt><dd>统计口径待确认</dd></div>
        </dl>
      </section>
    `,
  },
  groupDetail: {
    title: '群组详情',
    body: (group) => `
      <section class="detail-section group-detail-section">
        <h3>基本信息</h3>
        <table class="info-table">
          <tbody>
            <tr><th>群名称</th><td colspan="3">${group.name}</td></tr>
            <tr><th>群 JID</th><td colspan="3"><span class="copy-cell">${group.jid} <button class="copy-btn" data-copy="${group.jid}" title="复制">⧉</button></span></td></tr>
            <tr><th>成员数</th><td>${group.members}</td><th>建群时间</th><td>${group.createdAt}</td></tr>
            <tr><th>国家</th><td>${group.country}</td><th>大洲</th><td>${group.continent}</td></tr>
          </tbody>
        </table>
      </section>
      <section class="detail-section group-detail-section">
        <h3>所属账号</h3>
        <table class="info-table">
          <tbody>
            <tr><th>账号 ID</th><td>${group.ownerId}</td><th>当前身份</th><td><em class="tag warm">${group.identity}</em></td></tr>
            <tr><th>账号名称</th><td>${group.ownerName}</td><th>完整手机号</th><td><span class="copy-cell">${group.ownerPhone} <button class="copy-btn" data-copy="${group.ownerPhone}" title="复制">⧉</button></span></td></tr>
          </tbody>
        </table>
      </section>
      <section class="detail-section group-detail-section">
        <h3>可用性与邀请链接</h3>
        <table class="info-table">
          <tbody>
            <tr><th>可用性</th><td><em class="tag ${group.status === '正常' ? 'ok' : 'banned'}">${group.availability}</em></td></tr>
            <tr><th>邀请链接</th><td><span class="copy-cell">${group.inviteLink} <button class="copy-btn" data-copy="${group.inviteLink}" title="复制">⧉</button></span></td></tr>
          </tbody>
        </table>
      </section>
      <section class="detail-section group-detail-section">
        <h3>群策略</h3>
        <table class="info-table">
          <tbody>
            <tr><th>当前策略 <button class="help-icon" data-help="当前策略对应 WS 群组是否开启允许添加其他成员：开启为全员加入，关闭为管理员审核。">?</button></th><td><em class="tag ${group.policy === '全员加入' ? 'ok' : 'warm'}">${group.policy}</em></td></tr>
            <tr><th>关联任务</th><td>${group.relatedTask === '未占用' ? group.relatedTask : `<button class="link inline-link" data-jump-view="taskView">${group.relatedTask}</button>`}</td></tr>
            <tr><th>历史筛选</th><td id="groupFilterSummary">未应用</td></tr>
          </tbody>
        </table>
      </section>
    `,
  },
};

const views = document.querySelectorAll('.view');
const navButtons = document.querySelectorAll('[data-view]');
const crumbName = document.querySelector('#crumbName');
const drawer = document.querySelector('.drawer');
const mask = document.querySelector('#mask');
const drawerTitle = document.querySelector('#drawerTitle');
const drawerBody = document.querySelector('#drawerBody');
const historyButton = document.querySelector('.history-filter');

function historySummary() {
  const values = [appState.historyFilter.continent, appState.historyFilter.country, appState.historyFilter.days, appState.historyFilter.members].filter((value) => value && value !== '不限' && value !== '不限制');
  return values.length ? values.join(' / ') : '未应用';
}

function renderHistorySummary() {
  if (!historyButton) return;
  const summary = historySummary();
  historyButton.textContent = summary === '未应用' ? '历史群组筛选' : `历史群组筛选：${summary}`;
  historyButton.classList.toggle('has-filter', summary !== '未应用');
  document.querySelectorAll('[data-history-summary]').forEach((item) => {
    item.textContent = summary;
  });
}

function renderGroupProjects() {
  const rows = drawerBody.querySelector('#projectRows');
  const total = drawerBody.querySelector('#projectTotal');
  if (!rows) return;
  rows.innerHTML = appState.groupProjects.map((project) => `
    <tr data-project-id="${project.id}">
      <td>${project.name}</td>
      <td>${project.available}</td>
      <td><button class="link" data-project-action="edit">✎ 编辑</button><button class="link danger-text" data-project-action="delete">▱ 删除</button></td>
    </tr>
  `).join('');
  if (total) total.textContent = `共 ${appState.groupProjects.length} 条`;
}

function closeProjectModal() {
  const modal = document.querySelector('.project-modal-mask');
  if (modal) modal.remove();
}

function openProjectEditModal(id) {
  const project = appState.groupProjects.find((item) => item.id === id);
  if (!project) return;
  closeProjectModal();
  const modal = document.createElement('div');
  modal.className = 'project-modal-mask';
  modal.innerHTML = `
    <div class="project-modal" role="dialog" aria-modal="true">
      <header><h3>编辑群组分组</h3><button data-project-modal="cancel">×</button></header>
      <label><span>分组名称</span><input id="projectNameInput" value="${project.name}" /></label>
      <label><span>可用群</span><input id="projectAvailableInput" type="number" min="0" value="${project.available}" /></label>
      <footer><button data-project-modal="cancel">取消</button><button class="primary" data-project-modal="save">保存</button></footer>
    </div>
  `;
  document.body.appendChild(modal);
  const nameInput = modal.querySelector('#projectNameInput');
  const availableInput = modal.querySelector('#projectAvailableInput');
  nameInput.focus();
  nameInput.select();

  modal.addEventListener('click', (event) => {
    if (event.target === modal || event.target.closest('[data-project-modal="cancel"]')) {
      closeProjectModal();
      return;
    }
    if (event.target.closest('[data-project-modal="save"]')) {
      const name = nameInput.value.trim();
      if (!name) {
        showToast('分组名称不能为空');
        return;
      }
      project.name = name;
      project.available = Math.max(0, Number(availableInput.value || 0));
      closeProjectModal();
      renderGroupProjects();
      showToast('分组已保存');
    }
  });
}

function bindGroupProjectList() {
  renderGroupProjects();
  const addButton = drawerBody.querySelector('#addGroupProject');
  addButton.addEventListener('click', () => {
    const nextId = Math.max(0, ...appState.groupProjects.map((item) => item.id)) + 1;
    appState.groupProjects.unshift({ id: nextId, name: `新建群组分组${nextId}`, available: 0 });
    renderGroupProjects();
    openProjectEditModal(nextId);
  });

  drawerBody.querySelector('#projectRows').addEventListener('click', (event) => {
    const button = event.target.closest('button[data-project-action]');
    if (!button) return;
    const row = button.closest('tr');
    const id = Number(row.dataset.projectId);
    const action = button.dataset.projectAction;

    if (action === 'edit') {
      openProjectEditModal(id);
      return;
    }

    if (action === 'delete') {
      const project = appState.groupProjects.find((item) => item.id === id);
      if (!window.confirm(`确认删除「${project.name}」吗？`)) return;
      appState.groupProjects = appState.groupProjects.filter((item) => item.id !== id);
      renderGroupProjects();
      showToast('分组已删除');
    }
  });
}

function showView(id) {
  views.forEach((view) => view.classList.toggle('active', view.id === id));
  const navView = id === 'taskWorkbenchView' ? 'taskView' : id;
  navButtons.forEach((button) => button.classList.toggle('active', button.dataset.view === navView));
  crumbName.textContent = id === 'groupView' ? '群组列表' : id === 'taskWorkbenchView' ? '拉群任务 / 任务详情' : '拉群任务';
}

function taskStatusClass(status) {
  if (status === '已完成') return 'done';
  if (status === '已暂停') return 'paused';
  if (status === '部分完成') return 'partial';
  return 'failed';
}

function showTaskWorkbench(taskId) {
  const task = appState.tasks[taskId] || appState.tasks.GM20260729010;
  workbenchState.activeTaskId = taskId in appState.tasks ? taskId : 'GM20260729010';
  document.querySelector('#workbenchTaskName').textContent = task.name;
  document.querySelector('#workbenchTaskId').textContent = task.id;
  const status = document.querySelector('#workbenchTaskStatus');
  status.className = `state ${taskStatusClass(task.status)}`;
  status.textContent = `● ${task.status}`;
  document.querySelectorAll('.workbench-group-name').forEach((name, index) => {
    name.textContent = `${task.name} · 群组 ${String.fromCharCode(65 + index)}`;
  });
  document.querySelectorAll('.group-expand-row').forEach((row) => {
    row.hidden = true;
    row.querySelector('td').innerHTML = '';
  });
  document.querySelectorAll('[data-workbench-group]').forEach((row) => { row.hidden = false; });
  document.querySelectorAll('[data-workbench-filter]').forEach((button) => button.classList.toggle('active', button.dataset.workbenchFilter === 'all'));
  showView('taskWorkbenchView');
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function bindDrawerInteractions(key, context = {}) {
  if (key === 'historyFilter') {
    const continentSelect = drawerBody.querySelector('#continentSelect');
    const countrySelect = drawerBody.querySelector('#countrySelect');
    continentSelect.value = appState.historyFilter.continent;
    countrySelect.value = appState.historyFilter.country;

    drawerBody.querySelectorAll('.quick-tags').forEach((group) => {
      const filterKey = group.dataset.filterKey;
      group.querySelectorAll('button').forEach((button) => {
        button.classList.toggle('active', button.textContent.trim() === appState.historyFilter[filterKey]);
      });
      group.addEventListener('click', (event) => {
        const button = event.target.closest('button');
        if (!button) return;
        group.querySelectorAll('button').forEach((item) => item.classList.remove('active'));
        button.classList.add('active');
        appState.historyFilter[filterKey] = button.textContent.trim();
      });
    });

    drawerBody.querySelector('#clearHistoryFilter').addEventListener('click', () => {
      appState.historyFilter = { continent: '不限', country: '不限', days: '不限制', members: '不限制' };
      openDrawer('historyFilter');
      renderHistorySummary();
    });

    const commitHistoryFilter = () => {
      appState.historyFilter.continent = continentSelect.value;
      appState.historyFilter.country = countrySelect.value;
      renderHistorySummary();
    };

    drawerBody.querySelector('#applyHistoryFilter').addEventListener('click', () => {
      commitHistoryFilter();
      closeDrawer();
    });

    drawerBody.querySelector('#queryHistoryFilter').addEventListener('click', () => {
      commitHistoryFilter();
      const groupTitle = document.querySelector('#groupView .table-panel h1');
      if (groupTitle) groupTitle.textContent = `群组列表（${historySummary()}）`;
      showView('groupView');
      closeDrawer();
    });
  }

  if (key === 'groupProjectList') {
    bindGroupProjectList();
  }

  drawerBody.querySelectorAll('[data-jump-view]').forEach((button) => {
    button.addEventListener('click', () => {
      closeDrawer();
      showView(button.dataset.jumpView);
    });
  });
}

function openDrawer(key, context = {}) {
  const detail = details[key];
  if (!detail) return;
  const group = context.groupId ? appState.groups[context.groupId] || appState.groups[101] : appState.groups[101];
  const task = context.taskId ? appState.tasks[context.taskId] || appState.tasks.GM20260729010 : appState.tasks.GM20260729010;
  drawerTitle.textContent = detail.title;
  drawerBody.innerHTML = typeof detail.body === 'function' ? detail.body(key === 'taskDetail' ? task : group) : detail.body;
  drawer.classList.add('open');
  mask.classList.add('open');
  bindDrawerInteractions(key, context);
  const summary = drawerBody.querySelector('#groupFilterSummary');
  if (summary) summary.textContent = historySummary();
}

function closeDrawer() {
  drawer.classList.remove('open');
  drawer.classList.remove('resource-drawer');
  mask.classList.remove('open');
}

const resourceLabels = { admin: '管理员', puller: '拉手', water: '水军', marketing: '营销账号' };

function activeTaskGroupName(groupId) {
  const task = appState.tasks[workbenchState.activeTaskId] || appState.tasks.GM20260729010;
  const group = workbenchState.groups[groupId];
  return `${task.name} · ${group.suffix}`;
}

function accountRows(type) {
  const rows = {
    admin: [['+639123****', '在线', '是', '管理员', '正常'], ['+639456****', '离线', '否', '无', '等待补充']],
    puller: [['+639333****', '在线', '是', '成员', '正常'], ['+639444****', '在线', '否', '成员', '可用']],
    water: [['+639222****', '在线', '是', '成员', '正常'], ['+639777****', '离线', '否', '成员', '不可用']],
    marketing: [['+639555****', '离线', '是', '管理员', '异常'], ['+639888****', '在线', '否', '管理员', '可切换']],
  };
  return rows[type] || rows.admin;
}

function accountTable(type, selectable = false) {
  return `
    <table class="resource-account-table">
      <thead><tr>${selectable ? '<th>选择</th>' : ''}<th>账号</th><th>在线状态</th><th>是否在群</th><th>群内权限</th><th>当前状态</th></tr></thead>
      <tbody>${accountRows(type).map((row, index) => `<tr>${selectable ? `<td><input type="radio" name="resourceAccount" ${index === 0 ? 'checked' : ''} /></td>` : ''}${row.map((cell) => `<td>${cell}</td>`).join('')}</tr>`).join('')}</tbody>
    </table>`;
}

function openCustomDrawer(title, body) {
  drawerTitle.textContent = title;
  drawerBody.innerHTML = body;
  drawer.classList.add('open', 'resource-drawer');
  mask.classList.add('open');
}

function openResourceAccounts(groupId, type = 'admin') {
  const group = workbenchState.groups[groupId];
  openCustomDrawer(`${resourceLabels[type] || '账号'}资源`, `
    <section class="resource-drawer-summary"><span>当前群组</span><h3>${activeTaskGroupName(groupId)}</h3><p>${group.groupId} · ${group.members} 人</p></section>
    <section class="detail-section"><h3>${resourceLabels[type] || '账号'}列表</h3>${accountTable(type)}</section>
    <p class="drawer-pending-note">账号在线状态、群内权限及可用状态当前为 Mock；真实接口与刷新频率待确认。</p>
  `);
}

function resourceActionBody(groupId, action) {
  const group = workbenchState.groups[groupId];
  const common = `<section class="resource-drawer-summary"><span>当前群组</span><h3>${activeTaskGroupName(groupId)}</h3><p>${group.groupId} · ${group.members} 人 · 当前阶段：${group.stage}</p><div><b>目标人数 ${group.target.total}</b><b>成功拉入 ${group.target.success}</b><b>剩余 ${group.target.pending}</b></div></section>`;

  if (action === 'supplementPuller') return `${common}
    <section class="detail-section"><h3>补充设置</h3><div class="resource-form-grid"><label><span>拉手分组</span><select><option>菲律宾可用拉手组</option></select></label><label><span>补充数量</span><input type="number" value="2" min="1" /></label><label><span>选择方式</span><select><option>自动选择</option><option>手动选择</option></select></label><label><span>补充方式</span><select><option>踩链接进群</option><option>当前管理员邀请进群</option></select></label><label class="wide-check"><input type="checkbox" checked />继续使用当前剩余目标数据</label></div></section>
    <section class="detail-section"><h3>候选拉手账号</h3>${accountTable('puller', true)}</section>
    <section class="resource-flow"><b>确认后流程</b><span>锁定账号 → 进入当前群组 → 校验进群 → 继续分配剩余目标数据 → 更新执行状态</span></section>
    <footer class="drawer-actions"><button data-resource-cancel>取消</button><button class="primary" data-resource-confirm="补充拉手" data-group-id="${groupId}">确认补充（原型）</button></footer>`;

  if (action === 'supplementAdmin') return `${common}
    <section class="resource-gap-grid"><span><small>当前管理员</small><b>1</b></span><span><small>要求管理员</small><b>2</b></span><span class="danger"><small>缺少管理员</small><b>1</b></span></section>
    <section class="detail-section"><h3>管理员设置</h3><div class="resource-form-grid"><label><span>管理员账号分组</span><select><option>菲律宾管理员备用组</option></select></label><label><span>管理员账号</span><select><option>+639456****</option></select></label><label><span>进入群组方式</span><select><option>当前管理员邀请进群</option><option>踩链接进群</option></select></label><label><span>执行设置的现有账号</span><select><option>+639123****（在线/管理员）</option></select></label></div></section>
    <section class="detail-section"><h3>候选管理员账号</h3>${accountTable('admin', true)}</section>
    <section class="resource-flow"><b>确认后流程</b><span>新管理员进群 → 校验在群 → 设置管理员 → 验证权限 → 更新管理员列表</span></section>
    <footer class="drawer-actions"><button data-resource-cancel>取消</button><button class="primary" data-resource-confirm="补充管理员" data-group-id="${groupId}">确认补充（原型）</button></footer>`;

  if (action === 'supplementWater') return `${common}
    <section class="resource-gap-grid"><span><small>当前水军</small><b>4</b></span><span><small>目标水军</small><b>10</b></span><span class="danger"><small>缺少水军</small><b>6</b></span></section>
    <section class="detail-section"><h3>补充设置</h3><div class="resource-form-grid"><label><span>水军分组</span><select><option>菲律宾互动账号组</option></select></label><label><span>选择方式</span><select><option>自动选择</option><option>手动选择</option></select></label><label><span>补充数量</span><input type="number" value="6" min="1" /></label><label><span>进入群组方式</span><select><option>踩链接进群</option><option>当前管理员邀请进群</option></select></label></div><p class="exclusion-note">自动排除：已在群、离线、异常、达到群组上限、当前不可调用的账号。</p></section>
    <section class="detail-section"><h3>候选水军账号</h3>${accountTable('water', true)}</section>
    <footer class="drawer-actions"><button data-resource-cancel>取消</button><button class="primary" data-resource-confirm="补充水军" data-group-id="${groupId}">确认补充（原型）</button></footer>`;

  if (action === 'supplementMarketing' || action === 'switchMarketing') {
    const defaultMode = action === 'switchMarketing' ? 'switch' : 'supplement';
    return `${common}
      <nav class="marketing-account-tabs" role="tablist" aria-label="营销账号调整方式">
        <button class="${defaultMode === 'supplement' ? 'active' : ''}" data-marketing-account-mode="supplement" role="tab" aria-selected="${defaultMode === 'supplement'}">补充营销号</button>
        <button class="${defaultMode === 'switch' ? 'active' : ''}" data-marketing-account-mode="switch" role="tab" aria-selected="${defaultMode === 'switch'}">切换营销账号</button>
      </nav>
      <section class="marketing-account-panel ${defaultMode === 'supplement' ? 'active' : ''}" data-marketing-account-panel="supplement" role="tabpanel">
        <section class="detail-section"><h3>候选营销账号</h3>${accountTable('marketing', true)}</section>
        <p class="drawer-pending-note">补充营销号的进群方式、管理员权限、发言权限及绑定规则待确认；当前仅展示静态 Mock 候选账号。</p>
        <footer class="drawer-actions"><button data-resource-cancel>取消</button><button class="primary" data-resource-confirm="补充营销号" data-group-id="${groupId}">确认补充（原型）</button></footer>
      </section>
      <section class="marketing-account-panel ${defaultMode === 'switch' ? 'active' : ''}" data-marketing-account-panel="switch" role="tabpanel">
        <section class="detail-section"><h3>原营销账号</h3>${accountTable('marketing')}</section>
        <section class="detail-section"><h3>新营销账号候选</h3><table class="resource-account-table"><thead><tr><th>选择</th><th>账号</th><th>在线状态</th><th>已进入群数</th><th>最大进群数</th><th>是否已在群</th><th>可用状态</th></tr></thead><tbody><tr><td><input type="radio" checked /></td><td>+639888****</td><td>在线</td><td>3</td><td>10</td><td>否</td><td>可切换</td></tr></tbody></table></section>
        <section class="resource-flow ordered"><b>切换顺序</b><span>1. 新账号进入群组</span><span>2. 校验已经在群内</span><span>3. 设置管理员</span><span>4. 验证发言权限</span><span>5. 绑定当前群组</span><span>6. 切换后续营销发送账号</span></section>
        <p class="drawer-pending-note">全部步骤成功才切换绑定；失败时原营销账号继续绑定且不停止当前营销任务。</p>
        <footer class="drawer-actions"><button data-resource-cancel>取消</button><button class="primary" data-resource-confirm="切换营销账号" data-group-id="${groupId}">确认切换（原型）</button></footer>
      </section>`;
  }

  return `${common}<section class="detail-section"><h3>重新执行</h3><p>将从失败步骤“${group.stage}”重新校验资源并继续；真实幂等与状态校验接口待确认。</p></section><footer class="drawer-actions"><button data-resource-cancel>取消</button><button class="primary" data-resource-confirm="重新执行" data-group-id="${groupId}">确认执行（原型）</button></footer>`;
}

function openGroupActionDrawer(groupId, action) {
  const labels = { supplementPuller: '补充拉手', supplementAdmin: '补充管理员', supplementWater: '补充水军', supplementMarketing: '补充营销号', switchMarketing: '切换营销账号', retryGroup: '重新执行' };
  const isMarketingAdjustment = action === 'supplementMarketing' || action === 'switchMarketing';
  openCustomDrawer(isMarketingAdjustment ? '营销账号调整' : labels[action] || '群组操作', resourceActionBody(groupId, action));
}

function renderGroupExpansion(groupId, defaultTab = 'overview') {
  const group = workbenchState.groups[groupId];
  const row = document.querySelector(`[data-expand-for="${groupId}"]`);
  if (!row || !group) return;
  row.querySelector('td').innerHTML = `
    <div class="group-expand-shell">
      <nav>${[['overview', '执行概况'], ['accounts', '账号资源'], ['targets', '目标数据'], ['records', '执行记录']].map(([key, label]) => `<button class="${key === defaultTab ? 'active' : ''}" data-expand-tab="${key}" data-expand-group-id="${groupId}">${label}</button>`).join('')}</nav>
      <section data-expand-panel="overview" class="${defaultTab === 'overview' ? 'active' : ''}"><div class="execution-timeline">${group.timeline.map(([name, state]) => `<span class="${state.includes('失败') ? 'failed' : state.includes('未开始') || state.includes('等待') ? 'pending' : 'done'}"><b>${name}</b><small>${state}</small></span>`).join('')}</div></section>
      <section data-expand-panel="accounts" class="${defaultTab === 'accounts' ? 'active' : ''}"><div class="account-resource-grid">${Object.keys(resourceLabels).map((type) => `<article><h4>${resourceLabels[type]}</h4>${accountTable(type)}</article>`).join('')}</div></section>
      <section data-expand-panel="targets" class="${defaultTab === 'targets' ? 'active' : ''}"><div class="target-stat-grid">${[['目标总数', group.target.total], ['已分配', group.target.assigned], ['成功进入', group.target.success], ['添加失败', group.target.failed], ['隐私限制', group.target.privacy], ['等待重试', group.target.retry], ['尚未执行', group.target.pending]].map(([label, value]) => `<span><small>${label}</small><b>${value}</b></span>`).join('')}</div></section>
      <section data-expand-panel="records" class="${defaultTab === 'records' ? 'active' : ''}"><table class="execution-record-table"><thead><tr><th>时间</th><th>操作类型</th><th>操作账号</th><th>操作对象</th><th>执行结果</th><th>失败原因</th></tr></thead><tbody>${group.records.map((record) => `<tr>${record.map((cell) => `<td>${cell}</td>`).join('')}</tr>`).join('')}</tbody></table></section>
    </div>`;
}

function toggleGroupExpansion(groupId, defaultTab = 'overview') {
  const row = document.querySelector(`[data-expand-for="${groupId}"]`);
  if (!row) return;
  const willOpen = row.hidden;
  document.querySelectorAll('.group-expand-row').forEach((item) => { item.hidden = true; });
  if (!willOpen) return;
  renderGroupExpansion(groupId, defaultTab);
  row.hidden = false;
}

navButtons.forEach((button) => button.addEventListener('click', () => showView(button.dataset.view)));
document.querySelectorAll('[data-panel]').forEach((button) => button.addEventListener('click', () => openDrawer(button.dataset.panel, { groupId: button.dataset.groupId, taskId: button.dataset.taskId })));
document.querySelector('#closeDrawer').addEventListener('click', closeDrawer);
mask.addEventListener('click', closeDrawer);
renderHistorySummary();

function updateGroupBatchActions() {
  const selected = document.querySelectorAll('#groupView .group-select:checked').length;
  const selectAll = document.querySelector('#groupSelectAll');
  const groupChecks = document.querySelectorAll('#groupView .group-select');
  document.querySelectorAll('#groupView [data-batch-action]').forEach((button) => {
    button.disabled = selected === 0;
  });
  if (selectAll) {
    selectAll.checked = groupChecks.length > 0 && selected === groupChecks.length;
    selectAll.indeterminate = selected > 0 && selected < groupChecks.length;
  }
}

document.querySelector('#groupSelectAll')?.addEventListener('change', (event) => {
  document.querySelectorAll('#groupView .group-select').forEach((checkbox) => {
    checkbox.checked = event.target.checked;
  });
  updateGroupBatchActions();
});

document.querySelectorAll('#groupView .group-select').forEach((checkbox) => {
  checkbox.addEventListener('change', updateGroupBatchActions);
});
updateGroupBatchActions();

function updateTaskSelection() {
  const checks = [...document.querySelectorAll('.task-select')];
  const selected = checks.filter((checkbox) => checkbox.checked).length;
  const selectAll = document.querySelector('#taskSelectAll');
  if (!selectAll) return;
  selectAll.checked = checks.length > 0 && selected === checks.length;
  selectAll.indeterminate = selected > 0 && selected < checks.length;
}

document.querySelector('#taskSelectAll')?.addEventListener('change', (event) => {
  document.querySelectorAll('.task-select').forEach((checkbox) => { checkbox.checked = event.target.checked; });
  updateTaskSelection();
});
document.querySelectorAll('.task-select').forEach((checkbox) => checkbox.addEventListener('change', updateTaskSelection));
updateTaskSelection();

function positionGroupMoreMenu(details) {
  const menu = details.querySelector('.group-more-menu');
  if (!menu) return;
  if (!details.open) {
    menu.style.removeProperty('left');
    menu.style.removeProperty('top');
    menu.removeAttribute('data-placement');
    return;
  }
  window.requestAnimationFrame(() => {
    const trigger = details.querySelector('summary');
    if (!details.open || !trigger) return;
    const triggerRect = trigger.getBoundingClientRect();
    const menuWidth = menu.offsetWidth;
    const menuHeight = menu.offsetHeight;
    const gap = 6;
    const viewportPadding = 8;
    const canOpenAbove = triggerRect.top - gap - menuHeight >= viewportPadding;
    const shouldOpenAbove = triggerRect.bottom + gap + menuHeight > window.innerHeight - viewportPadding && canOpenAbove;
    const left = Math.min(
      Math.max(viewportPadding, triggerRect.right - menuWidth),
      window.innerWidth - viewportPadding - menuWidth,
    );
    const top = shouldOpenAbove
      ? triggerRect.top - gap - menuHeight
      : Math.min(triggerRect.bottom + gap, window.innerHeight - viewportPadding - menuHeight);
    menu.style.left = `${Math.max(viewportPadding, left)}px`;
    menu.style.top = `${Math.max(viewportPadding, top)}px`;
    menu.dataset.placement = shouldOpenAbove ? 'top' : 'bottom';
  });
}

document.querySelectorAll('.group-row-actions details').forEach((details) => {
  details.addEventListener('toggle', () => positionGroupMoreMenu(details));
  details.querySelector('summary')?.addEventListener('click', () => {
    window.setTimeout(() => positionGroupMoreMenu(details), 0);
  });
});
function repositionOpenGroupMenus() {
  document.querySelectorAll('.group-row-actions details[open]').forEach(positionGroupMoreMenu);
}
window.addEventListener('resize', repositionOpenGroupMenus);
window.addEventListener('scroll', repositionOpenGroupMenus, true);

const taskBoard = document.querySelector('.task-board-panel');
const createTaskPanel = document.querySelector('.create-task-panel');
document.querySelector('#openTaskBoard')?.addEventListener('click', () => {
  taskBoard?.classList.add('open');
  document.body.classList.add('board-open');
});
document.querySelector('#closeTaskBoard')?.addEventListener('click', () => {
  taskBoard?.classList.remove('open');
  document.body.classList.remove('board-open');
});
document.querySelector('#openCreateTask')?.addEventListener('click', () => {
  createTaskPanel?.classList.add('open');
  document.body.classList.add('create-open');
});
document.querySelector('#closeCreateTask')?.addEventListener('click', () => {
  createTaskPanel?.classList.remove('open');
  document.body.classList.remove('create-open');
});

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    showToast(`已复制：${text}`);
  } catch (error) {
    showToast(`复制内容：${text}`);
  }
}

const passiveButtonMessages = new Map([
  ['查询', '已执行查询（原型演示）'],
  ['重置', '筛选条件已重置（原型演示）'],
  ['管理群组分组', '打开群组分组列表'],
  ['批量获取账号下群组', '已触发批量获取账号下群组（待接入）'],
  ['批量进群', '已触发批量进群（待接入）'],
  ['新建社群', '打开新建社群流程（待接入）'],
  ['新建普群', '打开新建普群流程（待接入）'],
  ['批量删除', '已触发批量删除（待接入）'],
  ['任务看板', '打开任务看板'],
  ['新建拉群任务', '打开新建拉群任务配置页'],
  ['批量暂停', '已触发批量暂停（待接入）'],
  ['↻', '已刷新当前列表（原型演示）'],
  ['⚙', '打开表格设置（待接入）'],
  ['⛶', '切换显示区域（原型演示）'],
  ['⌕', '打开全局搜索（待接入）'],
  ['清空', '筛选条件已清空'],
  ['应用筛选', '筛选条件已应用'],
]);

function showToast(message) {
  let toast = document.querySelector('.toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.className = 'toast';
    document.body.appendChild(toast);
  }
  toast.textContent = message;
  toast.classList.add('show');
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => toast.classList.remove('show'), 1500);
}

function buttonLabel(button) {
  return button.textContent.replace(/\s+/g, ' ').trim();
}


function updateMarketingVersionState() {
  const panel = document.querySelector('.create-task-panel');
  const version = panel?.querySelector('[data-marketing-version]');
  const isHistoryMarketing = Boolean(panel?.classList.contains('mode-link') && version?.value === 'history');
  panel?.classList.toggle('marketing-history', isHistoryMarketing);
}


function closeLinkPasteModal() {
  document.querySelector('.link-paste-mask')?.remove();
}

function openLinkPasteModal() {
  closeLinkPasteModal();
  const modal = document.createElement('div');
  modal.className = 'link-paste-mask';
  modal.innerHTML = `
    <div class="link-paste-modal" role="dialog" aria-modal="true">
      <header><h3>自定义粘贴链接</h3><button data-link-paste="cancel" type="button">×</button></header>
      <textarea id="linkPasteInput" placeholder="请输入群链接，一行一个，识别成功的链接将在右侧‘进群顺序展示’栏目显示"></textarea>
      <footer><button data-link-paste="cancel" type="button">取消</button><button class="primary" data-link-paste="save" type="button">保存</button></footer>
    </div>
  `;
  document.body.appendChild(modal);
  const input = modal.querySelector('#linkPasteInput');
  input?.focus();
  modal.addEventListener('click', (event) => {
    const action = event.target.closest('[data-link-paste]')?.dataset.linkPaste;
    if (event.target === modal || action === 'cancel') {
      closeLinkPasteModal();
      return;
    }
    if (action === 'save') {
      const lines = (input?.value || '').split('\n').map((line) => line.trim()).filter(Boolean);
      const entry = document.querySelector('.link-paste-entry');
      if (entry) entry.textContent = lines.length ? `已粘贴 ${lines.length} 条链接` : '自定义粘贴链接';
      showToast(lines.length ? `已保存 ${lines.length} 条链接（原型演示）` : '未填写链接');
      closeLinkPasteModal();
    }
  });
}

document.addEventListener('click', (event) => {
  const button = event.target.closest('button');
  if (!button) return;
  button.closest('.group-more-menu')?.closest('details')?.removeAttribute('open');

  if (button.dataset.openWorkbench) {
    showTaskWorkbench(button.dataset.openWorkbench);
    return;
  }

  if (button.dataset.backTaskList !== undefined) {
    showView('taskView');
    window.scrollTo({ top: 0, behavior: 'smooth' });
    return;
  }

  if (button.dataset.taskAction) {
    const actionNames = { pause: '暂停任务', resume: '恢复任务', retry: '重试任务', export: '导出任务', copy: '复制任务' };
    if (button.dataset.taskAction === 'pause' && !window.confirm('确认暂停该任务吗？')) return;
    showToast(`${actionNames[button.dataset.taskAction] || '任务操作'}已记录（静态原型）`);
    return;
  }

  if (button.dataset.taskBatch) {
    const selected = document.querySelectorAll('.task-select:checked').length;
    if (!selected) {
      showToast('请先选择任务');
      return;
    }
    showToast(`已选择 ${selected} 个任务，批量${button.dataset.taskBatch === 'pause' ? '暂停' : button.dataset.taskBatch === 'resume' ? '恢复' : '导出'}待接入`);
    return;
  }

  if (button.dataset.workbenchAction) {
    const actionNames = { refresh: '刷新任务详情', pause: '暂停任务', resume: '恢复任务', finish: '结束任务', export: '导出群组明细' };
    if ((button.dataset.workbenchAction === 'pause' || button.dataset.workbenchAction === 'finish') && !window.confirm(`确认${actionNames[button.dataset.workbenchAction]}吗？`)) return;
    showToast(`${actionNames[button.dataset.workbenchAction]}已记录（静态原型）`);
    return;
  }

  if (button.dataset.workbenchFilter) {
    const filter = button.dataset.workbenchFilter;
    document.querySelectorAll('[data-workbench-filter]').forEach((item) => item.classList.toggle('active', item === button));
    document.querySelectorAll('[data-workbench-group]').forEach((row) => {
      row.hidden = filter !== 'all' && !row.dataset.filterTags.split(' ').includes(filter);
      const expandRow = document.querySelector(`[data-expand-for="${row.dataset.workbenchGroup}"]`);
      if (expandRow) expandRow.hidden = true;
    });
    return;
  }

  if (button.dataset.workbenchQuery) {
    if (button.dataset.workbenchQuery === 'reset') {
      document.querySelectorAll('.workbench-filter-grid input').forEach((input) => { input.value = ''; });
      document.querySelectorAll('.workbench-filter-grid select').forEach((select) => { select.selectedIndex = 0; });
    }
    showToast(button.dataset.workbenchQuery === 'reset' ? '群组筛选已重置' : '已查询群组（静态 Mock）');
    return;
  }

  if (button.dataset.expandGroup) {
    toggleGroupExpansion(button.dataset.expandGroup, button.dataset.expandDefaultTab || 'overview');
    return;
  }

  if (button.dataset.expandTab) {
    const shell = button.closest('.group-expand-shell');
    shell.querySelectorAll('nav button').forEach((item) => item.classList.toggle('active', item === button));
    shell.querySelectorAll('[data-expand-panel]').forEach((panel) => panel.classList.toggle('active', panel.dataset.expandPanel === button.dataset.expandTab));
    return;
  }

  if (button.dataset.marketingAccountMode) {
    const mode = button.dataset.marketingAccountMode;
    const container = button.closest('.drawer-body') || drawerBody;
    container.querySelectorAll('[data-marketing-account-mode]').forEach((item) => {
      const isActive = item.dataset.marketingAccountMode === mode;
      item.classList.toggle('active', isActive);
      item.setAttribute('aria-selected', String(isActive));
    });
    container.querySelectorAll('[data-marketing-account-panel]').forEach((panel) => {
      panel.classList.toggle('active', panel.dataset.marketingAccountPanel === mode);
    });
    return;
  }

  if (button.dataset.resourceGroup) {
    openResourceAccounts(button.dataset.resourceGroup, button.dataset.resourceType);
    return;
  }

  if (button.dataset.groupAction) {
    const groupId = button.dataset.groupId;
    const action = button.dataset.groupAction;
    if (['supplementPuller', 'supplementAdmin', 'supplementWater', 'supplementMarketing', 'switchMarketing', 'retryGroup'].includes(action)) {
      openGroupActionDrawer(groupId, action);
      return;
    }
    if (action === 'viewAccounts') {
      openCustomDrawer('账号资源', `<section class="resource-drawer-summary"><span>当前群组</span><h3>${activeTaskGroupName(groupId)}</h3></section><div class="account-resource-grid drawer-grid">${Object.keys(resourceLabels).map((type) => `<article><h4>${resourceLabels[type]}</h4>${accountTable(type)}</article>`).join('')}</div><p class="drawer-pending-note">完整账号资源来自静态 Mock，真实在线状态和权限接口待确认。</p>`);
      return;
    }
    if (action === 'viewMembers') {
      openCustomDrawer('群成员', `<section class="resource-drawer-summary"><span>当前群组</span><h3>${activeTaskGroupName(groupId)}</h3></section><section class="detail-section"><h3>群成员样例</h3><table class="resource-account-table"><thead><tr><th>账号</th><th>群内身份</th><th>加入时间</th><th>状态</th></tr></thead><tbody><tr><td>+639123****</td><td>管理员</td><td>2026-07-29 09:30</td><td>正常</td></tr><tr><td>+639333****</td><td>成员</td><td>2026-07-29 10:12</td><td>正常</td></tr></tbody></table></section>`);
      return;
    }
    if (action === 'abandonGroup' && !window.confirm('放弃群组后将不再继续执行，确认放弃吗？')) return;
    showToast(`${action === 'refreshGroup' ? '群组状态已刷新' : action === 'pauseGroup' ? '群组暂停已记录' : '群组放弃已记录'}（静态原型）`);
    return;
  }

  if (button.dataset.resourceCancel !== undefined) {
    closeDrawer();
    return;
  }

  if (button.dataset.resourceConfirm) {
    showToast(`${button.dataset.resourceConfirm}已确认，真实执行接口待接入`);
    closeDrawer();
    return;
  }

  if (button.id === 'openTaskBoard') {
    document.querySelector('.task-board-panel')?.classList.add('open');
    document.body.classList.add('board-open');
    return;
  }

  if (button.id === 'closeTaskBoard') {
    document.querySelector('.task-board-panel')?.classList.remove('open');
    document.body.classList.remove('board-open');
    return;
  }

  if (button.id === 'openCreateTask') {
    document.querySelector('.create-task-panel')?.classList.add('open');
    document.body.classList.add('create-open');
    return;
  }

  if (button.id === 'closeCreateTask') {
    document.querySelector('.create-task-panel')?.classList.remove('open');
    document.body.classList.remove('create-open');
    return;
  }

  if (button.dataset.createMode) {
    const panel = document.querySelector('.create-task-panel');
    document.querySelectorAll('.create-tabs button').forEach((item) => item.classList.remove('active'));
    button.classList.add('active');
    panel?.classList.toggle('mode-link', button.dataset.createMode === 'link');
    panel?.classList.toggle('mode-fast', button.dataset.createMode === 'fast');
    updateMarketingVersionState();
    showToast(`已切换到${buttonLabel(button)}`);
    return;
  }

  if (button.dataset.linkPaste === 'open') {
    openLinkPasteModal();
    return;
  }

  if (button.dataset.filterToggle !== undefined) {
    const card = button.closest('.task-filter-card') || document.querySelector('#taskFilterCard');
    card?.classList.toggle('collapsed');
    return;
  }

  if (button.dataset.sectionToggle) {
    button.closest('.create-section')?.classList.toggle('collapsed');
    return;
  }

  button.classList.add('clicked');
  window.setTimeout(() => button.classList.remove('clicked'), 120);

  if (button.disabled) {
    showToast('当前按钮不可用');
    return;
  }

  if (button.dataset.copy) {
    copyText(button.dataset.copy);
    return;
  }

  if (button.dataset.help) {
    showToast(button.dataset.help);
    return;
  }

  if (button.dataset.boardNote) {
    const note = document.querySelector('#boardStatusNote');
    if (note) note.textContent = button.dataset.boardNote;
    return;
  }

  if (button.classList.contains('switch')) {
    button.classList.toggle('on');
    return;
  }

  const segmented = button.closest('.segmented');
  if (segmented) {
    segmented.querySelectorAll('button').forEach((item) => item.classList.remove('active'));
    button.classList.add('active');
    return;
  }

  if (button.dataset.uploadAction) {
    showToast(button.dataset.uploadAction === 'txt' ? '打开TXT料子上传（原型演示）' : '打开图片上传（原型演示）');
    return;
  }

  if (button.dataset.view || button.dataset.panel || button.dataset.jumpView || button.dataset.projectAction || button.dataset.projectModal || button.id === 'closeDrawer' || button.id === 'queryHistoryFilter' || button.id === 'addGroupProject' || button.id === 'openTaskBoard' || button.id === 'closeTaskBoard' || button.id === 'openCreateTask' || button.id === 'closeCreateTask' || button.dataset.filterToggle !== undefined || button.dataset.boardNote || button.dataset.createMode || button.dataset.linkPaste) return;

  const label = buttonLabel(button);
  if (passiveButtonMessages.has(label)) {
    showToast(passiveButtonMessages.get(label));
  }
});



document.addEventListener('change', (event) => {
  if (!event.target.closest('[data-marketing-version]')) return;
  updateMarketingVersionState();
  const select = event.target.closest('[data-marketing-version]');
  showToast(select.value === 'history' ? '已显示营销配置' : '已隐藏营销配置');
});

updateMarketingVersionState();
