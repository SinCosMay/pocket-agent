package dev.pocket.agent;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Constructor;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DevBridgeDeviceTest {
    private Context context() {return InstrumentationRegistry.getInstrumentation().getTargetContext();}

    private Object server() throws Exception {
        Class<?> type=Class.forName("dev.pocket.agent.DevMcpServer");
        Constructor<?> constructor=type.getDeclaredConstructor(Context.class,String.class);
        constructor.setAccessible(true);
        Object result=constructor.newInstance(context(),"instrumentation-test-token");
        type.getMethod("start",int.class,boolean.class).invoke(result,5000,false);
        return result;
    }
    private String request(String token,String method,String origin) throws Exception {
        JSONObject body=new JSONObject().put("jsonrpc","2.0").put("id",1).put("method",method);
        if(method.equals("initialize"))body.put("params",new JSONObject().put("protocolVersion","2025-11-25"));
        byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
        try(Socket socket=new Socket("127.0.0.1",8767)) {
            socket.setSoTimeout(9000);
            String headers="POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nAccept: application/json\r\nAuthorization: Bearer "+token+
                "\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n"+(origin==null?"":"Origin: https://untrusted.example\r\n")+"\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
            java.io.ByteArrayOutputStream response=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int n;
            while((n=socket.getInputStream().read(buffer))!=-1)response.write(buffer,0,n);
            return response.toString("UTF-8");
        }
    }
    private void startAgent() throws Exception {
        Context c=context();
        c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        c.startForegroundService(new Intent(c,AgentService.class).setAction(AgentService.ACTION_START));
        long end=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<end) {
            RuntimeHost host=AgentService.peekHost();
            if(host!=null)try {if(host.developmentToolList().get(2,TimeUnit.SECONDS).length()>0)return;}
            catch(Exception ignored){}
            Thread.sleep(150);
        }
        throw new AssertionError("Development tools did not start");
    }
    @Test public void authenticatedMcpListsSameModelToolsAndBlocksUntrustedOrigin() throws Exception {
        Assume.assumeTrue(BuildConfig.DEVELOPMENT_TOOLS);
        startAgent();
        Object bridge=server();
        try {
            assertTrue(request("wrong","ping",null).startsWith("HTTP/1.1 401"));
            assertTrue(request("instrumentation-test-token","ping","https://untrusted.example").startsWith("HTTP/1.1 403"));
            assertTrue(request("instrumentation-test-token","initialize",null).contains("pocket-agent-development"));
            String tools=request("instrumentation-test-token","tools/list",null);
            assertTrue(tools,tools.contains("phone_capabilities"));
            assertTrue(tools,tools.contains("memory_search"));
            assertTrue(tools,tools.contains("subagent"));
            assertTrue(tools,tools.contains("debug_history"));
        } finally {bridge.getClass().getMethod("stop").invoke(bridge);}
    }
    @Test public void localLogSearchAndPaginationRejectInvalidBounds() throws Exception {
        Assume.assumeTrue(BuildConfig.DEVELOPMENT_TOOLS);
        try(StateStore store=new StateStore(context())) {
            String nonce="dev-bridge-test-"+System.nanoTime();
            store.record(new JSONObject().put("type","log").put("message",nonce));
            JSONObject found=store.queryEvents(nonce,0,10).getJSONObject(0);
            assertEquals(nonce,found.getString("message"));
            JSONObject history=new JSONObject().put("query","absent-synthetic-query").put("limit",5);
            assertEquals(0,DevHistory.query(store,history).getJSONArray("messages").length());
            try {DevHistory.query(store,new JSONObject().put("limit",101));fail("limit should reject");}
            catch(IllegalArgumentException expected) {}
            try {DevHistory.events(store,new JSONObject().put("offset",-1));fail("offset should reject");}
            catch(IllegalArgumentException expected) {}
        }
    }
}
