# Hand-off — ICE connection churn, and what it costs

**Opened:** 2026-07-30, out of the battery/data work in `hand-off_battery-overnight-test.md`.
**Status:** ROOT CAUSE FOUND 2026-07-30 evening by a three-agent code audit — see §9. One fix built
and pushed; the decisive step is now a human one (upgrade the peer device).
**Live instrument:** `SK-ICEDIAG` (three committed dhtnet patches — see §6).

> Everything needed is in this file. It exists because the investigation has already outlived two
> conversations and my summaries drifted from the data twice (§7).

---

## 1. The two defects

### a. Dead offers — 2.2 per minute, each wasted

373 of 970 destroyed ICE transports ended in `SESS_READY` having **never negotiated**, with lifetimes
`min=30s median=30s max=60s`. Almost every one lives *exactly* 30 seconds: a fixed timeout, not a
failure. We gather ICE candidates, publish an offer, no peer answers, and 30 s later it is reaped.

Each costs STUN/TURN candidate gathering plus a DHT put, and buys nothing — these never connect, so
suppressing them loses no capability.

### b. Connection cycling — median life 45 s

531 of 970 reached `RUNNING`, i.e. **connected successfully**, and were then torn down. Median
lifetime **45 s**; maximum **7331 s** (2 h). The maximum is the important number: connections *can*
be held for hours, so a 45-second median is a decision being taken somewhere, not a protocol limit.

Every cycle pays a DHT lookup, an ICE negotiation and a TLS handshake. This is the larger data cost
and the harder question — something is choosing to close working links.

---

## 2. The measurements (2026-07-30, `+162`, 170 min, WiFi, mostly idle)

```
1005 created   970 destroyed   35 alive          → 5.9 creations/min
784 connect-out (79%)   214 answer-in (21%)      → WE initiate
14 distinct peer devices; 6 dominate
```

Final state of the 970 destroyed:

| state | n | meaning |
|---|---|---|
| 5 RUNNING | 531 | connected, then closed (median 45 s) |
| 3 SESS_READY | 373 | never negotiated, reaped at exactly 30 s |
| 6 FAILED | 61 | genuinely failed |
| 1 INIT / 4 NEGO | 4 | — |

**Data floor with this churn running:** 11.0, 11.0, 6.5 MiB per 30-min bucket on WiFi (15:02–16:33)
≈ **19 MiB/h**, against **5.3 MiB/h** measured overnight 2026-07-29→30 when the phone was quiet.
The churn is the leading candidate for that 3.5× difference but is **not proven** to cause it.

Earlier cellular sample (`+160`, 187 min): 1019 created / 993 destroyed / ~26 alive, 5.4/min,
bursting to 148 per 10-minute bucket. So the rate is **not** network-specific — it was slightly
*higher* on WiFi.

---

## 3. ⚠ Corrections — claims made earlier today that are WRONG

Anyone reading the earlier conversation or `hand-off_battery-overnight-test.md` should discount these:

- **`pj_ice_strans_state` was read one slot off.** The enum is
  `0 NULL, 1 INIT, 2 READY, 3 SESS_READY, 4 NEGO, 5 RUNNING, 6 FAILED`. Everything reported before
  ~17:00 assumed `4=RUNNING, 5=FAILED`.
- **"98% of spinning transports are FAILED zombies" — FALSE.** That reading was `410 state=5` = 410
  **RUNNING**. The transports spinning at 100 Hz were *live connections*.
- **"The throttle took CPU from 174% to 14%" — NOT SUPPORTED.** The throttle only fires on genuinely
  FAILED transports and engaged 28 times; 28 threads at ~1% cannot explain a 160-point drop. WiFi
  returned during that window, which was flagged as a confound and then argued past. The throttle is
  harmless and correct in principle (it compares against the symbol, not my mistaken number) but its
  benefit is unmeasured.
- **"Transports leak" — FALSE.** 970 of 1005 were destroyed; `alive` sits at 26–43.
- **What burned 174% of a core on cellular is therefore UNEXPLAINED.** Best current guess: ~40 live
  transports spinning in the pjnath unhandled-event path. Not established.

