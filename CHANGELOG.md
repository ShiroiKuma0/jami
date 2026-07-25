# 白い熊 GNU Jami — `20260717-01+86`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

## Data-runaway & false-wedge overhaul, 保存復元 automation, data meter (new in +78–+86)

**The 2026-07-25 root-cause batch** — a full forensic day: a 36 GiB/15 h data burn, an overnight
7-minute false-recovery limit cycle (56 incidents), and a ~400 % CPU runaway were traced to three
interlocking causes and fixed at the root:

- **False-wedge limit cycle killed.** The watchdog's echo-suppression regime (`pushMode`) was keyed
  to the DHT-mode *preference* while the charging/wedge machinery switched the *daemon* to full DHT
  underneath it — so every probe's own answers were discarded and the watchdog "recovered" a healthy
  system every 7 minutes, all night. It now tracks the daemon's actual proxy state. The give-up
  counter no longer resets on the transient post-recover health blip, and the wedge linger (5→10 min)
  now outlasts the old cycle period so repeat-wedge backoff really engages.
- **The data hog: blanket subscription refresh made conditional.** The fork's 3-minute proxy
  subscription re-arm re-downloaded every subscribed key's **full value set** every cycle (measured:
  1650 values / 2.5 MB on one landfilled account key; ~29 MB bursts; tens of GB/day). It now re-arms
  only after 10 minutes of genuine client silence. Post-recovery backfill syncs are capped at 6/hour
  (each one fanned fresh connection offers to every device of every contact).
- **Push-token rotation.** Generations of stale server-side proxy subscriptions kept pushing 2–3/s
  to the same FCM token (a standing 600 KB backlog inside microG, a Firebase thread at ~24 % CPU).
  The token is now rotated once after the update and automatically on repeat wedges (6-hour
  throttle) — orphaning the whole pile at Google's side within seconds.
- **Per-key data forensics built in.** Subscription requests now log per-request `bytes=` and the
  DHT `key=` (visible in release builds), and proxy connect-deadline events are logged too — a
  landfilled key names itself in logcat instead of needing a day of detective work.
- **"Full DHT on while charging" toggle** (Online recovery, default **on**): charging = the robust
  full DHT because battery is free; switch it off to keep the proxy's low-data profile on the charger.
- **Data-usage meter** (Online recovery): flip it on and the app measures its own real data use —
  live elapsed / ↓rx / ↑tx line while measuring (kernel per-UID counters, reboot-safe accumulation);
  flipping it off writes a history record (duration, bytes, DHT mode incl. the daemon-actual state,
  network) viewable in a themed history dialog.
- **Pinned recovery notification.** Repeating uniform-wedge notifications now update one pinned
  notification with a ×N counter instead of rotating through 20 slots and overwriting the shade.
