# Changelog

All notable fork-specific changes to **白い熊 GNU Jami** (`shiroikuma.jami`), a downstream fork of
[GNU Jami](https://github.com/savoirfairelinux/jami-client-android). Versions are the upstream
release date-code plus a per-build `+N` tail.

## 20260619-01+77 — 2026-06-29

A focused follow-up to the connectivity layer: the per-contact monitor's verdict is now grounded in the
delivery receipt (no more premature "unreachable"), the help page is restyled and fully settable, and
"Atomic reset" is renamed to "Hard reset". Still on upstream base **20260619-01** (versionCode 498).

### Contact live monitor
- **Honest, receipt-based verdict.** The dialog reads the ⌁ ping's **delivery receipt** as ground
  truth: "delivering…" → "delivered ✓", softening to "may be offline" only after a long wait — it never
  declares "unreachable" from channel state or a short timeout (that earlier verdict was a false
  negative and was removed).
- **Always shows what's happening.** A contact with no open channel reads an explicit state — e.g.
  "watching the DHT… opens the moment they're reachable" for an offline contact — instead of a blank.
- **Richer connected rows.** Each live channel shows how long it has been up and its channel names,
  alongside the device id (tap to copy).
- **Streamlined buttons.** The account-wide **Recover** button is gone (recovery belongs to the ⚡
  lightning); the dialog is **Message ping ⌁** (left) + **Close** (right), with a "● re-checks every
  2 s" live footer.
- **Escalation, not blind retries.** If your last message is still undelivered, Message ping no longer
  just fires again — it explains the daemon is already retrying and routes you to the ⚡ lightning,
  making clear that the lightning recovers your **whole account (all chats)**, with a "Send another
  anyway" fallback.

### Connectivity help page (long-press the account dot)
- **Restyled** for clarity: blue headings, yellow body, bold gestures, tighter structure.
- **Button names render as pills** that look like the real Contact-live-monitor buttons, so the guide
  matches the UI.
- **Fully settable** under *UI fonts & colours → "Connectivity help page"*: headings (colour + font &
  size), body text (colour + font & size), and button-pill text / border / fill colours. Headings
  default to the vivid `#0000FF` reachable blue.

### Naming
- **"Atomic reset" → "Hard reset"** everywhere — the lightning long-press, the help page, the recovery
  log, and the README.

## 20260619-01+67 — 2026-06-28

The connectivity study became a full self-healing connectivity layer: an automatic recovery watchdog,
a two-tier manual recover, honest real-time presence, and a focused per-contact connection monitor —
all on-device-tested across `+30`…`+67`. Still on upstream base **20260619-01** (versionCode 498).

### Connectivity default corrected
- The earlier "DHT proxy on" default proved wrong in practice — proxy-on routes every connection-setup
  through one link that, when it wedges, strands all external delivery (in and out) while the UI still
  shows "connected". New accounts now default to the reliable config: **DHT proxy OFF, UPnP + TURN on,
  local discovery off**.

### Self-healing online-recovery watchdog
- A background watchdog detects the wedge — nothing connected for a while, **or** an outgoing message
  that never confirms (`NOT SYNCING`, which also catches group swarms a raw connection table can't see)
  — and recovers automatically by dropping to the full DHT and re-registering.
- **Charging-aware:** while charging it holds the proxy off (full DHT, maximum reliability — free when
  plugged in) and manages it normally on battery.
- A rolling, timestamped recovery **log** records what it did and for how long; an optional test-swarm
  **canary** can drive detection actively.

### Smart vs atomic recover (the ⚡ lightning)
- **Tap = smart recover:** re-register on the current proxy when links are healthy; drop to the full
  DHT only when nothing is connected — so clearing one stuck contact no longer needlessly disables the
  proxy (and its battery savings) for everyone.
- **Long-press = atomic reset:** force full DHT + re-register unconditionally — the big hammer.
- The lightning glows **blue while a recover is settling**, yellow when idle.

### Per-contact live connection monitor
- Tap any contact's (or group's) **avatar** in the chat list — or a no-connection row in the full
  monitor — to open a focused live monitor: the avatar + resolved name, then **each member's channels**
  colour-coded like the monitor (connecting → negotiating ICE → securing TLS → connected) with device
  IDs (tap to copy).
- **Message ping (⌁):** sends a tiny probe that forces a direct channel to open (the only thing that
  does — a typing nudge isn't enough), so you can watch the link come up.
- **Recover** right inside the dialog, plus a live verdict: a ping that never connects concludes
  **"offline or unreachable"** instead of sitting on a misleading "reachable".

### Honest real-time presence
- The chat-list dot now means exactly what the daemon reports: **yellow = a live P2P connection right
  now, blue = announced online but no open channel, red = offline.**
- **Delivery-aware:** a conversation whose last outgoing message has been stuck more than a few seconds
  no longer reads connected — so "delivered but red" / "yellow but undelivered" mismatches are gone.
  Group dots are wired to the same status.

### Top-bar icons + in-app help
- **Sync ↻** — tap: the connection/recovery log; long-press: **pin DHT proxy off** (full DHT, maximum
  reliability; Sync turns blue while pinned).
- **Account dot ●** — tap: the connection-status dialog; long-press: a new **connectivity help page**
  that draws every icon, explains its tap/long-press, and recommends a fix for each common situation.
- **Lightning ⚡** — tap: smart recover; long-press: atomic reset.
- Icons are tightly grouped (the lightning is a sized action view), and the connection-status dialog
  gained "Sync now" + "Recovery" controls.

### Fixes
- **Tapping a group avatar no longer crashes** — the per-contact monitor iterates members instead of
  the single-contact accessor that throws for group swarms.
- The connection-status dialog and the per-contact monitor no longer assume a 1:1 conversation shape.

## 20260619-01+29 — 2026-06-26

A measured connectivity study turned into a set of self-healing connection-health features. Still on
upstream base **20260619-01** (versionCode 498); `+10`…`+28` were on-device test iterations and `+29`
is the shipped result.

### Connectivity: measured, then defaulted
- Ran a controlled on-device A/B test of every connectivity setting (UPnP, DHT proxy, local peer
  discovery, TURN), measuring delivery success, latency and idle CPU. Result: **"Use DHT proxy" is the
  only setting that materially matters** — proxy on ≈ 0–2% idle CPU with reliable external delivery
  (including an instant push-wake on an incoming external message), proxy off ≈ 10–12% CPU; UPnP, local
  peer discovery and TURN had no measurable effect on delivery. The one cost of proxy-on: a message
  between your **own same-device accounts** can strand until nudged.
- New accounts now default to **UPnP + TURN + local peer discovery + DHT proxy all ON**.

### “Sync now” — clear stranded inter-account messages
- A new **sync icon** in the chat-list top bar (left of the connection dot). Under DHT-proxy mode a
  message to one of your own same-device accounts (or a same-device group swarm) can sit undelivered;
  tapping Sync briefly drops every account to the full-DHT path and back, flushing those messages. The
  toggle is **sequential and spaced** — doing it all at once / too fast crashed the daemon, so it now
  mirrors the safe manual sequence.

### Honest connection health (dot + monitor + status dialog)
- The “needs attention” alarm is now driven by the **actual undelivered message** (an outgoing text
  whose delivery never confirms) in same-device conversations — which also catches **group swarms** the
  connection table can’t even see. A genuinely stuck message turns the account red and rings the dot;
  normal connecting and idle never false-alarm.
- **Account dot ring:** red when a message is stuck, **blue while an account is still connecting**.
- **Connection monitor:** problem accounts sort to the top; a red **“⚠ message not delivered → \<chat\>”**
  row (with the account avatar) names exactly what is stuck; “connecting” reads blue and is never itself
  a problem; your other same-device accounts show **“reachable”** instead of a false “offline”; all
  accounts are **folded by default**; the fold indicator is a filled ▶/▼ triangle whose **size is
  settable** in UI fonts & colours.
- **Connection-status dialog (tap the dot):** now a **3-level foldable** view mirroring the monitor —
  account → contact (with avatar) → individual device connections — with the full-page **Monitor** button
  kept.

### Fixes
- The **Diagnostic logs** screen no longer auto-pops the “Last crash report” sheet on every visit (it is
  still reachable via the bug icon).

## 20260619-01+9 — 2026-06-25

Connectivity, push and a rebuilt connection monitor, plus app-wide dialog theming. Still on upstream
base **20260619-01** (versionCode 498). The `+2`…`+8` builds were on-device test iterations of this
work; `+9` is the shipped result.

### Connectivity & push
- Ship the **`withUnifiedPush` flavor** (Google-free push): paired with a UnifiedPush distributor
  (e.g. ntfy) and the DHT proxy, backgrounded accounts deactivate and wake on a push, dropping idle
  CPU to near zero with **no Google/Firebase dependency**. Retires the always-awake `noPush` flavor,
  which kept four same-device accounts at 10–35% idle and overheated the phone.
- Diagnosed (no code change needed): the daemon's frequent `Broken pipe` / "TLS non-properly
  terminated" ERROR logs are **normal swarm-conversation sync teardown**, not connectivity failures;
  local peer discovery (mDNS) only helps on a multi-device LAN and is otherwise unnecessary.

### Connection monitor (rebuilt)
- The monitor and the account-dot **status dialog now cover all accounts**, not just the current one.
- **Honest health model:** an account is **healthy** (yellow) when registered and actually syncing,
  **connecting** (blue) while it establishes, and red only when genuinely **offline** (unregistered)
  or **not-syncing** (registered but isolated > 2.5 min). Short-lived sync connections are shown
  neutral and **never flagged red** — the monitor no longer cries wolf over normal churn, and the dot
  alarm ring fires only for a real, otherwise-invisible isolation.
- **Per-account avatars** on foldable account headers, large fold chevrons, indented sub-rows, and a
  failing-first layout; tap any connection for **device detail** (device / contact IDs, status, copy)
  with a **per-account Reconnect**; an **icon & colour legend** behind a “?”.
- Opening the monitor from the status dialog returns to that dialog on Back.

### Theme & dialogs
- **Every dialog is black/yellow app-wide** now — the app theme routes `alertDialogTheme` /
  `materialAlertDialogTheme` through the fork's dialog style, so even Android's own **preference
  dialogs** (e.g. the DHT-proxy-address editor) match; hand-built dialogs share a `DialogTheme` helper
  (black fill, yellow rounded border, yellow buttons).

### UI fonts & colours
- The screen is renamed **白い熊 GNU Jami UI** and gains an **app-language override** (run the UI in a
  chosen language independent of the phone locale).
- Five new **settable connection-monitor colour roles** — connected/healthy (defaults to `#FFFF00`),
  in-progress/connecting (blue), problem (red) — recolourable like every other role.

### Build / infra
- The **`jami-build` skill now builds `withUnifiedPush`** as the canonical flavor (output under
  `app/build/outputs/apk/withUnifiedPush/release/`).
- Version tail advanced to **20260619-01+9**.

## 20260619-01+1 — 2026-06-21

First public release of the fork, built on upstream GNU Jami **20260619-01** (versionCode 498) with
the full customization stack below. Installs side-by-side with the official `cx.ring`.

### Install identity
- Repackaged as **`shiroikuma.jami`** with the label **白い熊 GNU Jami**, so it installs alongside
  official Jami (`namespace` stays `cx.ring` for R/BuildConfig; only the `applicationId` differs).
- FileProvider authority derived from `${applicationId}` — fixes `INSTALL_FAILED_CONFLICTING_PROVIDER`
  against official Jami and keeps file-sharing working.
- `JAMI_DATADIR` repointed to the real install dir so the daemon's data path matches.
- Black/yellow knot **launcher icon** (yellow edge-trace).

### Theme — yellow-on-black
- Black backgrounds with `#FFFF00` foreground across the chat list, chat text (dates grey), toolbars,
  conversation bubbles (black fill + yellow border), the compose and search bars, the start-conversation
  button, and a yellow border around unread conversation rows.
- Black search bar with yellow hint/text (elevation overlay disabled); file messages get a yellow
  paperclip and black bubble + black icon square, both yellow-bordered; black/yellow link-preview card.
- Compose-bar thumbs-up shows a yellow outlined-thumb icon while still sending the emoji (display
  decoupled from payload); account-selection dialog black with yellow border, text and add-account icon.
- Themed account badges, fallback (generated) avatars and the home top bar; chat-list presence-dot
  colours; black/yellow settings screen (including switches); themed conversation long-press bottom
  sheet and the expanded home search view.

### UI fonts & colours
- New **UI fonts & colours** screen: per-element **font family / weight / size** for chat text,
  conversation title, chat-list title / preview / date, message time, search hint and settings text,
  each defaulting to the current value.
- Per-element **runtime colours** (text, fill, border, tint) for message bubbles, link-preview and
  file cards, the account badge, status icons, the online/offline icon, the unread-row border, presence
  dots, and every themed text surface — "unset" falls back to the current palette so nothing changes
  until you pick.
- **RGBA-slider colour picker** with a live preview swatch and a two-way `#AARRGGBB` hex field.
- External font import (`.ttf` / `.otf`) from storage via SAF, stored in app files.
- Grouped, inline fonts-settings layout with live preview; live refresh on leaving the screen; reachable
  from the chat-list overflow and Settings → Appearance.
- Line-driven sizing of list rows, avatars and the message status icon; configurable message-status-icon
  height kept beside the bubble; resizable account dot with reliable presence swap.

### Connectivity
- Connectivity resilience with on-device connection diagnostics (DHT reconnect + a diagnostic indicator).
- One-tap **recover Offline / disabled accounts** reconnect action.

### Automation
- Token-gated, exported **send / call / open** automation intents for external scripts.

### Names
- Registered-name resolution: retry stuck lookups, plus a **“Look up name”** action.

### UI & navigation
- Dual-pane **split-view toggle** (Settings → Appearance + chat-list overflow) to force single-pane on
  wide/foldable screens.
- Settable styled **“flash” messages** (toasts).
- Account **online/offline toggle** icon in the chat-list top bar.

### Build / infra
- AGP-9 built-in-Kotlin build-config migration (`android.builtInKotlin=true`, `android.newDsl=false`),
  with libjamiclient warning suppression and quieter Kotlin build logs.
- Per-build `+N` version tail tracked in an in-repo counter (`jami-android/shiroikuma-build.txt`), so
  every rebuild is an upgrade.
- Re-applied gnutls `--without-brotli --without-zstd` contrib fix at build time (not committed).
- Agent config: `CLAUDE.md` + `.claude/skills/` (`jami-build`, `upstream-new-version`,
  `publish-version`); gitignored `local.properties` SDK pin documented; upstream `FUNDING.yml` removed.

### Fixes
- Fully-qualify `RtlGridLayoutManager` so it resolves under `shiroikuma.jami` (avoids a runtime
  ClassNotFound / lintVital `RelativeClassResolution` failure caused by `applicationId ≠ namespace`).
- Outgoing file card no longer overflows when the filename is long.
- Online/offline menu icon now switches shape correctly on toggle.
