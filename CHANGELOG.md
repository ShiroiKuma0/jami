# 白い熊 GNU Jami — `20260731-01+008`

A downstream fork of [GNU Jami](https://github.com/savoirfairelinux/jami-client-android) for Android. Installs **side-by-side** with official Jami (app id `shiroikuma.jami`, label 白い熊 GNU Jami). Everything below is built on top of stock.

This release is one investigation, start to finish: a phone running at **109 % CPU** — a whole core, continuously — traced to a single thread, fixed, and then the reason it took a profiler to find fixed as well.

---

## ⏳ The most expensive bug of the day: a typing indicator that never stopped

Found by instrumenting the main thread's wake rate after the CPU work below was already done — and it turned out to cost far more than any of it.

`configureForTypingIndicator` built a **new** animated drawable on every bind and registered a callback that called `start()` again from `onAnimationEnd`. Nothing ever stopped one or unregistered the callback. So every typing indicator ever displayed left behind an immortal animator demanding a frame at the panel's refresh rate — for the life of the process, with the app backgrounded and the screen off — and they **accumulate**.

Measured on a 90 Hz panel, backgrounded, screen asleep: **up to 93 main-thread wakes/s and 12–76 % of a core**, climbing over hours, cleared only by a force-stop. A symbolised profile put the work in `AnimatorSet.doAnimationFrame ← pulseAnimationFrame` — the path an `AnimatedVectorDrawable` uses to drive its children — under `Choreographer.doFrame`, alongside `VectorDrawable::Group::onPropertyChanged` for the indicator's three groups. Nested animator sets reported children well over 100 %, which is the accumulation showing up directly.

The fix: **reuse** the drawable already on the icon instead of building one per bind (which alone bounds it to the recycler pool rather than unbounded), and **stop it on recycle** — being recycled, detached or invisible does not stop an animated vector drawable, only an explicit stop does. The self-restarting callback is dropped rather than repaired, because it was redundant: two of the three targets are already `repeatCount="infinite"`, so the set never legitimately ends, and the callback existed only to re-trigger the finite third. Nothing changes visually.

**Verified with a contact actively typing** — the exact trigger — at **0.5 / 1.0 / 2.0 / 3.9 wakes/s**, against a **64.0/s** baseline captured on the same phone minutes before.

This is a **stock GNU Jami defect**, not one this fork introduced: the identical code is at `upstream/master` in `ConversationAdapter.kt`, from upstream's *"chatView: implementation of new design"*. It needs only a contact who types and a few hours of uptime, so it plausibly affects every Android Jami user.

---

## 🔥 A core burned by one socket that could never be read

`shiroikuma.jami` sat at 109 % CPU with the screen off. **One thread** was responsible, and the numbers named its shape precisely: 100.0 % of a core, **zero voluntary context switches** — it never slept, ever — and 70 % of the burn in system time, i.e. hammering syscalls that returned immediately.

A symbolised profile put **99.94 %** of that thread in one call chain:

```
IceTransport::Impl::handleEvents → pj_ioqueue_poll → ioqueue_dispatch_read_event
    → ioqueue_on_read_complete → pj_ioqueue_recvfrom → recvfrom
```

An ICE candidate socket had entered a **permanent** error state — bound to a cellular interface that was up but had lost its route, while WiFi and a VPN held the default. Every read failed instantly. pjnath logs the error and returns "keep reading", the socket layer re-arms, level-triggered epoll re-reports at once, and round it goes: **15 774 iterations per second**, for 24.8 hours. Device-wide traffic during that time was 17 packets per second, so those reads were returning nothing but failure.

**Measured result: 109 % → 3.23 %** across a 10.6-hour run, with the system/user time ratio inverted from 2.4 to 0.26 — the syscall-hammering signature simply gone. Current builds idle **under 1 %** against a ~9 % baseline.

## 🧯 Three brakes that were all, quietly, doing nothing

The fork already carried three defences against exactly this. Every one of them turned out to be structurally blind to it:

- **pjlib's own 10 ms anti-busy-loop sleep** is gated on "no event was dispatched". Here the event *is* dispatched, so the sleep was never even considered. (It is separately gated on a non-zero timeout, which a zero timer-heap deadline also defeats.)
- **The fork's EPOLLONESHOT socket eviction** only ever inspected *unhandled* events — and had never executed once in its entire existence, for reasons below.
- **The fork's ICE throttle** was gated on ICE state `FAILED`, and the spinning transport was `RUNNING`.

That last one was a **measurement error, not a coding error**, and it is worth naming. The diagnostic logged `pj_ice_strans_state` as a bare integer with a legend written in a comment — and the legend omitted one enumerator, so every value below it was off by one and `RUNNING` read as `FAILED`. The census that justified the throttle ("410 of 418 spinning transports are FAILED") had really observed 410 in **RUNNING**. A second census, on transport teardown, was inverted the same way: transports counted as *connected* had actually never got past negotiation.

**Both diagnostics now print the state by name.** Never as a number again.

## 🛑 The new throttle: gated on behaviour, not on state

`SK-ICEREAP v2` replaces the state test with an observation: a poll that returns in under a millisecond cannot have blocked on anything, and one that delivered no payload achieved nothing a slower poll would have missed. **A thousand consecutive polls meeting both conditions cap the loop at 100 Hz** — about 0.6 % of a core instead of 100 %.

Requiring *both* conditions is what makes it safe for real traffic: a busy media transport delivers payload on essentially every poll and can never trip it, however fast it is running, whereas a timing-only test would throttle a healthy high-rate stream. The counter resets the instant either condition breaks. It is a throttle, not a thread exit — every event is still serviced, so no caller can be stranded.

## 🔇 The log line that cost 13 % of a burning core

pjnath reported that failing read on **every** iteration. `PJ_PERROR` builds its message with `vsnprintf` and `strerror` and only *then* consults the log level — so the message was formatted at 16 kHz and thrown away. Profiling attributed roughly **13 % of the burning core** to formatting text nobody would ever read.

`SK-RXERR` rate-limits it to the first failure plus one in ten thousand, carrying the socket descriptor and the consecutive count.

## 🔦 Why it took a profiler: Jami could not log its own network layer

The deeper finding. `setSipLogLevel()` defaulted pjsip's log level to **0** — which does not mean *minimum* logging. It means the log callback installed on the very next line **is never invoked for anything**, so every pjsip and pjnath diagnostic was discarded, permanently, and on Android the environment variable that would raise it cannot practically be set.

That is why a socket failing 16 000 times a second could burn a core for a day and leave no trace in logcat or in the in-app diagnostic log. The fault had to be found with a profiler because the layer that knew about it had no voice.

**The default is now 2 (fatal and error).** It is nearly free, and verifiably so: the formatting cost was already being paid and discarded, so raising the level adds the write, never the format. Verified on-device — pjsip-layer messages now reach the diagnostic log for the first time, which also makes an *absence* meaningful: zero read errors now means no socket is wedged, where before it only meant no channel existed.

## 🧰 The eviction, repaired — and it fires

The stuck-socket eviction had never run once. Three independent reasons, all fixed:

- **`EPOLLEXCLUSIVE` and `EPOLLONESHOT` are mutually exclusive in the kernel** — that pairing is precisely what pjlib submits to *probe* for exclusive support, expecting rejection. Since this kernel selects `EPOLLEXCLUSIVE` for every socket, the intended disarm was **impossible**, not merely skipped. Exclusive sockets now disarm by removal instead.
- **The re-arm was unreachable.** The old code deliberately left the socket's interest flags asserted "so the bookkeeping stays canonical" — but the re-registration path only acts when those flags *change*. A socket whose interest never changed would have been disarmed permanently: a deaf link, a starved connection, and precisely the reconnect storm this patch has caused once before.
- **The strike counter reset on the wrong events** — on events merely *queued*, before any attempt to handle them, so the dead socket it targets had its count cleared before it could ever reach the threshold.

**It now fires: 119 disarms in 8 minutes**, every one on an exclusive socket, which independently confirms the analysis above. It also revealed its own honest limit — the disarm does not *stick*, because the layer above re-posts a read unconditionally, so a dead socket cycles rather than going quiet. That is a design limit of evicting underneath a consumer that always asks again; it stays benign because each cycle buys a full poll budget of quiet. Both the behaviour and the limit are now written into the code rather than left to be rediscovered. The log is rate-limited to match (`SK-EPOLLQUIET` additionally silences one benign upstream teardown message that accounted for 386 of 511 lines in a capture).

## 🏗 Build system — five defects that were shipping stale or wrong code

Found while fixing the above, each one silently wrong for some time:

- **Guard-by-checksum (`SK-PATCHSUM`).** Every contrib patch step was guarded by "skip if this marker is in the source" — which **cannot see a patch that was regenerated**, because the old version contains the same marker. The step is skipped and stale code ships. This failed twice in two days, once delivering a build without a fix it was built to deliver. Guards are now an md5 over each package's patch set; stamps are written only after a *fully successful* build, so a failed build re-extracts next time instead of recording "up to date" for code that never compiled.
- **A `find` precedence bug** in the dhtnet rebuild guard — the very line added to prevent an earlier stale-library incident. `-o` binds looser than the implicit AND, so every `.cpp` matched unconditionally and dhtnet was rebuilt on *every* build while appearing to test the timestamp. It failed safe, so the only symptom was wasted time.
- **opendht had zero `$(APPLY)` lines for five live patches.** They survived only because the working tree happened to persist; a clean checkout dropped all five silently. The order is load-bearing and was verified against a pristine tarball — moving one patch earlier makes it fail outright and lose a hunk.
- **pjproject now builds before dhtnet**, which depends on it. The block's own ordering and its own documented cross-compiler gotcha were quietly incompatible; it only stayed hidden while pjproject was never rebuilt from scratch.
- **A superseded patch step** still referenced a patch file that no longer exists, its work having landed upstream, while the patch that replaced it was missing from the build entirely.

## 🔎 Diagnostics

- **`peer=` in the ICE transport log had always been empty.** The constructor moved the transport's name into a logger before the log line could read it — `createChild` takes its argument by value — so the field that ties a transport to a device printed nothing, for its entire existence. Any past inference drawn from it was worthless. Fixed by copying instead of moving; transport→device linkage now works, which makes a stale-connection census possible for the first time.
- ICE state is logged **by name** in both the lifecycle and teardown diagnostics.
- A new `SK-EVICT` line reports socket disarms, rate-limited per socket.

## 📚 Investigation notes

Also recorded, so the next person does not have to rediscover it: long-lived per-device connections are **by design**, not a leak — the connection manager deliberately reuses them and holds no expiry. The real gap is that **breakage discovery is write-triggered only**: of the four teardown paths none is periodic, a dead link's read returns "nothing yet, no error" indefinitely, and an ICE keepalive failure cannot move a transport out of `RUNNING`. A periodic idle probe would close it and is deliberately **not** included here — it would cost data and wakeups across four accounts, and the stale-connection count should decide that, not intuition.
