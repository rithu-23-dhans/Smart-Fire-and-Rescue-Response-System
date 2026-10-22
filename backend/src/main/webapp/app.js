const API = 'api';
const $ = (id) => document.getElementById(id);
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
async function request(path, options={}) {
  const response = await fetch(`${API}/${path}`, {
    headers: {'Content-Type':'application/json', ...(options.headers||{})},
    ...options
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || `Request failed (${response.status})`);
  return data;
}
function notify(message, error=false) {
  const el=$('notice'); el.textContent=message; el.classList.remove('hidden','error');
  if(error) el.classList.add('error');
}
function statusBadge(status) {
  const cls=String(status).toLowerCase().replace(/\s+/g,'-');
  return `<span class="status s-${esc(cls)}">${esc(status)}</span>`;
}
function fillLocations(locations) {
  const previous=$('location').value;
  const selects=[...document.querySelectorAll('.location-select'),$('location')];
  for(const select of selects) {
    const old=select.value;
    select.innerHTML=locations.map(l=>`<option value="${esc(l)}">${esc(l)}</option>`).join('');
    if(locations.includes(old)) select.value=old;
    else if(select=== $('location') && locations.includes(previous)) select.value=previous;
  }
}
function renderIncidents(incidents) {
  $('incidentCount').textContent=incidents.length;
  $('incidentRows').innerHTML=incidents.map(i=>`<tr>
    <td><b>${esc(i.id)}</b></td><td>${esc(i.type)}</td><td>${esc(i.location)}</td>
    <td>${esc(i.intensity)}/10</td><td>${esc(i.population)}</td>
    <td>${i.critical?'Yes':'No'}</td><td>${statusBadge(i.status)}</td><td>${esc(i.assignedUnit)}</td>
  </tr>`).join('') || '<tr><td colspan="8">No incidents recorded.</td></tr>';
}
function renderUnits(units) {
  $('unitSelect').innerHTML=units.map(u=>`<option value="${esc(u.id)}">${esc(u.id)} — ${esc(u.type)} (${esc(u.status)})</option>`).join('');
  $('unitCards').innerHTML=units.map(u=>`<article class="unit-card">
    <div class="unit-top"><b>${esc(u.id)}</b><span class="${u.available?'available':'busy'}">● ${esc(u.status)}</span></div>
    <div class="unit-type">${esc(u.type)}</div>
    <p><b>Station:</b> ${esc(u.station)}</p><p><b>Current location:</b> ${esc(u.location)}</p>
    <p><b>Incident:</b> ${esc(u.incidentId)}</p><p><b>Capabilities:</b> ${esc((u.capabilities||[]).join(', '))}</p>
  </article>`).join('');
}
async function refresh() {
  try {
    const data=await request('dashboard');
    renderIncidents(data.incidents);renderUnits(data.units);fillLocations(data.locations);
    $('pendingCount').textContent=data.pendingCount;
    $('activeCount').textContent=data.activeCount;
    $('availableCount').textContent=data.units.filter(u=>u.available).length;
  } catch(e) { notify(`${e.message} Check that Tomcat and MySQL are running and db.properties is configured.`,true); }
}
async function post(path,body={}) {
  const data=await request(path,{method:'POST',body:JSON.stringify(body)});
  notify(data.message || 'Operation completed.',!data.success);
  if(data.notification) notify(`${data.message} ${data.notification}`);
  if(data.updates?.length) notify(`${data.message} ${data.updates.join(' | ')}`);
  await refresh();
  return data;
}
$('refresh').addEventListener('click',refresh);
$('reportForm').addEventListener('submit',async e=>{
  e.preventDefault();const f=new FormData(e.currentTarget);
  try {const d=await post('incidents',{location:f.get('location'),type:f.get('type'),intensity:f.get('intensity'),population:f.get('population'),critical:f.has('critical')});
    if(d.incident) notify(`${d.message} Incident ID: ${d.incident.id}`);
    e.currentTarget.reset();await refresh();
  } catch(err){notify(err.message,true);}
});
$('dispatchBtn').addEventListener('click',async()=>{try{const d=await post('dispatch');if(d.success&&d.route)notify(`${d.message} Route: ${d.route.path.join(' → ')}. Estimated travel time: ${d.route.time}. ${d.notification||''}`)}catch(e){notify(e.message,true)}});
$('updateForm').addEventListener('submit',async e=>{
  e.preventDefault();const f=new FormData(e.currentTarget);
  try{await post('update',{id:f.get('id').trim(),intensity:f.get('intensity'),population:f.get('population'),critical:f.has('critical')});}catch(err){notify(err.message,true)}
});
$('trackBtn').addEventListener('click',async()=>{try{const d=await post('track',{unitId:$('unitSelect').value});if(d.route)notify(`${d.message} Current route: ${d.route.path.join(' → ')}`)}catch(e){notify(e.message,true)}});
$('completeBtn').addEventListener('click',async()=>{try{const d=await post('complete',{unitId:$('unitSelect').value});if(d.nextDispatch)notify(`${d.message} ${d.nextDispatch.message}`)}catch(e){notify(e.message,true)}});
$('roadForm').addEventListener('submit',async e=>{
  e.preventDefault();const f=new FormData(e.currentTarget);
  if(f.get('from')===f.get('to')){notify('Choose two different locations.',true);return}
  try{await post('close-road',{from:f.get('from'),to:f.get('to')})}catch(err){notify(err.message,true)}
});
$('routeForm').addEventListener('submit',async e=>{
  e.preventDefault();const f=new FormData(e.currentTarget);
  try{const d=await request(`route?start=${encodeURIComponent(f.get('start'))}&end=${encodeURIComponent(f.get('end'))}`);
    const el=$('routeResult');el.classList.remove('hidden');
    el.textContent=d.success?`Fastest route: ${d.route.path.join(' → ')} | Total travel time: ${d.route.time}`:d.message;
  }catch(err){notify(err.message,true)}
});
refresh();
