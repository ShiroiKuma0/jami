# 白い熊 GNU Jami — `20260717-01+77`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

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
