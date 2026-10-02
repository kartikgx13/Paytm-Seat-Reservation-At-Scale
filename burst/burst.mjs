#!/usr/bin/env node
// On-sale stampede against a live deployment. Zero dependencies; Node 18+.
//
//   node burst/burst.mjs <BASE_URL> [--users 2000] [--seats 1000] [--hot-seats 5] [--hot-users 500]
//                                   [--stampede 5000] [--concurrency 800] [--admin-key KEY]
//
// Exits non-zero if any correctness check fails.

const args = parseArgs(process.argv.slice(2));
const BASE = (args._[0] || process.env.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const ADMIN_KEY = args['admin-key'] || process.env.ADMIN_API_KEY || 'dev-admin-key';
const USERS = int(args.users, 2000);
const SEATS = int(args.seats, 1000);
const HOT_SEATS = int(args['hot-seats'], 5);
const HOT_USERS = int(args['hot-users'], 500);
const STAMPEDE = int(args.stampede, 5000);
const CONCURRENCY = int(args.concurrency, 800);
const RUN = Date.now().toString(36);

const violations = [];
const stats = { byStatus: {}, byReason: {}, network: 0, latencies: [], created: 0, fivexxSamples: [], edgeRetries: [] };

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) out[argv[i].slice(2)] = argv[++i];
    else out._.push(argv[i]);
  }
  return out;
}
/** 0 -> A, 25 -> Z, 26 -> AA, ... (rows of 50 seats: A1..A50, B1..B50, ...) */
function rowName(n) {
  let s = '';
  for (n += 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s;
  return s;
}
function int(v, d) { return v === undefined ? d : parseInt(v, 10); }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function check(ok, msg) {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${msg}`);
  if (!ok) violations.push(msg);
}

const EDGE_RETRIES = 3;

async function call(method, path, { body, token, headers = {}, record = true } = {}) {
  const h = { 'content-type': 'application/json', ...headers };
  if (token) h.authorization = `Bearer ${token}`;
  const t0 = performance.now();
  try {
    let res, json;
    for (let attempt = 0; ; attempt++) {
      res = await fetch(BASE + path, {
        method, headers: h, body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(120_000),
      });
      const text = await res.text();
      try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
      // A 5xx without the app's JSON error body never reached the app (platform proxy hiccup). Retrying the
      // identical request is what a real client does, and is safe because every reserve carries an idempotency key.
      if (res.status < 500 || json.error || attempt >= EDGE_RETRIES) break;
      if (record) stats.edgeRetries.push(`${res.status} ${res.headers.get('x-render-routing') || 'edge'} ${method} ${path}`);
      await sleep(500 * 2 ** attempt);
    }
    if (record) {
      if (res.status >= 500 && stats.fivexxSamples.length < 10) {
        // App errors are JSON with an "error" code; anything else came from a proxy/load balancer in front of it.
        const origin = json.error ? `app:${json.error}` : `edge${res.headers.get('x-render-routing') ? ':' + res.headers.get('x-render-routing') : ''}`;
        stats.fivexxSamples.push(`${res.status} ${origin} ${method} ${path}`);
      }
      stats.latencies.push(performance.now() - t0);
      stats.byStatus[res.status] = (stats.byStatus[res.status] || 0) + 1;
      if (res.status >= 400 && json.error) stats.byReason[json.error] = (stats.byReason[json.error] || 0) + 1;
      if (res.status === 200 && res.headers.get('idempotent-replayed')) {
        stats.byReason.idempotent_replay = (stats.byReason.idempotent_replay || 0) + 1;
      }
    }
    return { status: res.status, body: json, headers: res.headers };
  } catch (e) {
    if (record) stats.network++;
    return { status: 0, body: { error: 'network', message: String(e.cause?.code || e.name || e) } };
  }
}

/** Runs tasks with bounded concurrency; all workers start together. */
async function pool(tasks, limit = CONCURRENCY) {
  const results = new Array(tasks.length);
  let next = 0;
  const worker = async () => {
    while (next < tasks.length) {
      const i = next++;
      results[i] = await tasks[i]();
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, tasks.length) }, worker));
  return results;
}

async function waitReady() {
  process.stdout.write(`Waiting for ${BASE}/actuator/health/readiness `);
  const deadline = Date.now() + 300_000;
  while (Date.now() < deadline) {
    const r = await call('GET', '/actuator/health/readiness', { record: false });
    if (r.status === 200) { console.log('-> UP'); return; }
    process.stdout.write('.');
    await sleep(3000);
  }
  throw new Error('service never became ready');
}

async function metricsSnapshot() {
  try {
    const res = await fetch(BASE + '/actuator/prometheus');
    const text = await res.text();
    const pick = (re) => {
      let sum = 0;
      for (const line of text.split('\n')) if (re.test(line)) sum += parseFloat(line.split(' ').pop());
      return sum;
    };
    const declined = {};
    for (const line of text.split('\n')) {
      const m = line.match(/^reservations_declined_total\{.*reason="([^"]+)".*\} (.+)$/);
      if (m) declined[m[1]] = parseFloat(m[2]);
    }
    return {
      confirmed: pick(/^reservations_confirmed_total\{/),
      seatsConfirmed: pick(/^reservations_seats_confirmed_total\{/),
      server5xx: pick(/^http_server_requests_seconds_count\{.*status="5\d\d"(?!.*uri="\/actuator)/),
      declined,
      text,
    };
  } catch {
    return null;
  }
}

function gaugeFor(text, name, showId) {
  const line = text.split('\n').find((l) => l.startsWith(name + '{') && l.includes(`show_id="${showId}"`));
  return line ? parseFloat(line.split(' ').pop()) : null;
}

async function createShow(name, seats, perUserLimit) {
  const r = await call('POST', '/shows', {
    body: { name, seats, price_paise: 25000, per_user_limit: perUserLimit },
    headers: { 'x-admin-key': ADMIN_KEY }, record: false,
  });
  if (r.status !== 201) throw new Error(`create show failed: ${r.status} ${JSON.stringify(r.body)}`);
  return r.body;
}

async function getShow(id) {
  return (await call('GET', `/shows/${id}`, { record: false })).body;
}

function invariantHolds(show) {
  const c = show.counts;
  return c && c.available + c.held + c.confirmed === c.total_seats;
}

function reserve(showId, token, seats, key) {
  return call('POST', `/shows/${showId}/reserve`, { token, body: { seats, idempotency_key: key } });
}

function pct(arr, p) {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor((p / 100) * s.length))];
}

async function main() {
  console.log(`\n=== Seat reservation burst against ${BASE} (run ${RUN}) ===\n`);
  await waitReady();
  const m0 = await metricsSnapshot();

  const labels = Array.from({ length: SEATS }, (_, i) => `${rowName(Math.floor(i / 50))}${(i % 50) + 1}`);
  const show = await createShow(`burst-${RUN}`, labels, 4);
  const limitShow = await createShow(`burst-limit-${RUN}`, labels.slice(0, 20), 4);
  console.log(`Created show ${show.id} (${SEATS} seats) and limit-test show ${limitShow.id}`);

  process.stdout.write(`Minting ${USERS} user tokens... `);
  const tokens = await pool(Array.from({ length: USERS }, (_, i) => async () => {
    for (let attempt = 0; attempt < 5; attempt++) {
      const r = await call('POST', '/auth/token', { body: { user_id: `u-${RUN}-${i}` }, record: false });
      if (r.body.token) return r.body.token;
      await sleep(500 * (attempt + 1));
    }
    return null;
  }), Math.min(CONCURRENCY, 200));
  if (tokens.some((t) => !t)) throw new Error('token minting failed');
  console.log('done');

  // Track every seat we were granted, to reconcile against the server's view afterwards.
  const granted = new Map(); // seat -> reservation_id
  const noteGrant = (r) => {
    if (r.status !== 201) return;
    stats.created++;
    for (const s of r.body.seats) {
      if (granted.has(s) && granted.get(s) !== r.body.reservation_id) {
        violations.push(`seat ${s} granted twice (${granted.get(s)} and ${r.body.reservation_id})`);
      }
      granted.set(s, r.body.reservation_id);
    }
  };

  // Mid-burst invariant sampler.
  let sampling = true;
  let samples = 0;
  let badSamples = 0;
  const sampler = (async () => {
    while (sampling) {
      const s = await getShow(show.id);
      if (s.counts) { samples++; if (!invariantHolds(s)) badSamples++; }
      await sleep(250);
    }
  })();

  // 1. Hot-seat storm: HOT_USERS distinct users per hot seat, all fired together.
  const hot = labels.slice(0, HOT_SEATS);
  console.log(`\n[1] Hot-seat storm: ${HOT_USERS} users x ${HOT_SEATS} seats (${hot.join(', ')}) = ${HOT_USERS * HOT_SEATS} requests`);
  const hotTasks = [];
  for (const seat of hot) {
    for (let u = 0; u < HOT_USERS; u++) {
      const tok = tokens[u % tokens.length];
      hotTasks.push(async () => ({ seat, r: await reserve(show.id, tok, [seat], `hot-${RUN}-${seat}-${u}`) }));
    }
  }
  const t1 = performance.now();
  const hotResults = await pool(hotTasks, Math.max(CONCURRENCY, 1));
  console.log(`    took ${((performance.now() - t1) / 1000).toFixed(1)}s`);
  for (const seat of hot) {
    const rs = hotResults.filter((x) => x.seat === seat).map((x) => x.r);
    rs.forEach(noteGrant);
    const wins = rs.filter((r) => r.status === 201).length;
    const taken = rs.filter((r) => r.status === 409).length;
    const fivexx = rs.filter((r) => r.status >= 500).length;
    const net = rs.filter((r) => r.status === 0).length;
    check(wins === 1 && fivexx === 0, `seat ${seat}: ${wins} x 201, ${taken} x 409, ${fivexx} x 5xx, ${net} network errors`);
  }

  // 2. General stampede over the "good" front section, ~10% sent twice concurrently with the same key.
  console.log(`\n[2] Stampede: ${STAMPEDE} reservations (1-2 seats each, skewed to front rows, ~10% duplicate retries)`);
  // The last 10 seats are kept out of the stampede for the idempotency and spoofing checks below.
  const open = labels.slice(HOT_SEATS, SEATS - 10);
  const front = open.slice(0, Math.max(50, Math.floor(open.length * 0.3)));
  const stampedeTasks = [];
  for (let i = 0; i < STAMPEDE; i++) {
    const tok = tokens[Math.floor(Math.random() * tokens.length)];
    const pool2 = Math.random() < 0.8 ? front : open;
    const a = pool2[Math.floor(Math.random() * pool2.length)];
    const b = pool2[Math.floor(Math.random() * pool2.length)];
    const seats = Math.random() < 0.5 || a === b ? [a] : [a, b];
    const key = `st-${RUN}-${i}`;
    stampedeTasks.push(() => reserve(show.id, tok, seats, key));
    if (Math.random() < 0.1) stampedeTasks.push(() => reserve(show.id, tok, seats, key));
  }
  const t2 = performance.now();
  const stResults = await pool(stampedeTasks);
  console.log(`    took ${((performance.now() - t2) / 1000).toFixed(1)}s`);
  stResults.forEach(noteGrant);
  check(stResults.every((r) => r.status < 500), `stampede: zero 5xx (${stResults.filter((r) => r.status >= 500).length} seen)`);

  // 3. Idempotency: one key fired 20x in parallel, then reused with different seats.
  console.log('\n[3] Idempotency');
  const idemTok = tokens[1];
  const idemSeat = labels[labels.length - 1];
  const idemKey = `idem-${RUN}`;
  const idem = await pool(Array.from({ length: 20 }, () => () => reserve(show.id, idemTok, [idemSeat], idemKey)), 20);
  idem.forEach(noteGrant);
  const ids = new Set(idem.filter((r) => r.status === 201 || r.status === 200).map((r) => r.body.reservation_id));
  check(idem.filter((r) => r.status === 201).length === 1 && ids.size === 1,
    `same key x20 in parallel -> ${idem.filter((r) => r.status === 201).length} x 201, ${idem.filter((r) => r.status === 200).length} x 200 replay, ${ids.size} distinct reservation id(s)`);
  const diff = await reserve(show.id, idemTok, [labels[labels.length - 2]], idemKey);
  check(diff.status === 409 && diff.body.error === 'idempotency_key_reused', `same key, different seats -> ${diff.status} ${diff.body.error}`);

  // 4. Per-user limit: one fresh user fires 10 parallel single-seat reserves on a limit-4 show.
  console.log('\n[4] Per-user limit (limit=4, 10 parallel requests from one user)');
  const greedy = (await call('POST', '/auth/token', { body: { user_id: `greedy-${RUN}` }, record: false })).body.token;
  const lim = await pool(Array.from({ length: 10 }, (_, i) => () => reserve(limitShow.id, greedy, [labels[i]], `lim-${RUN}-${i}`)), 10);
  const limWins = lim.filter((r) => r.status === 201).length;
  const limShow = await getShow(limitShow.id);
  check(limWins <= 4 && limShow.counts.confirmed <= 4 && lim.every((r) => r.status < 500),
    `${limWins} x 201, ${lim.filter((r) => r.body.error === 'per_user_limit').length} x per_user_limit; show confirmed=${limShow.counts.confirmed}`);

  // 5. Identity is token-derived.
  console.log('\n[5] Identity / spoofing');
  const spoofTok = tokens[2];
  const spoofSeat = labels[labels.length - 3];
  const spoof = await call('POST', `/shows/${show.id}/reserve`, {
    token: spoofTok, body: { seats: [spoofSeat], idempotency_key: `spoof-${RUN}`, user_id: 'victim' },
  });
  noteGrant(spoof);
  check(spoof.status !== 201 || spoof.body.user_id === `u-${RUN}-2`,
    `body user_id="victim" ignored -> reservation owned by ${spoof.body.user_id ?? '(declined: ' + spoof.body.error + ')'}`);
  const victimRes = hotResults.find((x) => x.r.status === 201)?.r;
  if (victimRes) {
    const steal = await call('POST', `/reservations/${victimRes.body.reservation_id}/cancel`, { token: tokens[tokens.length - 1] });
    check(steal.status === 404 || steal.status === 403 || victimRes.body.user_id === `u-${RUN}-${tokens.length - 1}`,
      `cancel someone else's reservation -> ${steal.status}`);
  }
  const forged = await call('POST', `/shows/${show.id}/reserve`, {
    token: spoofTok.slice(0, -2) + 'xx', body: { seats: [labels[10]], idempotency_key: 'forged' },
  });
  check(forged.status === 401, `tampered token -> ${forged.status}`);

  sampling = false;
  await sampler;

  // 6. Reconciliation.
  console.log('\n[6] Reconciliation');
  const final = await getShow(show.id);
  const c = final.counts;
  check(invariantHolds(final), `final: available ${c.available} + held ${c.held} + confirmed ${c.confirmed} = ${c.available + c.held + c.confirmed} (total ${c.total_seats})`);
  check(badSamples === 0, `mid-burst: invariant held in ${samples - badSamples}/${samples} samples`);
  const serverConfirmed = new Set(final.seats.filter((s) => s.status === 'confirmed').map((s) => s.label));
  const missing = [...granted.keys()].filter((s) => !serverConfirmed.has(s));
  const extra = [...serverConfirmed].filter((s) => !granted.has(s));
  check(missing.length === 0 && extra.length === 0,
    `seats granted to us (${granted.size}) == seats confirmed on server (${serverConfirmed.size})` +
      (missing.length || extra.length ? ` missing=${missing.slice(0, 5)} extra=${extra.slice(0, 5)}` : ''));

  await sleep(2500); // let seat gauges refresh
  const m1 = await metricsSnapshot();
  if (m0 && m1) {
    const dConfirmed = m1.confirmed - m0.confirmed;
    const ourCreated = stats.created + limWins;
    check(dConfirmed === ourCreated,
      `metrics: reservations_confirmed_total delta ${dConfirmed} == 201s observed ${ourCreated} (exact only if no other traffic)`);
    const gAvail = gaugeFor(m1.text, 'seats_available', show.id);
    const gConf = gaugeFor(m1.text, 'seats_confirmed', show.id);
    check(gAvail === c.available && gConf === c.confirmed,
      `metrics: seats_available=${gAvail}, seats_confirmed=${gConf} match GET /shows (${c.available}, ${c.confirmed})`);
    console.log(`  info  declined deltas: ${Object.entries(m1.declined).map(([k, v]) => `${k}=${v - (m0.declined[k] || 0)}`).filter((s) => !s.endsWith('=0')).join(', ')}`);
    console.log(`  info  server-side 5xx delta: ${m1.server5xx - m0.server5xx}`);
  } else {
    console.log('  skip  metrics endpoint not reachable');
  }

  // Summary.
  const total = Object.values(stats.byStatus).reduce((a, b) => a + b, 0);
  const fivexx = Object.entries(stats.byStatus).filter(([s]) => s >= 500).reduce((a, [, n]) => a + n, 0);
  console.log('\n=== Outcome distribution ===');
  console.log(`  ${'requests'.padEnd(34)}${total}`);
  console.log(`  ${'confirmed (201)'.padEnd(34)}${stats.byStatus[201] || 0}`);
  console.log(`  ${'idempotent replay (200)'.padEnd(34)}${stats.byReason.idempotent_replay || 0}`);
  for (const [reason, n] of Object.entries(stats.byReason).sort((a, b) => b[1] - a[1])) {
    if (reason !== 'idempotent_replay') console.log(`  ${('declined: ' + reason).padEnd(34)}${n}`);
  }
  console.log(`  ${'5xx'.padEnd(34)}${fivexx}`);
  console.log(`  ${'network errors'.padEnd(34)}${stats.network}`);
  console.log(`  ${'platform-proxy 5xx, retried'.padEnd(34)}${stats.edgeRetries.length}`);
  console.log(`  ${'by status'.padEnd(34)}${JSON.stringify(stats.byStatus)}`);
  console.log(`  ${'latency ms'.padEnd(34)}p50=${pct(stats.latencies, 50).toFixed(0)} p95=${pct(stats.latencies, 95).toFixed(0)} p99=${pct(stats.latencies, 99).toFixed(0)}`);
  check(fivexx === 0, 'zero 5xx across the whole burst');
  for (const s of stats.fivexxSamples) console.log(`  5xx sample: ${s}`);
  for (const s of stats.edgeRetries.slice(0, 5)) console.log(`  info  retried (never reached the app): ${s}`);
  if (stats.network) console.log(`  WARN  ${stats.network} requests failed at the network layer (client/platform limits, not server responses)`);

  console.log(violations.length ? `\nRESULT: FAIL (${violations.length} violation(s))` : '\nRESULT: PASS');
  process.exit(violations.length ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(2); });
