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
 private static final class RejectingAccessibilityEditText extends EditText {
  RejectingAccessibilityEditText(android.content.Context context){super(context);}
  @Override public boolean performAccessibilityAction(int action,Bundle args) {
   if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT&&args!=null) {
    CharSequence value=args.getCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE);
    setText(value==null?"":value);
    return false;
   }
   return super.performAccessibilityAction(action,args);
  }
 }
 @Override public void onCreate(Bundle b) {
  super.onCreate(b);
  String chooser=getIntent().getStringExtra("chooser_case");
  if(chooser!=null) {showChooser(chooser);return;}
  getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  LinearLayout column=new LinearLayout(this);column.setOrientation(1);column.setPadding(24,80,24,24);
  column.setFocusableInTouchMode(true);
  column.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());view.setPadding(24+bars.left,24+bars.top,24+bars.right,24+bars.bottom);return insets;});
  TextView heading=new TextView(this);heading.setText("POCKET_AGENT_FIXTURE");column.addView(heading);
  EditText input=new EditText(this);input.setHint("AGENT_INPUT");input.setContentDescription("AGENT_INPUT");column.addView(input);
  EditText delayedInput=new DelayedAccessibilityEditText(this);delayedInput.setHint("AGENT_DELAYED_INPUT");delayedInput.setContentDescription("AGENT_DELAYED_INPUT");column.addView(delayedInput);
  EditText rejectingInput=new RejectingAccessibilityEditText(this);rejectingInput.setHint("AGENT_REJECTING_INPUT");rejectingInput.setContentDescription("AGENT_REJECTING_INPUT");column.addView(rejectingInput);
  TextView status=new TextView(this);status.setText("STATUS_READY");status.setContentDescription("AGENT_STATUS");
  Button click=new Button(this);click.setText("AGENT_CLICK");int[] count={0};click.setOnClickListener(v->{count[0]++;status.setText("STATUS_CLICKED:"+input.getText()+" COUNT:"+count[0]);getPreferences(0).edit().putInt("count",count[0]).apply();});column.addView(click);column.addView(status);setContentView(column);column.requestFocus();
 }
 private void showChooser(String mode) {
  FrameLayout outer=new FrameLayout(this);
  TextView status=new TextView(this);status.setText("CHOOSER_READY");status.setContentDescription("CHOOSER_STATUS");
  outer.setOnClickListener(v->status.setText("OUTER_CLICKED"));
  outer.setOnLongClickListener(v->{status.setText("OUTER_LONG_CLICKED");return true;});
  if("backdrop_sibling".equals(mode)) {
   FrameLayout backdrop=new FrameLayout(this);backdrop.setContentDescription("CHOOSER_BACKDROP");
   backdrop.setOnClickListener(v->status.setText("BACKDROP_CLICKED"));
   backdrop.setOnLongClickListener(v->{status.setText("BACKDROP_LONG_CLICKED");return true;});
   outer.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
  }
  FrameLayout panel=new FrameLayout(this);panel.setContentDescription("CHOOSER_PANEL");
  panel.setOnClickListener(v->status.setText("PANEL_CLICKED"));
  panel.setOnLongClickListener(v->{status.setText("PANEL_LONG_CLICKED");return true;});
  FrameLayout.LayoutParams panelParams=new FrameLayout.LayoutParams(600,700);panelParams.leftMargin=30;panelParams.topMargin=100;
  outer.addView(panel,panelParams);
  LinearLayout row=new LinearLayout(this);row.setOrientation(1);row.setContentDescription("CHOOSER_ROW");
  row.setOnClickListener(v->status.setText("CHOOSER_SELECTED"));
  row.setOnLongClickListener(v->{status.setText("CHOOSER_LONG_SELECTED");return true;});
  TextView label=new TextView(this);label.setText("Chrome");label.setTextSize(22);row.addView(label);
  if(mode.startsWith("duplicate")) {TextView duplicate=new TextView(this);duplicate.setText("Chrome");
   if("duplicate_disabled".equals(mode))duplicate.setEnabled(false);row.addView(duplicate);}
  FrameLayout.LayoutParams rowParams=new FrameLayout.LayoutParams("overflow_descendant".equals(mode)?580:400,200);rowParams.leftMargin=60;rowParams.topMargin=100;panel.addView(row,rowParams);
  if("disabled".equals(mode) || "disabled_only".equals(mode))row.setEnabled(false);
  if(mode.endsWith("_only")) {outer.setClickable(false);outer.setLongClickable(false);panel.setClickable(false);panel.setLongClickable(false);}
  if("long_parent".equals(mode))row.setLongClickable(false);
  if("disabled_label".equals(mode))label.setEnabled(false);
  if("invisible".equals(mode) || "invisible_only".equals(mode))row.setVisibility(android.view.View.INVISIBLE);
  if("overlap".equals(mode) || "contained_sibling".equals(mode) || "ineligible_siblings".equals(mode)) {
   FrameLayout competing=new FrameLayout(this);competing.setContentDescription("COMPETING_ROW");
   competing.setOnClickListener(v->status.setText("COMPETING_CLICKED"));
   competing.setOnLongClickListener(v->{status.setText("COMPETING_LONG_CLICKED");return true;});
   FrameLayout.LayoutParams params=new FrameLayout.LayoutParams("contained_sibling".equals(mode)?200:400,200);params.leftMargin=60;params.topMargin=100;
   panel.addView(competing,params);
   if("ineligible_siblings".equals(mode)) {
    competing.setEnabled(false);
    FrameLayout hidden=new FrameLayout(this);hidden.setClickable(true);hidden.setLongClickable(true);
    hidden.setVisibility(android.view.View.INVISIBLE);panel.addView(hidden,new FrameLayout.LayoutParams(params));
   }
  }
  FrameLayout.LayoutParams statusParams=new FrameLayout.LayoutParams(-1,100);statusParams.topMargin=900;outer.addView(status,statusParams);
  setContentView(outer);
 }
}
