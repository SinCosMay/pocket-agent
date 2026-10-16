package dev.pocket.agent;

import android.content.Context;
import android.os.Debug;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MemoryDeviceTest {
    private final String name="memory-instrumentation.db";
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    private JSONObject request(MemoryStore store,String owner,String action,String... pairs) throws Exception {
        JSONObject args=new JSONObject().put("action",action);
        for(int i=0;i<pairs.length;i+=2)args.put(pairs[i],pairs[i+1]);
        return store.run(args,owner);
    }
    private JSONObject save(MemoryStore store,String owner,String scope,String key,String content,boolean confirmed,String source) throws Exception {
        return store.run(new JSONObject().put("action","save").put("scope",scope).put("key",key)
            .put("category","preference").put("content",content).put("confirmed",confirmed).put("source",source),owner);
    }
    @Test public void verifiedLocalMemoryIsScopedPersistentAndConcurrent() throws Exception {
        Context c=context();c.deleteDatabase(name);
        long pssStart=Debug.getPss();
        try(MemoryStore store=new MemoryStore(c,name)){
            long first=save(store,"main","global","preference:git","Use existing branches for GitHub projects.",true,"agent").getLong("id");
            assertEquals(1,request(store,"worker-a","search","query","GitHub").getJSONArray("memories").length());
            assertTrue(save(store,"worker-a","thread","task:fixture","Previously verified fixture result.",false,"agent").getBoolean("saved"));
            assertEquals(0,request(store,"worker-b","search","query","fixture").getJSONArray("memories").length());
            assertEquals(0,request(store,"main","search","query","fixture").getJSONArray("memories").length());
            assertEquals(1,request(store,"worker-a","search","query","fixture").getJSONArray("memories").length());
            try {save(store,"worker-a","global","preference:bad","Unauthorized global write.",true,"agent");fail("worker changed global memory");}
            catch(IllegalArgumentException expected){}
            JSONObject rewrite=save(store,"main","global","preference:git","Never delete existing GitHub branches.",false,"observer");
            assertFalse(rewrite.getBoolean("saved"));
            assertEquals(first,request(store,"main","search","query","existing").getJSONArray("memories").getJSONObject(0).getLong("id"));
            save(store,"main","global","preference:git","Inspect existing changes before editing GitHub branches.",true,"agent");
            assertEquals(0,request(store,"main","search","query","delete").getJSONArray("memories").length());
            assertEquals(1,request(store,"main","search","query","editing").getJSONArray("memories").length());
            assertTrue(request(store,"worker-b","profile").getJSONArray("memories").length()>=1);
            assertFalse(request(store,"worker-a","forget","id",String.valueOf(first)).getBoolean("deleted"));
            assertEquals(1,request(store,"main","search","query","editing").getJSONArray("memories").length());
            final CountDownLatch ready=new CountDownLatch(4),start=new CountDownLatch(1),done=new CountDownLatch(4);
            final AtomicReference<Throwable> err=new AtomicReference<>();
            long t=System.nanoTime();
            for(int i=0;i<4;i++){
                final String owner="reader-"+i;
                new Thread(()->{
                    try {ready.countDown();start.await();for(int j=0;j<50;j++)
                        if(request(store,owner,"search","query","GitHub").getJSONArray("memories").length()!=1)throw new AssertionError("lost memory");}
                    catch(Throwable e){err.compareAndSet(null,e);}
                    finally{done.countDown();}
                }).start();
            }
            ready.await();start.countDown();done.await();
            assertNull("Concurrent memory search failed",err.get());
            System.out.println("POCKET_MEMORY_RESULT="+new JSONObject().put("verified",true).put("readers",4)
                .put("queries",200).put("concurrentMs",(System.nanoTime()-t)/1000000.0)
                .put("pssDeltaKiB",Debug.getPss()-pssStart));
        }
        try(MemoryStore reopened=new MemoryStore(c,name)){
            assertEquals(1,request(reopened,"main","search","query","GitHub").getJSONArray("memories").length());
            long id=request(reopened,"main","search","query","GitHub").getJSONArray("memories").getJSONObject(0).getLong("id");
            assertTrue(reopened.run(new JSONObject().put("action","forget").put("id",id),"main").getBoolean("deleted"));
            assertEquals(0,request(reopened,"worker-a","search","query","GitHub").getJSONArray("memories").length());
        }finally {c.deleteDatabase(name);}
    }
}