- **Account Settings dot honesty.** The avatar dot on the account-settings page showed a hardcoded
  red (the builder's OFFLINE default); it now reflects real account health.

**保存復元 state-export automation** (the 自由作業盤 batch-backup wire contract):

- New exported, token-gated broadcast receiver: `shiroikuma.jami.action.EXPORT_STATE` runs the
  Export/Import backup **headlessly** (same engine, same restorable zip — accounts included), and
  `…action.LIST_CATEGORIES` returns the category catalogue. Replies are fresh broadcasts with a
  correlation id (the only reply channel that survives EMUI); progress broadcasts carry **real
  counts** (`区分 n/7`), throttled to 500 ms. Directory precedence: caller-supplied path → the
  configured export directory. Distinct `automation disabled` / `bad token` errors; the full
  10-point acceptance checklist passed on-device.
- **The automation token never travels in a backup zip** — excluded on export and refused on import,
  so a restored backup can never leak or re-plant the credential.
- **Export filenames simplified**: `shiroikuma-jami_<yyyy-MM-dd_HH-mm-ss>.zip` — app name + stamp,
  no version; the latest-export scan still recognises the older names.
- **Automation controls moved into the Export/Import section** of the UI page (master switch,
  tap-to-copy token, regenerate, and a details link to the full usage page) — the backup automation
  lives next to the backups it drives.

**UI:**

- **⋮ long-press → UI settings.** In a chat, long-pressing the toolbar's overflow button opens the
  "白い熊 GNU Jami UI" settings page; its normal tap menu is untouched.
- **Unread accounts pop in the account picker.** In "Select account", an account with unread
  messages gets a row-wide box in the unread badge's own style (settable fill/border colours),
  inset from the dialog border so the box, badge, and dialog outlines read as three clean lines.

## Honest connection status + actionable stuck messages (new in +67–+77)

- **Status-dot freeze fixed.** The top-bar account dot is refreshed by a 2-second connectivity poll; a single exception in that poll (e.g. during a watchdog-triggered daemon restart) used to terminate the RxJava chain permanently, freezing the dot on its last colour while the dashboard showed the truth. The poll now resubscribes after 5 seconds and records the swallowed error in the recovery log, so the dot can no longer fossilize on stale-yellow.
- **Every account-status dot follows verified health, not registration.** The top-bar dot, the side-bar (nav) avatar, the dashboard rows, the monitor rows, **and the "Select account" picker** all colour their dot by the account's real health verdict — red for `NOT SYNCING` / `NOT RECEIVING`, blue for connecting, yellow only when genuinely healthy. A red account row is never paired with a green dot.
- **Stuck messages are actionable.** Each "message not delivered" line on the dashboard and monitor now:
  - **names the recipient unambiguously** — profile name **plus** the account username **plus** the id tail — so two of your own identities that share a display name (e.g. two accounts both named 白い熊) are never confused or shown as pointing "to themselves";
  - shows **how long it's been stuck** and **what it is** (📎 filename for a file, 💬 preview for text);
  - is **tappable → it opens that conversation** directly.
- **Red vs blue by cause.** A stuck message is **red — "message not delivered"** only when it's a genuine fault you can act on: a **same-device sync stall** (one of your own accounts failing to sync to another on the same phone), or a peer that is **connected yet never acknowledged**. It's **blue — "delivering…"** when you're simply reaching a recipient who is **offline or only announced** (away) — that's benign, leaves the account healthy, isn't counted as "needs attention", and ages out after 24 h. A message queued to an offline external contact can no longer turn your account red.
- **Verified-unreachable contact demotion.** A contact whose last outgoing message is stuck with no live channel is shown **red** on its presence dot; a stale `AVAILABLE` announce is held down until the message actually delivers or a real connected channel appears — so the dot can't claim a reachability the network can't back. (Deliberately distinct from the softer blue "delivering…" line: the dot is the strict "can't reach them" signal.)
- **File- and call-aware stuck detection.** The stuck scan walks to the newest outgoing **text *or* file** in a conversation (instead of only the last event), so an undelivered file followed by missed calls is caught — and delivery is judged by the per-recipient receipt map with the sender's *own* status entry excluded, fixing a false "delivered" that hid stuck messages in any conversation you had open.
- **Self-match fix.** The same-device detector no longer matches your own participant entry, so a one-to-one chat with an external contact is never mislabelled a same-device stall "to myself".
- **Same-device stalls drive recovery.** Because your own accounts on one phone are reachable by definition, a message stuck between them is treated as real local-fault evidence and drives the auto-recovery (with the existing once-then-escalate-then-stand-down ledger), instead of being written off as a benign offline wait.
- **Account picker restyle.** The "Select account" dialog is now about **half width** with **larger, tighter rows** (bigger avatars and names), rendered in a **scrollable container** so the **"+ Add account"** row is always present and can never be clipped off the bottom; the "+ Add account" row itself is compact.

## Export / Import of every setting + accounts, UI-page restyle (new in +65/+66)

- **Export / Import — the first section of the UI page.** Backs up **everything settable in the app into one timestamped zip** (`shiroikuma-jami-export_….zip`) and restores it category by category:
  - **Accounts (Jami archives)** — every password-less Jami account's daemon archive travels inside the zip (`accounts/<id>.gz` + metadata); password-protected archives are skipped and named in the result dialog. Import restores each archive through the daemon (wizard-style, with the fork's connectivity defaults) and **skips identities already on the device**, so a re-import never duplicates an account.
  - **Fonts & sizes** — the full per-element font config *plus the imported `.ttf`/`.otf` files themselves*, re-pointed to the new install on import.
  - **Colours**, **UI behaviour** (split view, dot/fold scales, app language), **online recovery & connectivity** (watchdog config, DHT mode, push backend), **automation & protected contacts**, and the stock **app settings**.
  - **Settable export directory** (SAF, persisted) with a **latest-export status line** queried in the background every time the page opens; no directory set → a save-as picker fallback.
  - Import is a **per-key merge — never a wipe** — so unknown keys survive and old exports load into new versions; the format is a zip of typed-JSON files plus a manifest.
  - Flow polish: the panel has select-all + per-category checkboxes and an ArcaneChat-style pill row (Cancel left, Import + Export right); the finished dialogs carry the yellow border; export-OK / import-"Later" close the whole chain (dialog → panel → page), "Restart now" relaunches the app, and failures flash while leaving the panel open.
