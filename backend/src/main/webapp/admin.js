// Admin Operations Console Script
const API_BASE = 'api';

const $ = (id) => document.getElementById(id);
const esc = (val) => String(val ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

function notify(msg, type = 'info') {
  const el = $('adminNotice');
  if (!el) return;
  el.textContent = msg;
  el.className = `alert alert-${type}`;
  el.classList.remove('hidden');
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function clearNotice() {
  const el = $('adminNotice');
  if (el) el.classList.add('hidden');
}

// Navigation Tabs
document.querySelectorAll('.nav-tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    tab.classList.add('active');
    const target = $(tab.dataset.tab);
    if (target) target.classList.add('active');
  });
});

// Authentication Management
async function checkAuth() {
  try {
    const res = await fetch(`${API_BASE}/admin/session`);
    const data = await res.json();
    if (data.authenticated) {
      $('loginModal').classList.add('hidden');
      $('userDisplay').textContent = `Admin: ${data.username}`;
      loadAll();
    } else {
      $('loginModal').classList.remove('hidden');
    }
  } catch (e) {
    $('loginModal').classList.remove('hidden');
  }
}

$('adminLoginForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const alertEl = $('loginAlert');
  alertEl.classList.add('hidden');

  const username = $('loginUser').value.trim();
  const password = $('loginPass').value;

  try {
    const res = await fetch(`${API_BASE}/admin/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    const data = await res.json();

    if (!res.ok || !data.success) {
      throw new Error(data.message || 'Login failed.');
    }

    $('loginModal').classList.add('hidden');
    $('userDisplay').textContent = `Admin: ${data.username}`;
    notify('Administrator session established.', 'success');
    loadAll();
  } catch (err) {
    alertEl.textContent = err.message;
    alertEl.classList.remove('hidden');
  }
});

$('logoutBtn').addEventListener('click', async () => {
  try {
    await fetch(`${API_BASE}/admin/logout`, { method: 'POST' });
  } catch (e) {}
  $('loginModal').classList.remove('hidden');
  notify('Logged out successfully.', 'info');
});

$('refreshBtn').addEventListener('click', () => {
  loadAll();
  notify('Operational data synchronized.', 'info');
});

function loadAll() {
  loadDashboard();
  loadRegistry();
  loadRoads();
}

// 1. Dashboard & Fleet Data
async function loadDashboard() {
  try {
    const res = await fetch(`${API_BASE}/admin/dashboard`);
    if (res.status === 401) {
      $('loginModal').classList.remove('hidden');
      return;
    }
    const data = await res.json();

    // KPIs
    $('statPending').textContent = data.pendingCount;
    $('statActive').textContent = data.activeCount;
    $('statCompleted').textContent = data.completedCount;
    $('statAvailable').textContent = `${data.availableUnits} / ${data.totalUnits}`;
    $('statClosedRoads').textContent = data.closedRoads;

    // Render Queue
    const pendingIncidents = (data.incidents || []).filter(i => i.status === 'Pending');
    renderQueueTable(pendingIncidents, data.units || []);

    // Render Fleet Cards
    renderFleet(data.units || []);
  } catch (e) {
    notify('Failed to load dashboard data: ' + e.message, 'error');
  }
}

function renderQueueTable(pending, allUnits) {
  const tbody = $('queueTableBody');
  if (!pending || pending.length === 0) {
    tbody.innerHTML = '<tr><td colspan="9" style="text-align: center; color: var(--text-muted); padding: 24px;">No pending incidents in the priority queue.</td></tr>';
    return;
  }

  tbody.innerHTML = pending.map((inc, idx) => `
    <tr>
      <td><b>#${idx + 1}</b></td>
      <td><b style="color: #38bdf8;">${esc(inc.id)}</b></td>
      <td>${esc(inc.location)}</td>
      <td>${esc(inc.type)}</td>
      <td><b style="color: ${inc.intensity >= 8 ? '#f87171' : '#fbbf24'};">${esc(inc.intensity)}/10</b></td>
      <td>${inc.critical ? '<span class="badge badge-busy">Critical</span>' : 'No'}</td>
      <td>${esc(inc.population)}</td>
      <td>${esc(inc.arrivalOrder)}</td>
      <td>
        <div style="display: flex; gap: 6px;">
          <button class="btn secondary" style="padding: 4px 10px; font-size: 0.8rem;" onclick="openManualDispatch('${esc(inc.id)}', '${esc(inc.type)}')">Manual Dispatch</button>
          <button class="btn secondary" style="padding: 4px 10px; font-size: 0.8rem;" onclick="prefillReprioritize('${esc(inc.id)}', ${inc.intensity}, ${inc.population}, ${inc.critical})">Edit</button>
        </div>
      </td>
    </tr>
  `).join('');
}

function renderFleet(units) {
  const container = $('fleetCards');
  if (!units || units.length === 0) {
    container.innerHTML = '<p style="color: var(--text-muted);">No units loaded.</p>';
    return;
  }

  container.innerHTML = units.map(u => {
    const isEnRoute = (u.status === 'En Route');
    const isReached = (u.status === 'Reached');
    const hasMission = !u.available;
    const statusCls = u.available ? 'badge-available' : (isReached ? 'badge-reached' : 'badge-en-route');

    const routeBreadcrumbs = (u.route && u.route.length > 0)
      ? `<div class="route-path" style="margin-top: 8px;">` +
        u.route.map(node => `<span class="route-node ${node === u.location ? 'active-node' : ''}">${esc(node)}</span>`).join('<span class="route-arrow">âž”</span>') +
        `</div>`
      : `<span class="tag">At Station</span>`;

    return `
      <article class="unit-card">
        <div>
          <div class="unit-card-header">
            <div>
              <h3>${esc(u.id)}</h3>
              <div class="unit-type">${esc(u.type)} Â· Base: ${esc(u.station)}</div>
            </div>
            <span class="badge ${statusCls}">â— ${esc(u.status)}</span>
          </div>

          <div class="unit-meta">
            <div><span>Current Waypoint:</span> <b>${esc(u.location)}</b></div>
            <div><span>Active Incident:</span> <b style="color: ${hasMission ? '#38bdf8' : 'var(--text-muted)'};">${esc(u.incidentId)}</b></div>
            <div style="margin-top: 6px;">
              <span>Capabilities:</span><br>
              ${(u.capabilities || []).map(c => `<span class="tag">${esc(c)}</span>`).join('')}
            </div>
            <div style="margin-top: 8px;">
              <span>Route Progress:</span>
              ${routeBreadcrumbs}
            </div>
          </div>
        </div>

        <div class="unit-actions">
          ${hasMission ? `
            <button class="btn secondary" style="flex: 1;" onclick="trackStep('${esc(u.id)}')">âž” Advance 1 Step</button>
            <button class="btn success" style="flex: 1;" onclick="completeMission('${esc(u.id)}')">âœ“ Complete Mission</button>
          ` : `
            <span style="font-size: 0.8rem; color: var(--text-muted); padding: 8px;">Ready for assignment</span>
          `}
        </div>
      </article>
    `;
  }).join('');
}

// Step-by-Step Unit Advance
window.trackStep = async function(unitId) {
  try {
    const res = await fetch(`${API_BASE}/admin/track-unit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ unitId })
    });
    const data = await res.json();
    if (!res.ok || !data.success) throw new Error(data.message || 'Tracking step failed.');
    notify(data.message, 'info');
    loadDashboard();
    loadRegistry();
  } catch (err) {
    notify(err.message, 'error');
  }
};

