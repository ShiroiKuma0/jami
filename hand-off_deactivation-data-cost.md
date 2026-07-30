# Hand-off — is background deactivation really that expensive, or did we make it so?

**Opened:** 2026-07-30 by 白い熊, to be run later. **Nothing here has been executed yet.**
**Status of the code:** `BACKGROUND_DEACTIVATION_ENABLED = false`
(`jami-android/app/src/main/java/cx/ring/application/JamiApplication.kt`, commit `89c4d6991`).

> Run this before ever re-enabling deactivation, and before treating the "133 MiB/h" figure as fact
> in any future decision.

---

## 1. The claim under test, and why it is suspect

**The claim** (from `hand-off_battery-overnight-test.md` §3b, and quoted repeatedly since): with
deactivation ON, the app cost **~133 MiB/h**, i.e. **~13 MiB per deactivate/restore cycle** at
~10 cycles/hour, because restoring an account re-establishes its proxy subscriptions and *"a proxy
re-subscribe re-downloads the full value set of every key"*.

**白い熊's objection, 2026-07-30:** upstream ships deactivation ON by default in the `withFirebase`
flavor. If it genuinely cost 133 MiB/h, that would be a glaring upstream bug someone would have
noticed. More likely **our own patches inflate it**, or the measurement was tainted.

That objection is well-founded and the figure should not be trusted as it stands, for four reasons:

1. **The per-cycle number was never measured.** 13 MiB is `133 ÷ 10` — a division, not an
   observation. No instrumentation ever attributed bytes to a single restore.
2. **The 133 MiB/h window is heavily confounded.** It was measured on `+148`, a build that also had:
   the **unbounded presence set** (68 permanent proxy listeners, later cut to ≤24/account by
   `e6f32c7be`); the **pre-veto watchdog**, which false-wedged and recovered several times an hour,
   each recovery re-subscribing everything; **no trust-request de-dup**, so an RSA-signing storm was
   running (`patches/jami-trust-request-confirm-once.patch`, +156, measured at 49% of CPU samples);
   and the **`.test` twin still linked**, adding four devices sharing every conversation.
   Every one of those is now fixed or removed. The cost today could be far lower.
3. **Comparable windows were never taken.** ON and OFF were measured on different builds, on
   different days, with different network conditions and different amounts of hand use.
4. **The mechanism is asserted, not traced.** Nobody has followed `setAccountActive(id, false, true)`
   → restore → `DhtProxyClient` re-subscribe in the code to establish whether a full value-set
   re-download is inherent to opendht's proxy protocol, or an artefact of
   `patches/opendht-proxy-subscription-refresh.patch` (SK-SUBREFRESH).

**What the decision rests on.** With the figure as it stands, deactivation costs ~9× more than the
redial churn it would prevent (~14 MiB/h), so it stays off and we accept being the permanent
initiator (see `hand-off_ice-connection-churn.md` §9). **If the real cost is much lower, that trade
inverts** — deactivation would stop us redialling peers that are absent by design, and would stop us
being the only always-on party in every pair.

---

## 2. Part A — code study (agents)

Commission agents with these briefs. Each must answer with `file:line` citations and must
distinguish "the code says X" from "I believe X".

### Agent 1 — is the re-download inherent to stock?

Scope: `daemon/contrib/build-aarch64-linux-android/opendht/src/dht_proxy_client.cpp`,
`daemon/src/jamidht/jamiaccount.cpp`, `daemon/src/manager.cpp`, and the pristine tarball
`daemon/contrib/tarballs/opendht-4.2.0.tar.gz` for comparison.

1. Trace `setAccountActive(id, false, shutdownConnections=true)` → `doUnregister` →
   what happens to the `DhtRunner` / `DhtProxyClient` and its listeners. Is the proxy client
   destroyed, or does it survive?
