---
name: upstream-new-version
description: One-command upstream sync + rebuild for the user's ShiroiKuma0/jami fork. Checks savoirfairelinux/jami-client-android for a newer version; if one exists, fast-forwards `master`, rebases the `custom` branch onto it (reconciling conflicts against the banked customization layers), then builds/signs/deploys the new APK by applying the jami-build skill. Holds all pushes until the user has tested the build on-device and explicitly says "Push". Use when the user runs /upstream-new-version, or asks to pull/sync/bump to a new Jami version, rebase their changes onto upstream, or rebase-and-rebuild the fork.
---

# upstream-new-version — sync to a newer upstream Jami and rebuild

This is an **orchestration layer on top of the `jami-build` skill**. It does not redefine any build fact — it sequences the upstream-sync + rebase work, then hands off to `jami-build` for the build/sign/deploy. `jami-build` remains the single source of truth for every build/identity/daemon/layout invariant.

## Step 0 — read jami-build first (mandatory)

Before doing anything, read `.claude/skills/jami-build/SKILL.md` (or invoke the `jami-build` skill). Everything below assumes its banked facts:

- Remotes: **`origin`** = `git@github.com:ShiroiKuma0/jami.git` (SSH, **push here**); **`upstream`** = `https://github.com/savoirfairelinux/jami-client-android.git` (HTTPS, **fetch only**).
- Branches: **`master`** mirrors upstream (fast-forward only, never carries our changes); **`custom`** carries the four install edits + theme/fonts/split-view layers + build-config edits, and is **rebased** onto each upstream tip.
- Working tree: `~/git/shiroikuma-jami`; Gradle root `jami-android/`; one submodule `daemon` → `https://review.jami.net/jami-daemon` (Gerrit, pinned by the superproject gitlink).
- Use the `r()` stderr-reddening helper in every command block: `r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }`.

## Non-negotiables this skill must honor

These are the CLAUDE.md / jami-build invariants that this flow most easily violates — do not break them:

- **`export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`** before any `gradlew`. (Git steps don't need it; the build does — it's in the jami-build build block.)
- **The `daemon/` gitlink stays pinned to upstream.** Never modify, bump, or commit anything under `daemon/`. The gitlink advances *automatically* with the rebase (it rides on `master`, which we ff to `upstream/master`) — we never set it by hand. Verify before any push: `git ls-tree custom daemon == git ls-tree upstream/master daemon`.
- **`android.newDsl=false` MUST stay** in `jami-android/gradle.properties` (protobuf plugin needs the legacy `BaseExtension`). Built-in Kotlin (`android.builtInKotlin=true`) is on and tolerates it. If a conflict touches these flags, keep this shape.
- **Never stage** `daemon/`, the generated SWIG bindings under `jami-android/libjamiclient/src/main/java/net/jami/daemon/`, or the gnutls `sed` to `daemon/contrib/src/gnutls/rules.mak` (per-build re-apply, never committed).
- **Hold every push until the user has tested the build on-device and explicitly says "Push".** The rebase rewrites `custom`, and the build can regress on a new upstream — so even a "successful" build is not pushed until on-device testing confirms it. This overrides the bare "Sync to a new upstream version" block in jami-build, which lists the pushes inline; here they move to the very end, gated on the user's word. See **Step 6**.

## Step 1 — check whether there is a new upstream version

Fetch upstream and compare. **Report and stop without building if there is nothing new.**

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }
cd ~/git/shiroikuma-jami

r git fetch upstream
r git fetch origin

# Is upstream/master strictly ahead of our master?
if git merge-base --is-ancestor upstream/master master; then
  cur=$(git show master:jami-android/app/build.gradle.kts | grep -oP 'versionName = "\K[^"]+' | head -1)
  echo -e "\033[1;36m>>> No new upstream version. master is already at or above upstream/master (versionName ${cur}). Nothing to do.\033[0m"
else
  old_vn=$(git show master:jami-android/app/build.gradle.kts          | grep -oP 'versionName = "\K[^"]+' | head -1)
  new_vn=$(git show upstream/master:jami-android/app/build.gradle.kts | grep -oP 'versionName = "\K[^"]+' | head -1)
  old_vc=$(git show master:jami-android/app/build.gradle.kts          | grep -oP 'versionCode = \K[0-9]+'  | head -1)
  new_vc=$(git show upstream/master:jami-android/app/build.gradle.kts | grep -oP 'versionCode = \K[0-9]+'  | head -1)
  ahead=$(git rev-list --count master..upstream/master)
  echo -e "\033[1;33m>>> New upstream version: versionName ${old_vn} -> ${new_vn} (versionCode ${old_vc} -> ${new_vc}), ${ahead} new upstream commit(s).\033[0m"
