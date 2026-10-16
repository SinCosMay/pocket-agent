package dev.pocket.agent;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Display;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The starting screen for agent-owned displays; never a physical-screen launcher. */
public final class VirtualWorkspaceActivity extends Activity {
    private record Launchable(String label,Intent intent) {}
    private final List<Launchable> apps=new ArrayList<>();
    private LinearLayout results;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(getDisplay()==null || getDisplay().getDisplayId()==Display.DEFAULT_DISPLAY) {
            finish();return;
        }
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20,24,20,12);root.setBackgroundColor(Color.rgb(248,249,251));
        TextView heading=new TextView(this);heading.setText("Pocket workspace");heading.setTextSize(24);heading.setTextColor(Color.rgb(23,33,52));
        root.addView(heading);
        TextView note=new TextView(this);note.setText("Choose an app to open on this private screen");note.setTextSize(14);
        root.addView(note);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("Search apps");search.setContentDescription("Search apps");
        root.addView(search);
        ScrollView scroll=new ScrollView(this);results=new LinearLayout(this);results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        for(ResolveInfo entry:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)) {
            String pkg=entry.activityInfo.packageName;
            if(getPackageName().equals(pkg))continue;
            Intent launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(pkg,entry.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            apps.add(new Launchable(String.valueOf(entry.loadLabel(getPackageManager())),launch));
        }
        apps.sort(Comparator.comparing(a->a.label().toLowerCase(Locale.ROOT)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {populate(s.toString());}
            @Override public void afterTextChanged(Editable text) {}
        });
        populate("");
    }
    private void populate(String term) {
        results.removeAllViews();int shown=0;
        String query=term.toLowerCase(Locale.ROOT).trim();
        for(Launchable app:apps) {
            if(!app.label().toLowerCase(Locale.ROOT).contains(query) && !app.intent().getComponent().getPackageName().toLowerCase(Locale.ROOT).contains(query))continue;
            Button button=new Button(this);button.setAllCaps(false);button.setText(app.label());button.setContentDescription("Open "+app.label());
            button.setOnClickListener(v->open(app));results.addView(button,new LinearLayout.LayoutParams(-1,-2));
            if(++shown==80)break;
        }
        if(shown==0) {
            TextView empty=new TextView(this);empty.setText("No apps match. Try another name.");results.addView(empty);
        }
    }
    private void open(Launchable app) {
        int id=getDisplay()==null?0:getDisplay().getDisplayId();
        if(id==Display.DEFAULT_DISPLAY)return;
        try {startActivity(app.intent(),ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle());}
        catch(RuntimeException ignored) {
            TextView message=new TextView(this);message.setText("Android prevented opening "+app.label()+" on this display.");
            results.addView(message,0);
        }
    }
}
