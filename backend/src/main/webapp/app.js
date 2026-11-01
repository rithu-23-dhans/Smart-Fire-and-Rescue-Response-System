// ============================================================
// Sudar Fire & Rescue — Public Citizen Portal Client
// Supports: guest reporting, citizen profiles, remember-me,
//           incident tracking, witness updates, route finder.
// ============================================================
const API_BASE = 'api';

// ---- Auth state ----
const AUTH_TOKEN_KEY = 'frs_remember_token'; // localStorage key
let authState = { loggedIn: false, citizenId: null, username: null, email: null, rememberToken: null };

// ---- Convenience ----
const $ = (id) => document.getElementById(id);
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, c =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

// ============================================================
// NOTIFICATIONS
// ============================================================
function notify(msg, type = 'info') {
  const el = $('globalNotice');
  if (!el) return;
  el.textContent = msg;
  el.className = `alert alert-${type}`;
  el.classList.remove('hidden');
  window.scrollTo({ top: 0, behavior: 'smooth' });
  setTimeout(() => el.classList.add('hidden'), 6000);
}
function clearNotice() {
  const el = $('globalNotice');
  if (el) el.classList.add('hidden');
}

// ============================================================
// TAB NAVIGATION
// ============================================================
function switchTab(tabId) {
  document.querySelectorAll('.nav-tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
  const tab = document.querySelector(`[data-tab="${tabId}"]`);
  if (tab) tab.classList.add('active');
  const content = $(tabId);
  if (content) content.classList.add('active');
  if (tabId === 'tab-profile' && authState.loggedIn) loadMyIncidents();
}

document.querySelectorAll('.nav-tab').forEach(tab => {
  tab.addEventListener('click', () => switchTab(tab.dataset.tab));
});

// ============================================================
// LOCATIONS LOADER
// ============================================================
async function loadLocations() {
  try {
    const res = await fetch(`${API_BASE}/public/locations`);
    const data = await res.json();
    if (data.success && data.locations) {
      const opts = data.locations.map(loc =>
        `<option value="${esc(loc)}">${esc(loc)}</option>`).join('');
      $('reportLocation').innerHTML = `<option value="">-- Select Incident Location --</option>${opts}`;
      $('routeStart').innerHTML = `<option value="">-- Select Origin --</option>${opts}`;
      $('routeEnd').innerHTML = `<option value="">-- Select Destination --</option>${opts}`;
    }
  } catch {
    notify('Failed to load locations from server. Please ensure backend is reachable.', 'error');
  }
}

// ============================================================
// AUTH: REMEMBER-ME (localStorage-based)
// ============================================================

/** Attaches the remember-me token to every API request that needs it. */
function authHeaders() {
  const token = authState.rememberToken || localStorage.getItem(AUTH_TOKEN_KEY);
  return token ? { 'X-Remember-Token': token } : {};
}

/** Saves the rotated token returned by the server (always up-to-date). */
function storeToken(token) {
  if (!token) return;
  authState.rememberToken = token;
  localStorage.setItem(AUTH_TOKEN_KEY, token);
}

/** Checks the server-side session on page load using the persisted token. */
async function checkAuthSession() {
  const savedToken = localStorage.getItem(AUTH_TOKEN_KEY);
  if (!savedToken) return;
  try {
    const res = await fetch(`${API_BASE}/public/citizen/session`, {
      headers: { 'X-Remember-Token': savedToken }
    });
    if (!res.ok) { localStorage.removeItem(AUTH_TOKEN_KEY); return; }
    const data = await res.json();
    // Grab rotated token from response header or body
    const newToken = res.headers.get('X-Remember-Token') || data.rememberToken;
    if (newToken) storeToken(newToken);
    if (data.success && data.loggedIn) {
      setAuthState(data.citizenId, data.username, data.email, newToken || savedToken);
      resetIdleTimer();
    } else {
      localStorage.removeItem(AUTH_TOKEN_KEY);
    }
  } catch {
    // Network error — keep local state, try again on next load
  }
}

function setAuthState(citizenId, username, email, rememberToken) {
  authState = { loggedIn: true, citizenId, username, email, rememberToken };
  renderAuthHeader();
  resetIdleTimer();
}

function clearAuthState() {
  authState = { loggedIn: false, citizenId: null, username: null, email: null, rememberToken: null };
  localStorage.removeItem(AUTH_TOKEN_KEY);
  renderAuthHeader();
  clearTimeout(idleTimer);
  clearTimeout(idleWarnTimer);
}

function renderAuthHeader() {
  const isIn = authState.loggedIn;
  $('headerGuest').classList.toggle('hidden', isIn);
  $('headerUser').classList.toggle('hidden', !isIn);
  $('navTabProfile').classList.toggle('hidden', !isIn);

  if (isIn) {
    const initial = (authState.username || '?')[0].toUpperCase();
    $('userAvatar').textContent = initial;
    $('userChipName').textContent = authState.username;
    $('profileAvatar').textContent = initial;
    $('profileName').textContent = authState.username;
    $('profileEmail').textContent = authState.email || '';
  }
}

// ============================================================
// AUTH MODAL
// ============================================================
function openAuthModal(mode = 'login') {
  $('authModal').classList.remove('hidden');
  switchAuthTab(mode);
}
function closeAuthModal() {
  $('authModal').classList.add('hidden');
  $('loginForm').reset();
  $('registerForm').reset();
  $('loginError').classList.add('hidden');
  $('registerError').classList.add('hidden');
}
function switchAuthTab(mode) {
  const isLogin = mode === 'login';
  $('authTabLogin').classList.toggle('active', isLogin);
  $('authTabRegister').classList.toggle('active', !isLogin);
  $('loginForm').classList.toggle('hidden', !isLogin);
  $('registerForm').classList.toggle('hidden', isLogin);
  $('authModalTitle').textContent = isLogin ? 'Sign In' : 'Create Account';
}

// Close modal on overlay click
$('authModal').addEventListener('click', (e) => {
  if (e.target === $('authModal')) closeAuthModal();
});
// Close on Escape
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') closeAuthModal();
});