fi
```

- "New version" = `upstream/master` is **not** an ancestor of our `master` (i.e. it advanced). The `versionName`/`versionCode` deltas are reported for the user but the advance of `upstream/master` is the real trigger.
- If there is nothing new, **stop here** — do not ff, do not rebase, do not build. Tell the user the current version and that they're up to date.
- Also note whether the **daemon submodule moved**: if `git ls-tree upstream/master daemon` differs from `git ls-tree master daemon`, the daemon advanced, so the build will **recompile the daemon + all contrib** (long — tens of minutes, hundreds of MB). Warn the user.

## Step 2 — fast-forward master, rebase custom (do NOT push yet)

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }
cd ~/git/shiroikuma-jami

# sanity: working tree must be clean before rebasing (the gnutls sed lives in daemon/ and is fine; app-tree must be clean)
r git status --short

r git checkout master
r git merge --ff-only upstream/master      # master only ever fast-forwards

r git checkout custom
r git rebase master                        # replay the custom stack onto the new tip
```

- **Do not `git push` here.** Both `origin master` and the force-push of `custom` are deferred to Step 6.
- If the rebase stops on conflicts, go to **Step 3**, resolve, `git rebase --continue`. If it goes irrecoverable, `git rebase --abort` and re-plan (the local branches are unchanged by an aborted rebase except `master`, which is safely ff'd).
- After a clean rebase, update the submodule to the (now-advanced) pinned commit:

```bash
r git submodule update --init --recursive  # daemon follows the superproject gitlink
```

## Step 3 — reconcile conflicts (apply judgment from jami-build)

The `custom` stack is ~31 commits as of handoff (`git log master..custom --oneline` to see them; commit subjects are self-describing). Conflicts cluster in a few predictable places — re-derive the *intent* against the new upstream files rather than blindly taking either side:

- **`jami-android/app/build.gradle.kts`** — the highest-probability conflict (upstream bumps `versionName`/`versionCode` here every release, and our version-tail logic + identity edits live here). Reconcile by re-applying, against upstream's new values:
  1. `applicationId = "shiroikuma.jami"` in `defaultConfig` (keep `namespace = "cx.ring"`).
  2. `-DJAMI_DATADIR=/data/data/shiroikuma.jami/files` (upstream writes `$namespace`).
  3. The per-build `+N` tail: `versionCode = <upstreamCode>*10000 + shiroikumaBuild`, `versionName = "<upstreamBase>" + (if N>0 "+N")`, reading the property `shiroikumaBuild` (default 0). **Use upstream's NEW base numbers**, keep our tail wrapper.
  4. The AGP-9 built-in-Kotlin block: removed `alias(libs.plugins.kotlin.android)`, the `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17; suppressWarnings = true } }`, `tasks.withType<KotlinCompile>()`, the `JvmTarget`/`KotlinCompile` imports, and `@file:Suppress("DEPRECATION")` at the top.
- **`jami-android/gradle.properties`** — keep `android.builtInKotlin=true`, **`android.newDsl=false` (mandatory)**, and the self-included `android.suppressUnsupportedOptionWarnings=android.builtInKotlin,android.newDsl,android.suppressUnsupportedOptionWarnings`.
- **`jami-android/build.gradle.kts` (root)** — keep the `kotlin.android` plugin alias **removed** from root `plugins { }`.
- **`jami-android/libjamiclient/build.gradle.kts`** — keep `suppressWarnings = true` in `kotlin { compilerOptions { } }`.
- **Install identity, stable targets** — `app_name` → `白い熊 GNU Jami` in `app/src/main/res/values/strings.xml`; FileProvider `<provider>` `android:authorities="${applicationId}.file_provider"` in `app/src/main/AndroidManifest.xml`.
- **Theme / fonts / split-view layers** — localized to the files enumerated in jami-build's *Customization layers* (e.g. `values/colors.xml`, `values-night/colors.xml`, `values/styles.xml`, drawables, `java/cx/ring/utils/{FontPrefs,ColorPrefs,FontUtil,UiPrefs}.kt`, `FontsSettingsFragment.kt`, the recolour/apply sites in `ConversationAdapter`/`MessageBubble`/`SmartListViewHolder`/`HomeFragment`/`AccountAdapter`/`AvatarDrawable.kt`, the menu/layout XML). If upstream renamed/moved a binding site, re-point the same edit there.
- **`.gitmodules` drift guard** — `git diff master:.gitmodules custom:.gitmodules` (and vs upstream). If upstream ever changes the daemon URL/path, adjust before the submodule update.

**Reconciliation rule of thumb:** the install-identity targets (`defaultConfig`, `app_name`, `JAMI_DATADIR`, FileProvider authority) are stable across versions; the build-config flags sit on upstream's own flags and so conflict most often but resolve to the fixed shape above; the theme/fonts edits are additive and rarely conflict unless upstream rewrote the same file. Never resolve a conflict by touching `daemon/` or by changing the daemon gitlink.

## Step 4 — verify the rebase result before building

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }
cd ~/git/shiroikuma-jami

