/* global Dashboard */
(() => {
  const $ = id => document.getElementById(id);
  const bridge = window.Dashboard;
  let editing = false, initialized = false;
  const duration = ms => {
    const minutes = Math.floor(ms / 60000);
    return minutes >= 60 ? `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分` : `${minutes} 分钟`;
  };
  const date = value => value ? new Date(value).toLocaleString('zh-CN', {hour12: false}) : '尚未同步';
  const text = (id, value) => { $(id).textContent = value; };
  function row(app, purpose, detail, ms, pkg) {
    const root = document.createElement('div');
    root.className = 'row';
    const info = document.createElement('div');
    info.className = 'details';
    const title = document.createElement('strong'); title.textContent = app;
    const caption = document.createElement('small'); caption.textContent = `${purpose === 'work' ? '工作' : '日常'} · ${detail}`;
    info.append(title, caption);
    const time = document.createElement('span'); time.className = 'duration'; time.textContent = duration(ms);
    if (pkg) {
      const icon = document.createElement('img');
      icon.className = 'app-icon'; icon.alt = ''; icon.src = `/mobile-icon?package=${encodeURIComponent(pkg)}`;
      icon.onerror = () => icon.remove(); root.append(icon);
    }
    root.append(info, time); return root;
  }
  function render() {
    if (!bridge) { text('message', '此页面需要在 Personal Dashboard 安卓应用中打开'); $('message').hidden = false; return; }
    const state = JSON.parse(bridge.state());
    text('device-name', state.name || '手机');
    text('role-badge', state.role === 'work' ? '工作机' : '日常机');
    text('current-purpose', state.purpose === 'work' ? '工作记录中' : '日常记录中');
    $('permission').hidden = !!state.permission;
    $('message').hidden = !state.message; text('message', state.message || '');
    text('work-time', duration(state.work || 0)); text('personal-time', duration(state.personal || 0));
    for (const mode of ['work', 'personal']) $(mode).classList.toggle('active', state.purpose === mode);
    $('apps').replaceChildren();
    for (const app of state.apps || []) $('apps').append(row(app.app, app.purpose, app.package, app.ms, app.package));
    if (!state.apps?.length) { $('apps').className = 'rows empty'; text('apps', state.permission ? '今天暂无已结束的应用会话' : '开启权限后显示应用记录'); }
    else $('apps').className = 'rows';
    $('sessions').replaceChildren();
    for (const session of state.recent || []) $('sessions').append(row(session.app, session.purpose, date(session.start), session.end - session.start));
    if (!state.recent?.length) text('sessions', '暂无记录');
    text('sync-state', state.server ? `${state.pending || 0} 条待上传` : '尚未连接');
    text('sync-detail', state.server ? `上次同步：${date(state.synced)}` : '记录保存在本机，连接电脑后自动上传。');
    text('shared-date', state.shared?.updated ? date(state.shared.updated) : '');
    $('shared-devices').replaceChildren();
    for (const device of state.shared?.devices || []) {
      $('shared-devices').append(row(device.name, device.purpose, device.role === 'work' ? '工作机' : '日常机', device.ms));
    }
    if (!state.shared?.devices?.length) text('shared-devices', '暂无已同步的手机记录');
    text('collect-state', state.collected ? `上次采集：${date(state.collected)}` : '等待采集');
    text('version', `Personal Dashboard ${state.version || '0.1.2'} · GitHub`);
    text('home-version', state.version || '0.1.2');
    for (const prefix of ['', 'home-']) {
      text(`${prefix}update-status`, state.update?.status || '等待每日检查');
      $(`${prefix}check-update`).disabled = !!state.update?.busy;
      $(`${prefix}install`).hidden = !state.update?.available || !!state.update?.busy;
      text(`${prefix}install`, state.update?.permissionRequired ? '允许安装更新' :
        state.update?.needsAction ? '确认安装' : state.update?.ready ? '安装更新' : '重试更新');
    }
    text('home-update-checked', state.update?.checked ? `上次检查：${date(state.update.checked)}` : '');
    if (!initialized && !editing) {
      for (const key of ['name', 'role', 'server']) $(key).value = state[key] || '';
      if (state.paired) $('token').placeholder = '已配对；留空保留现有配对码';
      initialized = true;
    }
  }
  for (const tab of document.querySelectorAll('[data-page]')) tab.onclick = () => {
    for (const section of ['overview', 'timeline', 'settings']) $(section).hidden = section !== tab.dataset.page;
    for (const button of document.querySelectorAll('[data-page]')) button.classList.toggle('active', button === tab);
  };
  for (const mode of ['work', 'personal']) $(mode).onclick = () => { bridge?.mode(mode); render(); };
  $('grant').onclick = () => bridge?.permission();
  $('refresh').onclick = () => bridge?.refresh();
  $('check-update').onclick = $('home-check-update').onclick = () => { bridge?.checkUpdate(); render(); };
  $('install').onclick = $('home-install').onclick = () => bridge?.install();
  $('export').onclick = () => bridge?.export();
  $('config').oninput = () => { editing = true; };
  $('config').onsubmit = event => {
    event.preventDefault();
    if (!bridge) return;
    const data = Object.fromEntries(['name', 'role', 'server', 'token'].map(key => [key, $(key).value]));
    const error = bridge.save(JSON.stringify(data));
    text('save-status', error || '已保存，正在同步'); if (!error) $('token').value = '';
  };
  render(); setInterval(render, 2500);
})();
