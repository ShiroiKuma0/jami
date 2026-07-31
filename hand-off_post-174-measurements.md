# Hand-off — the three measurements outstanding after `+174`

**Opened:** 2026-07-31 by 白い熊, to be run later after a break. Nothing here is started.
**Phone state at hand-off:** running `20260717-01+174`, installed ~09:15, on WiFi.
**Repo state:** `origin/custom` at `c4f51b73f`, daemon gitlink pinned, working tree clean.

Run the items **in the order given** — 1 is time-sensitive (it decides whether a shipped behaviour
change stays), 3 is opportunistic, 2 needs 白い熊 at the keyboard.

---

## 0. Read this first — it will bite you otherwise

- **The logcat buffer was enlarged to 16 MiB per buffer** (`adb shell logcat -b all -G 16M`) on
  2026-07-31 09:53, because EMUI's `libdnetwork` floods it — 2528 of every 4000 lines — and at the
  stock 256 KiB the buffer spanned barely **one minute**. **This does NOT survive a reboot.**
  Re-apply it before any measurement, and verify with `logcat -g`.
- **The daemon is now asleep most of the time** (16 % awake measured), so it logs almost nothing at
  rest. Sample **shortly after** an `accounts awake again` line, not at random.
- All the app's own state is in files that do not depend on logcat, and those are the primary
  source: `/sdcard/Android/data/shiroikuma.jami/files/` → `recovery-log.txt`,
  `data-hourly-log.txt`, `presence-map.txt`, `watchdog-incidents.log`.
- Standing rules that still apply: `adb` always with `dangerouslyDisableSandbox`, disconnect at the
  end of every batch, never `adb install` — 白い熊 installs manually.

---

## 1. FIRST — does background deactivation stay on?

It was re-enabled in `+172` (shipped in `+174`) on a **modelled** restore cost of 1–2.5 MiB that has
never been observed. The revert criteria are written in the code beside the flag
(`JamiApplication.kt`, `BACKGROUND_DEACTIVATION_ENABLED`). This item closes that out.

### Already measured, 2026-07-31 09:16–09:47 (31 min, first half hour after install)

| | result |
|---|---|
| duty cycle | 26 min asleep / 5 min awake = **16 % awake** — PASSES (threshold ~20 %) |
| sleep lengths | 5, 1, 8, 12+ min — lengthening, i.e. the 10-min cooldown is starting to govern |
| data | 7.7 then 11.9 MiB per 30-min bucket = 15–24 MiB/h, **but both include the install, startup burst and four restores** |

Against the pre-change reference: 77 % awake predicted with the grace leak; ~90 MiB/h measured
2026-07-30 with the screen on, ~15 MiB/h on a quiet asleep bucket.

### Still needed

**(a) Restore cost per cycle — the number that decides it.**
For each `accounts awake again` in `recovery-log.txt`, sum the `bytes=` of the
`SK-PROXYDIAG … listen req … ENDED … bytes=` burst that follows it. That is the restore cost, which
the diag can now see at all because `patches/opendht-push-refetch-hardening.patch` dropped the byte
threshold from 262144 to 0.

```
adb -s <dev> shell "logcat -d --pid=\$(pidof shiroikuma.jami) -s SK-PROXYDIAG" \
  | grep -oE "listen req #[0-9]+ ENDED code=[0-9]+ aborted=[0-9] bytes=[0-9]+" \
  | awk '{split($NF,b,"="); s+=b[2]; n++} END{printf "%d requests, %.2f MiB\n", n, s/1048576}'
```

> **If > ~4 MiB per restore → set `BACKGROUND_DEACTIVATION_ENABLED = false` and rebuild.**

**(b) Push classification.**

```
adb -s <dev> shell "logcat -d --pid=\$(pidof shiroikuma.jami) -s SK-PROXYDIAG" \
  | grep -oE "push key=[0-9a-f]+ ids=[0-9,]* pt=[^ ]*" | grep -oE "pt=[^ ]*" | sort | uniq -c | sort -rn
```

Empty `pt=` is speculative swarm noise (JamiAccount forces `connType=""` on that path) and is what
the cooldown is meant to gate. Non-empty means a real delivery: `application/im-gitmessage-id/…`,
`MIME_TYPE_GIT`, `audioCall`/`videoCall`, `sync`.

> **If > ~20 % classify as call/message/sync, the cooldown never governs** — the duty cycle will
> creep back up and the win evaporates. Re-check the duty cycle over a longer window before
> trusting the 16 %.

**(c) A clean data rate.** One 30-min bucket in `data-hourly-log.txt` with **no** install, no
reboot, screen off throughout. Compare against the 2026-07-30 references above. Watch the `mode=`
label: it now shows `/pref=…` **only on divergence**, so a plain `mode=proxy` is the normal case.

**(d) Duty cycle over hours, not 31 minutes.**

