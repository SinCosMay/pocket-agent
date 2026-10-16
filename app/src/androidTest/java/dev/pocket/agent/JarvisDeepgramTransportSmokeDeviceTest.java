package dev.pocket.agent;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Optional emulator-only detector-plus-Deepgram-transport smoke test (not a full
 * service handoff test). Requires a configured key in that emulator's app storage.
 * The key is used in-process and is never printed or exported.
 */
@RunWith(AndroidJUnit4.class)
public final class JarvisDeepgramTransportSmokeDeviceTest {
    private static byte[] readFixture(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n=input.read(buffer)) != -1;) output.write(buffer,0,n);
        return output.toByteArray();
    }

    @Test public void wakeDetectorAndLiveFluxTransportProduceConfirmedTranscript() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String credential = new VoiceCredentialStore(target).read();
        Assume.assumeTrue("Requires a configured Deepgram voice key", !credential.isEmpty());

        byte[] wakeAudio;
        byte[] queryAudio;
        Context fixture = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream input = fixture.getAssets().open("hey_jarvis.pcm")) {
            wakeAudio = readFixture(input);
        }
        try (InputStream input = fixture.getAssets().open("followup_query.pcm")) {
            queryAudio = readFixture(input);
        }

        float peak = 0;
        try (JarvisDetector detector = new JarvisDetector(target)) {
            for (int i=0;i<16;i++) detector.process(new short[1280]);
            for (int i=0;i+2559<wakeAudio.length;i+=2560) {
                short[] pcm=new short[1280];
                for(int j=0;j<1280;j++) pcm[j]=(short)(((wakeAudio[i+2*j+1]&255)<<8)|
                    (wakeAudio[i+2*j]&255));
                peak=Math.max(peak,detector.process(pcm));
            }
        }
        assertTrue("Model must wake before Deepgram is activated",peak>=0.75f);

        CountDownLatch done=new CountDownLatch(1);
        AtomicReference<String> finalTranscript=new AtomicReference<>();
        AtomicReference<String> failure=new AtomicReference<>();
        OkHttpClient client=new OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).build();
        Request request=new Request.Builder()
            .url("wss://api.deepgram.com/v2/listen?model=flux-general-en&encoding=linear16&sample_rate=16000")
            .header("Authorization","Token "+credential).build();
        WebSocket[] socketHolder=new WebSocket[1];
        try {
            WebSocket socket=client.newWebSocket(request,new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) {
                    new Thread(()->{
                        try {
                            // Exactly the same PCM16 frame format that AudioRecord streams.
                            for(int i=0;i<queryAudio.length;i+=2560) {
                                int size=Math.min(2560,queryAudio.length-i);
                                if(size%2!=0)size--;
                                if(size<=0)break;
                                if(!webSocket.send(ByteString.of(queryAudio,i,size)))
                                    throw new IllegalStateException("Deepgram rejected PCM");
                                Thread.sleep(80);
                            }
                            byte[] quiet=new byte[2560];
                            for(int i=0;i<45 && done.getCount()!=0;i++) {
                                if(!webSocket.send(ByteString.of(quiet)))break;
                                Thread.sleep(80);
                            }
                        }catch(Exception error){
                            failure.set(error.getClass().getSimpleName());
                            done.countDown();
                        }
                    },"LiveFluxFixturePublisher").start();
                }
                @Override public void onMessage(WebSocket webSocket,String text) {
                    try {
                        JSONObject event=new JSONObject(text);
                        String utterance=VoiceTranscript.completedTurn(event);
                        if(utterance!=null){
                            finalTranscript.set(utterance);
                            done.countDown();
                        } else if("Error".equals(event.optString("type"))) {
                            failure.set(event.optString("description","Deepgram returned error"));
                            done.countDown();
                        }
                    }catch(Exception ignored){}
                }
                @Override public void onFailure(WebSocket webSocket,Throwable error,Response response){
                    failure.set(error.getClass().getSimpleName());done.countDown();
                }
            });
            socketHolder[0]=socket;
            assertTrue("No confirmed Flux EndOfTurn: "+failure.get(),done.await(35,TimeUnit.SECONDS));
            assertNull("Deepgram stream error",failure.get());
            String transcript=finalTranscript.get();
            assertNotNull("No Flux-confirmed text",transcript);
            assertFalse(transcript.trim().isEmpty());
            System.out.println("JARVIS_E2E: localWake="+peak+" DeepgramFinalTranscript="+transcript);
        } finally {
            if(socketHolder[0]!=null)socketHolder[0].close(1000,"test complete");
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
