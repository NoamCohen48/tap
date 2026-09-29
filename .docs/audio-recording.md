# Device audio recording — research and scrcpy trial

Date: 2026-09-29. Research and implementation record. The owner chose a **scrcpy trial** first and subsequently approved testing. Other agents share the devices: any future device run must respect Tap's per-serial lock. This is audio *media* recording, not the Tap Studio action recorder (`recorder.md`), the daemon event log, or the deferred screen-video stream (`screen-streaming.md`).

## What is being captured

- **Output**: the device's playback, not the host machine's sound. scrcpy's default `output` forwards remote-submix audio and stops local playback on the phone while recording. This is a significant observable side effect; make the choice explicit.
- **Playback with duplication**: scrcpy's `playback` source on Android 13+ can retain local sound (`--audio-dup`); apps can opt out, so silence does not prove the AUT was silent.
- **Mic**: the device's microphone, a different input source, with privacy implications. Neither pathway promises voice-call recording.

## Android constraints

The public `AudioPlaybackCapture` API exists from Android 10 (API 29). A conventional recording APK needs `RECORD_AUDIO`, a user-approved MediaProjection token, and the same Android user/profile as the producing app. Only `USAGE_MEDIA`, `USAGE_GAME`, and `USAGE_UNKNOWN` playback can be captured, and the producing app/player must allow it. Effective capture policy is the most restrictive of manifest, AudioManager, and player settings. A MediaProjection can be stopped by the user/system; handle its callback rather than saving silence. Apps targeting Android 14+ must declare and use the mediaProjection foreground-service type and request fresh consent for each projection session.

`MediaRecorder.AudioSource.REMOTE_SUBMIX` requires `CAPTURE_AUDIO_OUTPUT`, reserved for system components: simply adding it to Tap's installed driver does not work. `adb shell screenrecord` writes video without audio. Scrcpy's shell-launched server is *not* an ordinary third-party APK; do not assume its audio access transfers to Tap's driver.

