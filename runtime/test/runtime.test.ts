import test from 'node:test';
import assert from 'node:assert/strict';
import {PocketRuntime,NativeBridge} from '../src/runtime.js';
import {delay} from '../src/scripted.js';
import {AssistantMessageEventStream,validateToolArguments} from '@earendil-works/pi-ai';
import {toolResult,MobileSubagentParams,nativeTools} from '../src/tools.js';
import {SubagentParams,parseSubagentInvocation} from '../vendor/pi9/schema.js';

class Bridge implements NativeBridge {
 state:any={};events:any[]=[];tools:any[]=[];credentials:any={authMode:'scripted'};
 call(method:string,argsJson:string):string {
  const args=JSON.parse(argsJson);let result:any;
  if(method==='credentials.get')result=this.credentials;
  if(method==='store.load')result=this.state;
  if(method==='store.save'){this.state=structuredClone(args.state);result={saved:true};}
  if(method==='tool'){this.tools.push(args);result={available:true,operation_prefix:'20741-0123456789abcdef',owner:args.owner};}
  return JSON.stringify({ok:true,result});
 }
 emit(json:string):void {this.events.push(JSON.parse(json));}
}
async function until(fn:()=>any,timeout=4000):Promise<void>{const end=Date.now()+timeout;while(!fn()){if(Date.now()>end)throw new Error('Timed out');await delay(10);}}
const command=(r:PocketRuntime,cmd:any)=>assert.equal(JSON.parse(r.dispatch(JSON.stringify(cmd))).ok,true);

test('Pi validates the full mutation ID contract before dispatch; historical receipt lookup remains separate',()=>{
 const tools=nativeTools({call:()=>{throw new Error('Validation must not dispatch');}},'contract');
 const open=tools.find(t=>t.name==='phone_open_app')!;
 const validate=(id:string)=>validateToolArguments(open,{type:'toolCall',id:'contract-call',name:open.name,arguments:{package_name:'dev.pocket.agent.fixture',operation_id:id}});
 const prefix='20741-0123456789abcdef_';
 for(const suffix of ['type001','x'.repeat(49),'invalid.nonce','unicode_é'])assert.throws(()=>validate(prefix+suffix),/operation_id/);
 for(const suffix of ['type0001','x'.repeat(48),'owner_gen1_type_0001'])assert.equal(validate(prefix+suffix).operation_id,prefix+suffix);
 const lookup=tools.find(t=>t.name==='phone_operation')!;
 assert.equal(validateToolArguments(lookup,{type:'toolCall',id:'history-call',name:lookup.name,arguments:{operation_id:'old-history-id'}}).operation_id,'old-history-id');
});

test('mobile spawn schema omits unsupported overrides while upstream actions and inherited grants stay intact',()=>{
 const mobile:any=MobileSubagentParams,upstream:any=SubagentParams;
 assert.deepEqual(Object.keys(mobile.properties.spawns.items.properties).sort(),['agent','label','prompt']);
 assert.deepEqual(mobile.properties.spawns.items.required,['agent','prompt','label']);
 assert.equal(mobile.properties.spawns.items.additionalProperties,false);
 for(const field of ['cwd','model','skills','thinking'])assert.ok(field in upstream.properties.spawns.items.properties,'upstream schema must remain unmodified');
 for(const field of Object.keys(upstream.properties).filter(x=>x!=='spawns'))assert.deepEqual(mobile.properties[field],upstream.properties[field]);
 assert.ok(!('error' in parseSubagentInvocation({action:'spawn',spawns:[{agent:'phone-operator',label:'Upstream contract',prompt:'Read state',model:'configured-model',cwd:'.',skills:[],thinking:'off'}]})));
 const runtime=new PocketRuntime(new Bridge());
 const tool=runtime.main.agent.state.tools.find((t:any)=>t.name==='subagent')!;
 assert.equal(tool.parameters,MobileSubagentParams);
 assert.match(tool.description,/Tools are inherited by children/);
 assert.ok(runtime.registry.agents.get('phone-operator')!.tools!.includes('phone_act'));
 assert.ok(!runtime.registry.agents.get('researcher')!.tools!.includes('phone_act'));
});

