package dev.pocket.agent;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeUiDeviceTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);

    private WebView web(View view){if(view instanceof WebView)return (WebView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){WebView found=web(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private String eval(String expression) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web(activity.getActivity().getWindow().getDecorView()).evaluateJavascript(expression,value->{result.set(value);done.countDown();}));
        assertTrue("WebView evaluation timed out",done.await(10,TimeUnit.SECONDS));return result.get();
    }
    private void ready(String expression) throws Exception {long deadline=android.os.SystemClock.elapsedRealtime()+15000;while(android.os.SystemClock.elapsedRealtime()<deadline){if("true".equals(eval(expression)))return;android.os.SystemClock.sleep(50);}fail("UI condition did not become true: "+expression);}
    private void clickText(String text) throws Exception {eval("Array.from(document.querySelectorAll('button')).find(n=>n.textContent.trim()==="+org.json.JSONObject.quote(text)+").click()");}
    private void select(String label,String value) throws Exception {eval("(()=>{const label=Array.from(document.querySelectorAll('label')).find(n=>n.textContent==="+org.json.JSONObject.quote(label)+");const n=document.getElementById(label.htmlFor);n.value="+org.json.JSONObject.quote(value)+";n.dispatchEvent(new Event('change'));})()");}
    private void capture(String name) throws Exception {
        eval("window.__qaFrameReady=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__qaFrameReady=true))");ready("window.__qaFrameReady===true");
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();android.os.SystemClock.sleep(350);
        if(name.startsWith("drawer-"))ready("document.querySelector('.drawer-layer').classList.contains('is-open')&&Math.abs(document.querySelector('#chat-drawer').getBoundingClientRect().left)<1");
        File folder=new File(activity.getActivity().getExternalFilesDir(null),"ui-qa");assertTrue(folder.isDirectory()||folder.mkdirs());
        android.graphics.Bitmap shot=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(shot);
        try(FileOutputStream out=new FileOutputStream(new File(folder,name+".png"))){assertTrue(shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));}finally{shot.recycle();}
    }
    private void home() throws Exception {eval("if(!document.querySelector('#settings-screen').hidden)window.pocketBack();if(!document.querySelector('#settings-screen').hidden)window.pocketBack();if(!document.querySelector('#activity-screen').hidden)window.pocketBack();");}
    private void settings() throws Exception {home();eval("document.querySelector('#open-chats').click()");ready("document.querySelector('.drawer-layer').classList.contains('is-open')");eval("document.querySelector('[data-action=settings]').click()");ready("!document.querySelector('#settings-screen').hidden");}
    private boolean keyboardShown(){java.util.concurrent.atomic.AtomicBoolean visible=new java.util.concurrent.atomic.AtomicBoolean();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{android.view.WindowInsets insets=activity.getActivity().getWindow().getDecorView().getRootWindowInsets();visible.set(insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime()));});return visible.get();}
    private void waitKeyboard(boolean shown) throws Exception {long deadline=android.os.SystemClock.elapsedRealtime()+10000;while(keyboardShown()!=shown&&android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(40);assertEquals("Actual Android keyboard visibility",shown,keyboardShown());}
    private void waitComposerStill() throws Exception {eval("window.__composerStill=false;(()=>{let last=-1,stable=0;function frame(){const y=document.querySelector('#message').getBoundingClientRect().top;stable=Math.abs(y-last)<0.25?stable+1:0;last=y;if(stable>=3)window.__composerStill=true;else requestAnimationFrame(frame);}requestAnimationFrame(frame);})()");ready("window.__composerStill");}
    private void showComposerKeyboard() throws Exception {
        waitComposerStill();org.json.JSONArray point=new org.json.JSONArray(eval("(()=>{const r=document.querySelector('#message').getBoundingClientRect();return [r.left+Math.min(20,r.width/2),r.top+r.height/2,innerWidth]})()"));
        android.graphics.Rect bounds=new android.graphics.Rect();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web(activity.getActivity().getWindow().getDecorView()).getGlobalVisibleRect(bounds));float scale=(float)(bounds.width()/point.getDouble(2));
        float x=bounds.left+(float)point.getDouble(0)*scale,y=bounds.top+(float)point.getDouble(1)*scale;long now=android.os.SystemClock.uptimeMillis();
        for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}){android.view.MotionEvent event=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,x,y,0);try{InstrumentationRegistry.getInstrumentation().sendPointerSync(event);}finally{event.recycle();}}
        waitKeyboard(true);waitComposerStill();
    }
    @Test public void keyboardDismissalSizesDraftAndBrandStartsNewChat() throws Exception {
        ready("typeof window.pocketRequest==='function'");home();
        eval("document.querySelector('#message').value='';document.querySelector('#message').dispatchEvent(new Event('input'))");
        showComposerKeyboard();ready("document.querySelector('#composer').classList.contains('is-expanded')");capture("keyboard-open-empty");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitKeyboard(false);
        ready("!document.querySelector('#composer').classList.contains('is-expanded')&&document.querySelector('#composer').getBoundingClientRect().height<61");capture("keyboard-closed-empty");
        showComposerKeyboard();eval("document.querySelector('#message').value='One short line';document.querySelector('#message').dispatchEvent(new Event('input'))");
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);waitKeyboard(false);
        ready("!document.querySelector('#composer').classList.contains('is-expanded')&&document.querySelector('#message').getBoundingClientRect().height<=42");
        eval("document.querySelector('#message').value="+org.json.JSONObject.quote("Line one\nLine two\nLine three\nLine four")+";document.querySelector('#message').dispatchEvent(new Event('input'))");
        ready("document.querySelector('#message').getBoundingClientRect().height>80&&document.querySelector('#message').getBoundingClientRect().height<=160");capture("keyboard-closed-multiline-draft");
        showComposerKeyboard();
        org.json.JSONObject rect=new org.json.JSONObject(eval("JSON.stringify((()=>{const r=document.querySelector('#conversation').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/3}})())").replaceAll("^\"|\"$", "").replace("\\\"","\""));
        android.graphics.Rect bounds=new android.graphics.Rect();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web(activity.getActivity().getWindow().getDecorView()).getGlobalVisibleRect(bounds));
        float density=(float)Double.parseDouble(eval("devicePixelRatio")),x=bounds.left+(float)rect.getDouble("x")*density,y=bounds.top+(float)rect.getDouble("y")*density;
        swipe(x,y,x,y+160);waitKeyboard(false);assertEquals("true",eval("document.querySelector('#message').value.includes('Line four')"));
        eval("window.__oldThread=null;window.pocketRequest('/native/snapshot').then(s=>window.__oldThread=s.state.mainThreadId)");ready("window.__oldThread!==null");
        eval("document.querySelector('#open-chats').click();document.querySelector('.drawer-brand').click()");
        eval("window.__newThread=null;(()=>{let attempts=0;async function poll(){const s=await window.pocketRequest('/native/snapshot');window.__newThread=s.state.mainThreadId;if(window.__newThread===window.__oldThread&&attempts++<100)setTimeout(poll,50);}void poll();})()");
        ready("window.__newThread&&window.__newThread!==window.__oldThread&&document.querySelector('#message').value===''&&!document.querySelector('#composer').classList.contains('is-expanded')");
    }
    @Test public void liveThreadUpdatesPreserveScrollDraftAndComposerLayout() throws Exception {
        ready("typeof window.pocketRequest==='function'");
        home();eval("window.__originalNative=window.PocketNative;window.__fixtureReady=false;window.pocketRequest('/native/snapshot').then(s=>{window.__fixture=s;s.state.mainThreadId='ui-scroll';s.state.messages=[{role:'user',text:'Can you help me plan today?'},{role:'assistant',text:'Of course. What would you like to focus on?'}];s.state.tasks=[{conversationId:'scroll-worker',mainThreadId:'ui-scroll',label:'Plan the day',status:'running',display:{state:'active',displayId:19}}];s.state.workerMessages={'scroll-worker':Array.from({length:40},(_,i)=>({role:i%2?'assistant':'user',text:'Thread message '+i+' — A longer reply to test reading and scrolling without a live update moving the page.'}))};window.PocketNative={request:(id,path,payload)=>path==='/native/snapshot'?window.pocketReply(id,window.__fixture,null):path==='/native/worker'?window.pocketReply(id,{},null):window.__originalNative.request(id,path,payload)};window.__fixtureReady=true;window.pocketEvent('agent',{});})");
        try {
            ready("window.__fixtureReady&&document.querySelector('#messages').textContent.includes('plan today')");
            eval("document.querySelector('#message').value='';document.querySelector('#message').dispatchEvent(new Event('input'));document.querySelector('#message').blur()");
            ready("document.querySelector('#composer').getBoundingClientRect().height<=60&&getComputedStyle(document.querySelector('#provider-picker')).display==='none'");
            eval("document.documentElement.dataset.theme='dark'");capture("compact-chat-dark");
            eval("document.documentElement.dataset.theme='light'");capture("compact-chat-light");
            eval("document.querySelector('#message').focus()");
            ready("document.querySelector('#composer').classList.contains('is-expanded')");capture("expanded-composer-light");
            eval("document.querySelector('#message').blur();document.documentElement.dataset.theme='dark';document.querySelector('#show-threads').click()");
            ready("document.querySelector('#settings-screen .native-task')!==null");
            eval("document.querySelector('#settings-screen .native-task').click()");
            ready("document.querySelector('.worker-messages')?.children.length===40");
            eval("window.__scrollBody=document.querySelector('#settings-screen .settings-body');window.__firstMessage=document.querySelector('.worker-messages').firstElementChild;window.__scrollBody.scrollTop=500;document.querySelector('#worker-draft').value='Keep this draft';window.__fixture.state.tasks[0].status='succeeded';window.__fixture.state.workerMessages['scroll-worker'].push({role:'assistant',text:'An update while reading'});window.pocketEvent('agent',{})");
            ready("document.querySelector('.worker-messages')?.children.length===41");
            assertEquals("true",eval("document.querySelector('#settings-screen .settings-body')===window.__scrollBody&&document.querySelector('.worker-messages').firstElementChild===window.__firstMessage&&Math.abs(window.__scrollBody.scrollTop-500)<2&&document.querySelector('#worker-draft').value==='Keep this draft'"));
            assertEquals("false",eval("document.querySelector('#settings-screen').textContent.includes('virtual display #19')"));capture("thread-scroll-preserved-dark");
        } finally {eval("window.PocketNative=window.__originalNative;document.documentElement.dataset.theme='dark';document.querySelector('#message').blur();window.pocketEvent('agent',{})");home();}
    }
    @Test public void approvedUiRoutesAndNativeBridgeWork() throws Exception {
        assertTrue(activity.getActivity().getPackageName().endsWith(".uipreview"));
        ready("typeof window.pocketRequest==='function'&&document.querySelector('#welcome h1')?.textContent==='What can I help with?'");
        assertEquals("1",eval("document.querySelectorAll('.topbar button:not([hidden])').length"));
        assertEquals("true",eval("document.querySelector('#composer').getBoundingClientRect().height<innerHeight/3"));
        eval("document.querySelector('#message').focus()");
        assertEquals("true",eval("getComputedStyle(document.querySelector('#message')).outlineStyle==='none'"));
        eval("document.querySelector('#message').blur()");
        eval("document.querySelector('#message').value='';document.querySelector('#message').dispatchEvent(new Event('input'))");
        assertEquals("\"Start Codex live voice\"",eval("document.querySelector('#send').getAttribute('aria-label')"));
        eval("document.querySelector('#message').value='Hello';document.querySelector('#message').dispatchEvent(new Event('input'))");
        assertEquals("\"Send message\"",eval("document.querySelector('#send').getAttribute('aria-label')"));
        eval("document.querySelector('#message').value='';document.querySelector('#message').dispatchEvent(new Event('input'))");
        eval("window.__qa=null;window.pocketRequest('/native/preferences',{name:'UI bridge test'}).then(()=>window.pocketRequest('/native/snapshot')).then(result=>window.__qa=result.preferences.name)");
        ready("window.__qa==='UI bridge test'");eval("window.pocketRequest('/native/preferences',{name:''})");
        settings();clickTextRoute("Voice & wake word");select("Speech recognition","groq");ready("document.querySelector('#groq-key')!==null");assertEquals("true",eval("document.querySelector('#deepgram-key')===null"));
        select("Speech recognition","system");ready("document.querySelector('#groq-key')===null&&document.querySelector('#deepgram-key')===null");
    }
    @Test public void failedKeyValidationPreservesConnectionAndInvalidatesEarlierSignIn() throws Exception {
        assertTrue(activity.getActivity().getPackageName().endsWith(".uipreview"));
        ready("typeof window.pocketRequest==='function'");
        CredentialStore credentials=new CredentialStore(activity.getActivity());org.json.JSONObject previous=credentials.get();
        java.lang.reflect.Field generation=MainActivity.class.getDeclaredField("accountGeneration");generation.setAccessible(true);
        long before=generation.getLong(activity.getActivity());
        try{
            eval("window.__qa=null;window.pocketRequest('/api/provider/key',{provider:'nebius',apiKey:''}).catch(error=>window.__qa=error.message)");
            ready("typeof window.__qa==='string'&&window.__qa.includes('Settings were not saved')");assertTrue(generation.getLong(activity.getActivity())>before);
            assertEquals(previous.toString(),credentials.get().toString());
        }finally{credentials.save(previous);}
    }
    @Test public void olderMainChatReopensForTypingAndThreadsStaySeparate() throws Exception {
        MainActivity app=activity.getActivity();assertTrue(app.getPackageName().endsWith(".uipreview"));
        ready("typeof window.pocketRequest==='function'");
        String oldId="77777777-7777-4777-8777-777777777777",newId="88888888-8888-4888-8888-888888888888";
        CredentialStore credentials=new CredentialStore(app);org.json.JSONObject priorCredentials=credentials.get();
        try(StateStore store=new StateStore(app)){
            org.json.JSONObject priorDurable=store.loadDurable(),priorSnapshot=store.load();
            try{
                credentials.save(new org.json.JSONObject().put("authMode","scripted").put("model","scripted"));
                org.json.JSONArray oldMessages=new org.json.JSONArray().put(new org.json.JSONObject().put("role","user").put("content","Older main question").put("timestamp",100)).put(new org.json.JSONObject().put("role","assistant").put("content","Older main answer").put("timestamp",101));
                store.saveDurable(new org.json.JSONObject().put("mainThreadId",oldId).put("mainMessages",oldMessages));
                store.saveDurable(new org.json.JSONObject().put("mainThreadId",newId).put("mainMessages",new org.json.JSONArray()));
                home();eval("document.querySelector('#open-chats').click()");ready("Array.from(document.querySelectorAll('.history-item')).some(n=>n.textContent==='Older main question')");clickText("Older main question");
                ready("document.querySelector('#messages').textContent.includes('Older main answer')&&!document.querySelector('#message').disabled");
                eval("document.querySelector('#message').value='Follow up here';document.querySelector('#message').dispatchEvent(new Event('input'))");
                assertEquals("\"Send message\"",eval("document.querySelector('#send').getAttribute('aria-label')"));capture("older-chat-editable");
                eval("window.pocketRequest('/native/send',{text:'Follow up here'})");
                ready("document.querySelector('#messages').textContent.includes('Main is responsive: Follow up here')");
                assertEquals(oldId,store.loadDurable().optString("mainThreadId"));
                eval("window.pocketRequest('/native/send',{text:'delegate delay=1000'})");
                ready("document.querySelector('#show-threads').textContent.includes('·')");
                assertEquals("0",eval("document.querySelectorAll('.main .native-task').length"));
                eval("document.querySelector('#show-threads').click()");ready("document.querySelector('#settings-screen .settings-title').textContent==='Threads'");capture("chat-threads-screen");
                assertEquals("true",eval("document.querySelectorAll('#settings-screen .native-task').length>0"));
                eval("window.pocketBack();document.querySelector('#message').value='';document.querySelector('#message').dispatchEvent(new Event('input'))");
                ready("document.querySelector('#send').getAttribute('aria-label')==='Start Codex live voice'");capture("empty-composer-live-voice");
            }finally{
                app.startService(new android.content.Intent(app,AgentService.class).setAction(AgentService.ACTION_STOP));
                long deadline=android.os.SystemClock.elapsedRealtime()+10000;while(AgentService.isRunning()&&android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(50);
                assertFalse("Fixture runtime must stop before restoring state",AgentService.isRunning());
                java.lang.reflect.Field closing=RuntimeOwner.class.getDeclaredField("previousClose");closing.setAccessible(true);Object pending=closing.get(null);if(pending instanceof java.util.concurrent.CompletableFuture<?> future)future.get(10,TimeUnit.SECONDS);
                store.saveDurable(priorDurable);store.save(priorSnapshot);credentials.save(priorCredentials);
            }
        }
    }
    private void swipe(float fromX,float fromY,float toX,float toY) throws Exception {
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        long down=android.os.SystemClock.uptimeMillis();
        for(int step=0;step<=16;step++){
            int action=step==0?android.view.MotionEvent.ACTION_DOWN:step==16?android.view.MotionEvent.ACTION_UP:android.view.MotionEvent.ACTION_MOVE;
            float progress=step/16f;android.view.MotionEvent event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,fromX+(toX-fromX)*progress,fromY+(toY-fromY)*progress,0);
            try{instrumentation.sendPointerSync(event);}finally{event.recycle();}android.os.SystemClock.sleep(16);
        }
    }
    @Test public void fingerSwipesOpenCloseAndRespectVerticalMovement() throws Exception {
        ready("typeof window.pocketBack==='function'");home();
        android.graphics.Rect bounds=new android.graphics.Rect();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->web(activity.getActivity().getWindow().getDecorView()).getGlobalVisibleRect(bounds));
        float left=bounds.left+bounds.width()*.2f,right=bounds.left+bounds.width()*.8f,y=bounds.top+bounds.height()*.4f;
        swipe(left,y,left,y-250);assertEquals("false",eval("document.querySelector('.drawer-layer').classList.contains('is-open')"));
        swipe(left,y,left+50,y);ready("!document.querySelector('.drawer-layer').classList.contains('is-open')");
        swipe(left,y,right,y);ready("document.querySelector('.drawer-layer').classList.contains('is-open')");capture("swipe-open");
        swipe(right,y,left,y);ready("!document.querySelector('.drawer-layer').classList.contains('is-open')");capture("swipe-closed");
    }
    @Test public void captureApprovedUiRouteMatrix() throws Exception {
        ready("typeof window.pocketBack==='function'");
        for(String theme:new String[]{"dark","light"}){
            settings();select("Appearance",theme);home();capture("home-"+theme);
            eval("document.querySelector('#open-chats').click()");capture("drawer-"+theme);eval("document.querySelector('[data-action=settings]').click()");capture("settings-"+theme);
            for(String route:new String[]{"Your Pocket","AI & model","Personal instructions","Voice & wake word","Phone access","Connections","Background work"}){
                if(route.equals("Your Pocket"))eval("document.querySelector('.settings-profile').click()");else clickTextRoute(route);
                capture(route.toLowerCase().replaceAll("[^a-z]+","-")+theme);
                if(route.equals("AI & model")){select("Provider","codex-account");ready("Array.from(document.querySelectorAll('button')).some(n=>n.textContent==='Sign in with ChatGPT')");capture("codex-connection-"+theme);select("Provider","nebius");ready("document.querySelector('#provider-key')!==null");capture("nebius-connection-"+theme);}
                if(route.equals("Voice & wake word")){for(String engine:new String[]{"groq","deepgram","codex","system"}){select("Speech recognition",engine);ready("document.querySelector('select').value==="+org.json.JSONObject.quote(engine));capture("voice-"+engine+"-"+theme);}}
                eval("window.pocketBack()");ready("document.querySelector('.settings-profile')!==null");
            }
            home();eval("document.querySelector('#open-chats').click();document.querySelector('[data-action=activity]').click()");
            for(String tab:new String[]{"Schedules","Subagents","Displays"}){clickText(tab);capture("activity-"+tab.toLowerCase()+"-"+theme);}home();
        }
    }
    private void clickTextRoute(String label) throws Exception {eval("Array.from(document.querySelectorAll('.settings-row-label')).find(n=>n.textContent==="+org.json.JSONObject.quote(label)+").closest('button').click()");}
}