---

## 4. What is actually established

- Creation rate ~5.4–5.9/min sustained, bursting to ~15/min, on both WiFi and cellular.
- 79% of sessions are initiated by us, concentrated on 6 of 14 known devices.
- Over half of all sessions connect successfully, then close with a 45 s median life.
- 373 sessions per 170 min never negotiate at all and die on a 30 s timeout.
- Idle data with this running is ~19 MiB/h vs ~5.3 MiB/h when quiet.

## 5. The open question

**Why is a connection requested, and why is it closed?** Specifically:

1. What triggers `connectDevice` ~4.6 times a minute outbound (784 / 170 min)?
2. What closes a `RUNNING` connection after ~45 s — an idle timeout, a per-conversation channel
   lifecycle, or the multi-account arrangement (4 accounts in one daemon)?
3. Are the 373 dead offers aimed at devices that no longer exist (stale device list), or at live
   devices too busy/asleep to answer?

---

## 6. The instrument

Three committed dhtnet patches, applied **in this order** (each one's diff context includes the
previous), all re-applied per build — see `CLAUDE.md` and the canonical block in
`.claude/skills/jami-build/SKILL.md`:

| patch | guard | what it logs |
|---|---|---|
| `patches/dhtnet-ice-transport-diag.patch` | `skIceCreated` | `create` / `destroy` with running totals; `spin` with ICE state every ~2000 iterations |
| `patches/dhtnet-throttle-failed-ice-transports.patch` | `SK_ICE_FAILED_POLL_MS` | throttles a FAILED transport's poll loop to 250 ms after a 5 s grace (behaviour change; benefit unmeasured) |
| `patches/dhtnet-ice-churn-diag.patch` | `SK_CM` | `initiate connect-out\|answer-in device=…`; peer on create; final ICE state on destroy |

All log at ERROR level via `__android_log_print`, because the daemon leaves opendht's logger null
unless `dhtLogLevel > 0`. Read with `adb shell logcat -d --pid=$(pidof shiroikuma.jami) -s SK-ICEDIAG`.

---

## 7. Method rules adopted after today's errors

1. **Check a symbolic constant against its header before interpreting any logged number.** The enum
   error cost a patch built on a false premise.
2. **Two independent windows before calling anything a steady state.** Two profiles taken minutes
   after an install were reported as steady state; both were transients.
3. **Name the confound and let it stand.** WiFi returning mid-window was flagged and then discounted
   because the result was attractive.
4. **A launch is the only proof the app starts.** Dex-string checks show code is present, never that
   it runs — two crash-on-start builds reached the phone on 2026-07-30.

---

## 8. State of the tree

`origin/custom` at `f075058e9` plus counter bumps. Everything above is committed; nothing is
outstanding. The battery/data work that produced this is in
`hand-off_battery-overnight-test.md` — its §3b measurement and §8 backlog remain valid, but its CPU
conclusions are superseded by §3 here.


---

## 9. ROOT CAUSE — the watchdog phase-locks two devices (2026-07-30, three-agent audit)

### The mechanism

`forceReconnectAccount()` / `forceReconnectAllAccounts()` → `JamiService.sendRegister(id, false)` →
the daemon takes the `not isEnabled()` branch of `doUnregister` (`jamiaccount.cpp:2757`) and runs
`shutdownConnections()`, which **destroys every peer TLS socket on that account**. The remote's
`read()` returns 0 and it records `peer-eof`. **That is the 84/92 signature** — a clean deliberate
remote teardown, and in this fork it has essentially one cause.

It is self-sustaining between two devices running the fork:

```
A quiet 2.5 min (DEAF_LIMIT halved in proxy mode) → probe (unanswerable) → strike 2
  → forceReconnectAccount → shutdownConnections
  → B sees peer-eof → 5 SwarmManagers removeNode → maintainBuckets → ~8 reconnects, NO BACKOFF
  → A re-registers 1.5 s later and RE-ANNOUNCES PRESENCE
  → B's per-account deafness clock resets to zero
  → B falls quiet from that instant → 2.5 min → … → B tears down → A sees peer-eof → …
```

