/* Backend Execution Center
 * ------------------------
 * Renders ExecutionEvent rows that the payment backend wrote while doing real
 * work. It does not simulate anything.
 *
 * Contract with the backend (public HTTP only, no shared code):
 *   POST /api/v1/payments                 initiate
 *   GET  /api/v1/payments/{id}/status     poll
 *   GET  /api/v1/execution/stream         SSE, every step as it happens
 *   GET  /api/v1/execution/{txnId}        the full trace, after the fact
 *   GET  /api/v1/execution/outbox         backlog + subscriber count
 *   GET  /api/v1/execution/recovery       self-healing activity
 *
 * The SSE stream is a convenience over durable data: every event is persisted
 * before it is broadcast, so a viewer who arrives late, refreshes, or never
 * connects at all loses nothing. That is also why closing this page cannot
 * affect a payment in flight.
 */

const API = localStorage.getItem('apiBase') || 'http://localhost:8083';
const PSP = localStorage.getItem('pspBase') || 'http://localhost:8082';

/**
 * The bearer token, obtained by logging in to psp-service.
 *
 * Kept in memory only, deliberately. Persisting it in localStorage would make
 * the demo marginally more convenient and would also mean any script on the
 * origin could read it -- the standard XSS-token-theft path. A page that has
 * to be signed in to each time is the honest trade for a page that talks to a
 * money-moving API.
 */
let TOKEN = null;

document.getElementById('apiBase').textContent = API;

// ── Sign-in ────────────────────────────────────────────────────────────
// The orchestrator verifies an RS256 token issued by psp-service. The
// showcase therefore authenticates like any other client -- it cannot simply
// assert an identity any more, which was the point of the change.
['mobile', 'device'].forEach(id => {
  const el = document.getElementById(id);
  const saved = localStorage.getItem(id);
  if (saved) el.value = saved;
  el.addEventListener('change', () => localStorage.setItem(id, el.value));
});

document.getElementById('login').addEventListener('click', signIn);

async function signIn() {
  const mobileNumber = document.getElementById('mobile').value.trim();
  const deviceId = document.getElementById('device').value.trim();
  const mpin = document.getElementById('mpin').value.trim();
  const state = document.getElementById('authState');

  if (!mobileNumber || !deviceId || !mpin) {
    state.textContent = 'mobile, device and MPIN are all required';
    state.className = 'auth-state bad';
    return;
  }

  state.textContent = 'signing in…';
  state.className = 'auth-state';

  try {
    const res = await fetch(PSP + '/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mobileNumber, deviceId, mpin }),
    });
    const data = await res.json();

    if (!res.ok) {
      // psp distinguishes wrong-MPIN from locked-account from MPIN-not-set,
      // so show what it actually said rather than a generic failure.
      state.textContent = (data.errorCode || res.status) + ': ' + (data.message || '');
      state.className = 'auth-state bad';
      return;
    }

    TOKEN = data.accessToken;
    const mins = Math.round((data.expiresIn || 0) / 60);
    state.textContent = 'signed in as ' + data.userId.slice(0, 8) + '… (' + mins + ' min)';
    state.className = 'auth-state ok';
    document.getElementById('authPanel').classList.add('signed-in');
    document.getElementById('run').disabled = false;
    document.getElementById('run').title = '';

  } catch (err) {
    state.textContent = 'psp-service unreachable at ' + PSP;
    state.className = 'auth-state bad';
  }
}

/** Headers for a call to the payment API. */
function authHeaders(extra) {
  return Object.assign({ 'Authorization': 'Bearer ' + TOKEN }, extra || {});
}

// ── The flow graph ─────────────────────────────────────────────────────
// One node per participant. Nodes are keyed by the `component` field the
// backend stamps on every execution event, so the graph cannot drift from
// what the backend actually reports.
const NODES = [
  { id: 'client',                name: 'Client',        role: 'this page' },
  { id: 'payment-orchestrator',  name: 'Orchestrator',  role: 'saga + state machine' },
  { id: 'vpa-service',           name: 'VPA Service',   role: 'payee directory' },
  { id: 'outbox-relay',          name: 'Outbox Relay',  role: 'at-least-once delivery' },
  { id: 'funds-command-handler', name: 'Funds Handler', role: 'executes movements' },
  { id: 'bank-service',          name: 'Bank',          role: 'money-holder + ledger' },
  { id: 'recovery-detector',     name: 'Detector',      role: 'finds stalled payments' },
  { id: 'recovery-worker',       name: 'Recovery',      role: 'reconciles, then decides' },
];

