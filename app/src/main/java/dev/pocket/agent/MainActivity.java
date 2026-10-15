package dev.pocket.agent;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.view.WindowManager;
import android.widget.AdapterView;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** Small native text surface; service/session ownership never follows this Activity. */
public final class MainActivity extends Activity implements AgentService.Observer {
    private TextView status, transcript, workerTranscript;
    private ScrollView messages;
    private EditText input;
    private LinearLayout taskList;
    private Button send;
    private JSONObject state = new JSONObject();
    private String viewedWorker;
    private StateStore store;
    private static final int IMPORT_CODEX = 90;
    private final ExecutorService accountFiles = Executors.newSingleThreadExecutor();
    private CodexAccountAuth accountFlow;
    private AlertDialog accountDialog;
    private long accountGeneration;
    private long pendingPickerGeneration = -1;
    private int pendingPickerRequest = -1;
    private Future<?> pendingImport;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new StateStore(this);
        LinearLayout root = column(); root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()|android.view.WindowInsets.Type.ime());
            android.view.WindowInsetsController controller=view.getWindowInsetsController();
            if(controller!=null)controller.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            view.setPadding(dp(14)+bars.left,dp(12)+bars.top,dp(14)+bars.right,dp(12)+bars.bottom);return insets;
        });
        LinearLayout toolbar = row(); TextView title = text("Pocket Agent"); title.setTextSize(22); title.setTypeface(null, Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button("Settings", this::settings)); root.addView(toolbar);
        status = text("Stopped · Send starts the runtime"); root.addView(status);
        messages = new ScrollView(this); transcript = text(""); transcript.setTextIsSelectable(true); transcript.setPadding(0, dp(12), 0, dp(12)); messages.addView(transcript);
        root.addView(messages, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView tasksTitle = text("Tasks"); tasksTitle.setTypeface(null, Typeface.BOLD); root.addView(tasksTitle);
        ScrollView taskScroll = new ScrollView(this); taskList = column(); taskScroll.addView(taskList); root.addView(taskScroll, new LinearLayout.LayoutParams(-1, dp(112)));
        input = new EditText(this); input.setHint("Message main agent"); input.setMinLines(1); input.setMaxLines(4); input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); root.addView(input);
        LinearLayout controls = row(); send = button("Send", () -> {
            String value = input.getText().toString().trim(); if (value.isEmpty()) return;
            command(json("action", "send", "text", value)); input.setText("");
        }); controls.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(button("Stop response", () -> command(json("action", "stop_main"))));
        controls.addView(button("Stop all", () -> {
            if (AgentService.isRunning()) startService(new Intent(this, AgentService.class).setAction(AgentService.ACTION_STOP));
        })); root.addView(controls); setContentView(root);
    }
    @Override protected void onStart() { super.onStart(); AgentService.observe(this); state = store.load(); render(); }
    @Override protected void onStop() { AgentService.unobserve(this); super.onStop(); }
    @Override protected void onDestroy() {
        invalidateAccountOperations();
        accountFiles.shutdownNow(); store.close(); super.onDestroy();
    }
    @Override public void onAgentEvent(JSONObject event) {
        String type = event.optString("type");
        if (type.equals("state")) { state = event.optJSONObject("state"); if (state == null) state = new JSONObject(); render(); }
        else if (type.equals("error")) { status.setText("Error · " + event.optString("error", "Runtime unavailable")); }
        else if (type.equals("ready") || type.equals("stopped")) render();
    }
    private void render() {
        status.setText(!AgentService.isRunning() ? "Stopped · Send starts the runtime" : state.optBoolean("busy") ? "Main is responding" : state.optBoolean("active") ? "Workers active · Main is available" : "Ready");
        if(AgentService.isRunning() && !state.isNull("lastError") && !state.optString("lastError").isEmpty()) status.setText("Error · "+state.optString("lastError"));
        String rendered=formatMessages(state.optJSONArray("messages"));
        if(!rendered.contentEquals(transcript.getText())) {
            boolean follow=messages.getScrollY()+messages.getHeight()>=transcript.getHeight()-dp(48);
            transcript.setText(rendered);
            if(follow)messages.post(()->messages.fullScroll(View.FOCUS_DOWN));
        }
        taskList.removeAllViews(); JSONArray tasks = state.optJSONArray("tasks");
        if (tasks == null || tasks.length() == 0) taskList.addView(text("No tasks yet"));
        else for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
            String id = task.optString("conversationId", task.optString("id"));
            taskList.addView(button(task.optString("label", id) + " · " + task.optString("status") + " · #" + task.optInt("generation"), () -> worker(id)));
        }
        if (workerTranscript != null && viewedWorker != null) workerTranscript.setText(workerMessages(viewedWorker));
    }
    private String formatMessages(JSONArray messages) {
        if (messages == null || messages.length() == 0) return "Start a conversation. Multi-step phone work runs in tasks while you keep talking here.";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) { JSONObject message = messages.optJSONObject(i); if (message == null) continue; text.append(message.optString("role")).append(":\n").append(message.optString("text", message.optString("content"))).append("\n\n"); }
        return text.toString();
    }
    private String workerMessages(String id) {
        JSONObject all = state.optJSONObject("workerMessages"); return formatMessages(all == null ? null : all.optJSONArray(id));
    }
    private void worker(String id) {
        viewedWorker = id; LinearLayout panel = column(); panel.setPadding(dp(16), dp(8), dp(16), 0);
        ScrollView scroll = new ScrollView(this); workerTranscript = text(workerMessages(id)); workerTranscript.setTextIsSelectable(true); scroll.addView(workerTranscript); panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(280)));
        EditText message = new EditText(this); message.setHint("Follow up or steer this worker"); panel.addView(message);
        LinearLayout actions = row();
        actions.addView(button("Message", () -> { if (!message.getText().toString().trim().isEmpty()) { command(json("action", "send", "conversationId", id, "text", message.getText().toString())); message.setText(""); } }));
        actions.addView(button("Steer", () -> { if (!message.getText().toString().trim().isEmpty()) { command(json("action", "steer_worker", "conversationId", id, "text", message.getText().toString())); message.setText(""); } }));
        actions.addView(button("Cancel", () -> command(json("action", "cancel_worker", "conversationId", id)))); panel.addView(actions);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Task " + id).setView(panel).setPositiveButton("Close", null).create();
        dialog.setOnDismissListener(ignored -> { viewedWorker = null; workerTranscript = null; }); dialog.show();
        command(json("action", "inspect_worker", "conversationId", id));
    }
    private void command(JSONObject command) {
        Intent service = new Intent(this, AgentService.class).setAction(AgentService.ACTION_COMMAND).putExtra("command", command.toString());
        startForegroundService(service);
    }
    private boolean activeWork() { return state.optBoolean("busy") || state.optBoolean("active"); }
    private void settings() {
        CredentialStore credentials = new CredentialStore(this); final JSONObject saved;
        try { saved = credentials.get(); } catch (Exception e) { toast("Unable to read protected credentials"); return; }
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(4), dp(20), dp(8));
        panel.addView(text("Provider")); Spinner mode = new Spinner(this);
        String[] names = {"Scripted model (local validation)", "API key (HTTPS)", "Codex emulator relay (development)", "Codex account (ChatGPT)"};
        String[] modes = {"scripted", "openai", "codex", CredentialStore.CODEX_ACCOUNT};
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        String auth = saved.optString("authMode");
        int selected = auth.equals("scripted") ? 0 : auth.equals("openai") || auth.equals("api-key") ? 1 : auth.equals(CredentialStore.CODEX_ACCOUNT) ? 3 : 2;
        mode.setSelection(selected); panel.addView(mode);
        LinearLayout endpointPanel = column(); panel.addView(endpointPanel);
        EditText endpoint = field(endpointPanel, "API endpoint", saved.optString("endpoint", "https://api.openai.com/v1")); endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText model = field(panel, "Model", saved.optString("model", selected == 3 ? CredentialStore.CODEX_MODEL : "gpt-4.1-mini"));
        LinearLayout secretPanel = column(); panel.addView(secretPanel);
        EditText secret = field(secretPanel, "API key / relay token (blank keeps saved value)", ""); secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); secret.setSaveEnabled(false);
        LinearLayout accountPanel = column(); panel.addView(accountPanel);
        String accountStatus = "No account connected";
        if (auth.equals(CredentialStore.CODEX_ACCOUNT) && !saved.optString("accountId").isEmpty()) {
            accountStatus = saved.optLong("expiresAt") <= System.currentTimeMillis() ? "Account connected · access token needs refresh" : "Account connected · access token valid";
        }
        accountPanel.addView(text(accountStatus));
        accountPanel.addView(text("Sign in using a device code, or select your Codex auth.json. Account tokens stay encrypted on this phone. Imported refresh tokens may share rotation with the original Codex installation."));
        final AlertDialog[] settingsDialog = new AlertDialog[1];
        accountPanel.addView(button("Sign in", () -> {
            if (activeWork()) { toast("Stop active work before changing accounts"); return; }
            settingsDialog[0].dismiss(); signInAccount();
        }));
        accountPanel.addView(button("Import Codex auth.json", () -> {
            if (activeWork()) { toast("Stop active work before changing accounts"); return; }
            settingsDialog[0].dismiss();
            long generation = invalidateAccountOperations();
            pendingPickerGeneration = generation;
            pendingPickerRequest = IMPORT_CODEX + (int) (generation % 60000);
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
            try { startActivityForResult(pick, pendingPickerRequest); } catch (Exception unavailable) { invalidateAccountOperations(); toast("No document picker is available"); }
        }));
        accountPanel.addView(button("Sign out", () -> {
            if (activeWork()) { toast("Stop active work before signing out"); return; }
            try { credentials.signOutCodex(); invalidateAccountOperations(); if (AgentService.isRunning()) command(json("action", "configure")); settingsDialog[0].dismiss(); toast("Account signed out on this phone"); }
            catch (Exception error) { toast("Unable to clear the protected account"); }
        }));
        TextView connectionHelp = text("Codex relay uses http://10.0.2.2:port/v1 on an emulator. Account mode connects directly and needs no API key or endpoint."); panel.addView(connectionHelp);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean account = position == 3;
                endpointPanel.setVisibility(account ? View.GONE : View.VISIBLE); secretPanel.setVisibility(account ? View.GONE : View.VISIBLE);
                accountPanel.setVisibility(account ? View.VISIBLE : View.GONE);
                if (account && !auth.equals(CredentialStore.CODEX_ACCOUNT)) model.setText(CredentialStore.CODEX_MODEL);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        panel.addView(button("Accessibility settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        panel.addView(button("Notification access settings", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        panel.addView(button("Choose phone permissions", this::permissions));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Connection & permissions").setView(scroll).setNegativeButton("Close", null).setPositiveButton("Save", null).create(); settingsDialog[0] = dialog;
        dialog.setOnShowListener(ignored -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    if (activeWork()) { toast("Stop active work before changing connections"); return; }
                    String selectedMode = modes[mode.getSelectedItemPosition()];
                    JSONObject config = json("authMode", selectedMode, "model", model.getText().toString().trim());
                    if (!selectedMode.equals(CredentialStore.CODEX_ACCOUNT)) {
                        config.put("endpoint", endpoint.getText().toString().trim());
                        // Never substitute an account token into the API-key field.
                        config.put("apiKey", secret.getText().length() == 0 ? saved.optString("apiKey") : secret.getText().toString().trim());
                    }
                    credentials.save(config); invalidateAccountOperations(); if (AgentService.isRunning()) command(json("action", "configure")); dialog.dismiss(); toast("Connection saved");
                } catch (Exception error) { toast(error instanceof IllegalArgumentException ? error.getMessage() : "Could not save connection"); }
            });
        }); dialog.show();
    }
    private void saveAccount(JSONObject account) throws Exception {
        if (activeWork()) throw new IllegalStateException("Stop active work before changing accounts");
        JSONObject config = new JSONObject(account.toString()).put("authMode", CredentialStore.CODEX_ACCOUNT).put("model", CredentialStore.CODEX_MODEL);
        new CredentialStore(this).save(config);
        if (AgentService.isRunning()) command(json("action", "configure"));
    }
    /** A newer account choice/sign-out always wins over an older file read or login callback. */
    private long invalidateAccountOperations() {
        long generation = ++accountGeneration;
        pendingPickerGeneration = -1; pendingPickerRequest = -1;
        Future<?> importing = pendingImport; pendingImport = null; if (importing != null) importing.cancel(true);
        CodexAccountAuth flow = accountFlow; accountFlow = null; if (flow != null) flow.close();
        AlertDialog dialog = accountDialog; accountDialog = null; if (dialog != null) dialog.dismiss();
        return generation;
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != pendingPickerRequest) return;
        long generation = pendingPickerGeneration; pendingPickerGeneration = -1; pendingPickerRequest = -1;
        if (generation != accountGeneration || result != RESULT_OK || data == null || data.getData() == null || isDestroyed()) return;
        Uri document = data.getData();
        if (!"content".equals(document.getScheme())) { toast("Select an account file from the document picker"); return; }
        toast("Reading selected account file");
        pendingImport = accountFiles.submit(() -> {
            JSONObject account;
            try (InputStream input = getContentResolver().openInputStream(document)) { account = CodexAccountAuth.readImport(input); }
            catch (Exception invalid) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && generation == accountGeneration) {
                        pendingImport = null; toast("Unable to import account. Select a valid Codex auth.json with account and refresh tokens.");
                    }
                }); return;
            }
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || generation != accountGeneration) return;
                pendingImport = null;
                try { saveAccount(account); invalidateAccountOperations(); toast(account.optLong("expiresAt") <= System.currentTimeMillis() ? "Account imported · refresh required on next request" : "Account imported"); settings(); }
                catch (Exception error) { toast(activeWork() ? "Stop active work, then import the account again" : "Unable to save the protected account"); }
            });
        });
    }
    private void signInAccount() {
        final long generation = invalidateAccountOperations();
        CodexAccountAuth flow = new CodexAccountAuth(); accountFlow = flow;
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView instructions = text("Requesting a device code…"); panel.addView(instructions);
        TextView code = text(""); code.setTextSize(26); code.setTypeface(null, Typeface.BOLD); code.setTextIsSelectable(true); panel.addView(code);
        Button browser = button("Open OpenAI sign-in", () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CodexAccountAuth.VERIFICATION_URI))); }
            catch (Exception unavailable) { toast("Open auth.openai.com/codex/device in a browser and enter the displayed code"); }
        }); browser.setEnabled(false); panel.addView(browser);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Sign in to Codex").setView(panel).setNegativeButton("Cancel", null).create(); accountDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (generation == accountGeneration) ++accountGeneration;
            flow.close(); if (accountFlow == flow) accountFlow = null; if (accountDialog == dialog) accountDialog = null;
        });
        dialog.show(); dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        flow.signIn(new CodexAccountAuth.Listener() {
            @Override public void onDeviceCode(String userCode) {
                if (isDestroyed() || generation != accountGeneration || !dialog.isShowing()) return;
                instructions.setText("Open the sign-in page and enter this code. Do not share it. Waiting for authorization (up to 15 minutes)…"); code.setText(userCode); browser.setEnabled(true);
            }
            @Override public void onSuccess(JSONObject account) {
                if (isDestroyed() || generation != accountGeneration || !dialog.isShowing()) return;
                try { saveAccount(account); dialog.dismiss(); toast("Codex account connected"); settings(); }
                catch (Exception error) { instructions.setText(activeWork() ? "Stop active work, then start sign-in again. The previous connection was preserved." : "Unable to save the protected account. The previous connection was preserved."); code.setText(""); browser.setEnabled(false); flow.close(); }
            }
            @Override public void onFailure(String safeMessage) {
                if (isDestroyed() || generation != accountGeneration || !dialog.isShowing()) return;
                instructions.setText(safeMessage + " The previous connection was preserved."); code.setText(""); browser.setEnabled(false); flow.close();
            }
        });
    }
    private void permissions() {
        String[] labels = {"Contacts", "Phone calls and phone status", "Send SMS", "Read and edit calendar", "Runtime notifications"};
        boolean[] selected = new boolean[labels.length];
        new AlertDialog.Builder(this).setTitle("Allow tools you want to use").setMultiChoiceItems(labels, selected, (dialog, index, checked) -> selected[index] = checked)
                .setNegativeButton("Close", null).setPositiveButton("Request selected", (dialog, which) -> {
                    java.util.ArrayList<String> needed = new java.util.ArrayList<>();
                    String[][] groups = {{Manifest.permission.READ_CONTACTS}, {Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE}, {Manifest.permission.SEND_SMS}, {Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, Build.VERSION.SDK_INT >= 33 ? new String[]{Manifest.permission.POST_NOTIFICATIONS} : new String[0]};
                    for (int index = 0; index < groups.length; index++) if (selected[index]) for (String permission : groups[index]) if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) needed.add(permission);
                    if (!needed.isEmpty()) requestPermissions(needed.toArray(new String[0]), 1); else toast("No additional permissions requested");
                }).show();
    }
    private EditText field(LinearLayout parent, String label, String value) { parent.addView(text(label)); EditText field = new EditText(this); field.setText(value); field.setSingleLine(); parent.addView(field); return field; }
    private static JSONObject json(String... values) { JSONObject object = new JSONObject(); try { for (int i = 0; i < values.length; i += 2) object.put(values[i], values[i + 1]); } catch (Exception e) { throw new IllegalArgumentException(e); } return object; }
    private TextView text(String value) { TextView view = new TextView(this); view.setText(value); view.setTextSize(15); return view; }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private Button button(String label, Runnable action) { Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setOnClickListener(ignored -> action.run()); return button; }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
}
