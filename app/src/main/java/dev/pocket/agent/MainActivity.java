package dev.pocket.agent;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
    @Override protected void onDestroy() { store.close(); super.onDestroy(); }
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
    private void settings() {
        CredentialStore credentials = new CredentialStore(this); final JSONObject saved;
        try { saved = credentials.get(); } catch (Exception e) { toast("Unable to read protected credentials"); return; }
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(4), dp(20), dp(8));
        panel.addView(text("Provider")); Spinner mode = new Spinner(this);
        String[] names = {"Scripted model (local validation)", "API key (HTTPS)", "Codex emulator relay (development)"};
        String[] modes = {"scripted", "openai", "codex"};
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        String auth = saved.optString("authMode"); mode.setSelection(auth.equals("scripted") ? 0 : auth.equals("openai") || auth.equals("api-key") ? 1 : 2); panel.addView(mode);
        EditText endpoint = field(panel, "API endpoint", saved.optString("endpoint", "https://api.openai.com/v1")); endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText model = field(panel, "Model", saved.optString("model", "gpt-4.1-mini"));
        EditText secret = field(panel, "API key / relay token (blank keeps saved value)", ""); secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        panel.addView(text("Codex relay uses http://10.0.2.2:port/v1 on an emulator. Account login on a standalone phone remains unavailable. Credentials stay in Android Keystore encrypted storage."));
        panel.addView(button("Accessibility settings", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        panel.addView(button("Notification access settings", () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        panel.addView(button("Choose phone permissions", this::permissions));
        ScrollView scroll = new ScrollView(this); scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Connection & permissions").setView(scroll).setNegativeButton("Close", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                if(state.optBoolean("busy") || state.optBoolean("active")) {toast("Stop active work before changing connections");return;}
                JSONObject config = json("authMode", modes[mode.getSelectedItemPosition()], "endpoint", endpoint.getText().toString().trim(), "model", model.getText().toString().trim(), "apiKey", secret.getText().length() == 0 ? saved.optString("apiKey") : secret.getText().toString().trim());
                credentials.save(config); if (AgentService.isRunning()) command(json("action", "configure")); dialog.dismiss(); toast("Connection saved");
            } catch (Exception error) { toast(error instanceof IllegalArgumentException ? error.getMessage() : "Could not save connection"); }
        })); dialog.show();
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