const flowEl = document.getElementById('flow');
NODES.forEach(n => {
  const el = document.createElement('div');
  el.className = 'node';
  el.id = 'node-' + n.id;
  el.innerHTML = `<div class="nname">${n.name}</div>
                  <div class="nrole">${n.role}</div>
                  <div class="nstat" id="stat-${n.id}">idle</div>`;
  flowEl.appendChild(el);
});

function touchNode(component, status, message) {
  const el = document.getElementById('node-' + component);
  if (!el) return;
  el.classList.remove('on', 'ok', 'bad', 'unk', 'busy');
  if (status === 'OK') el.classList.add('ok');
  else if (status === 'FAILED') el.classList.add('bad');
  else if (status === 'TIMEOUT' || status === 'SKIPPED') el.classList.add('unk');
  else el.classList.add('on', 'busy');
  const s = document.getElementById('stat-' + component);
  if (s) s.textContent = (message || status || '').slice(0, 44);
}

function resetFlow() {
  NODES.forEach(n => {
    const el = document.getElementById('node-' + n.id);
    el.className = 'node';
    document.getElementById('stat-' + n.id).textContent = 'idle';
  });
  document.querySelector('#calls tbody').innerHTML = '';
  document.getElementById('states').innerHTML = '';
  document.getElementById('log').innerHTML = '';
  seenStates.clear();
}

// ── State machine column ───────────────────────────────────────────────
const seenStates = new Set();
const UNCERTAIN_STATES = new Set(['UNCERTAIN_DEBIT', 'UNCERTAIN_CREDIT', 'UNCERTAIN_REVERSAL',
                                  'RECONCILING_DEBIT', 'RECONCILING_CREDIT', 'RECONCILING_REVERSAL']);
const BAD_STATES = new Set(['FAILED', 'DEBIT_FAILED', 'CREDIT_FAILED', 'REVERSAL_FAILED', 'MANUAL_REVIEW']);
const DONE_STATES = new Set(['COMPLETED', 'REVERSED']);

function pushState(to, note) {
  const key = to + '|' + (note || '');
  if (seenStates.has(key)) return;
  seenStates.add(key);

  const ol = document.getElementById('states');
  ol.querySelectorAll('li.cur').forEach(li => li.classList.remove('cur'));

  const li = document.createElement('li');
  li.className = 'cur';
  if (UNCERTAIN_STATES.has(to)) li.classList.add('unk');
  else if (BAD_STATES.has(to)) li.classList.add('bad');
  else if (DONE_STATES.has(to)) li.classList.add('done');
  li.innerHTML = `${to}${note ? `<small>${escapeHtml(note)}</small>` : ''}`;
  ol.appendChild(li);
  ol.scrollTop = ol.scrollHeight;
}

// ── Calls table + log ──────────────────────────────────────────────────
function addCall(e) {
  const tb = document.querySelector('#calls tbody');
  const tr = document.createElement('tr');
  tr.className = 'k-' + e.kind;
  tr.innerHTML = `
    <td>${e.seq}</td>
    <td>${escapeHtml(e.component)}</td>
    <td class="kind">${e.kind}</td>
    <td class="op">${escapeHtml(e.operation)}</td>
    <td class="st-${e.status}">${e.status}</td>
    <td class="num">${e.latencyMs != null ? e.latencyMs + 'ms' : ''}</td>`;
  tb.appendChild(tr);
  tr.scrollIntoView({ block: 'nearest' });
}

function addLog(e) {
  const log = document.getElementById('log');
  const div = document.createElement('div');
  let cls = 'ln';
  if (e.status === 'OK') cls += ' ok';
  if (e.status === 'FAILED') cls += ' bad';
  if (e.status === 'TIMEOUT' || e.status === 'SKIPPED') cls += ' unk';
  if (e.kind === 'RECOVERY') cls += ' rec';
  div.className = cls;

  const t = (e.at || '').split('T')[1] || '';
  div.innerHTML = `<span class="t">${t.slice(0, 12)}</span>` +
                  `<span class="c">${escapeHtml(e.component)}</span>` +
                  `<span class="m">${escapeHtml(e.message || e.operation)}</span>`;
  log.appendChild(div);
  log.scrollTop = log.scrollHeight;
}

// ── Event routing ──────────────────────────────────────────────────────
let currentTxn = null;