# daemon gitlink on custom must equal upstream's (we never carry a daemon change)
if [ "$(git ls-tree custom daemon | awk '{print $3}')" = "$(git ls-tree upstream/master daemon | awk '{print $3}')" ]; then
  echo -e "\033[1;36m>>> daemon gitlink OK (matches upstream)\033[0m"
else
  echo -e "\033[1;31m>>> daemon gitlink DIVERGED from upstream — STOP, do not build/push. Investigate the rebase.\033[0m"
fi

r git log master..custom --oneline   # the replayed custom stack; subjects should look familiar
r git status --short                  # only daemon/contrib (the gnutls sed) may be dirty; the app tree should be clean
```

If the gitlink diverged, stop and investigate — a rebase that absorbed a daemon change is wrong.

## Step 5 — build, sign, deploy (apply the jami-build skill)

Hand off to **jami-build** for the build. Use its **Build + sign + deploy block verbatim** — do not hand-roll one. That block already:

- exports `JAVA_HOME`/`PATH` (JDK 21 + build-tools `36.1.0`),
- re-applies the **gnutls `--without-brotli --without-zstd` sed** (idempotent, guard on the combined `--without-idn --without-brotli` string),
- regenerates the **SWIG JNI bindings** (`daemon/bin/jni/make-swig.sh` with `PACKAGEDIR=.../libjamiclient/src/main/java`),
- computes the **`+N` version tail** from `~/tmp/.shiroikuma_jami_build` (resets to 1 when the upstream base changes — which it just did, so expect N=1),
- runs `./gradlew -Parchs=arm64-v8a -PshiroikumaBuild="$N" assembleNoPushRelease`, then zipalign + apksigner with `~/.android-keystores/jami-custom.jks`,
- backs up to `~/tmp/shiroikuma-jami_<versionName>_arm64-v8a.apk` and offers the gated `adb push` to `/sdcard/tmp/`.

Notes specific to a fresh upstream:
- Because the **daemon submodule moved**, contrib recompiles from source — the build is **long** (tens of minutes, hundreds of MB). This is expected, not a hang.
- If the build breaks on the **AGP-9 built-in-Kotlin** config after the bump (the jami-build "migration caveat"), the fallback is to restore `android.builtInKotlin=false` + the `kotlin.android` plugin alias in `:app`+root, keep `newDsl=false`, and re-mute via the self-included suppress line.
- If a **new contrib package** fails on a host-lib header (same class as gnutls/brotli), disable that feature in the package's `rules.mak` configure line (re-applied each build, never committed) — see jami-build *Other build traps*.
- A failed Gradle run signs **no** APK and does not consume the build counter — paste "What went wrong / Caused by", fix, rebuild.

## Step 6 — push ONLY after the user tests and says "Push"

Stop after Step 5 with a built, signed, sideloaded APK and **wait**. Do not push. When the user has tested on-device and **explicitly says "Push"**, then — and only then — in that turn:

```bash
r() { "$@" 2> >(sed $'s/.*/\033[1;31m&\033[0m/' >&2); }
cd ~/git/shiroikuma-jami

r git checkout master
r git push origin master                       # ff, safe

r git checkout custom
r git push --force-with-lease origin custom    # rebased history → force-with-lease

# post-push verification: fork's custom daemon gitlink still equals upstream
if [ "$(git ls-tree origin/custom daemon | awk '{print $3}')" = "$(git ls-tree upstream/master daemon | awk '{print $3}')" ]; then
  echo -e "\033[1;36m>>> pushed; origin/custom daemon gitlink matches upstream — OK\033[0m"
else
  echo -e "\033[1;31m>>> origin/custom daemon gitlink DIVERGED — investigate immediately\033[0m"
fi
```

Staging discipline still applies if any conflict resolution required a *new committed edit* beyond the replayed stack: stage only `jami-android/app/src/main` (app-source) or only the named build-config files (`gradle.properties`, the three `build.gradle.kts`) — **never** `daemon/`, the generated SWIG bindings, or the gnutls sed.

## One-line summary of the flow

`fetch upstream` → new version? (else stop) → ff `master` → rebase `custom` (reconcile per Step 3) → submodule update → verify daemon gitlink → **apply jami-build to build/sign/deploy** → user tests → on "Push": push `master`, force-with-lease `custom`, verify gitlink.
