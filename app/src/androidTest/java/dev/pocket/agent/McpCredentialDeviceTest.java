package dev.pocket.agent;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Keystore-backed MCP config checks; no remote request or OAuth flow is used. */
@RunWith(AndroidJUnit4.class)
public class McpCredentialDeviceTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}

    @Test public void remoteServerCrudStaysEncryptedAndSurvivesProviderUpdates() throws Exception {
        Assume.assumeTrue("Android Keystore check requires an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        String token="synthetic-mcp-token-device-test";
        String id=null;
        try {
            JSONObject added=credentials.saveMcpServer(new JSONObject().put("name","Test server")
                    .put("url","https://mcp.example.test/mcp").put("authMode","bearer").put("bearerToken",token));
            id=added.getString("id");
            JSONObject restored=credentials.mcpServers().getJSONObject(0);
            assertEquals(id,restored.getString("id"));assertEquals(token,restored.getString("bearerToken"));
            String envelope=context().getSharedPreferences("provider-secrets",Context.MODE_PRIVATE).getString("encrypted","");
            assertFalse("MCP token leaked into preferences",envelope.contains(token));
            credentials.save(new JSONObject().put("authMode","scripted").put("model","scripted")
                    .put("endpoint","https://api.openai.com/v1").put("apiKey",""));
            assertEquals("Provider settings update dropped MCP config",token,
                    credentials.mcpServers().getJSONObject(0).getString("bearerToken"));
            credentials.saveMcpServer(new JSONObject().put("id",id).put("name","Renamed server")
                    .put("url","https://mcp.example.test/v2").put("authMode","bearer").put("bearerToken",token));
            assertEquals("Renamed server",credentials.mcpServers().getJSONObject(0).getString("name"));
            credentials.deleteMcpServer(id);
            JSONArray remaining=credentials.mcpServers();
            for(int i=0;i<remaining.length();i++)assertNotEquals(id,remaining.getJSONObject(i).getString("id"));
        } finally { credentials.save(saved); }
    }

    @Test public void mcpCredentialsRejectUnsafeUrlsHeadersAndDuplicateCustomHeaders() throws Exception {
        JSONObject base=new JSONObject().put("name","Invalid test").put("url","https://mcp.example.test/mcp").put("authMode","headers");
        CredentialStore credentials=new CredentialStore(context());
        assertThrows(IllegalArgumentException.class,()->credentials.saveMcpServer(new JSONObject(base.toString()).put("url","http://mcp.example.test/mcp")));
        assertThrows(IllegalArgumentException.class,()->credentials.saveMcpServer(new JSONObject(base.toString()).put("url","https://user:pass@mcp.example.test/mcp")));
        JSONArray duplicate=new JSONArray().put(new JSONObject().put("name","X-Token").put("value","one"))
                .put(new JSONObject().put("name","x-token").put("value","two"));
        assertThrows(IllegalArgumentException.class,()->credentials.saveMcpServer(new JSONObject(base.toString()).put("headers",duplicate)));
    }
}