// ============================================================
// REGISTER
// ============================================================
$('registerForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const errEl = $('registerError');
  errEl.classList.add('hidden');

  const username = $('regUsername').value.trim();
  const email    = $('regEmail').value.trim();
  const password = $('regPassword').value;

  // Carry pending incident to link on register
  const pendingId    = window._pendingIncidentId    || null;
  const pendingToken = window._pendingTrackingToken || null;

  try {
    const res = await fetch(`${API_BASE}/public/citizen/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, email, password, incidentId: pendingId, trackingToken: pendingToken })
    });
    const data = await res.json();
    if (!res.ok || !data.success) {
      errEl.textContent = data.message || 'Registration failed.';
      errEl.classList.remove('hidden');
      return;
    }
    storeToken(data.rememberToken);
    setAuthState(data.citizenId, data.username || username, data.email || email, data.rememberToken);
    closeAuthModal();
    notify(`Welcome, ${username}! Your account is created and you're signed in.`, 'success');
    if (pendingId) {
      window._pendingIncidentId = null;
      window._pendingTrackingToken = null;
      $('postReportLink').classList.add('hidden');
      notify(`Incident ${pendingId} has been linked to your profile!`, 'success');
    }
    switchTab('tab-profile');
  } catch (err) {
    errEl.textContent = err.message || 'Network error.';
    errEl.classList.remove('hidden');
  }
});

