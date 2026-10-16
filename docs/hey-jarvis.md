# Hey Jarvis local detection

Pocket Agent supports an optional, user-enabled Android microphone foreground service that
runs openWakeWord entirely on-device. The detector uses 16 kHz mono PCM and evaluates one
80 ms frame at a time with a default decision threshold of **0.75**. Settings exposes an adjustable 1–99% confidence threshold,
persisted separately from cloud credentials and applied to active listening without restarting.
Thresholds below 30% are deliberately marked experimental because of false activations. ONNX inference is
single-threaded. The Node/Pi runtime, Deepgram STT and TTS are not started until a wake-word
detection. Android will show the microphone privacy indicator while enabled.

The service has a foreground notification with a **Disable wake word** action. It pauses
recording before Deepgram acquires the microphone. Its last 800 ms of audio is queued before
fresh Deepgram PCM to reduce truncation of a continuous wake phrase plus command. Short
recording errors retry after a three-second backoff. The command session returns to wake
listening after Deepgram stops; stop-all disables the wake service first.

## Enrollment limitation

Optional on-device enrollment collects five examples of "Hey Jarvis" and three unrelated
phrases, then trains a small phrase-specific verifier. This reduces some false triggers for
that user's speech patterns, but **does not provide authenticated speaker recognition**:
another person who says the wake phrase may activate the assistant. The model has no
speaker-identity embedding and cannot determine whether the user, another speaker or a
synthetic voice said "Hey Jarvis". Sensitive actions must be authorized separately. The
app describes this as **wake-phrase calibration**, not Siri/Google-style Voice Match.

Only numerical features are retained during enrollment; the fitted filter is saved in
app-private storage. Raw audio is never persisted. Reset removes the calibration.

## Emulator verification

The `voice-emulator.yml` workflow runs microphone-capture/replay and ONNX model instrumentation
on an Android emulator. Synthetic fixture PCM covers a held-out British-accent wake phrase,
an English female voice reading the wake phrase, and unrelated commands. These are useful
smoke/regression tests, not an estimate of wake-word accuracy on human speech or idle battery
cost. Tests must not modify the physical phone.

`JarvisDeepgramTransportSmokeDeviceTest` optionally sends a PCM16 fixture to **real**
Deepgram Flux when a Deepgram API key is already configured in the test emulator. This is a
**detector-plus-transport smoke test, not proof of the whole agent handoff**. It skips when
credentials are missing. To validate full audio-to-agent behavior, verify a foreground
wake-word service, the Deepgram `EndOfTurn`, an agent command and response/TTS in one recorded
emulator session. Do not claim the full integration passed from fixture-only tests.

## Licensing

The openWakeWord pretrained model weights are under CC BY-NC-SA 4.0, not the Apache license
for the openWakeWord source. The APK bundles `assets/wakeword/NOTICE.txt`, the three model
files and the ONNX Runtime MIT copyright notice. This is intended for permitted
noncommercial evaluation and must not be shipped commercially without licensing review.

## Low-confidence diagnostics

Synthetic wake-phrase fixture tests exercise the model but cannot establish accuracy for
an individual accent or Xiaomi microphone hardware. When enrollment shows a very low
generic confidence (for example 1–3%), first check the capture signal. The enrollment
recorder now warms up for 1.2 seconds before prompting "Speak now", captures 4.2 seconds
of wake-phrase audio, and displays its measured microphone RMS level alongside the peak
generic confidence. Users can switch between Android VOICE_RECOGNITION (original default)
and standard MIC sources under Settings; stop and restart wake listening to apply the source
to its foreground service. Both enrollment and wake listening use that same source.

If the audio level is low, investigate microphone routing, physical position, OS permissions
and noise suppression. If audio RMS is healthy but the generic confidence remains very low,
this pretrained model may not match the user's pronunciation. A lower threshold is only
for experimentation: it does **not** fix the model or provide speaker recognition. A
better model/retraining based on representative speech would be the next step.
