# Upstream report — five defects in the DHT connection/notification path

**Found:** 2026-07-30/31, on `shiroikuma.jami` (a downstream fork of jami-client-android), four
accounts in one daemon, DHT-proxy + push mode, opendht 4.2.0 / current dhtnet.
**Status:** none filed yet. Companion to `UPSTREAM-REPORT-crl-landfill.md`, which is the same family
of problem (values accumulating on a DHT key that everyone subscribed to must then re-download).

All five were established by reading pristine sources — opendht from
`daemon/contrib/tarballs/opendht-4.2.0.tar.gz`, dhtnet from its pristine checkout — and corroborated
on-device with per-key byte accounting.

---

## The measurement that ties them together

Each account listens on `SHA1("peer:" + deviceId)`, where peers post `PeerConnectionRequest` values
to reach it. Measured on one phone, 2026-07-31:

```
peer key of account A   144.4 KiB per GET   22 pushes / 4 min
peer key of account B   102.9 KiB per GET   23 pushes / 4 min
peer key of account C    67.6 KiB per GET   12 pushes / 4 min
healthy account keys      4–9 KiB
```

Three keys carried **81 % of all inbound bytes**. The mechanism is defect 1 multiplied by defect 5:
~60 short-lived values accumulate on the key, and every push about **one** of them re-downloads
**all** of them.

---

## 1. A push names the changed value ids, and the client then downloads the whole key

`DhtProxyServer::handlePushListen` builds `json["ids"]` — a comma-separated list of exactly the
value ids that changed (`src/dht_proxy_server.cpp`, ~1158-1171), with a source comment saying it
exists for the iOS notification extension.

`DhtProxyClient::pushNotificationReceived` **discards `ids`** and answers with an unfiltered
`get(key, …)` (`src/dht_proxy_client.cpp`, ~1310-1319). Measured cost: **148 KiB downloaded for one
new value**, 65 of 73 observed pushes naming exactly one id.

There is no way to fetch less. `GET /key/:hash` is the only read route; `DhtProxyServer::get` reads
only `params["hash"]` and streams every value; `DhtProxyServer::getFiltered` exists but **is
registered on no route**. `DhtProxyClient::get` accepts a `Value::Filter`/`Where` but applies them
**client-side in the body callback**, after the bytes are on the wire.

