import test from 'node:test';
import assert from 'node:assert/strict';
import {PocketRuntime,NativeBridge} from '../src/runtime.js';
import {delay} from '../src/scripted.js';

class Bridge implements NativeBridge {
 state:any={};events:any[]=[];tools:any[]=[];credentials:any={authMode:'scripted'};
 call(method:string,argsJson:string):string {
  const args=JSON.parse(argsJson);let result:any;
  if(method==='credentials.get')result=this.credentials;
  if(method==='store.load')result=this.state;
  if(method==='store.save'){this.state=structuredClone(args.state);result={saved:true};}
  if(method==='tool'){this.tools.push(args);result={available:true,operation_prefix:'fixture',owner:args.owner};}
  return JSON.stringify({ok:true,result});
 }
 emit(json:string):void {this.events.push(JSON.parse(json));}
}
async function until(fn:()=>any,timeout=4000):Promise<void>{const end=Date.now()+timeout;while(!fn()){if(Date.now()>end)throw new Error('Timed out');await delay(10);}}
const command=(r:PocketRuntime,cmd:any)=>assert.equal(JSON.parse(r.dispatch(JSON.stringify(cmd))).ok,true);

test('main responds under worker load; completion is persisted and wakes main once; worker direct followup retains identity',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate long fixture delay=300'});
 await until(()=>r.state().tasks.length===1&&!r.busy);
 const id=r.state().tasks[0].conversationId;
 assert.equal(r.state().tasks[0].status,'running');
 command(r,{action:'send',text:'hello while working'});
 await until(()=>r.state().messages.some((m:any)=>m.text.includes('Main is responsive: hello')));
 assert.equal(r.state().tasks[0].status,'running');
 command(r,{action:'steer_worker',conversationId:id,text:'Use care'});
 await until(()=>r.state().tasks[0].status==='completed'&&!r.state().active);
 assert.ok(bridge.tools.some(t=>t.owner===id&&t.name==='phone_capabilities'));
 assert.equal(r.state().mainTurns,3);
 assert.equal(bridge.state.delivered.length,1);
 assert.equal(bridge.state.workers[0].generations[0].receipts.model,true);
 assert.ok(bridge.state.workers[0].generations[0].steers.length);
 const restored=new PocketRuntime(bridge);await delay(50);
 assert.equal(restored.state().mainTurns,3);
 command(restored,{action:'send',conversationId:id,text:'Follow up delay=10'});
 await until(()=>restored.state().tasks[0].generation===2&&restored.state().tasks[0].status==='completed'&&!restored.state().active);
 assert.equal(restored.state().tasks[0].conversationId,id);
 assert.equal(restored.state().mainTurns,3,'user initiated worker followup does not wake main');
 command(restored,{action:'inspect_worker',conversationId:id});
 assert.equal(bridge.state.workers[0].generations[1].receipts.user,true);
});
test('recursive Pi9 children complete with shared concurrency cap one and suspended join slot',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge,{maxExecuting:1,maxConversations:12});
 command(r,{action:'send',text:'delegate nested fixture'});
 await until(()=>r.state().tasks.length===2&&!r.state().active);
 assert.ok(r.state().tasks.every((t:any)=>t.status==='completed'));
 const parent=r.state().tasks.find((t:any)=>!t.parentConversationId);
 assert.equal(parent.generations[0].nestedJoins[0].state,'completed');
 assert.equal(r.state().mainTurns,2);
});
test('process death preserves worker identity/transcript and marks work interrupted; resume requires receipt',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=500'});
 await until(()=>r.state().tasks[0]?.status==='running');
 const checkpoint=structuredClone(bridge.state),id=checkpoint.workers[0].conversationId;
 await r.runtime.cancelSubagent(id);
 bridge.state=checkpoint;
 const restored=new PocketRuntime(bridge);
 await until(()=>!restored.state().active);
 assert.equal(restored.state().tasks[0].status,'interrupted');
 assert.match(restored.state().tasks[0].generations[0].status.error,/unknown outcomes/);
 assert.equal(restored.state().tasks[0].conversationId,id);
 command(restored,{action:'send',conversationId:id,text:'Inspect before continuing delay=10'});
 await until(()=>restored.state().tasks[0].generation===2&&!restored.state().active);
 assert.equal(restored.state().tasks[0].status,'completed');
});
test('stop current main leaves worker running; cancel worker and stop all are distinct',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=30000'});
 await until(()=>r.state().tasks[0]?.status==='running');
 command(r,{action:'stop_main'});assert.equal(r.state().tasks[0].status,'running');
 command(r,{action:'cancel_worker',conversationId:r.state().tasks[0].conversationId});
 await until(()=>!r.state().active);
 assert.equal(r.state().tasks[0].status,'cancelled');
 command(r,{action:'send',text:'delegate fixture delay=30000'});
 await until(()=>r.state().tasks[1]?.status==='running');
 command(r,{action:'stop_all'});await until(()=>!r.state().active);
 assert.equal(r.state().tasks[1].status,'cancelled');
});
