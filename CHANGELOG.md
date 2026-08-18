# 白い熊 GNU Jami — `20260807-01+2026-08-17.17-36.gf0c774eb+003`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

An upstream-sync and versioning release. No user-facing behaviour changes: every feature in `+002` is here unaltered, rebuilt on a newer upstream base. The interesting part is that this is the **first release whose version string depends on the pin to say anything at all**.

---

## 🔄 Upstream sync — two sensitive permissions dropped

Rebased onto upstream `f0c774eb0`, one commit ahead of the previous base. It removes two `uses-permission` declarations from the manifest:

- **`READ_PROFILE`** was removed from the platform at API 23. Since `minSdk` is 26 it cannot be granted on any supported device — but it still belongs to the `CONTACTS` permission group, so it inflated the contact-related access the app *appeared* to request.
- **`CHANGE_WIFI_STATE`** allows toggling and reconfiguring Wi-Fi. No `WifiManager` call exists anywhere in upstream's client or in the daemon.

`WRITE_EPG_DATA` is deliberately kept — the Android TV preview-channel integration needs it.

Upstream's justification covers upstream's own code, which is not sufficient for this fork: it has since added `NetProbe`, the recovery watchdog, `ProxyProbe` and `PushProbe`, all of which touch the network. Those were checked directly before accepting the rebase — **no `WifiManager` usage exists in the fork's added code either**, so the narrower permission set is safe here too.

The 450-commit `custom` stack replayed onto the new base with **no conflicts**, and all four install-identity edits (`applicationId`, `app_name`, `JAMI_DATADIR`, the FileProvider authority) plus the build-config invariants survived intact.

---

## 🏷 The version pin, now load-bearing

The fork's `versionName` pins the upstream commit it is built on:

```
<upstream version>+<base commit date>.<HH-MM>.g<8-char sha>+<build counter>
```

Two changes landed here. The pin gained **`HH-MM`**, because two syncs landing on the same day tied on the date and handed the ordering back to the random sha. And **`+` now opens each top-level group** — upstream's version, the pin, our counter — while the pin's own date, time and sha stay dot-joined, since all three describe one commit. Not `~`: git rejects it in a refname, it sorts above every digit, and dpkg reads it as a pre-release marker.

**This is the first sync to land on a plain upstream commit rather than a release tag.** Every previous base landed exactly on one of upstream's `android/release_*` tags, so `20260807-01` already identified it and the pin was redundant. It is not redundant now: upstream's `versionName` and `versionCode` both stand still at `20260807-01` / `503` across this sync, so **without the pin this build would be indistinguishable from the last one**.

The build counter deliberately did **not** reset. It resets on a change of `upstreamVersionName` and nothing else — under git-tracking a sync can move the base while `upstreamVersionCode` stays put, and `versionCode = upstreamVersionCode * 10000 + N` would then run backwards, which Android refuses to install as a downgrade. The pin orders the name; the counter guarantees the code.

---

## 🏗 Build

**The canonical build block now computes the pin the same way `build.gradle.kts` does.** The new format was applied to the Gradle logic but not to the block that derives the **APK filename**, so this release would have shipped an artifact named `20260807-01.2026-08-07.g46f48193+003` while carrying a `versionName` of `20260807-01+2026-08-17.17-36.gf0c774eb+003`. The block's own comment already warned that the two must mirror each other exactly; the format simply living in two places is what let one of them be missed, and that is now recorded inline alongside the fix.

The daemon gitlink did not move (`7dafdb454`), so **contrib was reused wholesale** — the patch-checksum gate found every package current and re-extracted nothing. Contrib passed its sanity gate with no rejected hunks and every package stamped; the app-only build finished in 2m 2s.

Build counter `+002` → `+003` (`versionCode 5030002 → 5030003`), on a **moved** upstream base — the first time those two facts have been true of the same release.
