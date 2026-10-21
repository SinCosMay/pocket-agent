package dev.pocket.agent;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.JavaAudioDeviceModule;

/**
 * Subscription-authenticated native WebRTC audio transport. It is an I/O adapter only:
 * delegation requests must be executed by the caller's Pi harness, never by this class.
 *
 * The owner wires connect/close/sendSpeech/interruptSpeech to
 * SubscriptionVoiceLifecycle.Port and forwards epoch-tagged callbacks to the lifecycle.
 * Callbacks are delivered on Android's main looper. An owner must provide already
 * refreshed subscription credentials; this class never persists or logs them.
 *
 * This experimental backend should remain opt-in until a complete real-device call
 * (including authenticated sideband and received audio) has been demonstrated.
 */
final class SubscriptionWebRtcTransport implements AutoCloseable {
    interface CredentialProvider {
        /** Must return an unexpired ChatGPT subscription token and its account ID. */
        Credentials credentials() throws Exception;
    }

    static final class Credentials {
        final String accessToken;
        final String accountId;

        Credentials(String accessToken, String accountId) {
            if (accessToken == null || accessToken.isEmpty() || accessToken.length() > 32768
                    || !accessToken.matches("[^\\x00-\\x20\\x7f]+")
                    || accountId == null || !accountId.matches("[A-Za-z0-9._:-]{1,200}")) {
                throw new IllegalArgumentException("Invalid subscription credentials");
            }
            this.accessToken = accessToken;
            this.accountId = accountId;
        }
    }

    interface Listener {
        void onConnected(long epoch);
        void onDelegation(long epoch, String delegationId, String userText);
        void onActivity(long epoch);
        void onInterrupted(long epoch);
        default void onSpeechStarted(long epoch) {}
        default void onSpeechStopped(long epoch) {}
        void onPlaybackFinished(long epoch);
        void onDisconnected(long epoch);
        void onError(long epoch, String safeMessage);
        /** Informational transcript only; tool work must originate in onDelegation. */
        default void onTranscript(long epoch, String text) {}
    }

    private static final String CALL_URL =
            "https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas";
    private static final String WS_ROOT = "wss://api.openai.com/v1/live/";
    private static final String CLIENT_NAME = "pocket-agent-voice";
    private static final String CLIENT_VERSION = "0.163.0";
    private static final String INSTRUCTIONS =
            "You are the voice interface for Pocket Agent, a separate client-side agent. "
            + "Delegate every user request to the client. You must not execute tools, "
            + "perform tasks, or answer requests yourself. Speak only the results supplied "
            + "by the client via speakable delegation context. Do not claim to have used tools.";
    private static final int MAX_SDP_BYTES = 131072;
    private static final int MAX_EVENT_CHARS = 65536;
    private static final int MAX_SPEECH_CHARS = 8000;
    private static final long CONNECT_TIMEOUT_MS = 15000;
    private static final long ICE_GATHER_TIMEOUT_MS = 7000;

    private static final Object WEBRTC_INIT_LOCK = new Object();
    private static boolean webrtcInitialized;
    private final AtomicLong failureEpoch = new AtomicLong(-1);
    private final Context context;
    private final CredentialProvider credentials;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread workerThread = new HandlerThread("PocketSubscriptionRtc");
    private final Handler worker;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private final AtomicLong wantedEpoch = new AtomicLong(-1);
    private final AtomicBoolean disposed = new AtomicBoolean();
    private Session current;

    private static final class Session {
        final long epoch;
        final boolean allowMicrophone;
        final String requestId = UUID.randomUUID().toString();
        final Set<String> delegations = new HashSet<>();
        final Set<String> pendingSpeech = new HashSet<>();
        final LinkedHashSet<String> eventIds = new LinkedHashSet<>();
        final ArrayDeque<String[]> pendingDelegations = new ArrayDeque<>();
        Credentials credentials;
        PeerConnectionFactory factory;
        PeerConnection peer;
        AudioSource microphone;
        AudioTrack track;
        DataChannel channel;
        Call request;
        WebSocket sideband;
        String callId;
        boolean localDescriptionSet;
        boolean offerSent;
        boolean rtcConnected;
        boolean dataConnected;
        boolean sidebandOpening;
        boolean sidebandConnected;
        boolean connectedNotified;
        boolean pendingSpeechPlayback;
        int speechSerial;
        boolean responseActive;
        boolean cancelRequested;
        boolean finished;
        Session(long epoch, boolean microphone) { this.epoch = epoch; this.allowMicrophone = microphone; }
    }

