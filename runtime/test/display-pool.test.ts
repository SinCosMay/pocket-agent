import test from 'node:test';
import assert from 'node:assert/strict';
import {DisplayPool} from '../src/display-pool.js';
import {PocketRuntime,NativeBridge} from '../src/runtime.js';
import {nativeTools,subagentTool} from '../src/tools.js';

const pause=(ms=15)=>new Promise(resolve=>setTimeout(resolve,ms));
const unwrap=(result:any)=>result.details;

class Bridge implements NativeBridge {
  state:any={};
  calls:any[]=[];
  failures=new Set<string>();
  call(method:string,json:string):string {
    const args=JSON.parse(json);
    let result:any={ok:true};
    if(method==='credentials.get')result={authMode:'scripted'};
    else if(method==='store.load')result=this.state;
    else if(method==='store.save')this.state=args.state;
    else if(method==='tool') {
      this.calls.push(args);
      if(args.name==='phone_screen'&&args.args.action==='acquire'&&this.failures.has(args.owner))
        result={isError:true,code:'NOT_READY',message:'Shizuku not running'};
      else result={status:args.name==='phone_screen'?'acquired':'ok',owner:args.owner,display_id:args.name==='phone_screen'?this.calls.length:undefined};
    }
    return JSON.stringify({ok:true,result});
  }
  emit(_event:string):void{}
}

test('strict FIFO capacity three; fourth waits, direct call bypasses, release resumes automatically',async()=>{
  const bridge=new Bridge(),app=new PocketRuntime(bridge);
  const execute=(owner:string,name:string,args:any={})=>nativeTools(app,owner).find(t=>t.name===name)!.execute('t',args);
  await Promise.all(['one','two','three'].map(owner=>execute(owner,'phone_observe')));
  assert.equal(app.displayPool.active,3);
  assert.deepEqual(bridge.calls.filter(c=>c.name==='phone_screen'&&c.args.action==='acquire').map(c=>c.owner),['one','two','three']);
  let completed=false;
  const fourth=execute('four','phone_open_app',{package_name:'fixture',operation_id:'any'}).then((r:any)=>{completed=true;return r;});
  await pause();
  assert.equal(completed,false);
  assert.equal(app.displayPool.waiting,1);
  const direct=await execute('four','phone_sms',{number:'test',text:'hello',operation_id:'any'});
  assert.equal(unwrap(direct).owner,'four');
  assert.equal(app.displayPool.waiting,1,'direct operation must never take a UI slot');
  assert.equal(bridge.calls.some(c=>c.owner==='four'&&c.name==='phone_screen'),false);
  app.releaseScreen('two');
  assert.equal(unwrap(await fourth).owner,'four');
  assert.equal(app.displayPool.active,3);
  assert.equal(app.displayPool.waiting,0);
  assert.deepEqual(bridge.calls.filter(c=>c.name==='phone_screen'&&c.args.action==='acquire').map(c=>c.owner),['one','two','three','four']);
});

test('waiting cancellation does not dispatch native work or consume a released slot',async()=>{
  const bridge=new Bridge(),app=new PocketRuntime(bridge);
  const execute=(owner:string,signal?:AbortSignal)=>nativeTools(app,owner).find(t=>t.name==='phone_observe')!.execute('t',{},signal);
  await Promise.all(['a','b','c'].map(owner=>execute(owner)));
  const cancelled=new AbortController();
  const first=execute('d',cancelled.signal).then(()=>false,()=>true);
  const next=execute('e');
  await pause();
  assert.equal(app.displayPool.waiting,2);
  cancelled.abort();
  assert.equal(await first,true);
  app.releaseScreen('b');
  assert.equal(unwrap(await next).owner,'e');
  assert.equal(bridge.calls.some(c=>c.owner==='d'&&c.name==='phone_screen'),false);
  assert.equal(app.displayPool.waiting,0);
  assert.equal(app.displayPool.active,3);
});

test('Shizuku acquisition failure frees reservation and wakes waiting owner',async()=>{
  const bridge=new Bridge(),app=new PocketRuntime(bridge);
  const execute=(owner:string)=>nativeTools(app,owner).find(t=>t.name==='phone_observe')!.execute('t',{});
  bridge.failures.add('a');
  const failed=await execute('a');
  assert.equal(failed.isError,true);
  assert.equal(app.displayPool.active,0);
  await Promise.all(['b','c','d'].map(owner=>execute(owner)));
  let acquired=false;
  const next=execute('e').then(r=>{acquired=true;return r;});
  await pause();
  assert.equal(acquired,false);
  app.releaseScreen('c');
  assert.equal(unwrap(await next).owner,'e');
  assert.equal(app.displayPool.active,3);
});

test('a Shizuku service death transparently reacquires one fresh lease for the same worker',async()=>{
  class DisconnectingBridge extends Bridge {
    failNext=false;
    override call(method:string,json:string):string {
      const args=JSON.parse(json);
      if(method==='tool'&&args.name==='phone_observe'&&this.failNext) {
        this.failNext=false;
        return JSON.stringify({ok:true,result:{isError:true,code:'NOT_READY',message:'Shizuku backend disconnected; observe after recovery'}});
      }
      return super.call(method,json);
    }
  }
  const bridge=new DisconnectingBridge(),app=new PocketRuntime(bridge);
  const observe=()=>nativeTools(app,'worker').find(t=>t.name==='phone_observe')!.execute('t',{});
  assert.equal(unwrap(await observe()).owner,'worker');
  assert.equal(app.displayPool.active,1);
  bridge.failNext=true;
  const recovered=await observe();
  assert.equal(recovered.isError,false);
  assert.equal(app.displayPool.active,1,'recovery must replace the dead lease without leaking a pool slot');
  assert.equal(bridge.calls.filter(c=>c.name==='phone_screen'&&c.args.action==='acquire').length,2);
});

test('FIFO queue rejects duplicate requests and never leaves display zero available',async()=>{
  const pool=new DisplayPool(1);
  await pool.reserve('first');
  const one=pool.reserve('second');
  await assert.rejects(pool.reserve('second'),/Already waiting/);
  pool.release('first');
  await one;
  assert.equal(pool.has('second'),true);
  pool.release('second');
  assert.equal(pool.active,0);
  assert.equal(pool.waiting,0);
});

test('join without a parent releases the main display and collects worker output',async()=>{
  const releases:string[]=[];
  let released=false;
  const binding={completion:Promise.resolve(),finalizeCollection:()=>[{subagentId:'amber-cedar',status:'completed'}],release:()=>{released=true;}};
  const app:any={runtime:{bindSubagentJoin:()=>binding},releaseScreen:(owner:string)=>releases.push(owner),save:()=>{},publish:()=>{}};
  const result=await subagentTool(app).execute('join-1',{action:'join',subagentIds:['amber-cedar']});
  assert.deepEqual(releases,['main']);
  assert.equal(released,true);
  assert.equal(result.isError,false);
  assert.equal(result.details.results[0].subagentId,'amber-cedar');
});