// ============================================================
// LOGIN
// ============================================================
$('loginForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const errEl = $('loginError');
  errEl.classList.add('hidden');

  const username = $('loginUsername').value.trim();
  const password = $('loginPassword').value;

  try {
    const res = await fetch(`${API_BASE}/public/citizen/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    const data = await res.json();
    if (!res.ok || !data.success) {
      errEl.textContent = data.message || 'Login failed.';
      errEl.classList.remove('hidden');
      return;
    }
    storeToken(data.rememberToken);
    setAuthState(data.citizenId, data.username || username, data.email, data.rememberToken);
    closeAuthModal();
    notify(`Welcome back, ${data.username || username}! You are now signed in.`, 'success');

    // If there's a pending incident, claim it now
    const pendingId    = window._pendingIncidentId;
    const pendingToken = window._pendingTrackingToken;
    if (pendingId && pendingToken) {
      await claimIncident(pendingId, pendingToken);
    }
    switchTab('tab-profile');
  } catch (err) {
    errEl.textContent = err.message || 'Network error.';
    errEl.classList.remove('hidden');
  }
});

// ============================================================
// LOGOUT
// ============================================================
async function handleLogout(e) {
  if (e) { e.stopPropagation(); }
  const token = authState.rememberToken || localStorage.getItem(AUTH_TOKEN_KEY);
  clearAuthState();
  // Fire-and-forget server revocation
  if (token) {
    fetch(`${API_BASE}/public/citizen/logout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ rememberToken: token })
    }).catch(() => {});
  }
  switchTab('tab-report');
  notify('You have been signed out.', 'info');
}

// ============================================================
// CLAIM INCIDENT (for logged-in users after anon report)
// ============================================================
async function claimIncident(incidentId, trackingToken) {
  try {
    const res = await fetch(`${API_BASE}/public/citizen/claim-incident`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ incidentId, trackingToken })
    });
    const data = await res.json();
    const newToken = res.headers.get('X-Remember-Token');
    if (newToken) storeToken(newToken);
    if (data.success) {
      window._pendingIncidentId = null;
      window._pendingTrackingToken = null;
      $('postReportLink').classList.add('hidden');
      notify(`Incident ${incidentId} has been saved to your profile.`, 'success');
    }
  } catch { /* silent */ }
}

// ============================================================
// REPORT EMERGENCY FORM
// ============================================================
$('publicReportForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  clearNotice();

  const body = {
    location:    $('reportLocation').value,
    type:        $('reportType').value,
    intensity:   parseInt($('reportIntensity').value, 10),
    population:  parseInt($('reportPopulation').value, 10),
    critical:    $('reportCritical').checked,
    description: $('reportDesc').value.trim()
  };

  // If logged in, send citizenId so backend auto-links on creation
  if (authState.loggedIn && authState.citizenId) {
    body.citizenId = authState.citizenId;
  }

  const btn = $('btnSubmitReport');
  btn.disabled = true;
  btn.textContent = 'Submitting...';

  try {
    const res = await fetch(`${API_BASE}/public/incidents`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify(body)
    });
    const data = await res.json();
    const newToken = res.headers.get('X-Remember-Token');
    if (newToken) storeToken(newToken);

    if (!res.ok || !data.success) throw new Error(data.message || 'Failed to submit incident.');

    notify(`Incident reported! Reference ID: ${data.incidentId}`, 'success');
    $('createdIncidentId').textContent  = data.incidentId;
    $('createdTrackingToken').textContent = data.trackingToken;
    $('reportSuccessCard').classList.remove('hidden');

    // Pre-fill tracking form
    $('trackId').value    = data.incidentId;
    $('trackToken').value = data.trackingToken;

    // Show post-report link section
    renderPostReportSection(data.incidentId, data.trackingToken);

    $('publicReportForm').reset();
  } catch (err) {
    notify(err.message, 'error');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Submit Report';
  }
});

