# Pocket Agent voice assistant (Android)

## Product contract

Pocket Agent remains the assistant and subagent host. Android's selected digital-assistant
gesture opens a temporary voice UI. A live microphone stream goes to Deepgram STT;
a finalized utterance is submitted as **one normal main-agent user message**. Only
text deltas produced in that particular main response are sent to Deepgram streaming
TTS. Subsequent Pi9 worker completion messages and ordinary text messages remain
visible in chat without speech. Dismissing the assistant closes microphone, audio
playback and speech WebSockets but **does not cancel Pi or any worker**. Stop response,
Cancel worker and Stop all remain distinct actions.

This is not Deepgram's Conversational Agent API. No Deepgram model makes decisions,
dispatches tools or starts subagents.

## Reference findings

Verified against private `ru-aish/fe.voice.ind` (read-only), principally
`src/lib/deepgram-agent-config.ts`,
`src/app/deepgram-agent-test/deepgram-agent-panel.tsx`, and
`src/app/demo/components/DemoVoiceSession.tsx`.
The reference uses 16 kHz PCM16 input, 24 kHz PCM16 output, Aura voice synthesis,
and explicit first-response latency event timing. Its demonstration **does** use
Deepgram's Agent SDK and has a different orchestration path; those SDK calls were
not copied into this Android implementation.

Pocket Agent instead uses directly documented Deepgram APIs:

- STT: `wss://api.deepgram.com/v1/listen` using Nova-3, raw 16 kHz
  signed PCM16 mono, 40 ms microphone packets, interim transcripts and 350 ms endpointing.
  Completed utterances concatenate `is_final` fragments until `speech_final`.
- TTS: `wss://api.deepgram.com/v1/speak` with Aura-2 Thalia,
  24 kHz signed PCM16 mono. The outgoing Pi response is cut at punctuation
  or a long whitespace boundary, preserving incomplete word fragments.
  One `Flush` is sent per response and Android `AudioTrack` plays received PCM
  incrementally without writing an audio file.
- Only two WebSockets are opened by a visible voice session. No always-on microphone,
  greeting request, separate LLM or polling loop is introduced.
- A separate Android Keystore encrypted key is configured in Settings. It is not
  copied from the reference repository and never passed into Pi transcripts.
  The microphone permission must be granted on the device.

## Android activation

1. Install the new APK built by GitHub Actions.
2. Configure a model provider for Pocket Agent and a Deepgram voice API key.
3. Grant **Microphone** from Pocket Agent's settings.
4. Select **Choose default assistant** or set Pocket Agent under Android's
   Settings → Apps → Default apps → Digital assistant app.
5. Configure the power-button long-press to **Digital assistant** if that
   device supports it. Some OEMs reserve this gesture for the power menu.
6. Long-press the power button to speak. Close the voice window to stop audio;
   workers remain active in the foreground service and text chat.

The Android manifest registers a protected `VoiceInteractionService` and
`VoiceInteractionSessionService` rather than a global accessibility shortcut.
The voice service itself is lightweight. No platform bypass, root or key interception
is needed. Default-assistant support and key mapping depend on OEM firmware.

## Failure/lifecycle boundaries

- Opening without microphone permission or a Deepgram key yields a setup prompt
  and does not open a microphone.
- Malformed streaming recognition packets are ignored; finalized text is accumulated
  once; silence does not send an empty prompt.
- While Pi is thinking or speaking, mic recording is stopped to prevent playback
  from becoming a second user instruction. This first implementation is intentionally
  **half duplex**; echo cancellation and interruption/barge-in are not yet implemented.
- Closing the overlay discards streamed voice data, pending TTS and its per-session
  identifier. Late Pi responses cannot speak into a subsequent voice window.
- The main queue persists in the existing runtime. Main response failure emits
  `voice_end` and no false spoken success. Tool-only turns return to listening
  without waiting for an empty TTS response.
- Network loss, permission revocation, denied audio hardware and overrun are surfaced
  as voice status; no speech provider error is treated as worker task completion.
- The TTS queue bounds pending synthesis commands and bytes; STT WebSocket
  backlog is capped, avoiding indefinite retained audio.
- No wake-word listener or screen-off microphone service is installed. The selected
  assistant gesture is the entrypoint; workers can continue with the screen off
  under the existing Android service limitations.
- UI and agent remain separate owners. `onHide`/dismiss destroys all voice
  audio transport, but never issues `stop_main` or `stop_all`.

## Metrics and verification

A voice session reports `PocketVoiceLatency` to logcat as:
`first_token_ms=<elapsed since finalized utterance> first_audio_ms=<elapsed>`.
These are application end-to-end intervals rather than Deepgram's Agent SDK's
provider timing values; compare carefully. Values require actual configured
Deepgram credentials and a recorded spoken utterance; no performance number
is assumed from the reference or from synthetic unit tests.

Validation checklist:

1. Runtime unit tests: speech-tagged main reply produces text deltas and a
   final marker; user text and worker completion do not.
2. Android instrumentation: recognition fragment assembly, early speech
   clause segmentation, assistant-service registration.
3. Build and sign on GitHub Actions; install **that artifact** on Fedora Android
   emulator and select the assistant via the system voice-interaction role.
4. Invoke the actual system voice gesture/session and confirm the microphone
   indicator, completed STT transcript, model output, TTS audio and dismissal.
5. Exercise an async subagent completion after dismiss and ensure it appears
   in chat without resuming speech.
6. Inspect `logcat -s PocketVoiceLatency:I` for actual voice latency,
   and foreground memory/battery with system tools. Never claim live voice
   latency was measured without a Deepgram-connected voice trial.

Known limitations: Deepgram STT/TTS consumes network access and quota.
An OEM may not expose the power-button assistant gesture; it may invoke a
different assistant despite the selected role. Android 12+ background service
and microphone restrictions still apply.