// Complete Mission
window.completeMission = async function(unitId) {
  try {
    const res = await fetch(`${API_BASE}/admin/complete-mission`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ unitId })
    });
    const data = await res.json();
    if (!res.ok || !data.success) throw new Error(data.message || 'Mission completion failed.');
    let msg = data.message;
    if (data.nextDispatch && data.nextDispatch.success) {
      msg += ` Â· Next dispatch: ${data.nextDispatch.message}`;
    }
    notify(msg, 'success');
    loadDashboard();
    loadRegistry();
  } catch (err) {
    notify(err.message, 'error');
  }
};

// Auto-Dispatch
$('autoDispatchBtn').addEventListener('click', async () => {
  try {
    const res = await fetch(`${API_BASE}/admin/dispatch`, { method: 'POST' });
    const data = await res.json();
    if (!res.ok || !data.success) throw new Error(data.message || 'Dispatch failed.');
    notify(`${data.message} ${data.notification || ''}`, 'success');
    loadDashboard();
    loadRegistry();
  } catch (err) {
    notify(err.message, 'error');
  }
});

// Manual Dispatch
window.openManualDispatch = async function(incidentId, incidentType) {
  $('manualIncidentId').value = incidentId;
  $('manualDispatchTitle').textContent = `Manual Dispatch: ${incidentId} (${incidentType})`;

  try {
    const res = await fetch(`${API_BASE}/admin/units`);
    const data = await res.json();
    const units = data.units || [];

    const available = units.filter(u => u.available && (u.capabilities || []).includes(incidentType));
    const select = $('manualUnitSelect');
    if (available.length === 0) {
      select.innerHTML = `<option value="">No compatible available units (${incidentType})</option>`;
    } else {
      select.innerHTML = available.map(u => `<option value="${esc(u.id)}">${esc(u.id)} â€” ${esc(u.type)} (at ${esc(u.location)})</option>`).join('');
    }
    $('manualDispatchModal').classList.remove('hidden');
  } catch (e) {
    notify(e.message, 'error');
  }
};

