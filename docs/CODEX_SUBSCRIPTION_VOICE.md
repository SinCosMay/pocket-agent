# ChatGPT subscription voice backend

Branch: `feat/codex-subscription-voice-wake`. This experimental backend is **opt-in**. Normal Deepgram and Android speech routing remains the default and can be restored by unchecking the subscription-voice toggle in Settings.

## Architecture

- Pocket Agent's **Pi runtime is the only agent/tool execution harness**. The realtime voice service has no Android/tool privileges beyond the microphone, audio playback, and handing recognized input to Pi.
- The Android foreground `SubscriptionVoiceService` owns `SubscriptionVoiceLifecycle` (idle timeout, Pi turn IDs, queueing, interruption). `SubscriptionVoiceTransport` wraps `SubscriptionWebRtcTransport`, which owns one WebRTC peer, native microphone/audio output and authenticated control WebSocket per epoch.
- ChatGPT account tokens are read from encrypted `CredentialStore`. `SubscriptionVoiceCredentials` can perform one OAuth refresh off the main thread and atomically persist it. No OpenAI API key and no separate Codex executable are required.
- The same UUID is used in `session-id`, `thread-id`, and `x-session-id`; model `gpt-live-1-codex`; voice `cove`; `delegation.type=client`; `openai-alpha=quicksilver=v2`; a consistent `originator` / `User-Agent` identity. These exact protocol fields were material to a successful direct session.
- Wake word and Android assistant gestures open the new service when selected. Pi `speak_to_user` routes through the same service. A user turn has a UUID `voiceSessionId`, sent as the existing main Pi `send` command; cancellation uses scoped `stop_main` without stopping workers.
- Realtime `delegation.created` supplies an input-text content item and unique delegation ID. Pi output is returned as a `delegation.context.append` with `channel=speakable`. No model-native tool calls run in the voice session.
- After first connection there is a bounded 12-second window for the initial GPT-Live transcript (which may be delayed). Meaningful `session.input_transcript.delta` fragments extend an 8-second post-fragment grace window; an explicit VAD speech-start allows up to 60 seconds for a long utterance. After playback, ordinary idle follow-up is three seconds. Pending Pi work and output playback do not idle-close the session. An overall 45-second connect deadline includes potential credential refresh.
- Standard WebRTC `output_audio_buffer.stopped` events are matched to the active `response_id` when present. The conservative GPT-Live V3 fallback accepts only an **assistant** `turn.done` with a matching ID from `turn.created` (or a `response.done` matching `response.created`) **and then** a 3.5-second quiet period after reflected audio. A long gap alone cannot complete playback, and stale or unidentified completion events are ignored. The transport retains a bounded set of retired IDs across interruptions to reject delayed events from earlier speech. If GPT-Live emits no identifiable completion event, an inactivity watchdog fails after 30 seconds without reflected audio (or 60 seconds without the first audio), with a text-length-based absolute cap of at most ten minutes; it does not claim playback success. Transient OAuth-refresh network failures report connection unavailability rather than incorrectly requiring sign-in. Assistant output transcripts are never forwarded as user barge-in. An inferred ending still **does not prove audible playback**; verify announcement ordering and final audio on a physical device.

## Verified Linux proof (October 21, 2026)

The constrained `scripts/codex_voice_subscription_smoke.py` uses an existing Codex/ChatGPT login, synthetic audio, Chrome WebRTC, Python Playwright and an authenticated v3 sideband without launching any Codex process. A successful test showed:

- Call endpoint returned valid SDP, and WebRTC ICE/data channel connected.
- Authenticated `wss://api.openai.com/v1/live/{call_id}` control socket opened.
- One `delegation.created` event was observed on the data channel; the sideband accepted a simulated Pocket Agent `delegation.context.append`.
- Audio arrived from the realtime model (e.g. **552 inbound RTP audio packets**, **613 outbound**), with no reported authorization error.
- Delegation payload shape: `item.id`, `item.target=client`, and `item.content[0]={type:input_text,text:...}`; a second event stream may omit the event, so Android handles both data channel and sideband with a deduplication set.

Before the fix, the same request produced `Voice session access denied`; missing correlated thread/session headers, inconsistent client metadata and an unsupported voice selection explained the failing setup. The Android backend copies the corrected protocol shape; **a successful Linux call is not proof of Android audio functionality**.

Tests remain somewhat non-deterministic because the voice model may not delegate a particular synthetic utterance; zero delegations intentionally fails the stricter smoke assertion. No credentials, SDP or spoken content are logged.

## Validation

- `scripts/SubscriptionVoiceLifecycleSimulator.java` compiles with the standalone Java coordinator and tests duplicate wake, timeout, UUID scoped Pi cancellation, stale events, queued announcements, interruption and idle close under a one-core, 256 MiB scope.
- Native Android Java dependency is `io.github.webrtc-sdk:android:150.7871.01` (Maven Central), plus the existing OkHttp.
- Android compilation and integration checks belong in GitHub Actions. Do not run a resource-heavy local Android build or emulator on the user's workstation. Real-device microphone and battery tests are left to the user.

## Outstanding device validation

A successful APK compile is not sufficient for release. Verify on a physical Android phone: account import/sign-in and expired-token refresh, microphone permission/foreground-service policy, one voice request delegated to Pi and read aloud, `speak_to_user` without preceding user input, interruption while Pi is busy, two consecutive turns, three-second idle teardown, wake-word recovery, and switching the experimental toggle off to use legacy voice. Leave the new backend opt-in until these pass.

Security note: account credentials remain in Android Keystore-encrypted storage; never emit access/refresh tokens in diagnostics or issue/PR descriptions.
