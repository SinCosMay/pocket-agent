# Codex subscription voice integration (work in progress)

Branch: `feat/codex-subscription-voice-wake` (branched from latest local `origin/main` on October 21, 2026).

## Non-negotiable architecture

- **Pi remains the sole agent harness** for model reasoning, tool calls, Android control, worker scheduling, and saved conversations.
- Codex realtime voice supplies the audio conversation through **ChatGPT account/subscription authentication**, not an OpenAI API key and not usage-based GPT-Live API billing.
- Do **not** install a second Codex coding harness or require a PC/relay on the phone.
- The existing Android/Deepgram voice path is unchanged until an independent Android Codex voice transport has been verified. Do not enable a nonfunctional experimental backend by default.

## Wake, speaking, and session lifecycle

The first committed component, `SubscriptionVoiceLifecycle`, is a platform-independent coordinator intended to sit between Android's voice foreground service and a subscription WebRTC transport. It uses a narrow `Port` for networking and Pi commands; it does not itself capture audio, speak, open sockets, or replace Pi.

1. Local wake detector or Android assistant gesture calls `wake`. It opens a single connection, not a second agent session or another Codex harness. Duplicate wakes reuse that connection.
2. When connected, the voice transport listens with one Android microphone/audio track. A realtime **`delegation.created`** event delivers the user request to Pi via the existing main `send` command. Voice is the interface, not the executor.
3. Pi `voice_delta` events are accumulated for the matching main turn; `voice_end` returns the result to Codex Voice as speakable delegation context. Other Pi workers remain completely independent. No side-effecting tools run in the voice model.
4. Pi's existing `speak_to_user({message,listen_after})` should route to `announce`. It opens a voice connection on demand if none exists, speaks via session context, then listens when `listen_after=true`; otherwise it disconnects after playback.
5. In `LISTENING` state only, three seconds without activity disconnects the WebRTC, sideband, and microphone. Active Pi responses, playback, or queued announcements do not trigger idle closure. Connection establishment is bounded to 15 seconds.
6. A new user utterance during speech cancels queued audio and, when applicable, only the active voice-scoped main Pi turn. Pi workers continue. Stop voice always releases the transport and never cancels unrelated workers.
7. Network disconnection, explicit stop, and stale callbacks use a generation ID and release session resources; a late callback from an older session cannot resurrect the microphone. App restarts never replay uncertain tool effects.

The coordinator needs to be wired to an Android foreground `Service`, a native WebRTC dependency and an authenticated Codex subscription voice adapter. This **is not complete yet**.

## Verified Linux findings and blocker

A minimal reproducible smoke test is in `scripts/codex_voice_subscription_smoke.py`.

- Installed `codex-cli 0.163.0` with ChatGPT account sign-in and Codex **app-server**: WebRTC offer, authenticated answer, connected ICE/data channel, outgoing synthetic speech and incoming model audio all succeeded. This proves the subscription route with the Codex controller, **not** standalone Android compatibility.
- A separate Linux probe **without running any Codex executable** sent a standards-compliant WebRTC offer directly to `https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas`, with account access token and `openai-alpha: quicksilver=v2`. The endpoint returned an SDP answer and call location; thus a full Codex executable is not needed for *call creation*.
- That standalone direct session did **not** reach a working speech round-trip: sideband WebSocket attachment to `wss://api.openai.com/v1/live/<callId>` failed with WebSocket `InvalidStatus`, and the peer eventually disconnected with zero inbound RTP packets. A standalone subscription voice implementation is therefore **not proven**.
- Codex upstream's experimental v3 protocol uses `delegation.type=client`, `delegation.created`, and `delegation.context.append` with `channel=speakable`; input can also arrive as `turn.done` transcript events. The sideband connection must be authenticated and bound to the same live call; it cannot be omitted. Its service routing/authentication needs further proof before we ship an Android WebRTC dependency.

Useful upstream references:
- `openai/codex/codex-rs/core/src/realtime_conversation.rs` and `sideband.rs`
- `openai/codex/codex-rs/codex-api/src/endpoint/realtime_call.rs`
- `openai/codex/codex-rs/codex-api/src/endpoint/realtime_websocket/{methods.rs,protocol_frameless_bidi.rs}`

## Resource-restricted testing

No Android emulator, APK build, dev server, Rust compilation, Codex installation, or full-repository tests were run.

The self-contained simulator compiles just two Java files and exercises duplicate wake, stale callbacks, Pi handoff, idle cleanup, no-listen speech requests, queued announcements, barge-in, failure, and timeout:

```sh
systemd-run --user --scope -p MemoryMax=256M -p CPUQuota=100% --quiet \
  taskset -c 0 nice -n 12 bash -c '
    set -eu
    d=$(mktemp -d)
    trap "rm -rf \"$d\"" EXIT
    javac -J-Xmx64m -d "$d" \
      app/src/main/java/dev/pocket/agent/SubscriptionVoiceLifecycle.java \
      scripts/SubscriptionVoiceLifecycleSimulator.java
    java -Xmx48m -XX:ActiveProcessorCount=1 -cp "$d" \
      dev.pocket.agent.SubscriptionVoiceLifecycleSimulator
  '
```

Result: **3 scenarios passed** (one core, 256 MiB total cgroup limit, 48 MiB Java heap).

The Linux realtime probe ran in a separate user scope capped at **850 MiB RAM and one CPU quota**, affined to two logical cores. It requires a logged-in Codex account, local Chrome, Playwright, espeak-ng, ffmpeg and `websockets`. It does not show token contents, provider response bodies or SDP in output. It is explicitly diagnostic and currently exits nonzero because standalone sideband is not authorized.

## Completion gates

Before wiring this backend into the normal wake path:

1. Demonstrate a standalone sideband connection and actual delegated speech result **without a Codex executable**, using only existing ChatGPT-account credentials.
2. Confirm Codex voice never executes tools on its own and always hands tool requests to Pi; handle duplicate and interrupted delegation IDs.
3. Add the Android WebRTC audio/network transport, encrypted token refresh, and a user-controlled subscription voice setting. Keep the legacy path available and disabled only after new transport is ready.
4. Route wake word, assistant gesture, `speak_to_user`, stop voice, and Pi voice events into the coordinator.
5. Run Android-compilation and scoped tests in GitHub Actions, then defer real-device acoustics/battery tests to the user. Do not start a local emulator or broad build.
