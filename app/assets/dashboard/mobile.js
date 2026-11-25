/* global Dashboard */
(() => {
  const $ = id => document.getElementById(id), bridge = window.Dashboard;
  const localDate = value => {
    const d = new Date(value);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  };
  const today = () => localDate(Date.now());
  let period = 'day', pinnedDate = '', anchor, state, selectedPackage, initialized = false, signature;
  try {
    const saved = JSON.parse(localStorage.getItem('dashboard-view') || '{}');
    if (saved?.period === 'week') period = 'week';
    if (/^\d{4}-\d{2}-\d{2}$/.test(saved?.date) && localDate(`${saved.date}T12:00:00`) === saved.date) pinnedDate = saved.date;
  } catch (_) {}
  function saveView() {
    try { localStorage.setItem('dashboard-view', JSON.stringify({period, date: pinnedDate})); } catch (_) {}
  }
  const duration = ms => {
    const minutes = Math.floor(ms / 60000);
    if (ms > 0 && minutes === 0) return '不足 1 分钟';
    return minutes >= 60 ? `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分` : `${minutes} 分钟`;
  };
  const text = (id, value) => { $(id).textContent = value; };
  function chart(target, values, labels, output, frequency = false) {
    const root = $(target), max = Math.max(1, ...values);
    root.replaceChildren();
    root.style.setProperty('--columns', values.length);
    values.forEach((value, i) => {
      const button = document.createElement('button');
      button.className = 'bar-column';
      const description = `${labels[i]}：${frequency ? `${value} 次` : duration(value)}`;
      button.setAttribute('aria-label', description); button.title = description;
      const track = document.createElement('span'); track.className = 'bar-track';
      const bar = document.createElement('i'); bar.style.height = `${value / max * 100}%`;
      track.append(bar);
      const label = document.createElement('span'); label.className = 'bar-label';
      label.textContent = values.length === 24 ? (i % 3 === 0 || i === 23 ? i : '') : labels[i].slice(5);
      button.append(track, label);
      button.onclick = () => {
        for (const item of root.children) item.classList.remove('selected');
        button.classList.add('selected'); text(output, description);
      };
      root.append(button);
    });
    text(output, values.some(Boolean) ? '' : '暂无使用记录');
  }
  const hourLabels = () => Array.from({length: 24}, (_, i) => `${String(i).padStart(2, '0')}:00–${String(i + 1).padStart(2, '0')}:00`);
  function detail() {
    const app = state.apps.find(item => item.package === selectedPackage);
    if (!app) { $('app-detail').close(); return; }
    text('detail-title', app.app); text('detail-total', duration(app.ms));
    text('detail-range', state.start === state.last ? state.start : `${state.start} 至 ${state.last}`);
    $('detail-group').hidden = period !== 'week';
    if (period !== 'week') $('detail-group').value = 'hours';
    const daily = $('detail-group').value === 'days';
    $('detail-metric').disabled = daily;
    const frequency = !daily && $('detail-metric').value === 'frequency';
    chart('detail-chart', daily ? app.days : frequency ? app.frequency : app.hours,
      daily ? state.dates : hourLabels(), 'detail-selection', frequency);
  }
  function render() {
    if (!bridge) { text('message', '此页面需要在 Personal Dashboard 安卓应用中打开'); $('message').hidden = false; return; }
    anchor = pinnedDate && pinnedDate <= today() ? pinnedDate : today();
    try { state = JSON.parse(bridge.report(period, anchor)); }
    catch (_) { text('message', '暂时无法读取使用记录'); $('message').hidden = false; return; }
    if (!state.apps) { text('message', state.message || '暂时无法读取使用记录'); $('message').hidden = false; return; }
    text('device-name', state.name || '手机'); text('role-badge', state.role === 'work' ? '工作机' : '日常机');
    $('permission').hidden = !!state.permission;
    const limits = state.limits || {};
    const accessStatus = limits.accessibility ? '时段拦截已连接，禁止时段打开将返回桌面。'
      : limits.accessibilityGranted ? '无障碍已授权，但服务未连接；时段拦截暂未生效。'
      : '系统未启用无障碍服务；时段拦截暂未生效。';
    $('limits-enabled').checked = limits.enabled !== false;
    $('limit-notice').hidden = !limits.enabled || limits.accessibility || (limits.canHide && limits.exact && !limits.error);
    text('limit-notice-text', limits.canHide ? '已启用时段隐藏；开启无障碍后可在结束时退出应用。' : '时段限制尚未生效：请开启无障碍权限。当前手机无法隐藏图标。');
    text('limit-status', !limits.enabled ? '已关闭时段限制' : limits.canHide
      ? `有隐藏权限，按手机时间隐藏或恢复图标。${limits.exact ? '' : '未允许精确闹钟，图标切换可能延迟。'}${limits.accessibility ? '时段拦截已连接。' : ''}`
      : `没有隐藏权限，图标仍会显示。${accessStatus}`);
    $('limit-error').hidden = !limits.error; text('limit-error', limits.error || '');
    $('limit-alarm').hidden = !limits.enabled || !limits.canHide || limits.exact;
    $('message').hidden = !state.message; text('message', state.message || '');
    text('total-time', duration(state.total));
    const current = state.start <= today() && state.last >= today();
    text('total-label', period === 'day' ? current ? '本机今日使用' : '本机当日使用' : current ? '本机本周使用' : '本机当周使用');
    text('range-label', state.start === state.last ? state.start : `${state.start} 至 ${state.last}`);
    $('date').value = anchor; $('date').max = today();
    $('next').disabled = current;
    for (const mode of ['day', 'week']) { $(mode).classList.toggle('active', period === mode); $(mode).setAttribute('aria-selected', period === mode); }
    text('distribution-title', period === 'week' ? '每日分布' : '24 小时分布');
    // Keep focused rows and chart selections stable while polling unchanged data.
    const nextSignature = JSON.stringify([period, state.start, state.apps]);
    if (signature !== nextSignature) {
      signature = nextSignature;
      chart('summary-chart', period === 'week' ? state.days : state.hours,
        period === 'week' ? state.dates : hourLabels(), 'summary-selection');
      $('apps').replaceChildren();
      for (const app of state.apps) {
        const row = document.createElement('button'); row.className = 'row';
        const icon = document.createElement('img'); icon.className = 'app-icon'; icon.alt = '';
        icon.src = `/mobile-icon?package=${encodeURIComponent(app.package)}`;
        icon.onerror = () => {
          const placeholder = document.createElement('span'); placeholder.className = 'app-icon app-initial';
          placeholder.textContent = (app.app || '?').slice(0, 1); placeholder.setAttribute('aria-hidden', 'true');
          Object.assign(placeholder.style, {display: 'grid', placeItems: 'center', background: '#e2e9ed', color: '#466b91'});
          icon.replaceWith(placeholder);
        };
        const name = document.createElement('strong'); name.textContent = app.app;
        const time = document.createElement('span'); time.className = 'duration'; time.textContent = duration(app.ms);
        row.append(icon, name, time);
        row.onclick = () => { selectedPackage = app.package; $('detail-group').value = 'hours'; $('detail-metric').value = 'duration'; detail(); $('app-detail').showModal(); };
        $('apps').append(row);
      }
      if (!state.apps.length) { const empty = document.createElement('p'); empty.className = 'empty'; empty.textContent = state.permission ? '暂无使用记录' : '开启权限后显示应用记录'; $('apps').append(empty); }
      if ($('app-detail').open) detail();
    }
    text('app-count', `${state.apps.length} 个应用`);
    text('sync-state', state.server ? `待同步 ${state.pending || 0} 条` : '尚未连接电脑');
    text('collect-state', state.collected ? `采集于 ${new Date(state.collected).toLocaleTimeString('zh-CN', {hour: '2-digit', minute: '2-digit', hour12: false})}` : '');
    text('version', `Personal Dashboard ${state.version}`);
    text('update-status', state.update?.status || '每日在后台自动检查更新');
    $('check-update').disabled = !!state.update?.busy;
    $('install').hidden = !state.update?.available || !!state.update?.busy;
    text('install', state.update?.permissionRequired ? '允许安装更新' : state.update?.needsAction ? '确认安装' : state.update?.ready ? '安装更新' : '重试更新');
    if (!initialized) {
      for (const key of ['name', 'role', 'server']) $(key).value = state[key] || '';
      if (state.paired) $('token').placeholder = '已配对；留空保留现有配对码';
      initialized = true;
    }
  }
  for (const mode of ['day', 'week']) $(mode).onclick = () => { period = mode; saveView(); render(); };
  $('date').onchange = () => {
    if ($('date').value && $('date').value <= today()) {
      pinnedDate = $('date').value === today() ? '' : $('date').value; saveView(); render();
    }
  };
  function move(direction) {
    const d = new Date(`${anchor}T12:00:00`); d.setDate(d.getDate() + direction * (period === 'week' ? 7 : 1));
    const date = localDate(d); pinnedDate = date >= today() ? '' : date; saveView(); render();
  }
  $('previous').onclick = () => move(-1); $('next').onclick = () => move(1);
  $('detail-group').onchange = $('detail-metric').onchange = detail;
  $('close-detail').onclick = () => $('app-detail').close();
  $('connection').onclick = () => $('device-dialog').showModal();
  $('close-device').onclick = () => $('device-dialog').close();
  for (const id of ['app-detail', 'device-dialog']) $(id).onclick = event => { if (event.target === $(id)) { const r = $(id).getBoundingClientRect(); if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) $(id).close(); } };
  $('grant').onclick = () => bridge?.permission();
  $('limit-grant').onclick = $('limit-access').onclick = () => bridge?.limitPermission();
  $('limit-alarm').onclick = () => bridge?.exactAlarmPermission();
  $('limits-enabled').onchange = () => {
    const saved = bridge?.limits($('limits-enabled').checked);
    render();
    if (!saved) { text('limit-error', '时段限制开关未能保存'); $('limit-error').hidden = false; }
  };
  $('refresh').onclick = () => { bridge?.refresh(); render(); };
  $('check-update').onclick = () => { bridge?.checkUpdate(); render(); };
  $('install').onclick = () => bridge?.install();
  $('export').onclick = () => bridge?.export();
  $('config').onsubmit = event => {
    event.preventDefault(); if (!bridge) return;
    const data = Object.fromEntries(['name', 'role', 'server', 'token'].map(key => [key, $(key).value]));
    const error = bridge.save(JSON.stringify(data));
    text('save-status', error || '已保存，正在同步'); if (!error) $('token').value = '';
  };
  render(); setInterval(render, 2500);
})();
