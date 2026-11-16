/** GIVEN — the demo page served at http://localhost:8080. One card per feature. Use it on demo day. */
final class DemoPage {
    static final String HTML = """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>QuickBite on Redis</title>
<style>
  :root{--bg:#0d0221;--card:#1b0a3c;--text:#fff;--muted:#c8b6ff;--red:#ff1744;--pink:#f50057;--orange:#ff9100;
        --yellow:#ffea00;--green:#00e676;--cyan:#00e5ff;--blue:#2979ff;--violet:#d500f9}
  *{box-sizing:border-box}
  body{margin:0;font-family:system-ui,-apple-system,sans-serif;background:var(--bg);color:var(--text);padding:20px 16px 60px}
  h1{margin:0 0 4px;font-size:30px;background:linear-gradient(90deg,var(--red),var(--orange),var(--yellow));-webkit-background-clip:text;color:transparent}
  .sub{color:var(--muted);margin-bottom:18px}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));gap:16px;max-width:1300px}
  .card{background:var(--card);border-radius:16px;padding:16px;border-top:5px solid var(--c)}
  .card h2{margin:0 0 2px;font-size:18px}.tag{display:inline-block;font-size:11px;font-weight:800;padding:2px 8px;border-radius:999px;background:var(--c);color:#000;margin-bottom:8px}
  .hint{font-size:12px;color:var(--muted);margin:2px 0 10px}
  button{border:0;border-radius:10px;padding:8px 12px;margin:0 6px 6px 0;font-weight:700;cursor:pointer;background:var(--c);color:#000}
  button.alt{background:transparent;color:var(--c);outline:2px solid var(--c)}
  input{background:#2a1458;border:0;color:#fff;border-radius:8px;padding:8px;width:130px;margin:0 6px 6px 0}
  pre{background:#090118;border-radius:10px;padding:10px;font-size:12px;max-height:220px;overflow:auto;white-space:pre-wrap;margin:6px 0 0}
  .big{font-size:26px;font-weight:900;color:var(--c)}
  ol.tl{list-style:none;padding:0;margin:8px 0 0}ol.tl li{padding:6px 10px;margin:4px 0;border-radius:8px;font-weight:700;color:#000}
  .PLACED{background:var(--violet)}.ACCEPTED{background:var(--blue);color:#fff!important}.PREPARING{background:var(--orange)}
  .OUT_FOR_DELIVERY{background:var(--pink);color:#fff!important}.DELIVERED{background:var(--green)}
  .bar{height:22px;border-radius:6px;background:var(--c);color:#000;font-size:12px;font-weight:800;padding:3px 8px;margin:4px 0;white-space:nowrap}
</style></head><body>
<h1>QuickBite on Redis</h1>
<div class="sub">Every card hits your code. Watch the keys change in another terminal: <code>redis-cli MONITOR</code></div>
<div class="grid">

<div class="card" style="--c:var(--cyan)"><span class="tag">TRACK B · OTP + SESSION</span><h2>1. Log in</h2>
  <div class="hint">otp:{phone} · otp:cooldown · otp:attempts · session:{token}</div>
  <input id="phone" value="9876543210"><button onclick="otp()">Send OTP</button><br>
  <input id="code" placeholder="6-digit code"><button onclick="verify()">Verify</button><button class="alt" onclick="me()">Who am I?</button>
  <pre id="authOut">not logged in</pre></div>

<div class="card" style="--c:var(--yellow)"><span class="tag">TRACK A · CACHE-ASIDE</span><h2>2. Menu</h2>
  <div class="hint">menu:{restaurantId} — first load 300 ms (DB), then ~1 ms (Redis)</div>
  <button onclick="menu('r1')">Dosa Junction</button><button onclick="menu('r2')">Biryani Brothers</button>
  <button class="alt" onclick="reprice()">Change dosa price</button>
  <div class="big" id="menuStat">—</div><pre id="menuOut"></pre></div>

<div class="card" style="--c:var(--green)"><span class="tag">TRACK A + E · CART → CHECKOUT</span><h2>3. Order</h2>
  <div class="hint">cart:{phone} HASH → orders:stream → order:{id}:status (run ./run.sh worker w1!)</div>
  <button onclick="add('masala-dosa',1)">+ Masala Dosa</button><button onclick="add('filter-coffee',1)">+ Filter Coffee</button>
  <button class="alt" onclick="add('masala-dosa',-1)">− Dosa</button><br><button onclick="checkout()">Checkout ▶</button>
  <pre id="cartOut">log in first</pre><ol class="tl" id="timeline"></ol></div>

<div class="card" style="--c:var(--red)"><span class="tag">TRACK B · RATE LIMIT</span><h2>4. Spam the API</h2>
  <div class="hint">5 requests / minute per user. Sliding window (ZSET) vs fixed window (INCR)</div>
  <button onclick="spam('sliding')">Fire 8 (sliding)</button><button class="alt" onclick="spam('fixed')">Fire 8 (fixed)</button>
  <pre id="rlOut"></pre></div>

<div class="card" style="--c:var(--orange)"><span class="tag">TRACK C · LUA ATOMICITY</span><h2>5. ₹1 Biryani flash sale</h2>
  <div class="hint">100 plates, 300 buyers at once. Naive read-check-write vs one Lua script</div>
  <button onclick="sale('naive')">Stampede: naive</button><button onclick="sale('atomic')">Stampede: Lua</button>
  <div class="big" id="saleStat">—</div><pre id="saleOut"></pre></div>

<div class="card" style="--c:var(--violet)"><span class="tag">TRACK D · SORTED SET</span><h2>6. Top restaurants this week</h2>
  <div class="hint">lb:restaurants:{year}-W{week} — ZINCRBY on every delivered order</div>
  <button onclick="board()">Refresh</button><div id="boardOut"></div></div>

<div class="card" style="--c:var(--blue)"><span class="tag">TRACK D · GEO</span><h2>7. Riders near Koramangala</h2>
  <div class="hint">riders:geo + rider:{id}:alive heartbeat</div>
  <input id="km" value="5" style="width:60px"> km <button onclick="nearby()">Search</button>
  <div id="riderOut"></div></div>
</div>

<script>
let token = null;
const $ = id => document.getElementById(id);
const show = (id, x) => $(id).textContent = typeof x === 'string' ? x : JSON.stringify(x, null, 2);
async function api(method, url, body) {
  const h = {'Content-Type': 'application/json'}; if (token) h.Authorization = 'Bearer ' + token;
  const r = await fetch(url, {method, headers: h, body: body ? JSON.stringify(body) : undefined});
  const text = await r.text(); let data; try { data = JSON.parse(text); } catch { data = text; }
  return {status: r.status, data, headers: r.headers};
}
async function otp() { const r = await api('POST', '/auth/otp', {phone: $('phone').value}); show('authOut', {status: r.status, ...r.data});
  if (r.data.devCode) $('code').value = r.data.devCode; }
async function verify() { const r = await api('POST', '/auth/verify', {phone: $('phone').value, code: $('code').value});
  if (r.data.token) token = r.data.token; show('authOut', {status: r.status, ...r.data}); cart(); }
async function me() { const r = await api('GET', '/auth/me'); show('authOut', {status: r.status, ...r.data}); }
async function menu(id) { const t = performance.now(); const r = await api('GET', `/restaurants/${id}/menu`);
  $('menuStat').textContent = `${r.headers.get('X-Cache') || r.status} · ${Math.round(performance.now() - t)} ms`; show('menuOut', r.data); }
async function reprice() { const p = 100 + Math.floor(Math.random() * 60);
  show('menuOut', await api('PUT', '/restaurants/r1/menu/masala-dosa', {price: p})); $('menuStat').textContent = 'price → ₹' + p + ' (cache deleted)'; }
const user = () => $('phone').value;
async function cart() { show('cartOut', (await api('GET', '/cart/' + user())).data); }
async function add(itemId, qty) { show('cartOut', (await api('POST', `/cart/${user()}/items`, {itemId, qty})).data); }
async function checkout() {
  const r = await api('POST', '/checkout'); show('cartOut', {status: r.status, ...r.data});
  if (r.status !== 202) return;
  $('timeline').innerHTML = '';
  const es = new EventSource(r.data.live);
  const addStep = s => { const li = document.createElement('li'); li.className = s; li.textContent = s.replaceAll('_', ' '); $('timeline').appendChild(li); };
  addStep('PLACED');
  es.addEventListener('status', e => { const d = JSON.parse(e.data); if (d.status !== 'PLACED') addStep(d.status);
    if (d.status === 'DELIVERED') { es.close(); board(); } });
}
async function spam(algo) { const u = 'spam-' + Date.now(); const lines = [];
  for (let i = 1; i <= 8; i++) { const r = await api('POST', `/ratelimit-demo?algo=${algo}&user=${u}`);
    lines.push(`#${i} → ${r.status} remaining=${r.headers.get('X-RateLimit-Remaining')}` + (r.status === 429 ? ` retry in ${r.headers.get('Retry-After')}s` : '')); }
  show('rlOut', lines.join('\\n')); }
async function sale(mode) { const id = 'biryani-' + mode; await api('POST', `/sales/${id}/start`, {stock: 100});
  $('saleStat').textContent = 'selling...';
  const results = await Promise.all([...Array(300)].map((_, i) => api('POST', `/sales/${id}/buy?mode=${mode === 'naive' ? 'naive' : 'lua'}`, {userId: 'u' + i})));
  const ok = results.filter(r => r.data.result === 'OK').length; const st = (await api('GET', `/sales/${id}`)).data;
  $('saleStat').textContent = `${ok} sold of 100 ${ok > 100 ? '💥 OVERSOLD' : '✅'}`; show('saleOut', st); }
async function board() { const r = await api('GET', '/leaderboard?top=5');
  if (r.status !== 200) { $('boardOut').textContent = JSON.stringify(r.data); return; }
  const max = Math.max(1, ...r.data.top.map(e => e.orders));
  $('boardOut').innerHTML = r.data.top.map(e => `<div class="bar" style="width:${30 + 70 * e.orders / max}%">#${e.rank} ${e.name} · ${e.orders}</div>`).join('') || '<pre>no orders yet this week</pre>'; }
async function nearby() { const r = await api('GET', `/riders/nearby?lat=12.9340&lng=77.6260&km=${$('km').value}`);
  if (r.status !== 200) { $('riderOut').textContent = JSON.stringify(r.data); return; }
  $('riderOut').innerHTML = r.data.map(x => `<div class="bar" style="width:${Math.max(25, 100 - x.distanceKm * 6)}%">${x.riderId} · ${x.distanceKm.toFixed(2)} km</div>`).join('') || '<pre>nobody nearby</pre>'; }
</script></body></html>
""";
}
