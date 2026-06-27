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
- **Known upstream bug — invalid resource-directory qualifiers (the `nn_NO` class).** Upstream's recurring `i18n: automatic bump` commits sometimes add a `res/values-<lang>_<REGION>/` directory with an **underscore** (e.g. `values-nn_NO`, first seen in the `20260522-01` bump). An underscore is **not** a legal Android resource qualifier — AGP aborts late, at `:app:mergeNoPushReleaseResources`, with `Invalid resource directory name` (a clean rebase, then a build failure tens of minutes in). The valid form uses `-r<REGION>`: `values-nn_NO` → `values-nn-rNO` (matches the sibling `values-nb-rNO`). This is **not** a rebase conflict, so it slips through Step 3 untouched and is caught by the scan in **Step 4** below. The rename is a *new committed edit* on `custom` (it lands at push time under the normal `jami-android/app/src/main` staging) — it will recur on future bumps until upstream fixes it.

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

# Invalid resource-dir qualifier guard (the nn_NO class — see Step 3). Underscores are illegal;
# the region form is -r<REGION>. Auto-rename every offender so the build's resource-merge won't abort.
shopt -s nullglob
for d in jami-android/app/src/main/res/values-*_*; do
  base=$(basename "$d")                                    # e.g. values-nn_NO
  fixed="values-$(echo "${base#values-}" | sed 's/_\([A-Z][A-Z]*\)$/-r\1/')"   # -> values-nn-rNO
  if [ "$fixed" != "$base" ]; then
    echo -e "\033[1;33m>>> invalid resource dir $base -> $fixed (upstream nn_NO-class bug); renaming\033[0m"
    git mv "$d" "jami-android/app/src/main/res/$fixed"
  fi
done
shopt -u nullglob
echo -e "\033[1;36m>>> remaining underscore res dirs (must be NONE): $(ls -d jami-android/app/src/main/res/values-*_* 2>/dev/null || echo none)\033[0m"

# Runtime relative-class guard (applicationId shiroikuma.jami != namespace cx.ring — see Banked failures).
# Leading-dot class names in RUNTIME attrs (app:layoutManager / app:layout_behavior / class / android:name)
# resolve via the applicationId at runtime, NOT the namespace, so :app:lintVitalNoPushRelease aborts late
# (RelativeClassResolution) and the app would ClassNotFound-crash. tools:* attrs are design-time and exempt.
# NOT auto-fixed (rewriting class attrs is riskier than the nn_NO dir rename) — fully-qualify each by hand.
rel=$(grep -rnE '(app:layoutManager|app:layout_behavior|class|android:name)="\.[a-zA-Z]' jami-android/app/src/main/res 2>/dev/null)
if [ -n "$rel" ]; then
  echo -e "\033[1;31m>>> runtime relative-class refs — fully-qualify each to cx.ring.* before building:\033[0m"
  echo -e "\033[1;31m$rel\033[0m"
  echo -e "\033[1;33m    e.g. app:layoutManager=\".views.RtlGridLayoutManager\" -> \"cx.ring.views.RtlGridLayoutManager\"\033[0m"
else
  echo -e "\033[1;36m>>> runtime relative-class refs: none — clean.\033[0m"
fi
```

If the gitlink diverged, stop and investigate — a rebase that absorbed a daemon change is wrong. The resource-dir guard above auto-fixes the `nn_NO`-class upstream bug; if it renames anything, that rename is committed at push time (Step 6) under the normal `jami-android/app/src/main` staging. The runtime relative-class guard only **reports** (it does not auto-rewrite) — fully-qualify each listed `.foo.Bar` to `cx.ring.foo.Bar` by hand before building, or `:app:lintVitalNoPushRelease` aborts late.

## Step 5 — build, sign, deploy (apply the jami-build skill)

Hand off to **jami-build** for the build. Use its **Build + sign + deploy block verbatim** — do not hand-roll one. That block already:

- exports `JAVA_HOME`/`PATH` (JDK 21 + build-tools `36.1.0`),
- re-applies the **gnutls `--without-brotli --without-zstd` sed** (idempotent, guard on the combined `--without-idn --without-brotli` string),
- regenerates the **SWIG JNI bindings** (`daemon/bin/jni/make-swig.sh` with `PACKAGEDIR=.../libjamiclient/src/main/java`),
- computes the **`+N` version tail** from `~/tmp/.shiroikuma_jami_build` (resets to 1 when the upstream base changes — which it just did, so expect N=1),
- runs `./gradlew -Parchs=arm64-v8a -PshiroikumaBuild="$N" assembleNoPushRelease`, then zipalign + apksigner with `~/.android-keystores/jami-custom.jks`,
- backs up to `~/tmp/shiroikuma-jami_<versionName>_arm64-v8a.apk` and delivers it via the **`/after-build`** skill (auto adb-push to the phone, else scp to skhw — no prompt).

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

`fetch upstream` → new version? (else stop) → ff `master` → rebase `custom` (reconcile per Step 3) → submodule update → verify daemon gitlink + auto-fix invalid `values-*_*` resource dirs (nn_NO class) → **apply jami-build to build/sign/deploy** → user tests → on "Push": push `master`, force-with-lease `custom`, verify gitlink.

## Banked failures from real runs

- **`nn_NO` resource-qualifier bug** (first hit on the `20260515-01 → 20260522-01` sync). Upstream's i18n bump added `res/values-nn_NO/`; underscore qualifiers are illegal and AGP aborts at `:app:mergeNoPushReleaseResources` *after* a clean rebase and a long contrib build. Fixed by `values-nn_NO → values-nn-rNO`; now auto-handled by the Step 4 guard. Watch for the same shape on any future `i18n: automatic bump`. (On the `20260522-01 → 20260612-01` sync upstream had *removed* `values-nn_NO` itself and shipped a valid `values-nn`, so our rename commit became a rename/delete conflict — resolve by dropping our orphan; the fix commit goes empty and is skipped.)
- **`RelativeClassResolution` lintVital fatal** — a **class** of failure from `applicationId` (`shiroikuma.jami`) **≠** `namespace` (`cx.ring`). Leading-dot class names in **runtime** layout attrs (`app:layoutManager` / `app:layout_behavior` / `class` / `android:name`) resolve via the *applicationId* at runtime, not the namespace — so they ClassNotFound-crash in this fork (never upstream, where the two are equal) and `:app:lintVitalNoPushRelease` aborts **late, after the long native build**. First hit on the `20260522-01 → 20260612-01` sync: `res/layout/item_reaction_visualizer.xml` had `app:layoutManager=".views.RtlGridLayoutManager"`; fixed by fully-qualifying to `cx.ring.views.RtlGridLayoutManager` (committed as a normal `jami-android/app/src/main` edit). `tools:context=".client.X"` and other `tools:*` are design-time, stripped at build, and **not** flagged — leave them. Upstream keeps writing relative names in their layouts, so this recurs; the Step 4 guard now greps and reports offenders pre-build.
- **`SDK location not found`** if the build runs in a shell that didn't inherit the interactive profile: the jami-build block exports `JAVA_HOME`/`PATH` but **not** `ANDROID_HOME`. When building from a non-login/background shell (as this skill may), also `export ANDROID_HOME="$HOME/android-sdk"` (and `ANDROID_SDK_ROOT`) — or rely on a committed `jami-android/local.properties` (untracked here). The daemon still cross-compiles fine; only the AGP config step needs the SDK path.
- **Stale-APK trap.** Only sign/deploy after confirming the build returned `BUILD SUCCESSFUL` *and* the unsigned APK's mtime is newer than the build start — a failed Gradle run leaves the previous APK in the output dir, and signing it ships the wrong version. The build counter (`~/tmp/.shiroikuma_jami_build`) must be consumed only on success.

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)