2. Trace the restore path → `doRegister` → how are listeners re-established? Does the proxy server
   answer a fresh `SUBSCRIBE` with the full value set of each key, or with a delta? Cite
   `dht_proxy_server.cpp` behaviour for `refresh=true` versus a new listener.
3. **Is there any cache that survives deactivation** and makes the restore cheap in stock? Look at
   `OpValueCache` / `op_cache.h` lifetime relative to the proxy client.
4. Quantify from the code: for N listeners with V values each, what is re-downloaded on restore?

### Agent 2 — do OUR patches inflate it?

Scope: the fork's behaviour-changing native patches, especially
`patches/opendht-proxy-subscription-refresh.patch` (SK-SUBREFRESH: confirm interval 15 min → 3 min,
plus a quiet-gated blanket resubscribe) and `patches/opendht-proxy-connect-resilience.patch`, plus
the app-side restore path (`AccountService.restoreProxyAccountsAfterBackground`,
`resubscribeAccountPresence`, and `JamiApplication.onBackgroundPushReceived`).

1. Does SK-SUBREFRESH cause **extra** subscriptions or re-downloads around a deactivate/restore
   cycle that stock would not perform?
2. Does the fork's restore path do anything stock does not — e.g. an extra
   `resubscribeAccountPresence`, an extra `connectivityChanged`, an extra re-register?
3. Was the presence set bounded at the time of the 133 MiB/h measurement? (No — `e6f32c7be` came
   later.) Estimate how much of the cost was the 68-listener set that no longer exists.
4. Is there any fork change that makes a restore re-subscribe MORE keys than stock would?

### Agent 3 — the counterfactual

1. What does upstream's `withFirebase` flavor actually cost per cycle, reasoning from the code?
2. Would a stock user with 4 accounts and 41 conversations see the same? Note upstream's constants:
   `BACKGROUND_DEACTIVATION_DELAY_MS = 5 s`, `PUSH_GRACE_MS = 30 s`,
   `MAX_BACKGROUND_ACTIVE_MS = 10 min`, `NONCALL_RESTORE_COOLDOWN_MS = 3 min`.
3. Is there an upstream throttle we are not benefiting from because of how the hoist into `main` was
   done (`f9025c1a9`)?

---

## 3. Part B — the measurement (30 minutes may not be enough; read this first)

**A 30-minute sample gives ONE hourly-log bucket, which is not a measurement.** The hourly log
writes 30-minute buckets (`data-hourly-log.txt`). Use one of these instead:

- **Preferred:** the in-app live meter — Connection monitor → **Data** → start a session, run the
  window, **Stop and save**. It measures continuously and records the mode label.
- Or set a shorter recording window in Settings → UI fonts & colours → Online recovery.

### Protocol — A/B on the SAME build, SAME day, SAME network

1. Build with `BACKGROUND_DEACTIVATION_ENABLED = true`, everything else at current tip.
2. **Window OFF** — deactivation disabled (current default), phone idle, screen off, WiFi, no
   installs, no adb during the window. 30 min minimum, 60 preferred. Record via the live meter.
3. **Window ON** — flip the flag (a rebuild, so allow 15 min settling after install before starting
   the clock — the install itself re-subscribes everything and would otherwise land inside the
   sample). Same duration, same conditions.
4. From `recovery-log.txt`, count `all accounts asleep` / `accounts awake again` pairs in the ON
   window. **Cost per cycle = (ON bytes − OFF bytes) ÷ cycles.** That is the number nobody has ever
   measured.

### Controls that must hold, or the window is void

- No app install inside either window, and ≥15 min since the last one.
- No network transition (check `net=` in the hourly log, and both windows on the same network).
- No `verified WEDGE` / recovery lines in either window — a recovery re-subscribes everything and
  would be attributed to deactivation.
- Screen off throughout, off charger not required (charging does not affect data).

### Decision criteria

