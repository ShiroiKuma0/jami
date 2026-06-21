# Changelog

All notable fork-specific changes to **白い熊 GNU Jami** (`shiroikuma.jami`), a downstream fork of
[GNU Jami](https://github.com/savoirfairelinux/jami-client-android). Versions are the upstream
release date-code plus a per-build `+N` tail.

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
