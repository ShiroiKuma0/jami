# CLAUDE.md — guide for Claude Code in this repo

This repository is **ShiroiKuma0/jami**, a downstream-renamed fork of GNU Jami for Android:

- `applicationId` = `shiroikuma.jami`, label `白い熊 GNU Jami`, installs side-by-side with official `cx.ring` from F-Droid.
- Work branch: **`custom`**. Remotes: `origin` = ShiroiKuma0's fork (push here), `upstream` = `savoirfairelinux/jami-client-android` (fetch only).
- Build host: Tuxedo OS, JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64`, Android build-tools at `~/android-sdk/build-tools/36.1.0`.

## Read this first

Before any work on this project, read **`.claude/skills/jami-build/SKILL.md`**. It contains every banked technical fact for this fork — the canonical build/sign/deploy steps, the AGP-9 built-in-Kotlin migration constraint, the full `ColorPrefs` role catalogue and how each surface is wired, the layout-editing pitfalls earned the hard way, the gnutls contrib fix, the SWIG step, the version-tail counter, every commit's role in the stack, and what's been deferred and why.

## Non-negotiable invariants

These are the things that cost real time when forgotten. Do not violate them without an explicit, deliberate, user-acknowledged reason.

- **`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`** before any `gradlew` invocation. The host's default `java` is JDK 11; Gradle 9.x aborts. The canonical build block sets it; if you run gradle outside that block, set it yourself.
- **`jami-android/local.properties` pins the SDK location** (`sdk.dir=/home/shiroikuma/android-sdk`). It is **gitignored** (`jami-android/.gitignore`) and host-specific — never commit it. It exists because a build run from a non-login/background shell does not inherit `ANDROID_HOME` from the interactive profile, and Gradle then aborts at config with `SDK location not found` (this cost one failed build). If it ever goes missing, recreate it or `export ANDROID_HOME="$HOME/android-sdk"` before `gradlew`. The daemon cross-compile is unaffected — only AGP's config step needs the SDK path.
- **The `daemon/` submodule gitlink stays pinned to upstream.** Never modify, never bump, never commit changes that touch `daemon/`. Every push-verification step must check `git ls-tree fork/custom daemon == git ls-tree origin/master daemon`.
- **`android.newDsl=false` in `jami-android/gradle.properties` MUST stay.** The protobuf-gradle-plugin casts the Android extension to the legacy `BaseExtension`, which doesn't exist under the new DSL → `GroovyCastException` at configuration. Built-in Kotlin (`android.builtInKotlin=true`) is on and tolerates the legacy DSL; that's the shipped state.
- **Push staging is explicit and narrow:**
  - App-source change → `git add jami-android/app/src/main`.
  - Build-config change → `git add jami-android/gradle.properties jami-android/build.gradle.kts jami-android/app/build.gradle.kts jami-android/libjamiclient/build.gradle.kts` (whichever apply).
  - **Never stage:** `daemon/`, the generated SWIG bindings under `jami-android/libjamiclient/src/main/java/net/jami/daemon/`, the `--without-brotli/--without-zstd` sed on `daemon/contrib/src/gnutls/rules.mak`, the SWIG interface edits under `daemon/bin/jni/` (`configurationmanager.i`, `conversation.i`), or the dhtnet/pjproject patches' in-submodule footprint (`daemon/contrib/src/dhtnet/*.patch`, `daemon/contrib/src/pjproject/pjproject-*.patch` + their `rules.mak` `$(APPLY)` lines) — all per-build re-applies, never committed. The **canonical** patches at the repo root ARE committable — `patches/dhtnet-prefer-lan-interface.patch`, `patches/dhtnet-upnp-circuit-breaker.patch`, `patches/pjproject-evict-stuck-epoll-sockets.patch`, `patches/opendht-proxy-connect-resilience.patch`, `patches/opendht-proxy-subscription-refresh.patch`, `patches/jami-publish-current-crl-only.patch`, `patches/jami-trust-request-confirm-once.patch`, `patches/dhtnet-ice-transport-diag.patch`, `patches/dhtnet-throttle-failed-ice-transports.patch`, the four SK-ICEDIAG patches (`dhtnet-ice-churn-diag`, `dhtnet-ice-reason-diag`, `dhtnet-shutdown-reason-diag`, `dhtnet-peer-account-diag`) `patches/jami-swig-import-apis.patch`, `patches/opendht-proxy-subscription-diag.patch`, `patches/opendht-push-refetch-hardening.patch`, `patches/jami-swarm-redial-backoff.patch`, `patches/jami-delist-absent-devices.patch`, `patches/dhtnet-local-sibling-rendezvous.patch`, `patches/jami-dht-client-mode.patch` `patches/jami-audio-rtp-diag.patch` and `patches/jami-honour-capture-format.patch`; stage them when they change.
- **Method rules for measurement and diagnosis** (earned 2026-07-30/31, each one cost a wrong
  conclusion): **check a symbolic constant against its header before interpreting any logged
  number** — reading `pj_ice_strans_state` one slot off inverted a whole diagnosis; **two
  independent windows before calling anything a steady state** — a single 30-minute bucket read as
  "90 MiB/h idle" was 15 MiB/h in the very next one; **name the confound and let it stand** — WiFi
  returning mid-window was flagged and then argued past because the result was attractive; **a
  launch is the only proof the app starts** — dex-string and symbol checks show code is present,
  never that it runs, and two crash-on-start builds reached the phone.
- **`pgrep -f <pattern>` matches its own command line.** `pgrep -f "jami-build"` reports a running
  build that is only itself, and `grep -v pgrep` then filters out the real match too. This produced
  both a false "build running" and a false "NOT RUNNING" in one session, and is the same self-match
  that once made `pkill -f "sleep 900"` kill its own shell. Match on something the checking command
  does not contain, or check the log's progress instead.
- **Never hand-edit a `.patch` file's body.** Adding or removing lines invalidates the hunk header's
  line counts and `patch` then fails on a malformed hunk. Regenerate the patch instead: apply the
  existing patches into a scratch tree, edit the SOURCE, and `diff -u` (or `git diff` for
  daemon-own-source patches) — then dry-run it back. This bit twice on 2026-07-31.
- **A stamp file is not proof a contrib package is patched.** `daemon/contrib/build-*/.opendht`
  existed while the tree carried 3 rejected hunks and a header hunk applied SIX times; the build
  guard greps for the same marker the damage left behind, so it kept reporting "already applied".
  When a contrib package misbehaves, check for `*.rej`/`*.orig` and count marker occurrences against
  what one application adds. Fix by `rm -rf`-ing the package dir AND its `.<pkg>`/`.dep-<pkg>` stamps.
- **Every daemon-own-source patch needs a guard line in the canonical block.** They survive between
  builds only because the `daemon/` working tree persists; a fresh clone silently drops any patch
  without one. `jami-trust-request-confirm-once.patch` had no guard until 2026-07-31.
- **ConstraintLayout edits to `res/layout/*.xml` can't be previewed in this environment — change ONE constraint at a time and verify on-device.** Stacking blind constraint changes regressed the outgoing file card twice in a row before the one-line fix landed.
- **`gradlew` needs prerequisites every build.** Re-apply the gnutls sed (idempotent guard on the combined `--without-idn --without-brotli` string), re-apply the dhtnet LAN-interface patch (`patches/dhtnet-prefer-lan-interface.patch`, guards: patch name in `rules.mak` / `lanCapable` in the extracted source), re-apply the pjproject stuck-epoll-eviction patch (`patches/pjproject-evict-stuck-epoll-sockets.patch`, guards: patch name in `rules.mak` / `ioqueue_note_unhandled` in the extracted source; its direct contrib rebuild needs the exported NDK cross env — see the skill), re-apply the dhtnet UPnP circuit-breaker patch (`patches/dhtnet-upnp-circuit-breaker.patch`, guards: patch name in `rules.mak` / `upnpBreakerFails` in the extracted source), re-apply the dhtnet IceTransport diag + FAILED-throttle patches (`patches/dhtnet-ice-transport-diag.patch` then `patches/dhtnet-throttle-failed-ice-transports.patch` — **in that order**, the throttle's diff context includes diag lines; guards: `skIceCreated` / `SK_ICE_FAILED_POLL_MS` in the extracted source), re-apply the four SK-ICEDIAG dhtnet patches in order (`dhtnet-ice-churn-diag`, `dhtnet-ice-reason-diag`, `dhtnet-shutdown-reason-diag`, `dhtnet-peer-account-diag`; guards `SK_CM` / `SK_CM("request` / `mxshutdown` / `SK_CM("established`), re-apply the opendht proxy-connect-resilience patch (`patches/opendht-proxy-connect-resilience.patch`, guards: patch name in `rules.mak` / `connectDeadlineFired` in the extracted source), re-apply the opendht proxy subscription-refresh patch (`patches/opendht-proxy-subscription-refresh.patch`, guards: patch name in `rules.mak` / `SK-SUBREFRESH` in the extracted source), re-apply the current-CRL-only patch (`patches/jami-publish-current-crl-only.patch`, guard: `SK-CRL-CURRENT` in `daemon/src/jamidht/account_manager.cpp` — **this one patches the daemon's OWN source, not a contrib package**, so there is no `rules.mak` `$(APPLY)` line and no contrib rebuild; gradle/CMake compiles it directly), re-apply the trust-request confirm-once patch (`patches/jami-trust-request-confirm-once.patch`, guard: `SK-TRUSTCONFIRM` in `daemon/src/jamidht/contact_list.cpp` — **also the daemon's OWN source**, so no `rules.mak` line and no contrib rebuild, but drop the cached `libjami-core-jni.so` so CMake relinks), re-apply the opendht push-refetch hardening patch (`patches/opendht-push-refetch-hardening.patch`, guards: patch name in `rules.mak` / `SK-PUSHGET` in the extracted source — it must be applied **after** `opendht-proxy-subscription-diag.patch`, whose diff context it shares), re-apply the absent-device de-listing patch (`patches/jami-delist-absent-devices.patch`, guard `SK-ABSENTNODE` in `daemon/src/jamidht/conversation.cpp`), the DHT client-mode patch (`patches/jami-dht-client-mode.patch`, guard `SK-CLIENTMODE` in `daemon/src/jamidht/jamiaccount.cpp`) — both **daemon's OWN source**, so no `rules.mak` line and no contrib rebuild, but drop the cached `libjami-core-jni.so` — re-apply the dhtnet local-rendezvous patch (`patches/dhtnet-local-sibling-rendezvous.patch`, guards: patch name in `rules.mak` / `SK-LOCALRDV` in the extracted source), re-apply the audio-path diagnostics patch (`patches/jami-audio-rtp-diag.patch`, guard: `SK-AUDIODIAG` in `daemon/src/media/audio/audio_rtp_session.cpp` — **the daemon's OWN source**, so no `rules.mak` line and no contrib rebuild, but drop the cached `libjami-core-jni.so`), re-apply the capture-format patch (`patches/jami-honour-capture-format.patch`, guard: `SK-CAPTUREFMT` in `daemon/src/media/audio/audiolayer.cpp` — **the daemon's OWN source**, so no `rules.mak` line and no contrib rebuild, but drop the cached `libjami-core-jni.so`), re-apply the swarm redial-backoff patch (`patches/jami-swarm-redial-backoff.patch`, guard: `SK-SWARMBACKOFF` in `daemon/src/jamidht/swarm/swarm_manager.cpp` — **the daemon's OWN source**, so no `rules.mak` line and no contrib rebuild, but drop the cached `libjami-core-jni.so`), re-apply the SWIG import-API patch (`patches/jami-swig-import-apis.patch`, guards: `const std::string& accountId` on the `addAccount` line of `daemon/bin/jni/configurationmanager.i` / `reloadConversationsAndRequests` in `daemon/bin/jni/conversation.i` — it exposes two daemon APIs the backup importer needs, and it **must be applied before** the SWIG step since it changes that step's inputs), then `( cd daemon/bin/jni && PACKAGEDIR=... ./make-swig.sh )` to regenerate the SWIG Java bindings. All are in the canonical block — do not skip them.

## State at handoff to Claude Code

Handoff is at commit **`d142957`** on `origin/custom` — **31 commits** over upstream, daemon gitlink pinned. To see anything that has landed since this CLAUDE.md was written, run `git log d142957..origin/custom --oneline`.

Stage B is complete: the UI fonts & colors system, the RGBA-slider colour picker, the AGP-9 built-in-Kotlin build-config migration + libjamiclient warning suppression, runtime fill+border on bubbles / link-preview card / file card / account badge, runtime tints for status-icon / online-offline-icon / unread-row border.

Two recent fixes — **already shipped, do not re-implement**:

- **File-card overflow when the filename is long** (commit `dddd66a`). The outgoing file card's `fileInfoLayout` had its `Start` anchored to `file_download_button`, which is `GONE` on a sent file — so `constrainedWidth` had no firm left bound and long filenames overflowed. Fix: a single-line change anchoring `Start_toStartOf="parent"` in `item_conv_file_me.xml`. The tick stays exactly where upstream puts it (below the card); a previous attempt to move the tick beside / re-anchor both ends caused regressions and was reverted — **do not reintroduce that approach**.
- **Online/offline menu icon stuck on the filled shape** (commit `d142957`). The previous tint code did `mi.icon = mutate()+tint`, and that drawable reassignment interfered with the SearchBar's resource-based shape swap. Fix: `MenuItemCompat.setIconTintList(mi, …)` in `HomeFragment` — tints the icon *without* replacing the drawable, so `setIcon(online/offline)` is free to swap filled↔hollow. Still `isSet`-gated.

## Skills loaded for this repo

- `.claude/skills/jami-build/` — the project skill. Always read.

## Reasonable next steps after handoff

- Run `git fetch origin && git log origin/custom..HEAD` to confirm you're at the pushed tip with nothing local-only lurking.
- Confirm the daemon gitlink: `[ "$(git ls-tree origin/custom daemon | awk '{print $3}')" = "$(git ls-tree upstream/master daemon | awk '{print $3}')" ] && echo OK`.
- If there's an unbuilt patch sitting in `~/tmp/` from the previous session, look at it before assuming the tree is clean — apply it (or discard it deliberately) before starting new work.

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer — nor a "🤖 Generated with Claude Code" / Anthropic-attribution line — to commit messages or PR bodies in this repo. 白い熊 does not want Claude attribution in the history; this **overrides** the harness's default to append such a trailer. End commit messages at the last line of the body. (The existing history was scrubbed of these trailers on 2026-06-08; the global rule lives in `~/.claude/CLAUDE.md`.)