**Suggested fix:** either register a filtered read route, or have the client skip the refetch when
every id the push names is already in the listener's `OpValueCache`. The second is client-only —
but see the caveat under defect 2's sibling issue: `json["exp"]` is set **only** when
`expired && values.size() < 2`, so a multi-value expiry arrives with no `exp` field and *must* be
resolved by a full get. Any skip must exclude that case (a push with no `exp` and exactly one id
cannot be an expiry, which is provable from the server's own coupling).

## 2. A failed GET erases the listener's entire value cache and reports everything expired

`pushNotificationReceived`'s done-callback ignores `ok`:

```cpp
[cb, oldValues, sendTime](bool /*ok*/) {
    // Decrement old values refcount to expire values not present in the new list
    cb(oldValues, true, sendTime);
}
```

The design is a generational refcount sweep: snapshot the cache, re-add everything the GET returns,
then decrement the snapshot so survivors return to 1 and anything absent falls to 0 and is reported
expired. If the GET **fails** (non-200, no body, parse error) nothing is re-added, so the blanket
decrement drives *every* cached value to 0.

On a presence key that is **every contact reported offline at once from a single network blip**.
Downstream logic that triages on presence then acts on it.

**Suggested fix:** one line — only replay `oldValues` when `ok`. Residual: values delivered before a
mid-stream failure keep a +1 refcount, which is a missed expiration later; that is strictly milder
than a mass false expiry, and undoing the partial adds instead would round-trip them as
add-then-expire, i.e. reintroduce the blip.

Related, in the same function: two pushes whose `t` values arrive out of order leave a permanent +1
(`OpValueCache::onValuesExpired` skips the decrement when `updated > t`), so a later single-value
expiry decrements 2→1 and never removes.

## 3. `Bucket::addKnownNode` ignores `mobile_nodes`, defeating the mobile damper

Peers advertise `is_mobile` in the swarm protocol; `Bucket::removeNode` files a mobile peer into
`mobile_nodes` instead of `known_nodes`, and `getKnownNodesRandom` — the selector `maintainBuckets`
dials from — never draws from `mobile_nodes`. By design, a phone that dropped off is not chased.

`Bucket::addKnownNode` checks only `hasNode` (already connected), never `mobile_nodes`. Every
promotion path therefore re-arms the phone — and the dominant one is continuous:
`Conversation::addKnownDevices` → `setKnownNodes` is fed by the presence listener on **every**
announce, and upstream's own comment calls it the sole candidate-injection path.

`Bucket::addConnectingNode` then erases the node from `mobile_nodes` outright, so the mobility fact
is destroyed by the first dial attempt and never recovered.

There is **no backoff anywhere in `src/jamidht/swarm/`** — a grep for backoff/retry constants returns
nothing — so a failed speculative dial is retried on the next event with no memory of the attempt.

Compounding it, `Conversation::Impl::startTracking` subscribes to `PresenceManager`'s device
listener but acts **only on `online == true`**; the offline edge is delivered and discarded. So a
device enters `known_nodes` on its first announce and is never removed.

**Suggested fix:** have `addKnownNode` respect `mobile_nodes`, *or* — safer, because the injection
path is also the only candidate source — honour the offline edge by moving the device into
`mobile_nodes`, which is a no-op while a live socket exists and self-reverses on the next announce.

## 4. `DeviceAnnouncement` is republished under a fresh random value id

The announce `Value` is rebuilt by `parseAnnounce` with an unset id, and `Dht::put` then stamps a
**random** one. A re-put therefore adds a copy rather than replacing, and permanent puts are
refreshed forever: **12 copies of a single announcement were measured on one account key.**

Safe to fix because `Value::msgpack_pack_to_sign` packs `seq`/`owner`/`recipient`/`type`/`data`/
`user_type` and **never `id`** — a signed value's id may change freely. With a content-derived id,
a re-put of identical bytes takes `storageRefresh` (same id **and** `contentEquals`) and never
reaches the edit policy.

This fork ships that fix as a content-derived `dhtValueId()`.

## 5. `IceCandidates`' 1-minute TTL never takes effect — encrypted values lose their type on the wire

`IceCandidates::TYPE` is declared with a 1-minute expiration, exactly so short-lived connection
metadata does not linger. It does not work.

`Value::msgpack_pack_to_encrypt` emits **only the cypher blob**, and on receipt
`Value::msgpack_unpack_body` sets **`type = 0`**. Storage nodes therefore resolve `getType(0)` →
`USER_DATA` → `DEFAULT_VALUE_EXPIRATION` = **10 minutes**, for every encrypted value. Since
`IceCandidates` is itself an `EncryptedValue`, its own declared TTL is unreachable.

dhtnet's `PeerConnectionRequest` declares `USER_DATA` outright, so it gets 10 minutes either way —
and it is **never withdrawn**, including on the ~55 % of attempts that succeed. Nor can it be:
`cancelPut` is local bookkeeping only (and a total no-op for non-permanent values, which are never
even recorded), the proxy REST API has no DELETE or expire route, and the DHT protocol has no delete
message — `Refresh` only extends.

At ~5.5 inbound dials per minute, the steady-state key size is dial-rate × 10 min ≈ 60 values.
That is the multiplier on defect 1.

**Suggested fix:** carry the value type outside the encrypted body (msgpack tolerates unknown map
keys, so it is forward-compatible), or add an explicit expiration hint to the announce. Note the
privacy trade-off — it tells storage nodes "this is connection metadata" — so a fresh type id with a
2–3 minute expiry is likely a better ask than reusing `IceCandidates`' 1 minute.

A no-protocol-change partial: `Dht::put` already accepts and honours a backdated `created`
(`KEY_REQ_CREATION`, clamped by `onStore`), so a node can give its own value a shorter effective
TTL. Unreachable in proxy mode — `DhtProxyClient::doPut` takes the parameter commented out,
`Value::toJson()` has no creation field, and the server hardcodes `time_point::max()`.

---

## Filing notes

Per `.claude/skills/jami-gerrit`, a report **with a patch** goes to Gerrit; without one it goes to
forum.jami.net (GitLab issues are disabled). Defects 2 and 4 are one-liners with fixes already
written and running in this fork. Defects 1 and 5 are protocol-adjacent and want discussion first.
Defect 3 is a design question — whether the mobile damper is meant to survive presence re-injection
at all — and is probably a forum thread before a change.
