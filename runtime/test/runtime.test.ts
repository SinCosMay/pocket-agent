import test from 'node:test';
import assert from 'node:assert/strict';
import {PocketRuntime,NativeBridge} from '../src/runtime.js';
import {delay} from '../src/scripted.js';
import {AssistantMessageEventStream} from '@earendil-works/pi-ai';

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

test('a resolved provider error remains visible, persisted and credential-redacted after idle/restart',async()=>{
 const bridge=new Bridge();bridge.credentials.apiKey='synthetic-secret-token';
 const r=new PocketRuntime(bridge);
 r.main.agent.streamFunction=((model:any)=>{
   const stream=new AssistantMessageEventStream();
   const message:any={role:'assistant',content:[],api:model.api,provider:model.provider,model:model.id,timestamp:Date.now(),stopReason:'error',errorMessage:'Invalid credential synthetic-secret-token: provider unavailable',usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}};
   stream.push({type:'error',reason:'error',error:message});stream.end(message);return stream;
 }) as any;
 command(r,{action:'send',text:'Check unavailable provider'});
 await until(()=>!r.state().active);
 assert.equal(r.state().lastError,'Invalid credential [redacted]: provider unavailable');
 assert.equal(r.state().messages.at(-1).stopReason,'error');
 assert.match(r.state().messages.at(-1).text,/Request failed: Invalid credential \[redacted\]/);
 assert.ok(bridge.events.some(e=>e.type==='error'&&e.error.includes('provider unavailable')));
 assert.equal(bridge.state.lastError,r.state().lastError);
 assert.ok(!JSON.stringify(bridge.state).includes('synthetic-secret-token'));
 assert.ok(!JSON.stringify(bridge.events).includes('synthetic-secret-token'));
 const restored=new PocketRuntime(bridge);await until(()=>!restored.state().active);
 assert.equal(restored.state().lastError,r.state().lastError);
 assert.equal(restored.state().messages.at(-1).stopReason,'error');
 command(restored,{action:'send',text:'hello'});await until(()=>!restored.state().active);
 assert.equal(restored.state().lastError,null,'next successful turn clears the error');
});

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
test('recursive tool grants intersect with the parent and survive durable restore',async()=>{
 const bridge=new Bridge(),r=new PocketRuntime(bridge,{maxExecuting:1,maxConversations:12});
 const handle=r.startTasks([{kind:'spawn',agent:'researcher',label:'Read only parent',prompt:'nested task'}],{initiatedBy:'model'});
 await handle.completion;await until(()=>!r.state().active);
 const child=r.state().tasks.find((t:any)=>t.parentConversationId);
 assert.ok(child);assert.ok(child.requestedConfig.tools.includes('phone_capabilities'));
 assert.ok(!child.requestedConfig.tools.includes('phone_act'));
 assert.ok(!child.effectiveConfig.tools.includes('phone_act'));
 const restored=new PocketRuntime(bridge);
 const reloaded=restored.state().tasks.find((t:any)=>t.conversationId===child.conversationId);
 assert.ok(!reloaded.requestedConfig.tools.includes('phone_act'));
 assert.ok(!restored.sessions.get(child.conversationId)!.agent.state.tools.some((x:any)=>x.name==='phone_act'));
});
test('scripted fixture executes the real typed tool loop and verifies exact postcondition',async()=>{
 class FixtureBridge extends Bridge {
   count=0;typed='';
   override call(method:string,json:string):string {
     const a=JSON.parse(json);
     if(method==='tool'){
       if(a.name==='phone_act'){if(a.args.kind==='type')this.typed=a.args.text;else if(a.args.kind==='click')this.count++;}
       if(a.name==='phone_observe')return JSON.stringify({ok:true,result:{package_name:'dev.pocket.agent.fixture',snapshot_id:`s${this.tools.length}`,nodes:[{description:'AGENT_INPUT'},{text:`STATUS_CLICKED:${this.typed} COUNT:${this.count}`} ]}});
     }
     return super.call(method,json);
   }
 }
 const bridge=new FixtureBridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=10'});
 await until(()=>r.state().tasks[0]?.status==='completed'&&!r.state().active);
 assert.equal(bridge.count,1);assert.equal(bridge.typed,'PocketWorks');
 assert.match(r.state().tasks[0].output,/Verified fixture outcome/);
 assert.ok(bridge.tools.some(x=>x.name==='phone_batch'));
 assert.equal(bridge.tools.filter(x=>x.name==='phone_act').length,2);
});