Each device's "recovery" is the other's silence. Nothing breaks the cycle except real third-party
inbound traffic.

### Rate by build

| build | watchdog | teardowns/hour |
|---|---|---|
| ~2026-07-16 (`20260706-01+N`, watchdog `dd22ce627`) | no probe, no strikes, no ledger, no stand-down; `conn==0 && >2min` → unconditional `fullRecover` every 60 s tick, plus an isUser self-match bug making every 1:1 conversation read as "stuck" | **up to 60** |
| 2026-07-29 (`b86af68f4`) | probe + strikes, no proxy-liveness veto | ~3.5 |
| 2026-07-30 (`f8d09dcd5`+) | proxy-liveness veto | ~0 on a healthy proxy |

One to two orders of magnitude. A peer on the old build accounts for the measured rate on its own.

### The bug this fork shipped, and fixed

`askProxyBeforeRecovering`'s middle branch — the one with POSITIVE evidence of health ("the proxy
answered") — called `fullRecover(proxySuspect = false)` and logged it as "re-registering only".
That is a **global teardown of all four accounts on proof that nothing was wrong**. Fixed: the
branch now does nothing and only counts a strike, so a genuinely dead subscription still escalates
on the next round.

The log wording was the reason this went unseen through three separate analyses on 2026-07-30:
"recovering WITHOUT touching the proxy" reads as the gentle path. What that branch spares is the
DHT proxy client and its value re-download — **not** the peer connections. The log lines now say
"RE-REGISTERING — drops every peer socket on every account".

### Exonerated by audit (do not re-suspect without new evidence)

- **All five behaviour-changing native patches**: opendht connect-resilience, opendht
  subscription-refresh, dhtnet LAN-interface (it is not even on the ICE path — `getHostName()` is
  used only by UPnP/NAT-PMP), dhtnet UPnP breaker, pjproject epoll eviction (confirmed v2 form,
  both call sites gated on `EPOLLERR`/`EPOLLHUP`). None can drop a live connection; none has a
  mutual-amplification path. Verified against pristine tarballs, with binary provenance checked.
- **The four SK-ICEDIAG patches** are behaviour-neutral (log-only).
- **Bounded presence tracking** and the shared connection poll are not connection-churn sources —
  though bounded presence does make false deafness *more* likely, by reducing presence announces.

### Still open

1. **`JamiApplication.kt:305`** — `connectivityChanged(true)` on every message push. Fires a beacon
   with a **3 s kill deadline** on every socket across all four accounts, and re-maintains every
   conversation's buckets. Fork-local to the shipped flavor since `+143`. Its premise (rebuild
   sockets torn down in doze) expired when background deactivation was disabled. NOT YET CHANGED.
2. **No backoff on the swarm reconnect path** — one teardown fans out to ~8 immediate `tryConnect`
   calls. The git-clone path beside it has proper exponential backoff to 12 h.
3. **The corrupt opendht build tree** — `connectDeadlineFired` applied SIX times, with `.rej`/`.orig`
   leftovers; it cannot compile. Invisible only because the `.opendht` stamp predates the damage.
   The build guard greps for that same marker, so it will keep reporting "already applied". Fix:
   `rm -rf` the opendht build dir and its stamps.
4. **Why we miss 3-second deadlines.** Both the beacon path and the upstream SIP `channelTimeout`
   require someone to fail a 3 s deadline. We measured zero of either on our side but 84 `peer-eof`,
   so the far end is killing. Our 12–30% of a core in the pjnath spin is a plausible reason we are
   slow to answer — which would make CPU and churn the same problem. NOT ESTABLISHED.

### The decisive next step is not code

Upgrade the peer device to a build with the proxy-liveness veto, then re-run a 10-minute
`SK-ICEDIAG` window. Prediction on the record: `peer-eof` falls sharply, connection lifetimes rise
well above the 45 s median, CPU follows the transport count down. If it does not, the
bilateral-watchdog theory is wrong.
