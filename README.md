<div align="center">

<img src="jami-android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 GNU Jami app icon" />

# 白い熊 GNU Jami

**Private, peer-to-peer messaging & calling — themed and tuned to taste.**

A fork of [GNU Jami](https://jami.net) with **major additions**: a full yellow-on-black theme, a
per-element **UI fonts & colours** system with an RGBA colour picker, deep **connectivity
resilience** — a **three-fold connection mode** (full DHT / Firebase / UnifiedPush) with an
**adaptive push→streaming fallback** that survives a dead push leg, a self-healing recovery watchdog
with **probe-verified** health, and a live **connection monitor** (per-contact too) — a swipeable
**media viewer** with hide/restore, **protected contacts** with vague notifications (masking media
too), **in-app message forwarding**, token-gated **automation intents** plus **保存復元 batch-backup automation**, a **DHT data-efficiency fix** that cut the fork's own DHT footprint ~80x, a live **data-usage
meter** with unattended logging, smarter **registered-name** lookups, **home-screen shortcuts** straight to a chat or a call, a **split-view** toggle, and one-tap **Export / Import** of every setting **and every
account** as a single backup file.

**📥 Latest release: [`20260717-01+102`](https://github.com/ShiroiKuma0/jami/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/jami/releases)

</div>

---

## Installs side-by-side with official Jami

This fork ships as **`shiroikuma.jami`** (label **白い熊 GNU Jami**) with its own FileProvider
authority, so it installs **right next to** the official `cx.ring` from F-Droid / Play — two apps,
two accounts, no conflict. It's signed with its own key, so it never installs *over* official Jami
(Android refuses mismatched keys); grab the APK from the [releases page](https://github.com/ShiroiKuma0/jami/releases)
and install it as its own app.

Built for **arm64-v8a**; the C++ Jami daemon and all of its contrib are compiled from source. Jami
itself is unchanged underneath — same protocol, same distributed network, same end-to-end
encryption — this fork only adds a thick layer of personalization and a few reliability and
automation tools on top.

## 🎨 Yellow-on-black theme

The fork's signature look: black backgrounds with a `#FFFF00` foreground, applied consistently
across the chat list, conversation bubbles, toolbars, the search and compose bars, **every dialog
app-wide** (including Android's own preference dialogs — yellow-outlined pill buttons on a bordered
black card) and the long-press bottom sheet, file and
link-preview cards, unread/pending badges, and the
account-selection dialog. Generated (no-photo) avatars are drawn black with yellow initials and a
yellow ring; presence dots, the home top bar and the expanded search view are themed to match. The
settings screen carries the same black/yellow treatment, down to the switches.

## 🔤 UI fonts & colours

A single **UI fonts & colours** screen lets you restyle every text surface independently. For each
element — chat text, conversation title, chat-list name / preview / date, message time, link-preview
title/description/domain, file name, settings text, the search hint and more — you can pick a **font
family, weight and size**, *and* set its **text, fill and border colours**.

- **RGBA-slider colour picker** with a live preview swatch and a two-way `#AARRGGBB` hex field, so
  every colour (including translucency) is exactly what you choose.
- **Bubble, card and badge fills + borders** are recoloured at runtime — sent/received bubbles,
  link-preview and file cards, and the account badge.
- **Import your own fonts** (`.ttf` / `.otf`) straight from storage — no permission needed.
- Every control **defaults to the current palette value**, so anything you leave unset looks exactly
  like stock. Changes apply live when you leave the screen.
- Line-driven sizing keeps larger fonts legible — list rows, avatars and the message status icon grow
  with the text instead of clipping, and the **status-icon height** is itself adjustable.
- The **connection-monitor state colours** (connected/healthy, connecting, problem) are settable here
  too, and an **app-language override** lets you run the UI in a language independent of the phone
  locale.

Reachable from the chat-list overflow (**UI fonts & colours**) and from **Settings → Appearance**.

## 💾 Export / Import — settings *and* accounts

The first section of the UI page backs up **everything settable in the app — and your Jami accounts
— into a single timestamped zip**, and restores it category by category. Pick an export directory
once (the page shows the latest export in it at a glance), tick the categories — **Accounts (Jami
archives)**, fonts & sizes (your imported font files travel inside the zip), colours, UI behaviour,
online recovery & connectivity, automation & protected contacts, app settings — and hit Export.
Import merges key-by-key (it never wipes what it doesn't know), skips accounts already on the
device so a re-import can't duplicate identities, and offers a one-tap restart to apply everything.

## 🔗 Home-screen shortcuts

Add a shortcut from the launcher and pick account → contact → **chat or call**. The icon is the
contact's avatar badged with the Jami mark and a yellow-traced chat or phone glyph, so the two kinds
are told apart at a glance; the badge colours are settable like everything else. Tapping opens that
conversation or places the call directly. Also available in-app from the search-bar overflow, and the
picker speaks English, Japanese and Czech by system locale.

---

## 📶 Connectivity, self-healing & a live connection monitor

Jami can quietly drift offline; this fork helps it heal itself **and** shows you the truth about its
links.

**Three-fold connection mode with an adaptive fallback.** Pick how the app stays reachable — a
**local DHT node**, **Firebase/FCM** (works through microG, no Google Play needed), or a custom
**UnifiedPush** distributor — from one dialog. The battery-cheap modes ride a push subscription, and
this fork adds the missing safety net: a watchdog measures whether **real pushes actually arrive**,
and when the push leg dies (a rate-limited server, a filtering VPN, a dead distributor) it
automatically clears the push token and switches the proxy to **streaming reception** so messages
keep flowing — then quietly returns to push when the leg recovers. It even keeps the process alive
for the outage's duration so streaming can't be reaped. The push path never silently strands you.

**Verified health, not guesses.** Every connectivity indicator is driven by what the app can *prove*
it received — a 60-second silent presence probe, an active call, real push arrivals — never a
registration flag that lies. A contact you're on a call with can never show red; an account that
reads "connected" but receives nothing is caught and recovered.

**Port mapping that actually works on phones.** Upstream dhtnet binds UPnP/NAT-PMP to the *first*
network interface it finds — on a real phone with mobile data up that's the cellular link, so both
protocols silently fail forever (`UPNP_E_INVALID_INTERFACE`) and no router port is ever opened. This
fork patches the daemon's interface selection to prefer the actual LAN interface (Wi-Fi/Ethernet),
so UPnP and NAT-PMP reach your real router and ICE gets direct-connect candidates — a bug still
present upstream.

**Self-healing watchdog.** A background watchdog watches for the wedge — nothing connected, or an
outgoing message that never confirms (which also catches **group swarms** a raw connection table can't
see) — and recovers automatically by re-registering on the full DHT. It holds the proxy off while
charging (reliability is free when you're plugged in) and manages it intelligently otherwise.

**Two-tier recovery — the ⚡ lightning.** Tap for a **smart recover**: it just re-registers when your
links are healthy, and drops to the full DHT only when nothing is connected, so fixing one stuck
contact never needlessly disables the proxy. Long-press for a **hard reset** — full DHT +
re-register, unconditionally. The lightning glows blue while recovering. After any recovery it also
**back-fills**: it actively pulls the messages that piled up while you were deaf, instead of waiting
for senders to notice you again.

**The ⬡ hub — mode by shape, state by colour.** The connection-mode icon is a **solid hexagon** for
full DHT (the heavy mode — your device is a node) and a **thick hollow hexagon** for proxy (the light
mode); its **colour** is the live state — yellow when the chosen mode is receiving fine, blue while
recovering or riding the streaming fallback, red when the current mode is verifiably broken. One tap
switches every account at once.

**Per-contact live monitor.** Tap any contact's (or group's) avatar to open a live monitor for just
them — it self-refreshes every couple of seconds. Each member's channels are colour-coded exactly like
the full monitor (connecting → negotiating ICE → securing TLS → connected) with device IDs, link uptime
and channel names; an offline contact reads "watching the DHT…" rather than a blank. A **Message ping**
forces a channel open, and its verdict is honest — it waits for the ⌁'s **delivery receipt** instead of
guessing from the channel, so it never cries "unreachable" prematurely. If a message is already stuck it
routes you to the account-wide ⚡ recovery instead of pinging into the void. Group swarms included.

**Honest presence + the full monitor.** The chat-list dot is a real-time light — **yellow = a live
connection right now, blue = online but no open pipe, red = offline** — and it's delivery-aware, so a
contact with a message stuck more than a few seconds never reads connected. The all-accounts
**connection monitor** (tap the dot) sorts problems to the top, names exactly what's stuck, reads
"connecting" as blue (never a fault), and folds three levels deep to each contact's device
connections. **Long-press the dot for an in-app help page** explaining every icon and what to do when
something's wrong — its button references render as the real pills, and its own headings, body and pill
colours (plus fonts) are settable too. Every colour, and the fold-triangle size, lives in *UI fonts & colours*.

**Actionable, honestly-coloured stuck messages.** When a message won't go through, the dashboard and
monitor don't just flag it — they explain and act. Each stuck line **names the recipient
unambiguously** (profile name + username + id tail, so two identities sharing a display name are never
confused), shows **how long it's been stuck and what it is** (📎 file or 💬 text preview), and is
**tappable — it opens that very conversation**. The colour tells you *whose* problem it is: **red** is
a genuine fault you can fix — a same-device sync stall between your own accounts, or a peer that's
connected yet never acknowledged — and **blue "delivering…"** means you're simply reaching someone
who's away (their message will land when they return). A message queued to an offline contact never
makes your account look broken, and every account-status dot — top bar, side avatar, dashboard,
monitor, and the account picker — follows the *verified health*, so a red row is never paired with a
falsely-green dot.

**Dual-backend push in one build.** The app ships both **Firebase/FCM** and **UnifiedPush** in a
single APK — switch between them (or a local DHT node) at runtime. Firebase is wired to work through
**microG**, so you get Google-style push with **no Google Play Services**; UnifiedPush pairs with any
distributor (e.g. [ntfy](https://ntfy.sh)), including a self-hosted one. Whichever you pick, the
adaptive fallback above covers it when the push server fails.

## 📉 DHT data efficiency — an upstream bug, fixed at the root

Jami's daemon republished **every certificate revocation list an account had ever seen** on each
registration — and because each put drew a fresh random value id, every re-registration *added* a
copy rather than replacing one. All of it permanent, re-announced to the network every 10 minutes,
forever. One account key here carried **266 values / 274 KB: 35 CRLs going back to October 2024**,
where 1.5 KB would do.

This fork publishes only the **current** CRL, under a content-derived id so re-registration
overwrites instead of piling up — verified safe first, since the newest list contains exactly the
union of every older one. The device announcement got the same treatment. Result across four
accounts: **661 values / 611 KB → 9 values / 7.4 KB**, with idle CPU roughly halved. A complete
upstream report ships in the repo.

---

## 🧭 Transport check — when DHT proxy is actually worth it

The full DHT rides UDP, so a network that blocks UDP breaks it completely no matter how healthy
everything else looks — the one case where DHT proxy is clearly the right mode. The connection
monitor now shows a **Transport** row: `UDP ✓ TCP ✓ — full DHT viable`, or in red
`UDP ✗ TCP ✓ — hostile network, DHT proxy advised`, always stamped with how old the reading is.
Tap to re-test, hold for the explanation; it re-tests itself whenever the network actually changes,
and tells you when a network turns hostile. It only ever **advises** — switching modes stays your
decision, on the hexagon.

---

## 🖼 Media viewer with hide/restore

Full-screen media opens into a **swipeable viewer**: swipe sideways through the pictures and videos
of the same direction (sent or received) without going back to the chat. **Swipe down** to hide the
current item from the swipe set (it stays in the conversation, marked "Suppressed" when opened
directly); **swipe up** to restore it. A dedicated button browses only your hidden media so nothing
is ever lost, and the action bar is plain yellow icons — share, download, open — instead of stock's
white pill.

## 🛡 Protected contacts

A companion app can mark contacts as **protected** over a local broadcast: a message from one of
them posts a deliberately **vague, contentless notification** — nothing identifying on the
lock-screen, Wear or Android Auto — with a companion-settable title/body and a private marker the
companion keys on. The stored list can be queried back (ordered-broadcast read-back), and every
other sender's notifications are untouched.

## ↪️ In-app message forwarding

Long-press any message or file → **「白い熊 Jami」で共有** forwards it to another chat through
Jami's **own** picker — no system share sheet. Pick the **sending account** from an avatar list,
pick the target conversation (with search and a content preview), and the message lands staged in
that chat's composer. The long-press menu itself is black/yellow, colour-settable, and pinned in
place so background sync can no longer make it dance.

## 🤖 Automation intents & 保存復元 batch backup

Token-gated, exported **send / call / open** intents let external scripts and automation apps drive
Jami headlessly — send a message, place a call, or open a conversation from anywhere on the device,
guarded by a secret token so only your own automations can trigger them. The same token also gates
the **保存復元 state-export contract**: an external backup orchestrator can list the export
categories and trigger a full headless backup (real-count progress broadcasts, a broadcast reply
with the written path and size), producing the exact same restorable zip as the Export/Import
panel — with the token itself never travelling inside any backup.

## 🔎 Registered-name resolution

Username lookups that get stuck are retried automatically, and a dedicated **“Look up name”** action
lets you resolve a registered name on demand instead of waiting on a silent failure.

## 🪟 Split-view toggle

On foldables and tablets, Jami shows the conversation list and the open chat side by side. A toggle
(in **Settings → Appearance** and the chat-list overflow menu) forces **single-pane** when you'd
rather focus on one screen at a time.

## 💬 Quality-of-life

- Settable styled **“flash” messages** (toasts) for in-app feedback.
- A resizable, real-time account-status **dot** in the top bar (tap for full status, long-press for help).
- A **“Location sharing status”** tool (overflow menu) that shows which conversations are really
  sharing your location — and can stop them all — without ever waking the sharing service.
- A black/yellow knot **launcher icon** so the fork is easy to spot.

---

## Built on GNU Jami

This is a downstream personalization of [GNU Jami](https://jami.net) for Android
([savoirfairelinux/jami-client-android](https://github.com/savoirfairelinux/jami-client-android)).
All credit for Jami — the protocol, the daemon, the network and the client — goes to its authors at
Savoir-faire Linux and the Jami community. Like upstream, this fork is licensed under the
**GNU General Public License v3.0** (see [`COPYING`](COPYING)).
