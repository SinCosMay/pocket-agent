package dev.pocket.agent;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class DevToolsActivity extends Activity {
    private TextView status;
    private EditText ngrok;
    private DevSecrets secrets;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        secrets=new DevSecrets(this);
        ScrollView scroll=new ScrollView(this);
        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24,24,24,24);
        scroll.addView(panel);
        TextView title=new TextView(this);title.setText("Development MCP bridge");title.setTextSize(24);
        panel.addView(title);
        TextView warning=new TextView(this);
        warning.setText("WARNING: this server exposes every phone agent tool and stored chat/tool history. Anyone holding its bearer token can control the phone. Start only for trusted testing, and stop when finished. The production APK has no bridge.");
        warning.setPadding(0,16,0,16);panel.addView(warning);
        status=new TextView(this);panel.addView(status);
        add(panel,"Refresh status",this::refresh);
        add(panel,"Start USB / localhost server",()->start(DevBridgeService.START_LOCAL));
        ngrok=new EditText(this);ngrok.setHint("ngrok authtoken (not the MCP bearer)");
        ngrok.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ngrok.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        ngrok.setSaveEnabled(false);panel.addView(ngrok);
        add(panel,"Save ngrok token",()->{
            String token=ngrok.getText().toString().trim();
            if(token.isEmpty()){message("Enter a token first");return;}
            try {secrets.write("ngrok",token);ngrok.setText("");message("Token encrypted on device");}
            catch(Exception error){message("Unable to save token");}
        });
        add(panel,"Start wireless ngrok bridge",()->{
            try {if(secrets.ngrok().trim().isEmpty()){message("Save ngrok token first");return;}}
            catch(Exception error){message("Token unavailable");return;}
            start(DevBridgeService.START_PUBLIC);
        });
        add(panel,"Copy MCP bearer token",()->{
            try {
                String token=secrets.bearer();
                ClipData clip=ClipData.newPlainText("Development MCP bearer",token);
                android.os.PersistableBundle sensitivity=new android.os.PersistableBundle();
                sensitivity.putBoolean("android.content.extra.IS_SENSITIVE",true);
                clip.getDescription().setExtras(sensitivity);
                getSystemService(ClipboardManager.class).setPrimaryClip(clip);
                message("MCP bearer copied; do not share it or save it in logs");
            }catch(Exception error){message("Unable to copy token");}
        });
        add(panel,"Rotate MCP bearer (bridge must be stopped)",()->{
            if(!DevBridgeService.status().optString("stage").equals("stopped")){
                message("Stop the bridge before rotating the bearer");return;
            }
            try {secrets.rotateBearer();message("Bearer rotated; previous token revoked. Chat history preserved.");}
            catch(Exception error){message("Unable to rotate bearer");}
        });
        add(panel,"Stop development bridge",()->{
            startService(new Intent(this,DevBridgeService.class).setAction(DevBridgeService.STOP));
            status.postDelayed(this::refresh,300);
        });
        TextView instructions=new TextView(this);
        instructions.setText("USB setup on PC: adb -s <serial> forward tcp:8767 tcp:8767\nMCP URL: http://127.0.0.1:8767/mcp\nAuthorization: Bearer <copied MCP bearer>\nFor ngrok, use the HTTPS URL shown above with that same MCP bearer. ngrok token and MCP bearer are different. The agent must be running to list/call native tools; history remains available after agent stops.");
        instructions.setTextIsSelectable(true);instructions.setPadding(0,20,0,0);panel.addView(instructions);
        setContentView(scroll);refresh();
    }
    private void add(LinearLayout panel,String caption,Runnable task){
        Button button=new Button(this);button.setText(caption);button.setOnClickListener(v->task.run());panel.addView(button);
    }
    private void message(String message){android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show();}
    private void start(String action) {
        startForegroundService(new Intent(this,DevBridgeService.class).setAction(action));
        status.postDelayed(this::refresh,900);
    }
    private void refresh() {
        org.json.JSONObject info=DevBridgeService.status();
        status.setText("Status: "+info.optString("stage")+"\nLocal: "+info.optString("localUrl")+"\nPublic: "+info.optString("publicUrl")+"\n"+info.optString("diagnostic"));
    }
}