test('batch vision keeps only latest bounded image and preserves image-free operation receipts',()=>{
 const first='Zmlyc3Qtc2NyZWVu',latest='bGF0ZXN0LXNjcmVlbg==';
 const result=toolResult({status:'completed',results:[
   {index:0,name:'phone_observe',result:{snapshot_id:'screen-1',nodes:[{text:'First screen'}],images:[{data:first,mimeType:'image/png'}]}},
   {index:1,name:'phone_act',result:{operation_id:'fixture_action001',status:'verified',receipt:{delivered:true}}},
   {index:2,name:'phone_observe',result:{snapshot_id:'screen-2',nodes:[{text:'Latest screen'}],images:[{data:latest,mimeType:'image/jpeg'}]}},
 ]});
 assert.deepEqual(result.content.filter((c:any)=>c.type==='image'),[{type:'image',data:latest,mimeType:'image/jpeg'}]);
 const text=result.content.find((c:any)=>c.type==='text').text;
 for(const value of [text,JSON.stringify(result.details)]) {
   assert.ok(!value.includes(first));assert.ok(!value.includes(latest));assert.ok(!value.includes('"images"'));
 }
 assert.deepEqual(JSON.parse(text),result.details);
 assert.deepEqual(result.details.results[1].result,{operation_id:'fixture_action001',status:'verified',receipt:{delivered:true}});
 assert.equal(result.details.results[0].result.nodes[0].text,'First screen');
 assert.equal(result.details.results[2].result.snapshot_id,'screen-2');
 const oversized=toolResult({results:[{result:{images:[{data:first,mimeType:'image/png'}]}},{result:{images:[{data:'X'.repeat(2000001),mimeType:'image/png'}],operation_id:'retained'}}]});
 assert.equal(oversized.content.filter((c:any)=>c.type==='image').length,0,'oversized latest image does not resurrect a stale screenshot');
 assert.equal(oversized.details.results[1].result.operation_id,'retained');
 assert.ok(JSON.stringify(oversized).length<1000);
});

test('fresh observations replace prior live screenshots; terminal runs retain text/results without base64',async()=>{
 const imageData='c2NyZWVuc2hvdA=='.repeat(80000); // >1 MB must not consume the text context budget.
 const images=(messages:any[])=>messages.flatMap(m=>Array.isArray(m.content)?m.content:[]).filter(c=>c.type==='image');
 let r:PocketRuntime;
 class ImageBridge extends Bridge {
   observations=0;priorImageCounts:number[]=[];
   override call(method:string,json:string):string {
     const args=JSON.parse(json);
     if(method==='tool'&&args.name==='phone_observe'){
       this.priorImageCounts.push(images(r.main.messages).length);this.observations++;
       return JSON.stringify({ok:true,result:{snapshot_id:`snapshot-${this.observations}`,nodes:[{text:`retained text ${this.observations}`}],images:[{data:imageData,mimeType:'image/png'}]}});
     }
     return super.call(method,json);
   }
 }
 const bridge=new ImageBridge();r=new PocketRuntime(bridge);
 const providerImageCounts:number[]=[];
 r.main.agent.streamFunction=((model:any,context:any)=>{
   providerImageCounts.push(images(context.messages).length);
   const stream=new AssistantMessageEventStream(),call=bridge.observations<2;
   const message:any={role:'assistant',content:call?[{type:'toolCall',id:`observe-${bridge.observations}`,name:'phone_observe',arguments:{include_image:true}}]:[{type:'text',text:'Observed twice; completed.'}],api:model.api,provider:model.provider,model:model.id,timestamp:Date.now(),stopReason:call?'toolUse':'stop',usage:{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}}};
   stream.push({type:'done',reason:message.stopReason,message});stream.end(message);return stream;
 }) as any;
 command(r,{action:'send',text:'Observe twice'});await until(()=>!r.state().active);
 assert.deepEqual(providerImageCounts,[0,1,1],'the active model context retains only the newest observation');
 assert.deepEqual(bridge.priorImageCounts,[0,0],'previous image is released before the next native capture');
 assert.equal(images(r.main.messages).length,0,'terminal response releases its last screenshot');
 const results=r.main.messages.filter(m=>m.role==='toolResult');
 assert.equal(results.length,2);assert.ok(results.every(m=>m.content.some((c:any)=>c.type==='text'&&c.text.includes('retained text'))));
 assert.ok(results.every(m=>m.content.some((c:any)=>c.type==='text'&&c.text.includes('Previous screenshot omitted'))));
 assert.ok(!JSON.stringify(bridge.state).includes(imageData));assert.equal(r.state().lastError,null);
});

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
 command(r,{action:'send',text:'delegate long task delay=300'});
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
       if(a.name==='phone_observe')return JSON.stringify({ok:true,result:{package_name:'dev.pocket.agent.fixture',snapshot_id:`s${this.tools.length}`,nodes:[{description:'AGENT_INPUT',text:this.typed},{text:this.count?`STATUS_CLICKED:${this.typed} COUNT:${this.count}`:'STATUS_READY'} ]}});
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

test('scripted fixture retries cold-launch NOT_READY reads without replaying mutations',async()=>{
 class ColdFixtureBridge extends Bridge {
   count=0;typed='';observes=0;
   override call(method:string,json:string):string {
     const a=JSON.parse(json);
     if(method==='tool') {
       if(a.name==='phone_act'){if(a.args.kind==='type')this.typed=a.args.text;else if(a.args.kind==='click')this.count++;}
       if(a.name==='phone_observe') {
         this.observes++;
         this.tools.push(a);
         if(this.observes<=2)return JSON.stringify({ok:true,result:{isError:true,code:'NOT_READY',message:'Foreground accessibility root is transitioning',retry_safe:false}});
         return JSON.stringify({ok:true,result:{package_name:'dev.pocket.agent.fixture',snapshot_id:`cold-${this.observes}`,nodes:[{description:'AGENT_INPUT',text:this.typed},{text:this.count?`STATUS_CLICKED:${this.typed} COUNT:${this.count}`:'STATUS_READY'} ]}});
       }
     }
     return super.call(method,json);
   }
 }
 const bridge=new ColdFixtureBridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=10'});
 await until(()=>r.state().tasks[0]?.status==='completed'&&!r.state().active);
 assert.equal(bridge.observes,5,'two transitional reads plus three workflow observations');
 assert.equal(bridge.tools.filter(x=>x.name==='phone_open_app').length,1);
 assert.equal(bridge.tools.filter(x=>x.name==='phone_act'&&x.args.kind==='type').length,1);
 assert.equal(bridge.tools.filter(x=>x.name==='phone_act'&&x.args.kind==='click').length,1);
 assert.equal(bridge.count,1);assert.equal(bridge.typed,'PocketWorks');
 assert.match(r.state().tasks[0].output,/Verified fixture outcome/);
});

