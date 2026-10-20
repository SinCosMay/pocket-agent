package dev.pocket.agent;

import android.content.Context;
import android.util.Base64;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic local account checks: no sign-in, refresh request or inference. */
@RunWith(AndroidJUnit4.class)
public class CodexAuthDeviceTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static String encoded(JSONObject value) {
        return Base64.encodeToString(value.toString().getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
    }
    private static JSONObject account(String refresh,String nonce) throws Exception {
        long expiry=System.currentTimeMillis()/1000+3600;
        String jwt=encoded(new JSONObject().put("alg","RS256").put("typ","JWT"))+"."
            +encoded(new JSONObject().put("exp",expiry).put("nonce",nonce).put("https://api.openai.com/auth",new JSONObject().put("chatgpt_account_id","synthetic-device-account")))+".c3ludGhldGlj";
        return new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna").put("accessToken",jwt).put("refreshToken",refresh).put("accountId","synthetic-device-account").put("expiresAt",expiry*1000);
    }
    private void assertProtected(JSONObject account) throws Exception {
        String encrypted=context().getSharedPreferences("provider-secrets",Context.MODE_PRIVATE).getString("encrypted",null);
        assertNotNull("Protected credential envelope is missing",encrypted);
        JSONObject envelope=new JSONObject(encrypted);
        assertTrue("AES-GCM nonce is missing",envelope.has("iv"));assertTrue("Encrypted payload is missing",envelope.has("data"));
        for(String field:new String[]{"accessToken","refreshToken","accountId"})
            assertFalse("Credential plaintext leaked to preferences",encrypted.contains(account.getString(field)));
        assertEquals("Only the encrypted envelope may be retained",1,context().getSharedPreferences("provider-secrets",Context.MODE_PRIVATE).getAll().size());
    }
    /** Failure-only runner cleanup, even when the live test never reached its finally block. */
    @Test public void clearProvisionedAccount() throws Exception {
        Assume.assumeTrue("Account cleanup requires an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());credentials.signOutCodex();
        assertFalse("Account remained signed in after test cleanup",credentials.get().optString("authMode").equals("codex-account"));
    }
    @Test public void mcpSecretsStayEncryptedAcrossEveryCredentialWritePath() throws Exception {
        Assume.assumeTrue("Synthetic credential tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        String bearer="synthetic-mcp-bearer-secret",custom="synthetic-mcp-header-secret";
        try {
            JSONObject server=new JSONObject().put("name","Test MCP").put("url","https://mcp.example.test/mcp")
                    .put("enabled",false).put("authMode","headers").put("headers",new JSONArray()
                            .put(new JSONObject().put("name","X-First").put("value",custom))
                            .put(new JSONObject().put("name","X-Second").put("value","second-secret")));
            credentials.saveMcpServer(server);
            JSONObject first=credentials.mcpServers().getJSONObject(0);
            assertFalse(first.getBoolean("enabled"));assertEquals(2,first.getJSONArray("headers").length());
            credentials.save(new JSONObject().put("authMode","scripted").put("model","scripted"));
            assertEquals(custom,credentials.mcpServers().getJSONObject(0).getJSONArray("headers").getJSONObject(0).getString("value"));
            JSONObject synthetic=account("mcp-refresh-before","mcp-provider");credentials.save(synthetic);
            JSONObject rotated=account("mcp-refresh-after","mcp-provider").put("oldRefreshToken","mcp-refresh-before");
            credentials.updateCodex(rotated);
            assertEquals(2,credentials.mcpServers().getJSONObject(0).getJSONArray("headers").length());
            credentials.signOutCodex();assertEquals("Test MCP",credentials.mcpServers().getJSONObject(0).getString("name"));
            credentials.saveMcpServer(new JSONObject().put("id",first.getString("id")).put("name","Test MCP")
                    .put("url","https://mcp.example.test/mcp").put("authMode","bearer").put("bearerToken",bearer));
            String encrypted=context().getSharedPreferences("provider-secrets",Context.MODE_PRIVATE).getString("encrypted","");
            assertFalse("MCP bearer token leaked to preferences",encrypted.contains(bearer));
            assertFalse("MCP custom header leaked to preferences",encrypted.contains(custom));
        } finally { credentials.save(saved); }
    }
    @Test public void startupDiagnosticsRedactAllMcpAuthenticationFormats() throws Exception {
        JSONObject credentials=new JSONObject().put("mcpServers",new JSONArray()
                .put(new JSONObject().put("bearerToken","bearer-secret"))
                .put(new JSONObject().put("headerValue","legacy-secret"))
                .put(new JSONObject().put("headers",new JSONArray().put(new JSONObject().put("value","custom-secret")))));
        String result=RuntimeHost.redactMcpStartupSecrets("bearer-secret legacy-secret custom-secret",credentials);
        assertEquals("[redacted] [redacted] [redacted]",result);
    }
    @Test public void importEncryptAndSignOut() throws Exception {
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        try {
            JSONObject synthetic=account("synthetic-import-refresh","import");
            JSONObject source=new JSONObject().put("tokens",new JSONObject().put("access_token",synthetic.getString("accessToken")).put("refresh_token",synthetic.getString("refreshToken")).put("account_id",synthetic.getString("accountId")));
            JSONObject imported=CodexAccountAuth.readImport(new ByteArrayInputStream(source.toString().getBytes(StandardCharsets.UTF_8)));
            assertEquals("JWT expiry must be inferred in milliseconds",synthetic.getLong("expiresAt"),imported.getLong("expiresAt"));
            imported.put("authMode","codex-account").put("model","gpt-6-luna");credentials.save(imported);
            JSONObject restored=new CredentialStore(context()).get();
            assertTrue("Imported access token did not round trip",synthetic.getString("accessToken").equals(restored.optString("accessToken")));
            assertTrue("Imported refresh token did not round trip",synthetic.getString("refreshToken").equals(restored.optString("refreshToken")));
            assertFalse("Account mode retained an endpoint override",restored.has("endpoint"));assertProtected(synthetic);
            credentials.signOutCodex();JSONObject out=credentials.get();assertEquals("scripted",out.optString("authMode"));
            for(String field:new String[]{"accessToken","refreshToken","accountId","expiresAt","idToken"})assertFalse("Sign-out retained an account field",out.has(field));
            System.out.println("CODEX_AUTH_RESULT="+new JSONObject().put("case","importEncryptAndSignOut").put("synthetic",true).put("imported",true).put("encryptedAtRest",true).put("signedOut",true));
        } finally { credentials.save(saved); }
    }
    @Test public void verifiedReasoningLevelsSurviveLowAndAllowLaterReuse() throws Exception {
        assertTrue("Persisted model proof must allow any saved verified level",
                MainActivity.hasPersistedReasoningProof("gpt-6-luna","medium","gpt-6-luna",
                        new String[]{"low","medium","high"}));
        assertFalse("Persisted proof must remain model-bound",
                MainActivity.hasPersistedReasoningProof("other-model","medium","gpt-6-luna",
                        new String[]{"low","medium","high"}));
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        try {
            JSONArray levels=new JSONArray().put("low").put("medium").put("high").put("ultra");
            JSONObject synthetic=account("synthetic-level-refresh","levels");
            credentials.save(synthetic);
            JSONObject connected=credentials.get();
            assertFalse("New account credentials must not create reasoning proof",connected.has("reasoningEffortModel"));
            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("reasoningEffort","high").put("preferredReasoningEffort","high")
                    .put("reasoningEffortModel","gpt-6-luna").put("reasoningEfforts",levels));
            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("reasoningEffort","low").put("preferredReasoningEffort","low")
                    .put("reasoningEffortModel","gpt-6-luna").put("reasoningEfforts",levels));
            JSONObject reopened=new CredentialStore(context()).get();
            assertEquals("Explicit verified low should remain active","low",reopened.optString("reasoningEffort"));
            assertEquals("Verified model proof was lost at low","gpt-6-luna",reopened.optString("reasoningEffortModel"));
            assertTrue("Verified high level was lost at low",
                    reopened.optJSONArray("reasoningEfforts").toString().contains("high"));
            assertFalse("Unsupported reasoning level was not sanitized",
                    reopened.optJSONArray("reasoningEfforts").toString().contains("ultra"));

            JSONObject rotated=account("synthetic-level-refresh-rotated","levels-rotated")
                    .put("oldRefreshToken","synthetic-level-refresh");
            JSONObject refreshed=credentials.updateCodex(rotated);
            assertEquals("Token refresh must retain model proof at low","gpt-6-luna",refreshed.optString("reasoningEffortModel"));
            assertTrue("Token refresh must retain verified levels at low",
                    refreshed.optJSONArray("reasoningEfforts").toString().contains("medium"));

            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("reasoningEffort","high").put("preferredReasoningEffort","high"));
            JSONObject high=credentials.get();
            assertEquals("Retained proof should allow high without rediscovery","high",high.optString("reasoningEffort"));

            credentials.save(new JSONObject().put("authMode","codex-account").put("model","gpt-6-luna")
                    .put("reasoningEffort","medium").put("preferredReasoningEffort","medium"));
            JSONObject medium=credentials.get();
            assertEquals("Retained proof should allow medium without rediscovery","medium",medium.optString("reasoningEffort"));
        } finally { credentials.save(saved); }
    }
    @Test public void rotationCompareAndSaveRejectsStaleAndSignedOut() throws Exception {
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore first=new CredentialStore(context()),second=new CredentialStore(context());JSONObject saved=first.get();
        try {
            JSONObject initial=account("synthetic-refresh-initial","initial");first.save(initial);
            JSONObject rotated=account("synthetic-refresh-rotated","rotated").put("oldRefreshToken",initial.getString("refreshToken"));
            JSONObject result=second.updateCodex(rotated);
            assertTrue("Rotation did not return the saved token",result.optString("refreshToken").equals(rotated.getString("refreshToken")));assertProtected(rotated);
            boolean staleRejected=false;
            try { first.updateCodex(account("synthetic-stale-overwrite","stale").put("oldRefreshToken",initial.getString("refreshToken"))); }
            catch(IllegalStateException expected) { staleRejected=true; }
            assertTrue("Stale rotation overwrote newer account credentials",staleRejected);
            assertTrue("Stale rotation changed the retained token",first.get().optString("refreshToken").equals(rotated.getString("refreshToken")));
            first.signOutCodex();boolean signedOutRejected=false;
            try { second.updateCodex(account("synthetic-resurrection","resurrect").put("oldRefreshToken",rotated.getString("refreshToken"))); }
            catch(IllegalStateException expected) { signedOutRejected=true; }
            assertTrue("Late refresh resurrected a signed-out account",signedOutRejected);assertEquals("scripted",first.get().optString("authMode"));
            System.out.println("CODEX_AUTH_RESULT="+new JSONObject().put("case","rotationCompareAndSaveRejectsStaleAndSignedOut").put("synthetic",true).put("rotationSaved",true).put("staleRejected",true).put("signedOutRefreshRejected",true));
        } finally { first.save(saved); }
    }
    @Test public void reasoningPreferenceDistinguishesVerifiedLowFromForcedLow() throws Exception {
        String[] verified=new String[]{"low","medium","high"};
        assertEquals("Verified Codex low must become the global preference","low",
                MainActivity.reasoningPreferenceAfterSelection("codex-account","gpt-6-luna","low","high",
                        verified,"",new String[0]));
        assertEquals("Persisted verified Codex low must become the global preference","low",
                MainActivity.reasoningPreferenceAfterSelection("codex-account","gpt-6-luna","low","high",
                        null,"gpt-6-luna",verified));
        assertEquals("Unverified forced low must preserve the prior preference","high",
                MainActivity.reasoningPreferenceAfterSelection("codex-account","gpt-6-luna","low","high",
                        null,"",new String[0]));
        assertEquals("Compatible provider selection must become the global preference","high",
                MainActivity.reasoningPreferenceAfterSelection("openai","gpt-5","high","low",
                        null,"",new String[0]));
        Assume.assumeTrue("Synthetic account tests require an emulator",CredentialStore.isEmulator());
        CredentialStore credentials=new CredentialStore(context());JSONObject saved=credentials.get();
        try {
            JSONObject openai=new JSONObject().put("authMode","openai").put("endpoint","https://api.openai.com/v1")
                    .put("model","gpt-5").put("apiKey","synthetic-provider-key")
                    .put("reasoningEffort","high").put("preferredReasoningEffort","high");
            credentials.save(openai);
            JSONObject before=credentials.get();
            assertEquals("high",before.optString("reasoningEffort"));
            assertEquals("high",before.optString("preferredReasoningEffort"));

            JSONObject codex=account("synthetic-preference-refresh","preference")
                    .put("preferredReasoningEffort",before.optString("preferredReasoningEffort"));
            credentials.save(codex);
            JSONObject clamped=new CredentialStore(context()).get();
            assertEquals("Codex without model proof must stay at low",CredentialStore.DEFAULT_REASONING_EFFORT,clamped.optString("reasoningEffort"));
            assertEquals("Forced Codex low must not erase the global preference","high",clamped.optString("preferredReasoningEffort"));
            assertFalse("Unverified Codex model must not gain reasoning proof",clamped.has("reasoningEffortModel"));
        } finally { credentials.save(saved); }
    }

}
