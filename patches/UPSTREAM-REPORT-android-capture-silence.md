# Forum draft — Android capture silence on the low-latency VOICE_COMMUNICATION path

For **forum.jami.net** (GitLab issues are disabled; this is a behaviour/policy question rather than a
defect with an obvious patch, so it belongs in a thread rather than straight on Gerrit).

**Two blanks to fill before posting** — the daemon log does not record them, so they have to come from
the affected phone (Settings → About phone): the **exact model** and the **Android version**. Every
maintainer will ask first, and "a Samsung" is not enough to reproduce.

---

## Suggested title

> Android: AAudio capture on the low-latency VOICE_COMMUNICATION path can deliver only digital silence (one-way audio)

## Post

Hello,

I would like to report a device-dependent one-way-audio failure on Android, with measurements, and ask
what shape a fix would ideally take before I propose one.

**Summary.** On at least one device, the daemon opens an AAudio capture stream that reports itself
healthy — no error, no disconnect, the microphone privacy indicator lit — and then delivers a
continuous run of **digital zeros**. The call connects, the remote party is heard perfectly, and the
local party is inaudible. Nothing in the logs indicates a fault, because from the daemon's point of view
nothing is faulty: it is faithfully encoding and sending silence.

### What it is not

Each of these was excluded by measurement rather than by reasoning:

- **Not permissions.** `RECORD_AUDIO` granted; the privacy indicator lights for the duration of the call.
- **Not the microphone or the OS.** A voice message recorded in Jami on the same phone, seconds before
  the call, measures **mean −22.1 dB, peak −1.2 dB**. That path uses Java `MediaRecorder` with
  `AudioSource.MIC`, so the hardware and the platform are fine — it is specifically the daemon's capture
  path that is silent.
- **Not mute.** `Audio Input muted [NO]`, logged twice per call on the affected side.
- **Not transport, SRTP or the codec.** At the receiving end: ~50 packets/s with no gap, zero decrypt
  errors, and every packet decoding successfully — 1135 decoded frames over a 26-second call and
  thousands more over a two-minute call, **every one bit-exact zero**.
- **Not the audio processor.** Measuring the capture chain at both ends — the buffer as AAudio hands it
  over, and again after `AudioProcessor` — shows the zeros arriving already zero. The WebRTC AP passes
  them through unchanged.

### The measurement that identified it

Instrumenting the affected phone's own capture path:

```
capture raw frm=510 rms=0.0000 peak=0.0000 | processed frm=101 rms=0.0000 peak=0.0000
```

Note `peak` is **exactly** zero, sustained. That is the diagnostic signature: a live microphone always
leaks a noise floor, so an exactly-zero peak over seconds is not a quiet room but an absent signal. On a
working phone the same counter idles around 0.0001 and jumps to 0.03–0.27 on speech.

Re-opening the input with `AAUDIO_INPUT_PRESET_VOICE_RECOGNITION` and
`AAUDIO_PERFORMANCE_MODE_NONE` produced audio in the very next second:

```
capture raw frm=41 rms=0.0068 peak=0.0240 | processed frm=64 rms=0.0050 peak=0.0292
capture raw frm=61 rms=0.0491 peak=0.2744 | processed frm=100 rms=0.0367 peak=0.2389
```

The raw frame rate falling from ~510/s to ~61/s at the switch is the MMAP fast path giving way to
ordinary buffers, which is consistent with the low-latency request being the relevant factor.

### Why there is nothing to fall back to

`buildStream()` asks for `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY` and, for capture,
`AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION`. Both are the right defaults — the preset is what engages the
device's own voice processing — and both are implicated in this class of HAL fault. Since
`a761596e0` ("audio: remove Android OpenSL layer", 2026-02-24) AAudio is the only Android audio layer,
so when its input misbehaves there is no second path, and the daemon has no way to notice: a stream that
delivers zeros is indistinguishable, at the API level, from a stream in a silent room.

### What I currently run downstream

An evidence-triggered fallback, in a personal fork:

- Track the peak of each raw capture buffer, before anything of ours touches it.
- If it reads exactly zero for **5 consecutive seconds** while the stream is running, log it and re-open
  the input once with `INPUT_PRESET_VOICE_RECOGNITION` and `PERFORMANCE_MODE_NONE`.
- Persist that decision per device, so the 5 seconds are paid once rather than at every process start.
- Unreachable on a working microphone, by construction — the trigger requires an exactly-zero peak.

It works on the affected phone, and it has no effect on any device I have that captures normally.

### What I would like guidance on

1. **Is automatic detection wanted in the daemon at all**, or would you rather this be a preference the
   user or client sets (an "audio input compatibility" toggle), with the daemon staying dumb?
2. **If automatic: what trigger?** 5 s of exact zero is deliberately conservative. It could be far
   shorter (the first ~20 buffers of a stream), at the cost of firing on a genuinely silent room —
   though even then the fallback is harmless.
3. **What should the fallback change?** I drop both the preset and the performance mode together, which
   is two variables at once. If only one matters, keeping `VOICE_COMMUNICATION` would preserve the
   device's voice processing, which is worth having.
4. **Playback too?** I have only observed this on capture, but the same low-latency request is made for
   output.
5. Would a **device allow/deny list** be preferable to behavioural detection? I would argue not — it
   cannot cover devices nobody has tested — but you may have history here that I do not.

### Related, already merged

Two genuine bugs found while chasing this, both merged, neither of them the cause of the silence:

- [35396](https://review.jami.net/c/jami-daemon/+/35396) — `dataCallback()` cast the platform buffer to
  `float*` unconditionally although `getStreamFormat()` in the same file already admits an I16 grant: a
  2× over-read past the end of the buffer and a heap overflow on capture.
- [35397](https://review.jami.net/c/jami-daemon/+/35397) — `hardwareInputFormatAvailable()` logged the
  granted capture format and discarded it, so `createAudioProcessor()` sized the AEC, noise suppressor
  and VAD from the playback format alone.

I can contribute the capture-chain instrumentation that produced the numbers above if it would be
useful in the tree — it is a handful of counters and one log line per second, per call.

### Environment

- Affected device: **&lt;MODEL&gt;**, Android **&lt;VERSION&gt;**, arm64.
- Jami Daemon 16.0.0 (Android), PJSIP 2.15.1, GnuTLS 3.8.13, OpenDHT 4.2.0.
- Reproducible on every call on that phone; unaffected peers on the same builds are fine.

Thank you for taking a look.