/** After anon report: show "Link to account / Create account" prompt. */
function renderPostReportSection(incidentId, trackingToken) {
  const el = $('postReportLink');
  if (authState.loggedIn) {
    // Already linked via backend; just show confirmation
    el.innerHTML = `<div class="post-report-linked">Incident automatically saved to your profile, <b>${esc(authState.username)}</b>.</div>`;
    el.classList.remove('hidden');
    return;
  }
  // Guest — store pending for later claim
  window._pendingIncidentId    = incidentId;
  window._pendingTrackingToken = trackingToken;

  el.innerHTML = `
    <div class="post-report-prompt">
      <div class="post-report-icon">&#128161;</div>
      <div class="post-report-text">
        <b>Save this incident to a profile</b> so you can track it anytime — even after closing the browser.
      </div>
      <div class="post-report-btns">
        <button class="btn btn-sm" onclick="openAuthModal('register')">Create Free Account</button>
        <button class="btn secondary btn-sm" onclick="openAuthModal('login')">Sign In to Existing Account</button>
      </div>
    </div>`;
  el.classList.remove('hidden');
}

// Copy Token Button
$('copyTokenBtn').addEventListener('click', () => {
  const token = $('createdTrackingToken').textContent;
  if (navigator.clipboard) {
    navigator.clipboard.writeText(token).then(() => notify('Tracking token copied to clipboard!', 'info'));
  } else {
    notify(`Token: ${token}`, 'info');
  }
});

// Quick Track Button
$('quickTrackBtn').addEventListener('click', () => {
  switchTab('tab-track');
  $('trackForm').dispatchEvent(new Event('submit'));
});

// ============================================================
// TRACK INCIDENT FORM
// ============================================================
$('trackForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  clearNotice();
  const id    = $('trackId').value.trim();
  const token = $('trackToken').value.trim();
  if (!id || !token) { notify('Please enter both Incident ID and Tracking Token.', 'error'); return; }
  try {
    const res  = await fetch(`${API_BASE}/public/track?id=${encodeURIComponent(id)}&token=${encodeURIComponent(token)}`);
    const data = await res.json();
    if (!res.ok || !data.success) throw new Error(data.message || 'Invalid Incident ID or Tracking Token.');
    renderTrackResult(data);
  } catch (err) {
    notify(err.message, 'error');
    $('trackResult').classList.add('hidden');
  }
});

// ============================================================
// STATUS EXPLAINER HELPER
// ============================================================
function getStatusExplanation(status) {
  const s = String(status).toLowerCase().trim();
  if (s === 'pending') return {
    icon: 'hourglass',
    color: '#d97706',
    bg: '#fffbeb',
    border: '#fde68a',
    title: 'Waiting for Dispatch',
    detail: 'Your incident has been received and logged. A response unit will be assigned shortly based on emergency priority.'
  };
  if (s === 'en route' || s === 'en-route') return {
    icon: 'truck',
    color: '#2563eb',
    bg: '#eff6ff',
    border: '#bfdbfe',
    title: 'Response Unit On the Way',
    detail: 'A fire and rescue unit has been dispatched and is travelling to your location right now.'
  };
  if (s === 'reached') return {
    icon: 'check-circle',
    color: '#7c3aed',
    bg: '#f5f3ff',
    border: '#ddd6fe',
    title: 'Unit Has Arrived at Scene',
    detail: 'The response unit has reached the incident location and emergency operations are underway.'
  };
  if (s === 'completed') return {
    icon: 'check-all',
    color: '#16a34a',
    bg: '#f0fdf4',
    border: '#bbf7d0',
    title: 'Incident Resolved',
    detail: 'This incident has been successfully resolved. The response unit has completed its mission.'
  };
  return { icon: 'info', color: '#64748b', bg: '#f8fafc', border: '#e2e8f0', title: status, detail: '' };
}

