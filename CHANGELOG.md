# 白い熊 GNU Jami — `20260807-01.2026-08-07.g46f48193+001`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

An upstream sync: **`20260731-01 → 20260807-01`** (versionCode 502 → 503). Two client commits carrying **24 daemon commits**, including an **opendht minor bump (4.2.0 → 4.3.1)** and a new dhtnet. That is the most invasive daemon move since this fork's patch stack was built, and getting it to build honestly took three attempts — each failing a different way, each worth writing down.

---

## ⬆️ What upstream brought

Real fixes, in areas this fork has spent time on:

- **A UPnP mapping leaked on every registration** (`c45b6a74b`), and **UPnP is now restored after re-enable** (`29d77e8b9`). Both land squarely on the port-mapping behaviour this fork already carries a circuit breaker for.
- **A message connection is requested once per device, not per message** (`7dafdb454`) — connection-count efficiency, the same territory as our per-device transport work.
- **Clone backoff honoured on all retry paths** (`90cbdbef3`), and **every device tracked in `startFetch`** (`5afbf8926`).
- **`IncomingTrustRequest` had its signal arguments in the wrong order** (`4f604a7de`).
- Video: **stops retrying forever on a busy device** (`eb12578c1`) and **won't open a device being released** (`e0472313f`).
- git transport: RAII lifecycle, the libgit2 stream contract honoured, and a bounded P2P read timeout.

The client side was two commits: the version bump and the daemon pointer.

---

## 🩹 One of our patches had to be re-derived

`dhtnet-shutdown-reason-diag` — which names *why* a peer connection was torn down — lost two of its eight hunks against the new dhtnet. Upstream had:

- dropped the `return false;`/`return true;` framing the TLS-shutdown handler, so the hunk's context no longer matched, and
- **added `shutdownAsync()`**, rerouting the write-error path through it, because `shutdown()` runs a user callback on every channel and must not run on a thread already inside one.

Regenerated against the new source rather than hand-edited — the preceding six patches applied into a scratch tree, the *source* edited there, `diff -u` taken, and the result dry-run back onto a pristine extract. `shutdownAsync` also gained the reason parameter and forwards it, so the write-error path still names itself instead of degrading to `unspecified`; that path is the exact case the diagnostic exists for.

**Everything else survived**, including the three patches most at risk: `conversation.cpp`, `contact_list.cpp` and `jamiaccount.cpp` — the last at **+122/−104** — all applied clean, as did all five opendht patches against 4.3.1.

---

## 🏗 Two build-system defects this bump exposed

Both were latent for months and needed exactly this combination to surface.

**A version bump is invisible to the patch-checksum gate.** `SK-PATCHSUM` force-re-extracts a contrib package when *our patch set* changes. It has no notion of the package's own version — so with opendht and dhtnet both bumped and our patches untouched, the gate passed and the old extracted sources stayed. The evidence was unambiguous once looked at: `libopendht.a` rebuilt today, `libdhtnet.a` still dated two days earlier. Without catching it, the new daemon would have linked a dhtnet built against opendht 4.2.0 — the `+163` stale-library failure in new clothing.

**Dependency order decides which `rules.mak` a package is extracted with.** dhtnet depends on opendht, so `make .dhtnet` pulls `.opendht` in as a dependency. With the opendht section sitting *after* dhtnet in the build block, that dependency-triggered extraction ran while `opendht/rules.mak` still had none of our `$(APPLY)` lines: a pristine opendht was extracted and built, and the later direct guards then applied 2 of our 5 patches onto it **out of order**. The marker census told the story — `SK-PUSHGET` six times where one application belongs, `SK-SUBREFRESH` and `connectDeadlineFired` at zero — and `dht_proxy_client.cpp` stopped compiling.

The block already carried this rule for one package: *pjproject must be built before dhtnet*. It was written as a fact about pjproject rather than as what it is — **every package we patch must be set up before anything that depends on it**. opendht now precedes dhtnet, with the reasoning recorded in the block.

A third, smaller one: `patch` failures inside the block do not stop it. There is no `set -e`, so a failed hunk and a failed `make` both scrolled past and the build continued toward a link against stale objects. That is why this release was caught at all — the build monitor was grepping for `Hunk … FAILED`, not relying on the exit status.

---

## 🔢 Versioning

`upstreamVersionName` genuinely changed this time, so the build counter **resets** — `+012` → `+001`, with `versionCode` going `5020012 → 5030001`. Still strictly increasing, because the counter is multiplied by the upstream code: the pin orders the name, the counter guarantees the code, and they move independently by design.

---

## 🌐 Carried forward

The full-locale rebrand from `+011` is unchanged: every user-visible mention of the app reads **白い熊 GNU Jami** across all 100 locales. The typing-indicator animator fix is now upstream's (merged as `af3fbe15f`), so this fork no longer carries a patch for it.
