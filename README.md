<div align="center">

<img src="jami-android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="120" alt="白い熊 GNU Jami app icon" />

# 白い熊 GNU Jami

**Private, peer-to-peer messaging & calling — themed and tuned to taste.**

A fork of [GNU Jami](https://jami.net) with **major additions**: a full yellow-on-black theme, a
per-element **UI fonts & colours** system with an RGBA colour picker, **connectivity resilience** —
a self-healing recovery watchdog, a live **connection monitor** (per-contact too), smart one-tap
recovery and **Google-free push** — a swipeable **media viewer** with hide/restore, **protected
contacts** with vague notifications, **in-app message forwarding**, token-gated **automation
intents**, smarter **registered-name** lookups, and a **split-view** toggle.

**📥 Latest release: [`20260717-01+1`](https://github.com/ShiroiKuma0/jami/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/jami/releases)

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

## 📶 Connectivity, self-healing & a live connection monitor

Jami can quietly drift offline; this fork helps it heal itself **and** shows you the truth about its
links. A measured on-device A/B test settled the base config: **DHT proxy off, UPnP + TURN on** is the
reliable one — proxy-on routes every connection through a single link that, when it wedges, strands
delivery while the UI still says "connected" — so new accounts default to that.

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
re-register, unconditionally. The lightning glows blue while recovering.

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

**Optional Google-free push.** The `withUnifiedPush` flavor, paired with a UnifiedPush distributor
(e.g. [ntfy](https://ntfy.sh)) and the proxy, lets backgrounded accounts deactivate and wake on a push
— no Google/Firebase — when you'd rather trade the proxy's wedge-risk for near-zero idle CPU.

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

## 🤖 Automation intents

Token-gated, exported **send / call / open** intents let external scripts and automation apps drive
Jami headlessly — send a message, place a call, or open a conversation from anywhere on the device,
guarded by a secret token so only your own automations can trigger them.

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