| result | conclusion |
|---|---|
| cost/cycle ≲ 1 MiB | the 133 MiB/h figure was an artefact of the `+148` confounds. **Re-enable deactivation** — it would remove our permanent-initiator role at negligible cost. |
| 1–5 MiB/cycle | genuine but modest. Decide against the ~14 MiB/h churn cost; consider raising the delay/grace constants so it fires rarely. |
| ≳10 MiB/cycle, and Agent 2 finds no fork amplification | the cost is inherent to opendht proxy subscriptions. **Keep deactivation off**, and the original decision stands. |
| ≳10 MiB/cycle, and Agent 2 DOES find fork amplification | fix the amplification first, then re-measure. |

---

## 4. What this feeds

The decision at `hand-off_ice-connection-churn.md` §9 and the swarm-backoff work. If deactivation
turns out to be cheap, the backoff matters less — we would stop chasing absent peers by being absent
ourselves, symmetrically with everyone else, which is what upstream designed.

**Do not re-enable deactivation without this measurement.** It was disabled on evidence
(`89c4d6991`), and it should only be re-enabled on evidence.

---

## 5. Part A RESULT — the three agents reported, 2026-07-30 23:0x

All three read the **pristine `opendht-4.2.0` tarball**, not the corrupt build tree.

### The mechanism is stock, and it is real

- Deactivation sends **no UNSUBSCRIBE**: `stop()` → `cancelAllListeners()` cancels only the local HTTP
  request (pristine `src/dht_proxy_client.cpp:240-257`). The **server keeps the listener 24 h**
  (`proxy::OP_TIMEOUT`), matched on `clientId` = the account id, stable across restarts.
- The restore's first listen is `ListenMethod::SUBSCRIBE`, whose body **omits `refresh`**
  (`fillBody(method == RESUBSCRIBE)`, `:1074`). The server therefore answers with
  `dht_->get(infoHash, …)` — **the complete value set** (`src/dht_proxy_server.cpp:988-1006`).
- **No cache survives**: `OpValueCache` is a member of the destroyed client, and proxy mode has no
  on-disk persistence (`persist_path` is consumed only by `Dht`, which proxy mode never constructs).
- Extra hidden cost: the fresh `OpValueCache` treats every arriving value as new, so the daemon
  **re-verifies every DeviceAnnouncement, CRL and TrustRequest** per restore — crypto, not just bytes.

**But**: stock does a full value-set `get()` on **every push notification** anyway — the push carries
only value *ids* (`dht_proxy_client.cpp:1300-1318`). So deactivation does not add a re-download that
would not otherwise happen; it **bunches K of them at restore** and adds K TLS handshakes.

### Why upstream has not noticed (this answers 白い熊's objection directly)