    SubscriptionWebRtcTransport(Context context, CredentialProvider credentials, Listener listener) {
        if (context == null || credentials == null || listener == null)
            throw new IllegalArgumentException("Missing subscription transport dependency");
        this.context = context.getApplicationContext();
        this.credentials = credentials;
        this.listener = listener;
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    /** Asynchronous; repeated calls with the same epoch reuse the running session. */
    void connect(long epoch, boolean microphone) {
        if (disposed.get() || epoch < 0) return;
        failureEpoch.set(-1);
        wantedEpoch.set(epoch);
        worker.post(() -> {
            if (wantedEpoch.get() != epoch || disposed.get()) return;
            if (current != null && current.epoch == epoch) return;
            disposeSession();
            Session session = new Session(epoch, microphone);
            current = session;
            worker.postDelayed(() -> {
                if (active(session) && !session.connectedNotified)
                    fail(session, "Subscription voice connection timed out");
            }, CONNECT_TIMEOUT_MS);
            initialize(session);
        });
    }

    /** Epoch fencing is synchronous; it takes effect before socket/native teardown. */
    void close(long epoch) {
        if (wantedEpoch.compareAndSet(epoch, -1)) {
            failureEpoch.set(-1);
            worker.post(() -> {
                if (current != null && current.epoch == epoch) disposeSession();
            });
        }
    }

    void sendSpeech(long epoch, String delegationId, String text) {
        if (text == null || text.isBlank() || text.length() > MAX_SPEECH_CHARS) return;
        worker.post(() -> {
            Session session = current;
            if (!active(session) || session.epoch != epoch || !session.connectedNotified) return;
            try {
                JSONObject message;
                if (delegationId == null) {
                    message = contextAppend("session.context.append", null, text);
                    session.pendingSpeechPlayback = true;
                    if (!send(session, message) || !send(session, new JSONObject().put("type", "response.create")))
                        fail(session, "Unable to send voice announcement");
                } else {
                    if (!session.delegations.contains(delegationId)
                            || !session.pendingSpeech.add(delegationId)) return;
                    message = contextAppend("delegation.context.append", delegationId, text);
                    session.pendingSpeechPlayback = true;
                    if (!send(session, message)) fail(session, "Unable to return delegated speech");
                }
                if (active(session) && session.pendingSpeechPlayback) {
                    int serial = ++session.speechSerial;
                    worker.postDelayed(() -> {
                        if (active(session) && session.pendingSpeechPlayback
                                && session.speechSerial == serial)
                            fail(session, "Voice playback did not finish");
                    }, 60_000);
                }
            } catch (JSONException invalid) {
                fail(session, "Unable to encode voice response");
            }
        });
    }

    /** Cancels the current spoken output only; never cancels the Pi task. */
    void interruptSpeech(long epoch) {
        worker.post(() -> {
            Session session = current;
            if (!active(session) || session.epoch != epoch || !session.connectedNotified) return;
            session.pendingSpeechPlayback = false;
            try {
                if (session.responseActive) {
                    session.cancelRequested = send(session, new JSONObject().put("type", "response.cancel"));
                    session.responseActive = false;
                }
                send(session, new JSONObject().put("type", "output_audio_buffer.clear"));
            } catch (JSONException ignored) {
                // Static event types cannot fail JSON serialization.
            }
        });
    }

    private static JSONObject contextAppend(String type, String delegationId, String text)
            throws JSONException {
        JSONObject event = new JSONObject().put("type", type)
                .put("channel", "speakable")
                .put("content", new JSONArray().put(new JSONObject()
                        .put("type", "input_text").put("text", text)));
        if (delegationId != null) event.put("delegation_item_id", delegationId);
        return event;
    }

    private boolean active(Session session) {
        return session != null && !session.finished && current == session
                && !disposed.get() && wantedEpoch.get() == session.epoch;
    }

    private void onMain(Session session, Runnable callback) {
        main.post(() -> {
            if (!disposed.get() && wantedEpoch.get() == session.epoch) callback.run();
        });
    }

    private void initialize(Session session) {
        try {
            if (session.allowMicrophone && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                fail(session, "Microphone permission is required for subscription voice");
                return;
            }
            session.credentials = credentials.credentials();
            if (session.credentials == null)
                throw new IllegalArgumentException("Missing subscription credentials");
            synchronized (WEBRTC_INIT_LOCK) {
                if (!webrtcInitialized) {
                    PeerConnectionFactory.initialize(
                            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
                    webrtcInitialized = true;
                }
            }
            JavaAudioDeviceModule audio = JavaAudioDeviceModule.builder(context).createAudioDeviceModule();
            try {
                session.factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio)
                        .createPeerConnectionFactory();
            } finally {
                audio.release();
            }
            PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(
                    Collections.emptyList());
            configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            session.peer = session.factory.createPeerConnection(configuration, peerObserver(session));
            if (session.peer == null) throw new IllegalStateException("WebRTC peer could not be created");
            if (session.allowMicrophone) {
                session.microphone = session.factory.createAudioSource(new MediaConstraints());
            session.track = session.factory.createAudioTrack("pocket-microphone", session.microphone);
            session.track.setEnabled(true);
            if (session.peer.addTrack(session.track, Collections.singletonList("pocket-audio")) == null)
                throw new IllegalStateException("WebRTC microphone track could not be added");
            } else {
                RtpTransceiver receiver = session.peer.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                    new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY));
                if (receiver == null) throw new IllegalStateException("WebRTC receive-only track unavailable");
            }
            session.channel = session.peer.createDataChannel("oai-events", new DataChannel.Init());
            if (session.channel == null) throw new IllegalStateException("WebRTC data channel unavailable");
            session.channel.registerObserver(channelObserver(session));
            session.peer.createOffer(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription sdp) {
                    worker.post(() -> {
                        if (!active(session)) return;
                        session.peer.setLocalDescription(setObserver(session, () -> {
                            session.localDescriptionSet = true;
                            if (session.peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE)
                                submitOffer(session);
                            else worker.postDelayed(() -> {
                                if (active(session) && !session.offerSent)
                                    fail(session, "WebRTC ICE gathering timed out");
                            }, ICE_GATHER_TIMEOUT_MS);
                        }), sdp);
                    });
                }
                @Override public void onSetSuccess() {}
                @Override public void onCreateFailure(String error) {
                    worker.post(() -> fail(session, "WebRTC could not create an audio offer"));
                }
                @Override public void onSetFailure(String error) {
                    worker.post(() -> fail(session, "WebRTC could not create an audio offer"));
                }
            }, new MediaConstraints());
        } catch (Exception | LinkageError problem) {
            fail(session, "Unable to initialize native subscription audio");
        }
    }

    private SdpObserver setObserver(Session session, Runnable success) {
        return new SdpObserver() {
            @Override public void onCreateSuccess(SessionDescription sdp) {}
            @Override public void onSetSuccess() {
                worker.post(() -> { if (active(session)) success.run(); });
            }
            @Override public void onCreateFailure(String error) {
                worker.post(() -> fail(session, "WebRTC description setup failed"));
            }
            @Override public void onSetFailure(String error) {
                worker.post(() -> fail(session, "WebRTC description setup failed"));
            }
        };
    }

    private PeerConnection.Observer peerObserver(Session session) {
        return new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                worker.post(() -> {
                    if (!active(session)) return;
                    if (state == PeerConnection.IceConnectionState.FAILED
                            || state == PeerConnection.IceConnectionState.CLOSED
                            || state == PeerConnection.IceConnectionState.DISCONNECTED)
                        disconnected(session);
                    else if (state == PeerConnection.IceConnectionState.CONNECTED
                            || state == PeerConnection.IceConnectionState.COMPLETED) {
                        session.rtcConnected = true;
                        attachSideband(session);
                        checkReady(session);
                    }
                });
            }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState state) {
                worker.post(() -> {
                    if (!active(session)) return;
                    if (state == PeerConnection.PeerConnectionState.FAILED
                            || state == PeerConnection.PeerConnectionState.CLOSED
                            || state == PeerConnection.PeerConnectionState.DISCONNECTED)
                        disconnected(session);
                    else if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                        session.rtcConnected = true;
                        attachSideband(session);
                        checkReady(session);
                    }
                });
            }
            @Override public void onIceConnectionReceivingChange(boolean receiving) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                worker.post(() -> {
                    if (active(session) && state == PeerConnection.IceGatheringState.COMPLETE
                            && session.localDescriptionSet) submitOffer(session);
                });
            }
            @Override public void onIceCandidate(IceCandidate candidate) {}
            @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
            @Override public void onAddStream(MediaStream stream) {}
            @Override public void onRemoveStream(MediaStream stream) {}
            @Override public void onDataChannel(DataChannel channel) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
                if (receiver.track() instanceof AudioTrack) {
                    ((AudioTrack) receiver.track()).setEnabled(true);
                }
            }
        };
    }

    private DataChannel.Observer channelObserver(Session session) {
        return new DataChannel.Observer() {
            @Override public void onBufferedAmountChange(long previousAmount) {}
            @Override public void onStateChange() {
                worker.post(() -> {
                    if (!active(session)) return;
                    DataChannel.State state = session.channel.state();
                    if (state == DataChannel.State.OPEN) {
                        session.dataConnected = true;
                        checkReady(session);
                    } else if (state == DataChannel.State.CLOSED && session.connectedNotified)
                        disconnected(session);
                });
            }
            @Override public void onMessage(DataChannel.Buffer buffer) {
                if (buffer.binary || buffer.data.remaining() > MAX_EVENT_CHARS) return;
                ByteBuffer raw = buffer.data.slice();
                byte[] bytes = new byte[raw.remaining()];
                raw.get(bytes);
                String message = new String(bytes, StandardCharsets.UTF_8);
                worker.post(() -> handleEvent(session, message));
            }
        };
    }

    private void submitOffer(Session session) {
        if (!active(session) || session.offerSent) return;
        SessionDescription local = session.peer.getLocalDescription();
        if (local == null || local.description == null || !local.description.startsWith("v=0")
                || local.description.length() > MAX_SDP_BYTES) {
            fail(session, "WebRTC generated an invalid local description");
            return;
        }
        session.offerSent = true;
        try {
            JSONObject body = new JSONObject().put("sdp", local.description)
                    .put("session", new JSONObject()
                            .put("instructions", INSTRUCTIONS)
                            .put("model", "gpt-live-1-codex")
                            .put("audio", new JSONObject().put("output",
                                    new JSONObject().put("voice", "cove")))
                            .put("delegation", new JSONObject().put("type", "client")));
            Request request = authHeaders(new Request.Builder().url(CALL_URL), session, CLIENT_NAME)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/sdp")
                    .post(RequestBody.create(body.toString(),
                            MediaType.get("application/json; charset=utf-8"))).build();
            session.request = client.newCall(request);
            session.request.enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException error) {
                    worker.post(() -> fail(session, "Subscription call setup failed"));
                }
                @Override public void onResponse(Call call, Response response) {
                    String answer = null;
                    String callId = null;
                    int code = response.code();
                    try (Response result = response) {
                        if (code == 201 && result.body() != null) {
                            String location = result.header("Location", "");
                            callId = parseCallId(location);
                            if (callId != null) {
                                try (InputStream input = result.body().byteStream();
                                        ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
                                    byte[] buffer = new byte[4096];
                                    int length;
                                    while ((length = input.read(buffer)) != -1) {
                                        if (sink.size() + length > MAX_SDP_BYTES) break;
                                        sink.write(buffer, 0, length);
                                    }
                                    if (length == -1)
                                        answer = sink.toString(StandardCharsets.UTF_8.name());
                                }
                            }
                        }
                    } catch (IOException invalid) {
                        // No request URLs, bodies or authentication data enter error messages.
                    }
                    final String sdpAnswer = answer;
                    final String confirmedId = callId;
                    worker.post(() -> {
                        if (!active(session)) return;
                        if (code != 201) {
                            fail(session, "Subscription call rejected (HTTP " + code + ")");
                        } else if (sdpAnswer == null || !sdpAnswer.startsWith("v=0")
                                || confirmedId == null) {
                            fail(session, "Subscription call returned an invalid SDP answer");
                        } else {
                            session.callId = confirmedId;
                            session.peer.setRemoteDescription(setObserver(session,
                                    () -> attachSideband(session)),
                                    new SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer));
                        }
                    });
                }
            });
        } catch (JSONException invalid) {
            fail(session, "Unable to encode subscription voice request");
        }
    }

    private static Request.Builder authHeaders(Request.Builder builder, Session session, String originator) {
        return builder.header("Authorization", "Bearer " + session.credentials.accessToken)
                .header("chatgpt-account-id", session.credentials.accountId)
                .header("originator", originator)
                .header("User-Agent", originator + "/" + CLIENT_VERSION)
                .header("version", CLIENT_VERSION)
                .header("session-id", session.requestId)
                .header("thread-id", session.requestId)
                .header("x-session-id", session.requestId)
                .header("openai-alpha", "quicksilver=v2");
    }

    private static String parseCallId(String location) {
        if (location == null || location.isEmpty()) return null;
        String path = location.split("\\?", 2)[0];
        String id = path.substring(path.lastIndexOf('/') + 1);
        return id.matches("rtc_[A-Za-z0-9_-]{3,128}") ? id : null;
    }

    private void attachSideband(Session session) {
        if (!active(session) || !session.rtcConnected || session.callId == null
                || session.sidebandOpening) return;
        session.sidebandOpening = true;
        Request request = authHeaders(new Request.Builder()
                .url(WS_ROOT + session.callId), session, "codex_cli_rs").build();
        session.sideband = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                worker.post(() -> {
                    if (!active(session) || session.sideband != socket) {
                        socket.close(1000, "stale");
                        return;
                    }
                    session.sidebandConnected = true;
                    checkReady(session);
                });
            }
            @Override public void onMessage(WebSocket socket, String text) {
                if (text.length() <= MAX_EVENT_CHARS)
                    worker.post(() -> {
                        if (active(session) && session.sideband == socket) handleEvent(session, text);
                    });
            }
            @Override public void onMessage(WebSocket socket, ByteString bytes) {}
            @Override public void onClosing(WebSocket socket, int code, String reason) {
                worker.post(() -> { if (active(session)) disconnected(session); });
            }
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                int status = response == null ? 0 : response.code();
                worker.post(() -> {
                    if (!active(session)) return;
                    fail(session, status > 0
                            ? "Subscription sideband rejected (HTTP " + status + ")"
                            : "Subscription sideband connection failed");
                });
            }
        });
    }

    private void checkReady(Session session) {
        if (!active(session) || session.connectedNotified || !session.rtcConnected
                || !session.dataConnected || !session.sidebandConnected) return;
        session.connectedNotified = true;
        onMain(session, () -> listener.onConnected(session.epoch));
        while (!session.pendingDelegations.isEmpty()) {
            String[] item = session.pendingDelegations.removeFirst();
            onMain(session, () -> listener.onDelegation(session.epoch, item[0], item[1]));
        }
    }

    private boolean send(Session session, JSONObject event) {
        return session.sidebandConnected && session.sideband != null
                && session.sideband.send(event.toString());
    }

    private void handleEvent(Session session, String raw) {
        if (!active(session) || raw.length() > MAX_EVENT_CHARS) return;
        JSONObject event;
        try { event = new JSONObject(raw); }
        catch (JSONException invalid) { return; }
        // Data channel and sideband mirror some events. Handle each event once.
        String eventId = event.optString("event_id", "");
        if (!eventId.isEmpty()) {
            if (!session.eventIds.add(eventId)) return;
            if (session.eventIds.size() > 512)
                session.eventIds.remove(session.eventIds.iterator().next());
        }
        String type = event.optString("type");
        if ("turn.created".equals(type) || "response.created".equals(type)
                || "session.output_audio.delta".equals(type)) session.responseActive = true;
        if ("turn.done".equals(type) || "response.done".equals(type)) session.responseActive = false;
        switch (type) {
            case "delegation.created": {
                JSONObject item = event.optJSONObject("item");
                if (item == null || !"client".equals(item.optString("target"))) return;
                String id = item.optString("id");
                if (id.isEmpty() || session.delegations.contains(id)) return;
                String spoken = textOf(item);
                if (spoken.isBlank()) spoken = textOf(event);
                if (spoken.isBlank() || !session.delegations.add(id)) return;
                String requestText = spoken;
                if (session.connectedNotified)
                    onMain(session, () -> listener.onDelegation(session.epoch, id, requestText));
                else if (session.pendingDelegations.size() < 16)
                    session.pendingDelegations.addLast(new String[]{id, requestText});
                break;
            }
            case "conversation.item.input_audio_transcription.completed":
            case "conversation.item.input_audio_transcription.delta":
            case "turn.done": {
                String spoken = textOf(event);
                if (!spoken.isBlank())
                    onMain(session, () -> listener.onTranscript(session.epoch, spoken));
                onMain(session, () -> listener.onActivity(session.epoch));
                break;
            }
            case "input_audio_buffer.speech_started":
            case "input_audio.speech_started":
                if (!session.allowMicrophone) break;
                onMain(session, () -> {
                    listener.onActivity(session.epoch);
                    listener.onSpeechStarted(session.epoch);
                    listener.onInterrupted(session.epoch);
                });
                break;
            case "input_audio_buffer.speech_stopped":
            case "input_audio.speech_stopped":
                onMain(session, () -> listener.onSpeechStopped(session.epoch));
                break;
            case "output_audio_buffer.stopped":
            case "session.output_audio.playback_done":
                if (session.pendingSpeechPlayback) {
                    session.pendingSpeechPlayback = false;
                    onMain(session, () -> listener.onPlaybackFinished(session.epoch));
                }
                break;
            case "error":
                // Never pass provider error messages through: they may contain secrets.
                JSONObject details = event.optJSONObject("error");
                String code = details == null ? "" : details.optString("code", "");
                if (session.cancelRequested && "response_cancel_not_active".equals(code)) {
                    session.cancelRequested = false;
                    break;
                }
                fail(session, "Subscription realtime service returned an error");
                break;
            default:
                break;
        }
    }

    private static String textOf(JSONObject event) { return textOf(event, 0); }

    private static String textOf(JSONObject event, int depth) {
        if (depth > 6) return "";
        for (String key : new String[]{"transcript", "text", "input_text"}) {
            String value = event.optString(key);
            if (!value.isBlank() && value.length() <= MAX_SPEECH_CHARS) return value;
        }
        for (String key : new String[]{"input", "item", "turn"}) {
            JSONObject nested = event.optJSONObject(key);
            if (nested != null) {
                String value = textOf(nested, depth + 1);
                if (!value.isBlank()) return value;
            }
        }
        for (String key : new String[]{"content", "parts"}) {
            JSONArray parts = event.optJSONArray(key);
            if (parts == null) continue;
            for (int i = 0; i < Math.min(16, parts.length()); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null) {
                    String value = textOf(part, depth + 1);
                    if (!value.isBlank()) return value;
                }
            }
        }
        return "";
    }

    private void disconnected(Session session) {
        if (!active(session)) return;
        long epoch = session.epoch;
        disposeSession();
        if (wantedEpoch.compareAndSet(epoch, -1)) {
            failureEpoch.set(epoch);
            main.post(() -> { if (!disposed.get() && failureEpoch.get() == epoch)
                listener.onDisconnected(epoch); });
        }
    }

    private void fail(Session session, String safeMessage) {
        if (!active(session)) return;
        long epoch = session.epoch;
        disposeSession();
        if (wantedEpoch.compareAndSet(epoch, -1)) {
            failureEpoch.set(epoch);
            main.post(() -> { if (!disposed.get() && failureEpoch.get() == epoch)
                listener.onError(epoch, safeMessage); });
        }
    }

    private void disposeSession() {
        Session session = current;
        current = null;
        if (session == null || session.finished) return;
        session.finished = true;
        if (session.request != null) safely(session.request::cancel);
        if (session.sideband != null) {
            if (session.sidebandConnected)
                safely(() -> session.sideband.send("{\"type\":\"session.close\"}"));
            safely(() -> session.sideband.close(1000, "voice stopped"));
            safely(session.sideband::cancel);
        }
        if (session.channel != null) {
            safely(session.channel::unregisterObserver);
            safely(session.channel::close);
            safely(session.channel::dispose);
        }
        if (session.peer != null) {
            safely(session.peer::close);
            safely(session.peer::dispose);
        }
        if (session.track != null) safely(session.track::dispose);
        if (session.microphone != null) safely(session.microphone::dispose);
        if (session.factory != null) safely(session.factory::dispose);
        session.credentials = null;
        session.delegations.clear();
        session.pendingSpeech.clear();
        session.eventIds.clear();
        session.pendingDelegations.clear();
    }

    private static void safely(Runnable cleanup) {
        try { cleanup.run(); }
        catch (RuntimeException | LinkageError ignored) {
            // Release remaining resources even if one JNI object is already invalid.
        }
    }

    @Override public void close() {
        if (!disposed.compareAndSet(false, true)) return;
        wantedEpoch.set(-1);
        failureEpoch.set(-1);
        worker.post(() -> {
            disposeSession();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            workerThread.quitSafely();
        });
    }
}
