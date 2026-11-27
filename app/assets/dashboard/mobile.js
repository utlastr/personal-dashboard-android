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
    if (ms > 0 && minutes === 0) return '<1 min';
    return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
  };
  const text = (id, value) => { $(id).textContent = value; };
  function chart(target, values, labels, output, frequency = false) {
    const root = $(target), max = Math.max(1, ...values);
    root.replaceChildren();
    root.style.setProperty('--columns', values.length);
    values.forEach((value, i) => {
      const button = document.createElement('button');
      button.className = 'bar-column';
      const description = `${labels[i]}: ${frequency ? `${value} ${value === 1 ? 'open' : 'opens'}` : duration(value)}`;
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
    text(output, values.some(Boolean) ? '' : 'No usage recorded');
  }
  const hourLabels = () => Array.from({length: 24}, (_, i) => `${String(i).padStart(2, '0')}:00–${String(i + 1).padStart(2, '0')}:00`);
  function detail() {
    const app = state.apps.find(item => item.package === selectedPackage);
    if (!app) { $('app-detail').close(); return; }
    text('detail-title', app.app); text('detail-total', duration(app.ms));
    text('detail-range', state.start === state.last ? state.start : `${state.start} to ${state.last}`);
    $('detail-group').hidden = period !== 'week';
    if (period !== 'week') $('detail-group').value = 'hours';
    const daily = $('detail-group').value === 'days';
    $('detail-metric').disabled = daily;
    const frequency = !daily && $('detail-metric').value === 'frequency';
    chart('detail-chart', daily ? app.days : frequency ? app.frequency : app.hours,
      daily ? state.dates : hourLabels(), 'detail-selection', frequency);
  }
  function render() {
    if (!bridge) { text('message', 'Open this page in the Personal Dashboard Android app.'); $('message').hidden = false; return; }
    anchor = pinnedDate && pinnedDate <= today() ? pinnedDate : today();
    try { state = JSON.parse(bridge.report(period, anchor)); }
    catch (_) { text('message', 'Unable to load usage data.'); $('message').hidden = false; return; }
    if (!state.apps) { text('message', state.message || 'Unable to load usage data.'); $('message').hidden = false; return; }
    text('role-badge', state.role === 'work' ? 'Work phone' : 'Personal phone');
    $('permission').hidden = !!state.permission;
    const limits = state.limits || {};
    const accessPath = 'Accessibility > Downloaded apps > Personal Dashboard App Limits';
    const accessStatus = limits.accessibilityGranted
      ? limits.accessibility ? 'App limits are active.' : 'Accessibility is on. The service is disconnected.'
      : `Accessibility is off. To enable app limits, go to ${accessPath}.`;
    $('limits-enabled').checked = limits.enabled !== false;
    $('limit-notice').hidden = !limits.enabled || (limits.accessibility && limits.accessibilityGranted);
    text('limit-notice-text', accessStatus);
    text('limit-status', !limits.enabled ? 'App limits are off.' : `${accessStatus} ${limits.canHide
      ? limits.exact ? 'Icons follow the schedule.' : 'Icon changes may be delayed until exact alarms are allowed.'
      : 'App icons remain visible.'}`);
    $('limit-error').hidden = !limits.error; text('limit-error', limits.error || '');
    $('limit-alarm').hidden = !limits.enabled || !limits.canHide || limits.exact;
    $('message').hidden = !state.message; text('message', state.message || '');
    const weekly = period === 'week';
    const elapsedDays = Math.max(1, state.dates.filter(date => date <= today()).length);
    text('total-time', duration(weekly ? state.total / elapsedDays : state.total));
    const current = state.start <= today() && state.last >= today();
    text('total-label', weekly ? 'Daily average' : current ? 'Screen time today' : 'Screen time');
    text('range-label', state.start === state.last ? state.start : `${state.start} to ${state.last}`);
    $('screen-time-total').hidden = !weekly;
    text('screen-time-total', `Total screen time: ${duration(state.total)}`);
    $('date').value = anchor; $('date').max = today();
    $('next').disabled = current;
    for (const mode of ['day', 'week']) { $(mode).classList.toggle('active', period === mode); $(mode).setAttribute('aria-selected', period === mode); }
    text('distribution-title', weekly ? 'Daily distribution' : 'Hourly distribution');
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
        const meta = document.createElement('span'); meta.className = 'app-meta';
        meta.append(name, time);
        row.append(icon, meta);
        row.onclick = () => { selectedPackage = app.package; $('detail-group').value = 'hours'; $('detail-metric').value = 'duration'; detail(); $('app-detail').showModal(); };
        $('apps').append(row);
      }
      if (!state.apps.length) { const empty = document.createElement('p'); empty.className = 'empty'; empty.textContent = state.permission ? 'No usage recorded' : 'Allow usage access to see app usage.'; $('apps').append(empty); }
      if ($('app-detail').open) detail();
    }
    text('sync-state', state.server ? `Pending sync: ${state.pending || 0}` : 'Desktop disconnected');
    text('collect-state', state.collected ? `Collected at ${new Date(state.collected).toLocaleTimeString('en-GB', {hour: '2-digit', minute: '2-digit'})}` : '');
    text('version', `Personal Dashboard ${state.version}`);
    text('update-status', state.update?.status || 'Checks for updates daily in the background.');
    $('check-update').disabled = !!state.update?.busy;
    $('install').hidden = !state.update?.available || !!state.update?.busy;
    text('install', state.update?.permissionRequired ? 'Allow installation' : state.update?.needsAction ? 'Confirm installation' : state.update?.ready ? 'Install update' : 'Retry update');
    if (!initialized) {
      for (const key of ['name', 'role', 'server']) $(key).value = state[key] || '';
      if (state.paired) $('token').placeholder = 'Leave blank to keep the current code';
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
    if (!saved) { text('limit-error', 'Could not save app limits.'); $('limit-error').hidden = false; }
  };
  $('refresh').onclick = () => { bridge?.refresh(); render(); };
  $('check-update').onclick = () => { bridge?.checkUpdate(); render(); };
  $('install').onclick = () => bridge?.install();
  $('export').onclick = () => bridge?.export();
  $('config').onsubmit = event => {
    event.preventDefault(); if (!bridge) return;
    const data = Object.fromEntries(['name', 'role', 'server', 'token'].map(key => [key, $(key).value]));
    const error = bridge.save(JSON.stringify(data));
    text('save-status', error || 'Saved. Syncing now.'); if (!error) $('token').value = '';
  };
  render(); setInterval(render, 2500);
})();
