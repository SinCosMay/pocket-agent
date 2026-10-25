import test from 'node:test';
import assert from 'node:assert/strict';
import {PocketRuntime} from '../src/runtime.js';
import {memoryTools,recallBlock} from '../src/memory.js';
import {delay} from '../src/scripted.js';

class MemoryBridge {
  state:any={};calls:any[]=[];events:any[]=[];
  records:any[]=[{id:1,scope:'global',category:'preference',content:'Use existing GitHub branches.',confirmed:true,updatedAt:12,source:'agent'}];
  history:any[]=[{threadId:'old-main',owner:'main',role:'assistant',timestamp:10,text:'The motherboard warranty claim was accepted.'}];
  call(method:string,json:string):string {
    const args=JSON.parse(json);
    let result:any={};
    if(method==='credentials.get')result={authMode:'scripted'};
    if(method==='store.load')result=this.state;
    if(method==='store.save')this.state=structuredClone(args.state);
    if(method==='history.search')result={messages:this.history.filter(x=>String(x.text).toLowerCase().includes(String(args.query).toLowerCase()))};
    if(method==='history.read')result={message:{id:args.id,...this.history[0]}};
    if(method==='memory') {
      this.calls.push(args);
      if(args.request.action==='save')result={saved:true,id:2};
      else if(args.request.action==='forget')result={deleted:args.owner==='main'};
      else result={memories:this.records};
    }
    if(method==='tool')result={available:true,owner:args.owner};
    return JSON.stringify({ok:true,result});
  }
  emit(j:string):void{this.events.push(JSON.parse(j));}
}
const unwrap=(result:any)=>result.details;
async function idle(runtime:PocketRuntime){
  const limit=Date.now()+4000;
  while(runtime.state().active){
    if(Date.now()>limit)throw Error('Runtime did not settle');
    await delay(10);
  }
}
test('main and worker memory tools honor grants and local/global write defaults',async()=>{
  const b=new MemoryBridge();
  const host={call:(method:string,args:any)=>JSON.parse(b.call(method,JSON.stringify(args))).result};
  const main=memoryTools(host,'main');
  assert.deepEqual(main.map(t=>t.name),['memory_search','memory_profile','memory_save','memory_forget','history_search','history_read']);
  await main.find(t=>t.name==='memory_save').execute('a',{key:'pref:git',content:'Use existing branches.',category:'preference',confirmed:true});
  assert.equal(b.calls.at(-1).request.scope,'global');
  const worker=memoryTools(host,'worker-1');
  await worker.find(t=>t.name==='memory_save').execute('b',{key:'task:abc',content:'Done.',category:'episodic'});
  assert.equal(b.calls.at(-1).request.scope,'thread');
  assert.equal(b.calls.at(-1).owner,'worker-1');
  assert.deepEqual(memoryTools(host,'worker-1',['memory_search','memory_profile']).map(t=>t.name),['memory_search','memory_profile']);
  const archived=await main.find(t=>t.name==='history_search').execute('h',{query:'motherboard'});
  assert.match(unwrap(archived).messages[0].text,/warranty claim/);
  const full=await main.find(t=>t.name==='history_read').execute('read',{id:7});
  assert.equal(unwrap(full).message.id,7);
  const memory=await worker.find(t=>t.name==='memory_search').execute('c',{query:'GitHub'});
  assert.equal(unwrap(memory).memories[0].id,1);
});
test('saved memories enter model context automatically, without duplicating recall across turns',async()=>{
  const b=new MemoryBridge(),r=new PocketRuntime(b);
  r.dispatch(JSON.stringify({action:'send',text:'Continue GitHub changes'}));
  await idle(r);
  const system=r.main.messages[0];
  assert.equal(system.role,'system');
  assert.match(system.content,/Use existing GitHub branches/);
  assert.match(system.content,/untrusted data/);
  assert.ok(b.calls.some(c=>c.request.action==='profile'));
  assert.ok(b.calls.some(c=>c.request.action==='search'));
  r.dispatch(JSON.stringify({action:'send',text:'Continue GitHub work'}));
  await idle(r);
  const latest=r.main.messages[0].content;
  assert.equal(latest.split('Use existing GitHub branches').length-1,1);
  assert.equal(r.state().lastError,null);
});
test('saved memory data does not override tool grants for the read-only researcher',()=>{
  const b=new MemoryBridge(),r=new PocketRuntime(b);
  const researcher=r.registry.agents.get('researcher')!;
  assert.ok(researcher.tools!.includes('memory_search'));
  assert.ok(researcher.tools!.includes('memory_profile'));
  assert.ok(!researcher.tools!.includes('memory_save'));
  assert.ok(!researcher.tools!.includes('memory_forget'));
  assert.ok(researcher.tools!.includes('history_search'));
  assert.ok(researcher.tools!.includes('history_read'));
  const block=recallBlock(r,'main','GitHub');
  assert.ok(block.includes('confirmed'));
  assert.match(recallBlock(r,'main','motherboard'),/The motherboard warranty claim was accepted/);
});
