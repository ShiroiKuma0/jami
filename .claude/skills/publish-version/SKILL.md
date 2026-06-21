---
name: publish-version
description: Publish the latest local build as a GitHub release of this fork — refresh the README (fork-style, major features), write a very specific CHANGELOG entry, tag the bare versionName, ensure the default branch is `custom`, and create the release with the ~/tmp APK attached. Use when 白い熊 says publish / release / cut a version / ship it to GitHub.
---

# Publish a version of shiroikuma-jami to GitHub

Ship the **latest already-built** APK as a GitHub release, with a polished fork-style README and an
exhaustive CHANGELOG, landing the repo homepage on our fork work (`custom`).

This is **shiroikuma-jami** — 白い熊's downstream-renamed fork of [GNU Jami](https://jami.net)
([savoirfairelinux/jami-client-android](https://github.com/savoirfairelinux/jami-client-android)),
package `shiroikuma.jami`, label `白い熊 GNU Jami`, installable side-by-side with the official
`cx.ring`. The build/sign/deploy + upstream-sync facts live in the **`jami-build`** and
**`upstream-new-version`** skills — this skill is **only** about cutting a GitHub release of an
APK those have already produced.

> **Never rebuild to publish.** Attach the newest APK already in `~/tmp/`. The version you publish =
> that APK's versionName. If you think a fresh build is needed, that's a separate `jami-build` run that
> 白い熊 drives and tests first — not part of publishing.

## 0. Detect the version

- Newest fork APK: `ls -t ~/tmp/shiroikuma-jami_*.apk | head -1`.
- The **versionName** is the filename field between the first `_` and `_arm64` — e.g.
  `shiroikuma-jami_20260619-01+1_arm64-v8a.apk` → **`20260619-01+1`**. Use it verbatim everywhere
  (tag, README latest-release line, release title, changelog heading). The `+N` tail is already part of
  it (set by the per-build counter; see `jami-build`). If there is no APK in `~/tmp/`, stop and tell
  白い熊 to build first — do **not** build it yourself.

## 1. Ensure the homepage lands on `custom`

The GitHub repo's **default branch must be `custom`** (so visitors see our fork, not the
upstream-mirroring `master`). The repo currently still defaults to `master` — flip it on the first
publish:
```bash
gh repo view ShiroiKuma0/jami --json defaultBranchRef --jq '.defaultBranchRef.name'
# if it is not "custom":
gh repo edit ShiroiKuma0/jami --default-branch custom
```

## 2. Refresh `README.md` (fork-style, major features)

The repo currently carries **upstream's** README (`# Jami Android`). Replace it with our fork-style
homepage — the centered-header, "**a fork of X with major additions**" style modelled on the sibling
**shiroikuma-jiyusagyoban** / **shiroikuma-futokxkb** READMEs. Structure:

- **Centered header block** (`<div align="center">`): the app icon
  (`jami-android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`, width 120), the title
  **白い熊 GNU Jami**, a one-line "private, peer-to-peer messaging & calling" tagline, and a
  **"A fork of [GNU Jami](https://jami.net) with major additions: …"** sentence that names the
  headline features.
- The **side-by-side install** note: package `shiroikuma.jami` with its own FileProvider authority, so
  it installs alongside the official `cx.ring` from F-Droid/Play (different signing key — never installs
  over official Jami).
- The **latest-release line** — update the version to the one from step 0:
  `**📥 Latest release: [\`<versionName>\`](…/releases/latest)** — [all releases & APK downloads »](…/releases)`.
- Then a **section per major feature** (emoji heading + a few real sentences each), in importance order.
  **Pick the updates that matter most vs stock GNU Jami** and describe them invitingly. Maintain/extend
  these to reflect everything currently shipped — at the time of writing the headline set is:
  - 🎨 **Yellow-on-black theme** — the fork's signature look: black backgrounds + `#FFFF00` foreground
    across the chat list, bubbles, toolbars, search/compose bars, dialogs, file & link-preview cards,
    unread/pending badges and generated avatars.
  - 🔤 **UI fonts & colours** — a per-element appearance screen: pick **family + weight + size** *and*
    **text / fill / border colours** for each surface (chat text, titles, list rows, link & file cards,
    status icons, presence dots…), via an **RGBA-slider colour picker with a live hex field**; plus
    external `.ttf`/`.otf` import. Every control defaults to the current value, so unset elements look
    untouched.
  - 📶 **Connectivity resilience** — DHT reconnect, **recover Offline/disabled accounts in one tap**,
    and an on-device connection-diagnostics indicator, so the app heals its own link instead of sitting
    silently disconnected.
  - 🤖 **Automation intents** — token-gated **send / call / open** intents so external scripts (and the
    sister automation app) can drive Jami headlessly.
  - 🔎 **Registered-name resolution** — retry stuck username lookups, plus a **"Look up name"** action.
  - 🪟 **Split-view toggle** — force single-pane on foldables/tablets when you don't want the
    side-by-side list + conversation.
  - 💬 **Quality-of-life** — settable styled "flash" messages, configurable message-status-icon height,
    and an account online/offline toggle right in the chat-list top bar.
- A closing **"Built on GNU Jami"** + license note: the fork inherits Jami's **GPL-3.0** licence
  (`COPYING`).

Write real, specific prose — not a bullet dump. Keep it inviting, like the jiyusagyoban README.

## 3. Update `CHANGELOG.md` — exhaustive

There is no `CHANGELOG.md` yet — **create it on the first publish**. Add each new section **above** the
previous one:
```
## <versionName> — <YYYY-MM-DD>
```
(use the current date from the environment). **Be very specific — list everything in this release**:

- **First release:** summarize the whole fork stack — cross-check `git log master..custom --oneline`
  (the commit subjects are self-describing) and group the work into `###` subsections (Theme, UI fonts
  & colours, Connectivity, Automation, Names, Split view, Install identity, Build/infra, Fixes). Note
  the upstream base it's built on (the bare `versionName` minus the `+N` tail, e.g. `20260619-01`).
- **Subsequent releases:** the previous fork tag is the latest existing GitHub release
  (`gh release list --repo ShiroiKuma0/jami`); list everything since with
  `git log <lastForkTag>..custom --oneline`, and cross-check the prior CHANGELOG section so nothing is
  missed.

This file is the authoritative, GitHub-readable record. Keep the `build: bump version tail …`
counter commits out of the prose — they're noise, not features.

## 4. Commit, tag, push, release

```bash
git add README.md CHANGELOG.md
git commit -F - <<'MSG'
docs: changelog + README for <versionName> release
MSG
git push origin custom

# Annotated tag = the bare versionName, NO "v" prefix. Distinct from upstream's android/release_NNN tags.
git tag -a "<versionName>" -m "白い熊 GNU Jami <versionName>"
git push origin "<versionName>"

# Release notes = just this version's CHANGELOG section (strip the "## <versionName> — date" header line).
mkdir -p .scratch
awk '/^## <versionName>/{p=1;next} /^## /{p=0} p' CHANGELOG.md > .scratch/release-notes.md
gh release create "<versionName>" \
  --repo ShiroiKuma0/jami \
  --title "白い熊 GNU Jami <versionName>" \
  --notes-file .scratch/release-notes.md \
  ~/tmp/shiroikuma-jami_<versionName>_arm64-v8a.apk
```
Then verify: `gh release list --repo ShiroiKuma0/jami` shows it as **Latest** and
`gh release view "<versionName>" --repo ShiroiKuma0/jami --json assets` lists the APK. Report the
release URL.

## Hard rules / invariants
- **Never rebuild to publish** — attach the newest APK already in `~/tmp/` (step 0). Publishing never
  triggers a daemon/contrib build.
- The transient `release-notes.md` goes in the gitignored **`.scratch/`**, never `~/tmp/`. `.scratch/`
  is not yet in this repo's `.gitignore` — add it once if missing:
  `grep -qxF '.scratch/' .gitignore || printf '\n.scratch/\n' >> .gitignore` (stage `.gitignore` with
  the docs commit).
- `gh` / `scp` / `git push` / `git tag … push` run **unsandboxed** (`dangerouslyDisableSandbox: true`).
- **No Claude/Anthropic attribution** in the commit, tag, or release body (repo `CLAUDE.md`). End the
  commit/tag message at the last line of its body.
- Tag is the **bare versionName** (e.g. `20260619-01+1`), no `v` — distinct from upstream's
  `android/release_NNN` tags so the two never collide.
- The release is cut from **`custom`**. Don't touch `master` here (it only mirrors upstream) and never
  stage `daemon/`, the generated SWIG bindings, or the gnutls `sed` — none of those belong in a
  docs-only release commit (see `jami-build` / `upstream-new-version` staging discipline).