function renderTrackResult(data) {
  const inc = data.incident;
  $('trackDisplayId').textContent       = inc.id;
  $('trackDisplayLoc').textContent      = inc.location;
  $('trackDisplayType').textContent     = inc.type;
  $('trackDisplaySeverity').textContent = `${inc.intensity}/10 (${inc.population} people in danger)`;
  $('trackDisplayCritical').textContent = inc.critical ? 'Yes (Critical Infrastructure)' : 'No';
  $('trackDisplayDesc').textContent     = `"${inc.description || 'No description provided.'}"`;

  const statusEl = $('trackDisplayStatus');
  statusEl.textContent = inc.status;
  statusEl.className   = `badge badge-${String(inc.status).toLowerCase().replace(/\s+/g, '-')}`;

  // Status explainer card
  const exp = getStatusExplanation(inc.status);
  const statusCard = $('statusExplainerCard');
  if (statusCard) {
    statusCard.style.background = exp.bg;
    statusCard.style.borderColor = exp.border;
    statusCard.querySelector('.se-title').textContent = exp.title;
    statusCard.querySelector('.se-detail').textContent = exp.detail;
    statusCard.querySelector('.se-title').style.color = exp.color;
    statusCard.classList.remove('hidden');
  }

  // Completed banner
  const completedBanner = $('completedBanner');
  if (completedBanner) {
    if (String(inc.status).toLowerCase() === 'completed') {
      completedBanner.classList.remove('hidden');
    } else {
      completedBanner.classList.add('hidden');
    }
  }

  if (data.assignedUnit) {
    $('trackNoUnit').classList.add('hidden');
    $('trackUnitDetails').classList.remove('hidden');
    const u = data.assignedUnit;
    $('trackUnitId').textContent     = u.id;
    $('trackUnitType').textContent   = u.type;
    $('trackUnitLoc').textContent    = u.location;
    $('trackUnitStatus').textContent = u.status;
    if (u.route && u.route.length > 0) {
      $('trackRoutePath').innerHTML = u.route.map((node, idx) => {
        const isActive = (node === u.location);
        const arrow = idx < u.route.length - 1 ? '<span class="route-arrow">&rsaquo;</span>' : '';
        return `<span class="route-node ${isActive ? 'active-node' : ''}">${esc(node)}</span>${arrow}`;
      }).join(' ');
    } else {
      $('trackRoutePath').innerHTML = `<span class="tag">At ${esc(u.location)}</span>`;
    }
  } else {
    $('trackNoUnit').classList.remove('hidden');
    $('trackUnitDetails').classList.add('hidden');
  }

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
      <div class="timeline-time">${esc(item.createdAt)} · <span class="tag">${esc(item.source)}</span></div>
      <div class="timeline-content"><b>[${esc(item.status)}]</b> ${esc(item.note)}</div>
    </li>`).join('');
}

// ============================================================
// WITNESS UPDATE FORM
// ============================================================
$('witnessUpdateForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const id    = $('trackId').value.trim();
  const token = $('trackToken').value.trim();
  const note  = $('updateNoteText').value.trim();
  if (!note) return;
  try {
    const res  = await fetch(`${API_BASE}/public/incident-update`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, token, note })
    });
    const data = await res.json();
    if (!res.ok || !data.success) throw new Error(data.message || 'Failed to submit update.');
    notify('Citizen situation update posted!', 'success');
    $('updateNoteText').value = '';
    renderTimeline(data.history);
  } catch (err) {
    notify(err.message, 'error');
  }
});

// ============================================================
// ROUTE CALCULATION FORM
// ============================================================
$('publicRouteForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  clearNotice();
  const start = $('routeStart').value;
  const end   = $('routeEnd').value;
  if (!start || !end) { notify('Please select both origin and destination.', 'error'); return; }
  if (start === end)  { notify('Origin and destination are the same location.', 'info'); return; }
  try {
    const res  = await fetch(`${API_BASE}/public/route?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`);
    const data = await res.json();
    if (!res.ok || !data.success) throw new Error(data.message || 'Could not find a valid route.');
    $('routeTravelTime').textContent = `Estimated Travel Time: ${data.route.time} minutes`;
    $('routePathNodes').innerHTML = data.route.path.map((node, idx) => {
      const arrow = idx < data.route.path.length - 1 ? '<span class="route-arrow">&rsaquo;</span>' : '';
      return `<span class="route-node">${esc(node)}</span>${arrow}`;
    }).join(' ');
    $('routeOutput').classList.remove('hidden');
  } catch (err) {
    notify(err.message, 'error');
    $('routeOutput').classList.add('hidden');
  }
});

// ============================================================
// MY INCIDENTS (Profile Tab)
// ============================================================
async function loadMyIncidents() {
  if (!authState.loggedIn) return;
  try {
    const res  = await fetch(`${API_BASE}/public/my-incidents`, { headers: authHeaders() });
    const data = await res.json();
    const newToken = res.headers.get('X-Remember-Token');
    if (newToken) storeToken(newToken);
    if (!res.ok || !data.success) { notify(data.message || 'Failed to load incidents.', 'error'); return; }
    renderMyIncidents(data.incidents || []);
  } catch (err) {
    notify('Failed to load your incidents.', 'error');
  }
}

function renderMyIncidents(incidents) {
  const empty = $('myIncidentsEmpty');
  const table = $('myIncidentsTable');
  const tbody = $('myIncidentsTbody');

  if (!incidents.length) {
    empty.classList.remove('hidden');
    table.classList.add('hidden');
    return;
  }

  empty.classList.add('hidden');
  table.classList.remove('hidden');

  tbody.innerHTML = incidents.map(inc => {
    const statusCls = String(inc.status).toLowerCase().replace(/\s+/g, '-');
    const isCompleted = String(inc.status).toLowerCase() === 'completed';
    const unit = inc.assignedUnit
      ? `<span class="unit-mini">${esc(inc.assignedUnit.id)} <small>(${esc(inc.assignedUnit.type)} · ${esc(inc.assignedUnit.status)})</small></span>`
      : `<span class="text-muted">-</span>`;
    const shortToken = inc.trackingToken
      ? `<span class="mono token-short" title="${esc(inc.trackingToken)}">${esc(inc.trackingToken.slice(0, 12))}...</span>`
      : '-';
    const statusBadge = isCompleted
      ? `<span class="badge badge-completed">Resolved</span>`
      : `<span class="badge badge-${statusCls}">${esc(inc.status)}</span>`;
    return `<tr class="${isCompleted ? 'row-completed' : ''}">
      <td><b>${esc(inc.id)}</b></td>
      <td>${esc(inc.type)}</td>
      <td>${esc(inc.location)}</td>
      <td><span class="severity-badge sev-${Math.min(Math.ceil(inc.intensity / 2), 5)}">${inc.intensity}/10</span></td>
      <td>${statusBadge}</td>
      <td>${unit}</td>
      <td>${shortToken}</td>
      <td><button class="btn btn-sm" onclick="quickTrackIncident('${esc(inc.id)}','${esc(inc.trackingToken)}')">Track</button></td>
    </tr>`;
  }).join('');
}

function quickTrackIncident(id, token) {
  $('trackId').value    = id;
  $('trackToken').value = token;
  switchTab('tab-track');
  $('trackForm').dispatchEvent(new Event('submit'));
}

// ============================================================
// INACTIVITY AUTO-LOGOUT (30 min idle = warning, then logout)
// ============================================================
const IDLE_TIMEOUT_MS  = 30 * 60 * 1000; // 30 minutes
const IDLE_WARN_MS     = 29 * 60 * 1000; // warn at 29 min
let idleTimer, idleWarnTimer;

function resetIdleTimer() {
  if (!authState.loggedIn) return;
  clearTimeout(idleTimer);
  clearTimeout(idleWarnTimer);
  idleWarnTimer = setTimeout(() => {
    notify('You have been inactive for 29 minutes. You will be signed out in 1 minute unless you interact.', 'info');
  }, IDLE_WARN_MS);
  idleTimer = setTimeout(() => {
    handleLogout();
    notify('You were signed out due to inactivity. Please sign in again.', 'info');
  }, IDLE_TIMEOUT_MS);
}

['mousemove','keydown','click','touchstart','scroll'].forEach(evt =>
  document.addEventListener(evt, resetIdleTimer, { passive: true })
);

// ============================================================
// INIT
// ============================================================
(async function init() {
  loadLocations();
  await checkAuthSession();  // restore remember-me session
  renderAuthHeader();
})();