function handleEvent(e) {
  // Only render the payment being watched. Everything else still happened and
  // is still persisted; it just is not this story.
  if (currentTxn && e.transactionId && e.transactionId !== currentTxn) return;

  addCall(e);
  addLog(e);
  touchNode(e.component, e.status, e.message);

  if (e.kind === 'STATE' && e.operation.includes('->')) {
    const to = e.operation.split('->')[1].trim();
    pushState(to, e.message);
  }
  if (e.kind === 'STATE' && e.operation === 'markUncertain') {
    touchNode('payment-orchestrator', 'TIMEOUT', 'outcome unknown');
  }
}

// ── SSE ────────────────────────────────────────────────────────────────
let es;
function connect() {
  es = new EventSource(API + '/api/v1/execution/stream');

  es.addEventListener('connected', () => {
    document.getElementById('dot').className = 'dot live';
    document.getElementById('connText').textContent = 'streaming';
  });

  es.addEventListener('execution', ev => {
    try { handleEvent(JSON.parse(ev.data)); } catch (_) { /* ignore malformed frame */ }
  });

  es.onerror = () => {
    document.getElementById('dot').className = 'dot dead';
    document.getElementById('connText').textContent = 'backend unreachable';
    // EventSource reconnects on its own. Not retrying by hand is deliberate:
    // a hand-rolled retry loop would hammer a backend that is already down.
  };
}
connect();

// ── Metrics poll ───────────────────────────────────────────────────────
// Two numbers worth watching:
//   outbox pending  - committed messages not yet delivered. Steady near zero
//                     means the relay is keeping up; a rising figure means
//                     state changes nothing else has been told about.
//   open cases      - payments currently under investigation.
async function poll() {
  try {
    const [o, r] = await Promise.all([
      fetch(API + '/api/v1/execution/outbox').then(x => x.json()),
      fetch(API + '/api/v1/execution/recovery').then(x => x.json()),
    ]);
    document.getElementById('outboxPending').textContent = o.pending;
    document.getElementById('openCases').textContent = r.openCases;
  } catch (_) { /* the connection dot already reports backend health */ }
}
poll();
setInterval(poll, 2000);

// ── Scenarios ──────────────────────────────────────────────────────────
const SCENARIOS = {
  happy: {
    simulate: null,
    note: 'Straight-through payment. Watch the debit commit before the credit is even requested — that ordering is what makes compensation possible if the credit later fails.'
  },
  timeout_credit: {
    simulate: 'TIMEOUT_CREDIT',
    note: 'The payer is debited, then the credit request times out. The outcome is UNKNOWN, not failed. The backend refuses to retry (double credit) and refuses to reverse (money created from nothing) — it asks the bank what actually happened, and only then decides.'
  },
  reject_credit: {
    simulate: 'REJECT_CREDIT',
    note: 'The bank refuses the credit outright. The outcome is KNOWN, so no reconciliation is needed: the saga compensates immediately by reversing the debit. Note the reversal is a new ledger posting, not an erasure.'
  },
};

let selected = 'happy';
document.querySelectorAll('.scn').forEach(btn => {
  btn.addEventListener('click', () => {
    if (btn.disabled) return;
    document.querySelectorAll('.scn').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    selected = btn.dataset.scn;
    showBanner(SCENARIOS[selected].note, false);
  });
});
document.querySelector('.scn[data-scn="happy"]').classList.add('active');
showBanner(SCENARIOS.happy.note, false);

function showBanner(text, isError) {
  const b = document.getElementById('banner');
  b.textContent = text;
  b.className = 'banner' + (isError ? ' err' : '');
}

// Remember the VPAs so the page is usable after a refresh.
['payerVpa', 'payeeVpa', 'amount'].forEach(id => {
  const el = document.getElementById(id);
  const saved = localStorage.getItem(id);
  if (saved) el.value = saved;
  el.addEventListener('change', () => localStorage.setItem(id, el.value));
});

document.getElementById('run').addEventListener('click', run);

