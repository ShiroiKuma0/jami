---
name: jami-gerrit
description: File a Jami bug fix / patch upstream to GNU Jami's Gerrit at review.jami.net (projects jami-daemon, jami-client-android). Prepares an isolated worktree on current upstream master, writes an upstream-styled commit with a Change-Id, and hands 白い熊 the single push command to run himself — the Gerrit HTTP password is never stored on this host and is entered only by him. Use whenever 白い熊 says "/jami-gerrit", "file this upstream", "post it to Gerrit", "send the patch to Jami", "submit upstream", or asks how a Jami patch was filed before, or for a new patchset on an existing change.
---

# File a Jami patch upstream via Gerrit

GNU Jami takes **code changes on Gerrit** (`review.jami.net`). It does **not** take bug reports on
GitLab — issues are disabled on `git.jami.net` for `jami-daemon`, `jami-client-android` and
`jami-project` (all 404), and `jami.net/bugs` redirects to `forum.jami.net`. So: **prose reports →
the forum; anything with a patch → Gerrit.** A patch is far more likely to land than a report.

## The division of labour (why this skill exists)

**Claude prepares everything; 白い熊 authenticates.** The Gerrit HTTP password is not stored anywhere
on this host (no `~/.git-credentials`, no `~/.netrc`, no credential helper) and should stay that way.

1. Claude does all of it — fetch, worktree, code, commit, Change-Id, verification.
2. Claude then prints **one** `!`-prefixed push command.
3. 白い熊 runs it and types the password at the prompt. Gerrit prints the change URL.

Do **not** ask for the password in order to run the push yourself. Offer it only if 白い熊 raises it,
and prefer him typing it directly so it stays out of the transcript.

## Banked account / transport facts

| Item | Value |
|---|---|
| Gerrit | `https://review.jami.net` — OAuth, **no CLA enforced** |
| HTTP user | `ShiroiKuma0` |
| Commit author (must match the account) | `ShiroiKuma0 <ShiroiKuma@sumo.do>` |
| Password | Gerrit → **Settings → HTTP Credentials** (distinct from the login password) |
| Transport | **HTTPS only.** SSH (29418/29420) is unreachable from this host — ignore the `port=` line in `daemon/.gitreview` |
| Push ref | `HEAD:refs/for/master` |
| Anonymous fetch | works (`git fetch origin master` on the daemon submodule) — no credentials needed to read |

**Prior art:** the false-unread fix was filed 2026-06-14 as
[change 34344](https://review.jami.net/c/jami-client-android/+/34344) on `jami-client-android` and
**landed** as `979ab7dd7` (it then auto-dropped from our fork on the next rebase). The CRL-landfill
fix was filed 2026-07-26 on `jami-daemon`
(Change-Id `I784094c47b85d14e86e2a0b83d62575d887afd96`, base `d1233e681`).

## Procedure

### 1. Pick the project and re-fetch real master

`jami-daemon` for anything under `daemon/src`; `jami-client-android` for app code.

```bash
cd ~/git/shiroikuma-jami/daemon        # or the client repo root
git fetch origin master
git log -1 --format='%h %ad' --date=short origin/master
```

**Always re-fetch.** Our submodule gitlink is pinned to whatever upstream's client pins, which lags
master by days or weeks — basing a change on the pinned commit invites a rebase conflict.
**Re-confirm the bug still exists at real master** before writing anything:
`git show origin/master:<file> | grep -n <the offending code>`.

### 2. Isolated worktree — never the build tree

The fork's `daemon/` working tree carries per-build re-applied patches (`SK-CRL-CURRENT`, gnutls,
dhtnet, pjproject, opendht). Filing from it would either ship fork markers upstream or disturb the
next build. Use a throwaway worktree:

```bash
W=<scratchpad>/gerrit-daemon
git worktree add --detach "$W" origin/master
```

### 3. Install Gerrit's commit-msg hook (Change-Id)

A worktree's `.git` is a **file**, so hooks live in the shared common dir — this is the step that
trips people up:

```bash
cd "$W"; H="$(git rev-parse --git-common-dir)/hooks"; mkdir -p "$H"
curl -s https://review.jami.net/tools/hooks/commit-msg -o "$H/commit-msg" && chmod +x "$H/commit-msg"
```

Without it the push is rejected for a missing `Change-Id`.

### 4. Write the change in UPSTREAM style

- **No fork branding.** Strip `SK-*` markers, "shiroikuma fork", and our dated measurement narration
  from the code comments. Comments explain *why* in neutral terms.
- Match surrounding style (this codebase uses `not`/`and`, 4-space indent, `JAMI_*` logging).
- Keep the change minimal and single-purpose — one logical fix per change.

### 5. Commit with the matching identity

```bash
git -c user.name="ShiroiKuma0" -c user.email="ShiroiKuma@sumo.do" commit -a -F <msgfile>
```

A good message states: what the code did, the **observed evidence** (numbers, sizes, durations), why
the fix is safe (the proof, not the assertion), and what was measured after. Cite the mechanism —
maintainers accept a change far more readily when the reasoning is checkable. **No Claude/Anthropic
attribution, ever.** Verify the trailer landed: `git log -1 --format=%B | grep Change-Id`.

### 6. Hand over the push

Print exactly this, for 白い熊 to run:

```
! cd <worktree> && git push https://ShiroiKuma0@review.jami.net/a/<project> HEAD:refs/for/master
```

Note the **`/a/`** path segment — it selects authenticated access. Gerrit replies with the change URL.

### 7. Afterwards

- Record the change URL / number and Change-Id in memory.
- Clean up: `git worktree remove <worktree>` (and `git worktree prune`).
- If our fork carries the same fix locally, note that it will **auto-drop on the rebase** once the
  change lands upstream — that is what happened with 34344.

## New patchset on an existing change

Amend, keeping the same `Change-Id`:

```bash
git fetch https://review.jami.net/<project> refs/changes/<NN>/<CHANGE>/<PS>
git checkout FETCH_HEAD
# edit, then:
git commit -a --amend            # keeps the Change-Id trailer
```
then hand over the same push command. (`<NN>` is the last two digits of the change number: change
34344 → `refs/changes/44/34344/2`.)

## Gotchas

- **`/a/` missing** → anonymous push → rejected.
- **Author email mismatch** → Gerrit rejects the committer identity.
- **Missing Change-Id** → rejected; the hook must be in the *common* hooks dir for a worktree.
- **`.gitreview` lies about transport here** — it advertises SSH port 29420; only HTTPS works.
- **Subject > ~50 chars** draws a warning (not a rejection) — 34344 hit this and needed a patchset 2.
- Do not push our fork's `custom` branch anywhere near Gerrit; only the prepared worktree commit.
