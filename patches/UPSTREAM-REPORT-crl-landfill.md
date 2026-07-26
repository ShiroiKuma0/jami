# Upstream report: the account key accumulates every CRL ever published, forever

**Status:** ready to file — not yet submitted.
**Where to file:** https://forum.jami.net (Jami's `jami.net/bugs` page points there; GitLab issues are
disabled on `git.jami.net/savoirfairelinux/jami-daemon`). The code change belongs on Gerrit,
https://review.jami.net.
**Affected:** `jami-daemon` at `f0e2a6a67f3faff045d3fd2ea2f2d8dc0f166f92` (2026-07-17), the commit
pinned by `jami-client-android` master. Verified present in pristine upstream source.
**Component:** `src/jamidht/account_manager.cpp` (`AccountManager::startSync`)

---

## Summary

Every time an account registers, the daemon republishes **every certificate revocation list it has
ever pinned** to the account's DHT key — not just the current one. Each republish is stored as a
**new value with a fresh random id** rather than replacing the previous copy, and each is a
**permanent put**, so the DHT re-announces all of them to the ~8 closest nodes every
`DEFAULT_VALUE_EXPIRATION` (10 minutes) for as long as the node runs.

The result is an account key that grows monotonically and never decays. On the reporter's setup it
reached **266 values / 274 KB on a single account key**, of which **1.5 KB was actually needed**.

## Impact

Bandwidth and CPU, scaling as **(number of CRLs) × (number of registrations) × (number of accounts)**.

The reporter measured a sustained **~118 MiB/h** on the client's uid that would not decay, traced to
this mechanism. After the fix below, the same four accounts' keys went from **661 values / 611 KB to
21 values / 14.1 KB (43×)**, and idle CPU roughly halved.

This is invisible to a typical user (one account, one or two CRLs, few re-registrations) and becomes
severe for anyone with a long device-revocation history, several accounts in one daemon, or software
that re-registers often.

## Root cause

Three independent facts compound. `src/jamidht/account_manager.cpp`, `startSync`:

```cpp
for (const auto& crl : info_->identity.second->issuer->getRevocationLists()) {
    auto crlVal = std::make_shared<dht::Value>(*crl);
    crlVal->priority = 1;
    dht_->put(h, crlVal, dht::DoneCallback {}, {}, true);   // permanent
}
```

1. **The whole history is republished.** `getRevocationLists()` returns every CRL pinned for the
   account. The receive side, a few lines below, pins every CRL it hears
   (`certStore().pinRevocationList(...)` inside `dht_->listen<dht::crypto::RevocationList>`), so the
   set only ever grows and propagates between devices.

2. **Each put creates a new value instead of replacing one.** The `dht::Value` is constructed with an
   unset id, and OpenDHT then assigns a random one — `src/dht.cpp`:

   ```cpp
   if (val->id == Value::INVALID_ID)
       val->id = std::uniform_int_distribution<Value::Id> {1}(rd);
   ```

   So registering N times leaves N copies of every CRL. This is directly observable: copy counts are
   uniform per account across both the device announcement and every CRL — the signature of
   "N registrations × the whole set".

3. **The puts are permanent.** With `permanent = true` and a 10-minute value expiry, all copies are
   re-announced roughly six times an hour indefinitely. Nothing expires while the node runs, which is
   why the key plateaus rather than decaying.

## Evidence

Read any account key directly from a public proxy — one JSON value per line:

```
curl -s http://dhtproxy.jami.net/<40-hex-account-id>
```

Decoding the `data` field (base64 → msgpack bin → DER) and parsing with
`openssl crl -inform DER -noout -text` gave, for four accounts on one device:

| account key | values | distinct CRLs | payload | copies of each |
|---|---|---|---|---|
| A | 266 | 35 | 274 KB | 8 |
| B | 147 | 21 | 137 KB | 7 |
| C | 187 | 17 | 165 KB | 11 |

The 35 distinct CRLs on key A spanned **October 2024 to July 2026** — the account's entire revocation
history, all still being actively re-announced.

## Why publishing only the newest CRL is safe

The CRLs are **strictly cumulative**. Sorting all 35 by `lastUpdate` and comparing revoked-serial
sets, each list is a superset of its predecessor, and the newest contains **8 serials — exactly the
union of all 35**. Publishing only the newest therefore loses no revocation, and is strictly safer
than the status quo: a peer that happens to read an old CRL from the key today sees *fewer*
revocations than it should.

## Proposed fix

Publish only the most recent CRL, under a **content-derived value id** so that re-registration
overwrites the existing value instead of adding a copy. A helper alongside `AccountInfo`:

```cpp
inline uint64_t
crlValueId(const dht::crypto::RevocationList& crl)
{
    const auto packed = crl.getPacked();
    const auto h = dht::InfoHash::get(packed.data(), packed.size());
    uint64_t id = 0;
    for (unsigned i = 0; i < sizeof(id); ++i)
        id = (id << 8) | h[i];
    return id ? id : 1; // 0 is dht::Value::INVALID_ID — would draw a random id again
}
```

and in `startSync`:

```cpp
std::shared_ptr<dht::crypto::RevocationList> newest;
for (const auto& crl : info_->identity.second->issuer->getRevocationLists())
    if (not newest or crl->getUpdateTime() > newest->getUpdateTime())
        newest = crl;
if (newest) {
    auto crlVal = std::make_shared<dht::Value>(*newest);
    crlVal->priority = 1;
    crlVal->id = crlValueId(*newest);
    dht_->put(h, crlVal, dht::DoneCallback {}, {}, true);
}
```

The same stable id should be applied to the immediate post-revocation announce in
`archive_account_manager.cpp`, otherwise revoking a device adds a random-id copy alongside the value
`startSync` already maintains.

The full patch is attached as `jami-publish-current-crl-only.patch`.

**Why a stable id is safe.** On a store, `Dht::storageStore`'s caller (`dht.cpp`) looks up
`getLocalById(hash, v->id)`; when a value with that id already exists and `*lv == *vc`, it calls
`storageRefresh()` and the edit policy is never consulted. `Value::operator==` compares
owner/type/data/user_type/signature **ignoring the id**, so a content-derived id always maps
identical bytes to the same id and re-puts land in the refresh branch. Only same-id-different-content
reaches `editPolicy`, which `DEFAULT_EDIT_POLICY` denies. Different devices publish different bytes,
so they derive different ids and cannot collide.

**The `DeviceAnnouncement` put in the same function has the same defect.** It is signed, but the id
is **not** part of the signature — `Value::msgpack_pack_to_sign` packs `seq`, `owner`, optional
`recipient`, `type`, `data` and optional `user_type`, and `checkSignature()` verifies exactly that
blob. So the announcement can take a content-derived id too, and should: today it leaves one orphaned
copy per daemon run (observed: 12 copies of a single announcement on one key). It duplicates far more
slowly than the CRL history, which is why it is called out separately rather than bundled here.

## Verification

Applied to the pinned daemon and installed on a device with four accounts:

- Key A: **266 values / 274 KB → 3 values / 2.6 KB** within ~15 minutes.
- Keys B and C reached the same floor (one CRL + one announcement) roughly 10 minutes later, as the
  old permanent puts expired once nothing refreshed them.
- Totals across four accounts: **661 values / 611 KB → 21 / 14.1 KB**.
- The surviving CRL still lists all 8 revoked serials.
- No regression in registration, presence or messaging over the following hours.
