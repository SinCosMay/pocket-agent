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

## Conversation context at voice startup

Each new subscription call reads the latest saved main-chat snapshot on the transport
worker, immediately before the call request. `SubscriptionVoiceContext` appends a
labelled JSON excerpt to `session.instructions`: up to 24 recent user/assistant text
messages and six task labels/IDs/statuses, preferring active work. Long messages retain
their beginning and end. The complete instructions are bounded to 16,000 characters,
including JSON escaping. This is a text excerpt, not a model-generated summary or a
second agent inference.

Only these public snapshot fields are selected. Provider configuration, credentials,
main/worker system prompts, worker transcripts, raw tool results and task outputs are
not copied. Historical text is explicitly untrusted context, never a request to replay
an earlier action. Saved task statuses may be stale; Pi must verify current outcomes.
If the snapshot cannot be read, the call continues with the base voice instructions.
Starting a fresh main chat removes the previous main excerpt on the next voice call.
Updates to the chat from elsewhere during an existing call are picked up on its next
connection; this change does not synchronize every UI message into an active session.

Pi remains the only tool executor and retains its own main conversation. Voice is
instructed to ask Pi for missing older details using its retained conversation and
available memory tools. This uses no Hindsight-specific settings or memory-store
schema and does not depend on the pending remote-memory PR. Once that work is merged,
Pi's configured recall path remains responsible for older context. No external memory
lookup is performed merely by opening voice.

### Subscription endpoint verification (October 23, 2026)

Controlled Linux calls used the same `gpt-live-1-codex` subscription AVAS endpoint,
an existing ChatGPT login, and generated audio streamed only after WebRTC connected.
No real microphone, phone, personal memory or separately billed API key was used.
Sanitized results are in [the context verification record](validation/2026-10-23-subscription-voice-context.json).

- `session.input` returned HTTP 201 and then failed over the data channel with
  `Unknown parameter: 'input'`. Public GPT-Live startup history is not accepted by
  this subscription route; HTTP status alone is not a successful session check.
- Recent conversation embedded in startup `instructions` supplied a fictional
  project name and next action absent from the generated audio. The assistant
  correctly recalled “Marigold Orbit” and “test the blue button,” with 592 received
  audio packets and no runtime errors.
- A separate memory-handoff call delegated the older-memory question, acknowledged
  a simulated client's result, and spoke the synthetic saved rule about a copper
  lantern, with 913 received audio packets and no runtime errors. The delegation
  contained the user's request rather than an explicit `memory_search` call, so
  tool selection belongs to Pi. This is not proof of a real memory-store lookup.

These calls verify the startup-instructions mechanism, not the new Android snapshot
reader or arbitrary context lengths on a physical device. Focused context regression
tests run against both a standalone JSON library and Android's JSON implementation
in the signed-APK emulator workflow. Physical text-chat-to-voice continuity remains
an acceptance check for the updated APK.

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