$('closeManualBtn').addEventListener('click', () => {
  $('manualDispatchModal').classList.add('hidden');
});

$('manualDispatchForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const incidentId = $('manualIncidentId').value;
  const unitId = $('manualUnitSelect').value;

  if (!unitId) {
    notify('Select a valid unit to dispatch.', 'error');
    return;
  }

  try {
    const res = await fetch(`${API_BASE}/admin/manual-dispatch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ incidentId, unitId })
    });
    const data = await res.json();
    if (!res.ok || !data.success) throw new Error(data.message || 'Manual dispatch failed.');

    $('manualDispatchModal').classList.add('hidden');
    notify(data.message, 'success');
    loadDashboard();
    loadRegistry();
  } catch (err) {
    notify(err.message, 'error');
  }
});

// Reprioritize Form
window.prefillReprioritize = function(id, intensity, population, critical) {
  $('reId').value = id;
  $('reIntensity').value = intensity;
  $('rePopulation').value = population;
  $('reCritical').checked = critical;
  $('reId').scrollIntoView({ behavior: 'smooth' });
};

$('reprioritizeForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const body = {
    id: $('reId').value.trim(),
    intensity: parseInt($('reIntensity').value, 10),
    population: parseInt($('rePopulation').value, 10),
    critical: $('reCritical').checked
  };

  try {
    const res = await fetch(`${API_BASE}/admin/update-incident`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await res.json();
    if (!res.ok || !data.success) throw new Error(data.message || 'Priority update failed.');

    notify(data.message, 'success');
    $('reprioritizeForm').reset();
    loadDashboard();
    loadRegistry();
  } catch (err) {
    notify(err.message, 'error');
  }
});

// 2. Incident Registry & Audit Log
async function loadRegistry() {
  const search = $('registrySearch').value;
  const status = $('registryFilterStatus').value;
  const type = $('registryFilterType').value;

  try {
    const url = `${API_BASE}/admin/incidents?search=${encodeURIComponent(search)}&status=${encodeURIComponent(status)}&type=${encodeURIComponent(type)}`;
    const res = await fetch(url);
    const data = await res.json();
    renderRegistryTable(data.incidents || []);
  } catch (e) {
    console.error('Error loading registry:', e);
  }
}

$('registrySearch').addEventListener('input', loadRegistry);
$('registryFilterStatus').addEventListener('change', loadRegistry);
$('registryFilterType').addEventListener('change', loadRegistry);

function renderRegistryTable(incidents) {
  const tbody = $('registryTableBody');
  if (!incidents || incidents.length === 0) {
    tbody.innerHTML = '<tr><td colspan="9" style="text-align: center; color: var(--text-muted); padding: 20px;">No incidents match filter.</td></tr>';
    return;
  }

  tbody.innerHTML = incidents.map(inc => {
    const statusCls = String(inc.status).toLowerCase().replace(/\s+/g, '-');
    return `
      <tr>
        <td><b>${esc(inc.id)}</b></td>
        <td>${esc(inc.location)}</td>
        <td>${esc(inc.type)}</td>
        <td><b>${esc(inc.intensity)}/10</b></td>
        <td>${inc.critical ? '<span class="badge badge-busy">Yes</span>' : 'No'}</td>
        <td><span class="badge badge-${statusCls}">${esc(inc.status)}</span></td>
        <td>${inc.assignedUnit === 'None' ? '<span style="color: var(--text-subtle);">None</span>' : `<b style="color: #38bdf8;">${esc(inc.assignedUnit)}</b>`}</td>
        <td><code style="font-size: 0.8rem; color: #fbbf24;">${esc(inc.trackingToken)}</code></td>
        <td>
          <button class="btn secondary" style="padding: 4px 8px; font-size: 0.8rem;" onclick="viewAuditTimeline('${esc(inc.id)}')">ðŸ“œ Timeline</button>
        </td>
      </tr>
    `;
  }).join('');
}

window.viewAuditTimeline = async function(incidentId) {
  $('timelineModalTitle').textContent = `Audit Timeline: Incident ${incidentId}`;
  try {
    const res = await fetch(`${API_BASE}/admin/incident-history?id=${encodeURIComponent(incidentId)}`);
    const data = await res.json();
    const list = $('adminTimelineList');

    if (!data.history || data.history.length === 0) {
      list.innerHTML = '<li class="timeline-item"><div class="timeline-content">No historical log entries.</div></li>';
    } else {
      list.innerHTML = data.history.map(item => `
        <li class="timeline-item">
          <div class="timeline-time">${esc(item.createdAt)} Â· <span class="tag">${esc(item.source)}</span></div>
          <div class="timeline-content"><b>[${esc(item.status)}]</b> ${esc(item.note)}</div>
        </li>
      `).join('');
    }
    $('timelineModal').classList.remove('hidden');
  } catch (e) {
    notify(e.message, 'error');
  }
};

$('closeTimelineBtn').addEventListener('click', () => {
  $('timelineModal').classList.add('hidden');
});

// 3. Roads Network Management
async function loadRoads() {
  try {
    const res = await fetch(`${API_BASE}/admin/roads`);
    const data = await res.json();
    renderRoadsTable(data.roads || []);
  } catch (e) {
    console.error('Error loading roads:', e);
  }
}

function renderRoadsTable(roads) {
  const tbody = $('roadsTableBody');
  if (!roads || roads.length === 0) {
    tbody.innerHTML = '<tr><td colspan="4" style="text-align: center; color: var(--text-muted);">No roads configured.</td></tr>';
    return;
  }

  tbody.innerHTML = roads.map(r => `
    <tr>
      <td><b>${esc(r.from)}</b> âŸ· <b>${esc(r.to)}</b></td>
      <td>${esc(r.travelTime)} minutes</td>
      <td>
        ${r.active ? '<span class="badge badge-completed">â— Open</span>' : '<span class="badge badge-busy">â›” Closed</span>'}
      </td>
      <td>
        ${r.active ? `
          <button class="btn danger" style="padding: 4px 10px; font-size: 0.8rem;" onclick="toggleRoad('${esc(r.from)}', '${esc(r.to)}', 'close')">Close Road</button>
        ` : `
          <button class="btn success" style="padding: 4px 10px; font-size: 0.8rem;" onclick="toggleRoad('${esc(r.from)}', '${esc(r.to)}', 'reopen')">Reopen Road</button>
        `}
      </td>
    </tr>
  `).join('');
}

window.toggleRoad = async function(from, to, action) {
  const endpoint = action === 'close' ? 'close-road' : 'reopen-road';
  try {
    const res = await fetch(`${API_BASE}/admin/${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ from, to })
    });
    const data = await res.json();
    if (!res.ok || !data.success) throw new Error(data.message || 'Road action failed.');

    const alertEl = $('roadAlertBox');
    let message = data.message;
    if (data.updates && data.updates.length > 0) {
      message += '<br><strong>Fleet Route Recalculations:</strong><br>' + data.updates.map(u => `â€¢ ${esc(u)}`).join('<br>');
    }
    alertEl.innerHTML = message;
    alertEl.classList.remove('hidden');

    loadRoads();
    loadDashboard();
  } catch (err) {
    notify(err.message, 'error');
  }
};

// Check authentication on startup
checkAuth();