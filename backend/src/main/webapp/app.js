// Public Citizen Portal Client
const API_BASE = 'api';

const $ = (id) => document.getElementById(id);
const esc = (val) => String(val ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

function notify(msg, type = 'info') {
  const el = $('globalNotice');
  if (!el) return;
  el.textContent = msg;
  el.className = `alert alert-${type}`;
  el.classList.remove('hidden');
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function clearNotice() {
  const el = $('globalNotice');
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

// Load Sudar Locations
async function loadLocations() {
  try {
    const res = await fetch(`${API_BASE}/public/locations`);
    const data = await res.json();
    if (data.success && data.locations) {
      const opts = data.locations.map(loc => `<option value="${esc(loc)}">${esc(loc)}</option>`).join('');
      $('reportLocation').innerHTML = `<option value="">-- Select Incident Location --</option>${opts}`;
      $('routeStart').innerHTML = `<option value="">-- Select Origin --</option>${opts}`;
      $('routeEnd').innerHTML = `<option value="">-- Select Destination --</option>${opts}`;
    }
  } catch (err) {
    notify('Failed to load locations from server. Please ensure backend is reachable.', 'error');
  }
}

// Report Incident Form
$('publicReportForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  clearNotice();

  const body = {
    location: $('reportLocation').value,
    type: $('reportType').value,
    intensity: parseInt($('reportIntensity').value, 10),
    population: parseInt($('reportPopulation').value, 10),
    critical: $('reportCritical').checked,
    description: $('reportDesc').value.trim()
  };

  try {
    const res = await fetch(`${API_BASE}/public/incidents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    const data = await res.json();

    if (!res.ok || !data.success) {
      throw new Error(data.message || 'Failed to submit incident.');
    }

    notify(`Incident reported successfully! Your Reference ID is ${data.incidentId}.`, 'success');
    $('createdIncidentId').textContent = data.incidentId;
    $('createdTrackingToken').textContent = data.trackingToken;
    $('reportSuccessCard').classList.remove('hidden');

    // Pre-fill tracking form
    $('trackId').value = data.incidentId;
    $('trackToken').value = data.trackingToken;

    $('publicReportForm').reset();
  } catch (err) {
    notify(err.message, 'error');
  }
});

// Copy Token Button
$('copyTokenBtn').addEventListener('click', () => {
  const token = $('createdTrackingToken').textContent;
  if (navigator.clipboard) {
    navigator.clipboard.writeText(token).then(() => {
      notify('Tracking token copied to clipboard!', 'info');
    });
  } else {
    notify(`Token: ${token}`, 'info');
  }
});

// Quick Track Button
$('quickTrackBtn').addEventListener('click', () => {
  document.querySelector('[data-tab="tab-track"]').click();
  $('trackForm').dispatchEvent(new Event('submit'));
});

// Track Incident Form
$('trackForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  clearNotice();

  const id = $('trackId').value.trim();
  const token = $('trackToken').value.trim();

  if (!id || !token) {
    notify('Please enter both Incident ID and Tracking Token.', 'error');
    return;
  }

  try {
    const res = await fetch(`${API_BASE}/public/track?id=${encodeURIComponent(id)}&token=${encodeURIComponent(token)}`);
    const data = await res.json();

    if (!res.ok || !data.success) {
      throw new Error(data.message || 'Invalid Incident ID or Tracking Token.');
    }

    renderTrackResult(data);
  } catch (err) {
    notify(err.message, 'error');
    $('trackResult').classList.add('hidden');
  }
});

function renderTrackResult(data) {
  const inc = data.incident;
  $('trackDisplayId').textContent = inc.id;
  $('trackDisplayLoc').textContent = inc.location;
  $('trackDisplayType').textContent = inc.type;
  $('trackDisplaySeverity').textContent = `${inc.intensity}/10 (${inc.population} people in danger)`;
  $('trackDisplayCritical').textContent = inc.critical ? 'Yes (Critical Infrastructure)' : 'No';
  $('trackDisplayDesc').textContent = `"${inc.description || 'No description provided.'}"`;

  const statusEl = $('trackDisplayStatus');
  statusEl.textContent = inc.status;
  const statusCls = String(inc.status).toLowerCase().replace(/\s+/g, '-');
  statusEl.className = `badge badge-${statusCls}`;

  // Assigned Unit
  if (data.assignedUnit) {
    $('trackNoUnit').classList.add('hidden');
    $('trackUnitDetails').classList.remove('hidden');

    const u = data.assignedUnit;
    $('trackUnitId').textContent = u.id;
    $('trackUnitType').textContent = u.type;
    $('trackUnitLoc').textContent = u.location;
    $('trackUnitStatus').textContent = u.status;

    // Render breadcrumbs
    if (u.route && u.route.length > 0) {
      $('trackRoutePath').innerHTML = u.route.map((node, idx) => {
        const isActive = (node === u.location);
        const arrow = idx < u.route.length - 1 ? '<span class="route-arrow">âž”</span>' : '';
        return `<span class="route-node ${isActive ? 'active-node' : ''}">${esc(node)}</span>${arrow}`;
      }).join(' ');
    } else {
      $('trackRoutePath').innerHTML = `<span class="tag">At ${esc(u.location)}</span>`;
    }
  } else {
    $('trackNoUnit').classList.remove('hidden');
    $('trackUnitDetails').classList.add('hidden');
  }

  // Render Timeline
  renderTimeline(data.history || []);
  $('trackResult').classList.remove('hidden');
}

function renderTimeline(history) {
  const list = $('timelineList');
  if (!history || history.length === 0) {
    list.innerHTML = '<li class="timeline-item"><div class="timeline-content">No logged updates yet.</div></li>';
    return;
  }
  list.innerHTML = history.map(item => `
    <li class="timeline-item">
      <div class="timeline-time">${esc(item.createdAt)} Â· <span class="tag">${esc(item.source)}</span></div>
      <div class="timeline-content">
        <b>[${esc(item.status)}]</b> ${esc(item.note)}
      </div>
    </li>
  `).join('');
}

// Witness Update Form
$('witnessUpdateForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const id = $('trackId').value.trim();
  const token = $('trackToken').value.trim();
  const note = $('updateNoteText').value.trim();

  if (!note) return;

  try {
    const res = await fetch(`${API_BASE}/public/incident-update`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, token, note })
    });
    const data = await res.json();
    if (!res.ok || !data.success) {
      throw new Error(data.message || 'Failed to submit update.');
    }

    notify('Citizen situation update posted!', 'success');
    $('updateNoteText').value = '';
    renderTimeline(data.history);
  } catch (err) {
    notify(err.message, 'error');
  }
});

// Route Calculation Form
$('publicRouteForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  clearNotice();

  const start = $('routeStart').value;
  const end = $('routeEnd').value;

  if (!start || !end) {
    notify('Please select both origin and destination.', 'error');
    return;
  }

  if (start === end) {
    notify('Origin and destination are the same location (0 min travel time).', 'info');
    return;
  }

  try {
    const res = await fetch(`${API_BASE}/public/route?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`);
    const data = await res.json();

    if (!res.ok || !data.success) {
      throw new Error(data.message || 'Could not find a valid route.');
    }

    $('routeTravelTime').textContent = `Estimated Travel Time: ${data.route.time} minutes`;
    $('routePathNodes').innerHTML = data.route.path.map((node, idx) => {
      const arrow = idx < data.route.path.length - 1 ? '<span class="route-arrow">âž”</span>' : '';
      return `<span class="route-node">${esc(node)}</span>${arrow}`;
    }).join(' ');

    $('routeOutput').classList.remove('hidden');
  } catch (err) {
    notify(err.message, 'error');
    $('routeOutput').classList.add('hidden');
  }
});

// Initialize on page load
loadLocations();