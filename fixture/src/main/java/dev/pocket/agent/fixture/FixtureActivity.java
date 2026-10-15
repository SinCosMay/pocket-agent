package dev.pocket.agent.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
public final class FixtureActivity extends Activity {
 @Override public void onCreate(Bundle b) {
  super.onCreate(b);
  LinearLayout column=new LinearLayout(this);column.setOrientation(1);column.setPadding(24,80,24,24);
  column.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());view.setPadding(24+bars.left,24+bars.top,24+bars.right,24+bars.bottom);return insets;});
  TextView heading=new TextView(this);heading.setText("POCKET_AGENT_FIXTURE");column.addView(heading);
  EditText input=new EditText(this);input.setHint("AGENT_INPUT");input.setContentDescription("AGENT_INPUT");column.addView(input);
  TextView status=new TextView(this);status.setText("STATUS_READY");status.setContentDescription("AGENT_STATUS");
  Button click=new Button(this);click.setText("AGENT_CLICK");int[] count={0};click.setOnClickListener(v->{count[0]++;status.setText("STATUS_CLICKED:"+input.getText()+" COUNT:"+count[0]);getPreferences(0).edit().putInt("count",count[0]).apply();});column.addView(click);column.addView(status);setContentView(column);
 }
}