async function run() {
  const payer = document.getElementById('payerVpa').value.trim();
  const payee = document.getElementById('payeeVpa').value.trim();
  const amount = document.getElementById('amount').value.trim();

  if (!TOKEN) {
    showBanner('Sign in first. The payment API requires a verified token from psp-service.', true);
    return;
  }

  if (!payer || !payee) {
    showBanner('Set payer and payee VPAs first. Run scripts/seed-demo-data.sh to create a pair.', true);
    return;
  }

  const btn = document.getElementById('run');
  btn.disabled = true;
  resetFlow();
  currentTxn = null;

  // A fresh idempotency key per run. It has to come from the client: the
  // server cannot tell a retry from a genuine second payment of the same
  // amount to the same payee, because only the caller knows its own intent.
  const key = crypto.randomUUID();
  document.getElementById('mKey').textContent = key;
  touchNode('client', 'STARTED', 'POST /api/v1/payments');

  // Recorded before the request is sent, not after it returns. The first saga
  // step runs inside the POST, so its execution events reach the stream while
  // the response is still in flight -- pushing INITIATED afterwards would list
  // it below states that came later.
  pushState('INITIATED', 'accepted - processing asynchronously');

  const body = {
    payerVpa: payer, payeeVpa: payee,
    amount: parseFloat(amount), currency: 'INR',
    remarks: 'showcase: ' + selected,
  };
  if (SCENARIOS[selected].simulate) body.simulate = SCENARIOS[selected].simulate;

  try {
    const res = await fetch(API + '/api/v1/payments', {
      method: 'POST',
      headers: authHeaders({
        'Content-Type': 'application/json',
        'Idempotency-Key': key,
      }),
      body: JSON.stringify(body),
    });

    const data = await res.json();
    if (!res.ok) {
      // 401 and 403 are worth distinguishing out loud: one means the token is
      // missing or stale, the other means the token is fine but this user does
      // not own the VPA they are trying to spend from.
      let hint = '';
      if (res.status === 401) hint = ' — sign in again; the token may have expired.';
      if (res.status === 403) hint = ' — that VPA belongs to a different user.';
      showBanner('Rejected (' + res.status + '): '
                 + (data.message || JSON.stringify(data)) + hint, true);
      touchNode('client', 'FAILED', res.status + ' rejected');
      btn.disabled = false;
      return;
    }

    currentTxn = data.transactionId;
    document.getElementById('mTxn').textContent = data.transactionId;
    document.getElementById('mRrn').textContent = data.rrn;
    touchNode('client', 'OK', '202 Accepted');

    // Backfill anything the stream may have delivered before currentTxn was
    // set, then let SSE carry the remainder.
    setTimeout(backfill, 400);
    watchUntilTerminal(data.transactionId, btn);

  } catch (err) {
    showBanner('Could not reach the backend at ' + API + ' — ' + err.message, true);
    touchNode('client', 'FAILED', 'network error');
    btn.disabled = false;
  }
}

async function backfill() {
  if (!currentTxn) return;
  try {
    const events = await fetch(API + '/api/v1/execution/' + currentTxn).then(r => r.json());
    const shown = new Set([...document.querySelectorAll('#calls tbody tr')]
      .map(tr => tr.children[0].textContent));
    events.filter(e => !shown.has(String(e.seq))).forEach(handleEvent);
    document.getElementById('mTrace').textContent = events.length ? events[0].traceId : '–';
  } catch (_) { /* the stream will still carry live events */ }
}

const TERMINAL = new Set(['COMPLETED', 'FAILED', 'DEBIT_FAILED', 'REVERSED', 'MANUAL_REVIEW']);

async function watchUntilTerminal(txnId, btn) {
  // Polling alongside SSE, deliberately. The stream is best-effort delivery of
  // durable rows; the status endpoint is the authority on where the payment
  // actually is. Trusting only the stream would mean a dropped frame leaves
  // the page permanently wrong.
  for (let i = 0; i < 90; i++) {
    await sleep(1000);
    try {
      const s = await fetch(API + '/api/v1/payments/' + txnId + '/status',
        { headers: authHeaders() }).then(r => r.json());
      if (TERMINAL.has(s.currentState)) {
        finish(s.currentState);
        break;
      }
    } catch (_) { /* keep watching */ }
  }
  btn.disabled = false;
  backfill();
}

function finish(state) {
  const verdicts = {
    COMPLETED:     'COMPLETED — money moved exactly once.',
    REVERSED:      'REVERSED — the credit never landed, so the debit was compensated. The payer is whole; both postings remain in the ledger.',
    DEBIT_FAILED:  'DEBIT_FAILED — no money moved. Failing before the debit is always safe.',
    FAILED:        'FAILED — rejected before any money moved.',
    MANUAL_REVIEW: 'MANUAL_REVIEW — the system could not establish the truth and escalated instead of guessing. This is a correct outcome, not a defect.',
  };
  showBanner(verdicts[state] || state, state === 'MANUAL_REVIEW');
}

// ── utils ──────────────────────────────────────────────────────────────
const sleep = ms => new Promise(r => setTimeout(r, ms));

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