```
adb -s <dev> shell "grep -E 'all accounts asleep|accounts awake again' \
  /sdcard/Android/data/shiroikuma.jami/files/recovery-log.txt | tail -40"
```

### Decision

| outcome | action |
|---|---|
| restore ≲ 4 MiB, awake < 20 %, noise-dominated pushes | **keep it on.** Record the measured restore cost in the code comment, replacing the 1–2.5 MiB estimate. |
| restore > 4 MiB | flip the flag false, rebuild, and note the measured figure |
| awake fraction creeping up | find what is refreshing the grace window; `lastPushTime` is now set only for call/message pushes, so anything else doing it is a bug |

---

## 2. LAST — file the five upstream defects

Everything needed is already written in **`patches/UPSTREAM-REPORT-dht-connection-costs.md`**, with
pristine-source citations for each.

| # | defect | has a working fix here? |
|---|---|---|
| 1 | a push names the changed value ids, the client re-downloads the whole key | partial (coalescing) |
| 2 | a failed GET erases the listener's cache and reports everything expired | **yes, one line** |
| 3 | `Bucket::addKnownNode` ignores `mobile_nodes`, defeating the mobile damper | yes (different approach) |
| 4 | `DeviceAnnouncement` republished under a fresh random value id | **yes, one line** |
| 5 | `IceCandidates`' 1-min TTL is dead — encrypted values lose their type on the wire | no |

**Routing**, per `.claude/skills/jami-gerrit`: a report **with a patch** goes to Gerrit; without one,
to forum.jami.net (GitLab issues are disabled). So 2 and 4 are Gerrit candidates; 1, 3 and 5 want a
forum thread first — 1 and 5 are protocol-adjacent, 3 is a design question (is the mobile damper
meant to survive presence re-injection at all?).

**This item needs 白い熊 at the keyboard.** Hard split, unchanged: Claude prepares the whole patch;
白い熊 runs the single push command and types the Gerrit password, which is never stored on this
host. HTTPS only, `/a/` path, Change-Id hook in the worktree's common hooks dir.

---

## 3. MIDDLE — read `SK-DHTSTATS` at the next full-DHT escalation

`patches/opendht-dht-message-stats-diag.patch` logs one line per `confirmNodes` tick:

```
in ping=… find=… get=… listen=… put= | out ping=… … | incoming=…/… good=…/…
```

`in` is work done **for other people**; `out` is our own traffic; `incoming=` counts routing-table
entries learned passively, purely because someone contacted us.

**It is silent in proxy mode** — proxy mode constructs no `Dht` at all — so it produces nothing
until something escalates to full DHT: a wedge escalation, or "Full DHT while charging" if that is
ever re-enabled (currently off, and it should stay off).

`getNodeMessageStats` **resets** the counters as it reads them, so each line is a delta, not a
running total.

**What it settles:** `client_mode` was shipped in `+174` on the argument that serving other people's
DHT traffic is a real cost. Nobody has ever measured that share. If `in` turns out to be a small
fraction of `out`, `client_mode` was near-worthless — which is fine, it is one line and inert at
rest, but we should know rather than assume. This is the honest follow-up to a change shipped on
reasoning alone.

---

## Appendix — what `+174` actually changed, for context

Shipped across `+172`–`+174`, all on `origin/custom`:

- **opendht push-refetch hardening** — per-listener GET coalescing (**4.1× measured**), the sweep
  gated on a successful GET, per-key byte attribution, diag threshold → 0
- **swarm redial backoff** — 60 s base doubling to 30 min, speculative path only
- **absent-device de-listing** — honours the presence OFFLINE edge upstream discards
- **dhtnet local rendezvous** — co-resident accounts skip the DHT entirely (**35 % of transports**)
- **`client_mode`** — inert at rest, escalation windows only
- **per-account wedge recovery** — a stuck message no longer escalates all four accounts
- **background deactivation re-enabled** — the subject of item 1
- **presence** — one-off subscriptions now release; `PresenceMap` writes `presence-map.txt`

**The central finding**, recorded in `.claude/skills/jami-build/SKILL.md`: each account listens on
`SHA1("peer:"+deviceId)`; those `PeerConnectionRequest` values are never withdrawn and **cannot** be
(no DELETE route in proxy mode, no delete message in the protocol, `cancelPut` is a no-op for
non-permanent values); each dial mints a fresh random id so retries accumulate; and a push about one
of them re-downloads **all ~60**. Measured 148 / 128 / 80 KiB per get, **81 % of all inbound bytes**.

**A trap worth not repeating:** a 40-hex DHT key that matches no account, no contact and no
conversation member is a **derived** key — `inbox:<deviceId>` or `peer:<deviceId>`, both `SHA1` of
that literal string. It was mistaken in turn for a landfilled contact key and for a group-member key
before `presence-map.txt` printed the computed hashes and settled it.