Sources: [Android playback capture](https://developer.android.com/media/platform/av-capture), [MediaProjection](https://developer.android.com/media/grow/media-projection), [REMOTE_SUBMIX](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#REMOTE_SUBMIX), [adb screenrecord limitations](https://developer.android.com/tools/adb).

## How scrcpy records (pinned source)

scrcpy v3.3.4, commit [`fb6381f5b9bb96f3fa823d899f4c32de2ec84ab3`](https://github.com/Genymobile/scrcpy/tree/fb6381f5b9bb96f3fa823d899f4c32de2ec84ab3) (Apache-2.0), is the reference for this trial; no source code is copied.

The host client pushes a server JAR to the device and runs it with `app_process` under the Android `shell` UID. Host and device communicate over ADB tunnels; audio, video, and controls use separate sockets. On-device `AudioRecord` obtains samples; `MediaCodec` encodes them (Opus by default); timestamped packets go to the host, where the client muxes/writes the recording. `--record` saves the encoded stream, rather than recording the host's speaker output. `--no-video --no-control --no-playback --record=file.opus` makes an audio-only artifact without opening a mirror/player. `--require-audio` makes lack of audio a failure rather than silently continuing without it. Select the serial with `-s` (never use an unscoped ADB operation).

The default `output` source is `REMOTE_SUBMIX`: it forwards the full eligible output but mutes local playback. The optional `playback` source uses reflected hidden AudioPolicy/AudioMix methods, requires Android 13+, can duplicate local playback with `--audio-dup`, and respects app opt-outs. `mic` uses `AudioRecord(MIC)`. scrcpy documents audio support from Android 11: on Android 11 an unlocked device and a transient shell-owned foreground activity are required to start capture; Android 12+ normally works directly. Android 10 and earlier are unsupported by scrcpy audio even though the *public* playback-capture API exists on Android 10. Device encoders may lack Opus; AAC is a fallback.

Sources: [audio](https://github.com/Genymobile/scrcpy/blob/fb6381f5b9bb96f3fa823d899f4c32de2ec84ab3/doc/audio.md), [recording](https://github.com/Genymobile/scrcpy/blob/fb6381f5b9bb96f3fa823d899f4c32de2ec84ab3/doc/recording.md), [architecture](https://github.com/Genymobile/scrcpy/blob/fb6381f5b9bb96f3fa823d899f4c32de2ec84ab3/doc/develop.md), [direct capture/Android 11 workaround](https://github.com/Genymobile/scrcpy/blob/fb6381f5b9bb96f3fa823d899f4c32de2ec84ab3/server/src/main/java/com/genymobile/scrcpy/audio/AudioDirectCapture.java), [Android 13 playback implementation](https://github.com/Genymobile/scrcpy/blob/fb6381f5b9bb96f3fa823d899f4c32de2ec84ab3/server/src/main/java/com/genymobile/scrcpy/audio/AudioPlaybackCapture.java).

## Options

| Option | Benefits | Costs / boundary |
|---|---|---|
| **Host-managed scrcpy** (trial chosen) | Existing audio-only recording, hardware encoding, no Tap driver hot-path work; Android 11+ output and microphone, Android 13+ playback duplication | External executable + server artifact, extra child/device process and ADB tunnel; exact serial, ownership, termination, time/size limits, security and license/distribution must be designed. API 29 cannot use scrcpy audio. |
| Separate Tap capture APK, public AudioPlaybackCapture | Android 10+ and UID-specific playback filtering; no hidden Android APIs | Consent UI, RECORD_AUDIO, foreground service, app opt-out/usage limits, custom codec/transport and cleanup. |
| Own shell-side capture server | Fully integrated transport/encoding | Large implementation/maintenance cost, hidden API/OEM exposure; not equivalent to adding code to the ordinary driver APK. |
| AUT-owned test hook | Can record app-owned audio precisely | App cooperation required; not generic device audio, contrary to Tap's no-AUT-changes path. |

## Manual feasibility trial (historical)

Before the daemon API existed, an opt-in validation script invoked scrcpy with an explicit
serial, `--no-video --no-control --no-playback --require-audio --audio-source=output`, a
fixed `--time-limit` and an Opus `--record` path. The trial wrapper acquired the emulator's
Tap serial lock non-blockingly before running the script. That script was **removed** once
product integration passed: it invoked scrcpy/ADB outside `:host:core`, and leaving an
unlocked alternate recording route would invite cross-agent device contention. Use the
attached-device client API instead.

### Approved emulator smoke (2026-09-29)

The owner approved testing. On emulator-5554 (API 34) the serial lock was acquired non-blockingly with `fcntl.lockf` (the same POSIX byte-range mechanism as Java `FileChannel.tryLock`), then the trial ran for 5 seconds with `output`. Installed scrcpy was **4.1**, not the pinned research revision 3.3.4; compatibility with v3.3.4 has not been measured. scrcpy initialized audio, reached its time limit and completed an Opus file. `ffprobe` read Opus, 48 kHz, stereo, duration 4.29 s. The stream had decoded samples but was digital silence (ffmpeg volumedetect max/mean −91 dB); **this proves capture/encoding/file finalization on a quiet device, not audible AUT playback capture**. Artifact: `tap-artifacts/audio-trial-output.opus` in this worktree (ignored by Git). No Samsung run or device-suite run. Only the emulator was touched; the lock was released afterwards.

### Controlled playback smoke (same emulator, 2026-09-29)

After the owner suggested playing a song, a local 8-second **generated WAV** was used instead: it alternates 440, 660, 880 and 523 Hz once per second, avoiding external music/copyright and making the recorded signal identifiable. With the emulator's Tap lock held, it was pushed serial-specifically to `/sdcard/Download`, indexed by MediaStore, and opened by the installed YouTube Music audio-preview activity through an `ACTION_VIEW` content URI while the `output` source recorded. A back key was sent afterwards; the WAV was removed and the directory rescanned. `scrcpy 4.1` completed `tap-artifacts/audio-trial-tone.opus` (Opus 48 kHz stereo, 12.85 s, 95,606 bytes). Decoding shows nonzero signal (peak −41.5 dBFS); a Goertzel check of successive seconds identifies the expected **440 → 660 → 880 → 523 → 440 Hz** progression. Thus device playback *really was captured*, not just a silent container. There were several seconds of startup silence because the player took ~4.3 s to launch. No app APK was modified or installed. The fixture activity was initially foreground; the probe launched a media activity temporarily, so even a serial-locked manual trial can disturb another agent that bypasses Tap's lock.

The `ffmpeg` PCM-decoding check emitted one non-monotonic DTS warning near 6 s; decoded signal was intact, but timestamps should be assessed before declaring artifact quality sufficient for production.

The manual trial alone validated feasibility. Product integration and its device evidence are recorded below.

## Product integration (this branch)

The daemon now exposes `DeviceService.StartAudioRecording` and `StopAudioRecording` with a
single external scrcpy process per attached device, a 60-second limit and ≤3 MiB returned
Opus artifact. Kotlin and Python clients offer matching start/stop calls. This requires a
host-installed scrcpy binary; it is **not bundled** in the daemon or its native image. Each
recording's temporary file and log live under `<state-dir>/recordings/` and are removed on
stop/detach; the audio child is terminated before the device lock is released. This is a
bounded first product version, not the future long-running streaming/video pipeline.

### Product verification

- Device-free: `:host:core:test :host:daemon:test :contracts:protocol:test :clients:kotlin:sdk:test` passed, including recorder fake-process tests, owner/client checks and native-image metadata coverage; Python/agent unit tests: 122 passed; generated stubs checked.
- JVM daemon + Python client, emulator-5554 (API 34): attached fixture, started `output` capture, opened an 8-second generated WAV through MediaStore, stopped capture and checked its SHA-256. `tap-artifacts/audio-api-tone.opus` contains the expected 440 → 660 → 880 → 523 Hz repeating sequence. The uploaded WAV was removed and the daemon stopped.
- Native daemon: `nativeCompile` succeeded and a native-image device smoke started and stopped audio, returning an Opus artifact. Another native smoke detached during recording: no scrcpy process or temporary recording remained. The native build consumed too many cores; `host/daemon/build.gradle.kts` now limits native-image parallelism to two, and the owner's `~/.gradle/gradle.properties` limits Gradle workers to two. No further unbounded build is needed.
- Not tested on the API 29 Samsung: the product refuses unsupported API levels before launching scrcpy. `playback` duplication and `mic` have unit/argument coverage but no device-family validation yet.

## Longer-term direction and limits

Clients never invoke ADB or scrcpy; capture belongs to the attached device and its owning connection. The host starts scrcpy only on request, keeps the recording file temporary, returns a bounded Opus artifact on Stop, and tears down scrcpy on detach and connection loss. This is independent of Tap's one-command driver executor. A future long-running pipeline would need streaming, disk quotas, automatic failure-artifact policy, and AV synchronization rather than the first version's 60-second/3-MiB unary limit. A sudden daemon `SIGKILL` can leave an external scrcpy child until its `--time-limit` expires; the session lock is then OS-released, so crash recovery of that child remains a follow-up. This work does not alter Tap Studio's `screen-streaming.md` decision or imply video recording.