test('fixture replans only confirmed pre-dispatch stale actions with fresh observations and IDs',async()=>{
 class ChangingFixtureBridge extends Bridge {
  count=0;typed='';rejected=new Set<string>();ids=new Set<string>();observes=0;
  override call(method:string,json:string):string {
   const a=JSON.parse(json);
   if(method==='tool') {
    if(a.name==='phone_act') {
     assert.ok(!this.ids.has(a.args.operation_id));this.ids.add(a.args.operation_id);
     this.tools.push(a);
     if(!this.rejected.has(a.args.kind)) {
      this.rejected.add(a.args.kind);
      return JSON.stringify({ok:true,result:{isError:true,code:'STALE_OBSERVATION',dispatch_status:'not_dispatched',retry_safe:false}});
     }
     assert.equal(a.args.snapshot_id,`changing-${this.observes}`);
     if(a.args.kind==='type')this.typed=a.args.text;else this.count++;
     return JSON.stringify({ok:true,result:{status:'dispatched'}});
    }
    if(a.name==='phone_observe') {
     this.observes++;this.tools.push(a);
     return JSON.stringify({ok:true,result:{package_name:'dev.pocket.agent.fixture',snapshot_id:`changing-${this.observes}`,nodes:[{description:'AGENT_INPUT',text:this.typed},{text:this.count?`STATUS_CLICKED:${this.typed} COUNT:${this.count}`:'STATUS_READY'}]}});
    }
   }
   return super.call(method,json);
  }
 }
 const bridge=new ChangingFixtureBridge(),r=new PocketRuntime(bridge);
 command(r,{action:'send',text:'delegate fixture delay=10'});
 await until(()=>r.state().tasks[0]?.status==='completed'&&!r.state().active);
 assert.equal(bridge.count,1);assert.equal(bridge.ids.size,4);assert.equal(bridge.observes,5);
 assert.match(r.state().tasks[0].output,/Verified fixture outcome/);
});

test('fixture never retries unknown mutations or stale errors without a pre-dispatch receipt',async()=>{
 for(const code of ['RESULT_UNKNOWN','STALE_OBSERVATION']) {
  class UnknownFixtureBridge extends Bridge {
   override call(method:string,json:string):string {
    const a=JSON.parse(json);
    if(method==='tool'&&a.name==='phone_act') {this.tools.push(a);return JSON.stringify({ok:true,result:{isError:true,code,retry_safe:false}});}
    if(method==='tool'&&a.name==='phone_observe')return JSON.stringify({ok:true,result:{package_name:'dev.pocket.agent.fixture',snapshot_id:'unknown-fixture',nodes:[{description:'AGENT_INPUT'}]}});
    return super.call(method,json);
   }
  }
  const bridge=new UnknownFixtureBridge(),r=new PocketRuntime(bridge);
  command(r,{action:'send',text:'delegate fixture delay=10'});
  await until(()=>r.state().tasks[0]?.status==='completed'&&!r.state().active);
  assert.equal(bridge.tools.filter(x=>x.name==='phone_act').length,1);
  assert.match(r.state().tasks[0].output,/Fixture failed/);
 }
});

test('account credentials stay redacted from durable state and events after provider changes',async()=>{
 const bridge=new Bridge();
 Object.assign(bridge.credentials,{accessToken:'synthetic-access-token',refreshToken:'synthetic-refresh-token',idToken:'synthetic-id-token',accountId:'synthetic-account-id'});
 const r=new PocketRuntime(bridge);
 const secrets=['synthetic-access-token','synthetic-refresh-token','synthetic-id-token','synthetic-account-id'];
 r.reportError(new Error(secrets.join(' ')));
 r.main.agent.state.messages.push({role:'assistant',content:[{type:'text',text:secrets.join(' ')}],timestamp:Date.now()} as any);
 r.save();r.publish();
 for(const secret of secrets){assert.ok(!JSON.stringify(bridge.state).includes(secret));assert.ok(!JSON.stringify(bridge.events).includes(secret));}
 bridge.credentials={authMode:'scripted'};
 command(r,{action:'configure'});
 r.reportError(new Error(secrets.join(' ')));r.save();r.publish();
 for(const secret of secrets){assert.ok(!JSON.stringify(bridge.state).includes(secret));assert.ok(!JSON.stringify(bridge.events).includes(secret));}
 await until(()=>!r.state().active);
});