Per-cycle cost is multiplicative in three dimensions, all in **pristine daemon code**:
∝ account count (one DhtRunner teardown+rebuild, ~5 fixed listens, a device-announce put, a CRL put),
∝ conversation count (`Conversation::bootstrap()` each), ∝ distinct contacts (one presence listen
each — and contacts' CRL history rides on the same key, so these are the fat ones). The optimization
is sound at one account with a few conversations and can still be a net loss at **4 accounts × 41
conversations** without anything being a bug. It also only landed upstream **2026-06-18**, six weeks
before we measured it.

### The hoist did not weaken anything

All six constants are upstream's verbatim; every guard survives in the same order;
`shutdownConnections=true` is upstream's own code. The fork deactivates **less** often than upstream
(the `backgroundSleepSafe` gate; upstream's FCM service re-armed after every push including
expirations). The real change was that before `f9025c1a9` the shipped `withUnifiedPush` flavor had
none of this and accounts never slept: 0/h → push-rate.

### What IS ours

- **The pinned presence set.** `resubscribeAccountPresence` issued unbalanced `trackBuddy(+1)`, so the
  daemon's `untrackBuddy` could never reach zero. The fork did not *invent* listeners — it **pinned
  listeners stock releases** (`conversation.cpp:1009-1053` `startTracking`/`stopTracking`). Upstream's
  only client-side presence path is UI-refcounted with a 5 s grace, so a backgrounded stock client
  holds **zero**. Capped at 24/account by `e6f32c7be`; was unbounded (68) at `+148`.
- **Two unbalanced `subscribeBuddy(…, true)` calls remain**, each permanently pinning one listener:
  `AccountService.kt:1066` (`openConnectionTo`) and `ConnectionMonitorFragment.kt:731`. NOT FIXED.
- **A stale comment overstating our own patch**: `AccountService.kt:984-986` claims SK-SUBREFRESH
  re-subscribes every listener every 3 min → "~1360 requests an hour", "~21 MiB/h floor". That
  describes the pre-2026-07-25 unconditional version. With the 10-minute quiet gate — and every
  listen response body stamping the rx clock — the real ceiling is ~one blanket per 12 min, and zero
  while any inbound exists. **Treat that 21 MiB/h as unverified.** NOT FIXED.

### What is exonerated

- **SK-SUBREFRESH does not double the restore.** The blanket is quiet-gated at 10 min; the confirm
  timer is armed when `searches_` is still empty; the next tick is 3 min away, but at `+148` the
  account lived only ~35 s per cycle, so the tick never arrived. Its only standing cost is the
  15→3 min interval = **two** HTTPS GETs of proxy-info JSON per tick, touching no value sets.
- **The restore path does strictly less than upstream.** `deactivateProxyAccountsForBackground` /
  `restoreProxyAccountsAfterBackground` are byte-identical to upstream; no extra
  `resubscribeAccountPresence`, no extra `connectivityChanged` (we have now *removed* the one upstream
  has), no extra re-register, no duplicate `subscribeBuddy` on restore.
- **connect-resilience** adds no keys (its `restartListeners` path uses `RESUBSCRIBE`, answered `{}`).
  Both daemon in-source patches strictly *reduce* per-restore traffic.

### Where the agents DISAGREE with §1 of this hand-off

§1 reason 2 assumed the 68-listener set was most of the 133 MiB/h. **Agent 2 could not support that**:
on every restore stock *also* re-establishes a large presence set, because `shutdownConnections=true`
leaves every swarm down and `convModule()->bootstrap()` then tracks up to `K = min(N, 3+log₂N)`
members per conversation. The two sets overlap through one refcount. The 68 set was mainly a
**standing** cost, not a per-cycle multiplier.

Two `+148` confounds this hand-off did not list, both plausibly larger than the presence set:
1. **The trust-request confirmation storm** — until `+156`, every re-delivered trust request on an
   already-active contact sent a fresh confirmation, a `putEncrypted` **per device** with an RSA
   sign+encrypt. Trust requests are permanent puts, so every fresh SUBSCRIBE on the inbox key
   re-delivered them all. Profiled at **49 % of CPU samples**. This fired on **every restore**.
2. **False-wedge recoveries** — each ran proxy off → 15 s → proxy on, i.e. a whole extra
   teardown + full re-SUBSCRIBE of every key, per account.

### Code-derived estimate, and the cheaper measurement

Agent 1's formula at today's bounded configuration: `K = 4 × 26 = 104` subscriptions,
≈ **1.1 MB per full restore of all four accounts** — the **"≲1 MiB/cycle"** row of §3's table, not
the "≳10 MiB" row. Parameters are assumed; the formula's structure is from the code.

`patches/opendht-proxy-subscription-diag.patch` already logs `bytes=` per listen request, but only
above a **262144-byte threshold**, so restore-sized subscriptions are invisible. **Dropping that
threshold to 0 measures cost-per-subscription directly, per key** — multiply by K for the per-cycle
DHT-value cost, with no ON build and no contaminated window. It does **not** capture the
conversation-bootstrap/ICE/TLS component, which is why it complements rather than replaces §3's A/B.
