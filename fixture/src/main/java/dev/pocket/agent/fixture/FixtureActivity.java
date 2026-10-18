package dev.pocket.agent.fixture;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
public final class FixtureActivity extends Activity {
 private static final class DelayedAccessibilityEditText extends EditText {
  DelayedAccessibilityEditText(android.content.Context context){super(context);}
  @Override public boolean performAccessibilityAction(int action,Bundle args) {
   if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT&&args!=null) {
    CharSequence value=args.getCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
    postDelayed(()->setText(value==null?"":value),350);
    return true;
   }
   return super.performAccessibilityAction(action,args);
  }
 }
 private static final class ThrowingAccessibilityEditText extends EditText {
  ThrowingAccessibilityEditText(android.content.Context context){super(context);}
  @Override public boolean performAccessibilityAction(int action,Bundle args) {
   if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT&&args!=null) {
    CharSequence value=args.getCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
    setText(value==null?"":value);
    throw new IllegalStateException("Synthetic post-dispatch provider failure");
   }
   return super.performAccessibilityAction(action,args);
  }
 }
 @Override public void onCreate(Bundle b) {
  super.onCreate(b);
  getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  LinearLayout column=new LinearLayout(this);column.setOrientation(1);column.setPadding(24,80,24,24);
  column.setFocusableInTouchMode(true);
  column.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());view.setPadding(24+bars.left,24+bars.top,24+bars.right,24+bars.bottom);return insets;});
  TextView heading=new TextView(this);heading.setText("POCKET_AGENT_FIXTURE");column.addView(heading);
  EditText input=new EditText(this);input.setHint("AGENT_INPUT");input.setContentDescription("AGENT_INPUT");column.addView(input);
  EditText delayedInput=new DelayedAccessibilityEditText(this);delayedInput.setHint("AGENT_DELAYED_INPUT");delayedInput.setContentDescription("AGENT_DELAYED_INPUT");column.addView(delayedInput);
  EditText throwingInput=new ThrowingAccessibilityEditText(this);throwingInput.setHint("AGENT_THROWING_INPUT");throwingInput.setContentDescription("AGENT_THROWING_INPUT");column.addView(throwingInput);
  TextView status=new TextView(this);status.setText("STATUS_READY");status.setContentDescription("AGENT_STATUS");
  Button click=new Button(this);click.setText("AGENT_CLICK");int[] count={0};click.setOnClickListener(v->{count[0]++;status.setText("STATUS_CLICKED:"+input.getText()+" COUNT:"+count[0]);getPreferences(0).edit().putInt("count",count[0]).apply();});column.addView(click);column.addView(status);setContentView(column);column.requestFocus();
 }
}