- **kxkb-style restyle of the whole UI page** — section headings are 20 sp bold with **text-wide** underlines, sections are separated by thin full-width hairlines, element headings are 17 sp bold with thinner text-wide underlines, and the whole page uses the tighter kxkb indent ladder.

## Call-screen fixes (new in +64)

- **In-call avatar** no longer sits in a grey box; the presence dot stays **yellow during an active call** (you're demonstrably connected to whoever you're talking to).
- **Camera toggle button** un-reversed — camera **on** is a filled yellow button with a black plain-camera icon (the app's "active" look, like Speaker); camera **off** is an outlined button with a yellow crossed-camera icon.
- **Quiet-account wedge tuning** — the per-account recovery no longer fires on a single unanswered presence probe when nothing is actually stuck; a genuinely stuck message still recovers immediately, but a merely-idle account needs a second consecutive miss, removing the occasional needless recovery on quiet accounts.

## Connectivity — three-fold push & adaptive resilience (headline of this release)

- **Three-fold connection mode** in one build — Local DHT node / **Firebase (FCM)** / **UnifiedPush** — chosen at runtime from the connectivity dialog. Firebase is initialized programmatically from GNU Jami's own public project (no `google-services.json`, plugin not applied) and **works through microG** — validated on-device: microG issues a token for the fork's package and delivers.
- **Adaptive push→streaming fallback.** A watchdog measures whether *real* pushes actually arrive (not just whether the server answers a self-test). When the push leg is dead — rate-limited server, filtering VPN, dead distributor — it clears the push token and switches proxy subscriptions to **streaming LISTEN** so messages keep flowing, then returns to push when the leg recovers. FCM (which has no phone-to-self self-test) exits via timed optimistic re-entry with a doubling hold on relapse; UnifiedPush uses an end-to-end ntfy self-test.
- **Permanent foreground service while streaming**, so the process survives an outage on any device (streaming needs a live daemon; push, its normal reviver, is what's dead). Your setting is backed up and restored on exit. Adaptive state persists across a process restart.
- **Push starvation trigger** — a verified wedge with no real push for 10 minutes drops to streaming regardless of any self-test (catches the case where the proxy→server leg is dead but the phone→server leg works).
- **Fresh-install default is push ON (Firebase)**, degrading safely to streaming when microG is unavailable.
- **Verified-health metrics.** Every connectivity indicator — top-bar dot, dashboard, per-contact dot — is driven by probe-verified reception (a 60-second silent presence probe, an active call, real push arrivals), never a registration flag. A contact in a live call can never show red; a "connected but receiving nothing" account is caught. New `NOT RECEIVING` state.
- **Probe-gated recovery.** A per-account wedge only recovers after a silent presence re-arm probe goes unanswered for 60 s, killing the false-positive recovery loop that fired on quiet evenings. A repeat verified wedge escalates to a proxy off→on cycle to force a fresh proxy endpoint.
- **Post-recovery back-fill** — after any recovery the app actively pulls the messages that piled up while deaf, instead of waiting for senders to retry.
- **Crash-safe re-register ledger** — the unregister→register nudge briefly persists a disabled flag; if the process dies inside that window an account could be left disabled on disk. A ledger detects and auto-re-enables any account *we* disabled, on the next start.
- **The ⬡ hub icon** now encodes the mode by **shape** (solid hexagon = full DHT, thick hollow hexagon = proxy) and the live state by **colour** (yellow healthy / blue recovering-or-streaming-fallback / red current-mode-broken). One tap switches every account.
- Diagnostics: OpenDHT proxy-client subscription instrumentation (logcat `SK-PROXYDIAG`), a push-cadence gap log, and a push-endpoint line on the connection dashboard. The settings-page Full-DHT toggle now logs and verifies the switch. Telemetry no longer reports bogus "on full DHT" durations.

## Connectivity — carried forward from earlier releases

- **dhtnet LAN-interface fix** — upstream binds UPnP/NAT-PMP to the first interface (cellular on a phone with mobile data up), so both silently fail forever; this fork prefers the real Wi-Fi/Ethernet interface so UPnP/NAT-PMP reach the router and ICE gets direct-connect candidates.
- **dhtnet UPnP circuit breaker** — skips UPnP mapping when the router has no working IGD instead of blocking every ICE setup for seconds.
- **OpenDHT 4.2.0 proxy fixes** — connect-deadline resilience (a black-holed cached proxy endpoint no longer strands delivery forever) and a 3-minute subscription refresh (a lapsed push subscription is rebuilt by the daemon itself).
- **pjproject stuck-epoll eviction** — disarms dead sockets that spun ICE poll threads at ~80 Hz (the 12 % idle-CPU regression), gated to error-carrying events only.
- **Self-healing watchdog** with error-storm detection, passive deafness clock, restricted-network (hostile-Wi-Fi) mode, and per-contact live monitor with a receipt-verified Message ping.

## UI & theming

- Full **yellow-on-black theme** across chat list, bubbles, toolbars, dialogs, cards, badges, generated avatars and the home top bar.
- **UI fonts & colours** — per-element family/weight/size **and** runtime text/fill/border/tint colours, with an **RGBA colour picker** (alpha per role) and external `.ttf`/`.otf` import.
- **Yellow-border dialogs everywhere** — a drop-in that restores the black-fill / 2 dp yellow-border / yellow-pill-button chrome on ~55 upstream dialogs that Material stripped.
- **Full yellow settings chrome** — settings icons, headings, switches (yellow dot on a traced black track, both states), the account-summary page (black toolbar, yellow title/values/pencils/icons), the Online/Offline pill and the two-tone invitation envelope — all themed, with a settable "Settings icons" colour role.
- **Call UI** — control toolbar fixed (never auto-hides, always expanded), earpiece/speaker toggle, separate mute and Bluetooth toggles, black-yellow theme.
- Line-driven sizing so larger fonts stay legible; presence-dot colours; split-view toggle for foldables/tablets.

## Media, messaging & integrations

- **Media viewer** with swipe-through and swipe-down-to-hide / swipe-up-to-restore, a hidden-media browser, and plain yellow action icons.
- **Protected contacts** — a companion app marks contacts protected over a local broadcast; their messages post a deliberately vague, contentless notification (masking media too), companion-settable, queryable back.
- **In-app message forwarding** — long-press → 「白い熊 Jami」で共有 forwards through Jami's own account/conversation picker, no system share sheet.
- **Automation intents** — token-gated exported send / call / open intents to drive Jami headlessly.
- **Registered-name resolution** — stuck lookups retried automatically, plus an on-demand "Look up name" action.
- **Missed-call notification** for swarm conversations (was gated off upstream).
- A **Location-sharing status** tool that shows and can stop all real location shares without waking the service.

## Install identity & packaging

- App id `shiroikuma.jami`, label **白い熊 GNU Jami**, own FileProvider authority and signing key — coexists with official `cx.ring`.
- Built from source for `arm64-v8a`, including the full libjami daemon + contrib. AGP-9 built-in-Kotlin build-config migration.

---

Built on [GNU Jami](https://jami.net) by Savoir-faire Linux and the Jami community. Licensed under the GNU GPL, same as upstream.
