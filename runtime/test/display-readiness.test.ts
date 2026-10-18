import test from 'node:test';
import assert from 'node:assert/strict';
import {nativeTools} from '../src/tools.js';

test('cold observation waits through transient NOT_READY without a separate app-open call',async()=>{
  let observes=0,acquires=0;
  const host:any={
    ensureDisplay:async()=>{acquires++;return {status:'acquired',display_id:7};},
    releaseScreen:()=>{},dropPriorImages:()=>{},
    call:(_method:string,payload:any)=>{
      if(payload.name!=='phone_observe')return {status:'ok'};
      observes++;
      if(observes<3)return {isError:true,code:'NOT_READY',message:'No active accessibility window yet; wait for the screen transition and observe again'};
      return {snapshot_id:'cold-ready',package_name:'dev.pocket.agent',window_id:4,nodes:[]};
    },
  };
  const observe=nativeTools(host,'cold-owner').find(t=>t.name==='phone_observe')!;
  const result=await observe.execute('cold',{});
  assert.equal(result.isError,false);
  assert.equal(result.details.package_name,'dev.pocket.agent');
  assert.equal(acquires,1);
  assert.equal(observes,3);
});

test('open-app readiness never returns the previous package as the fresh post-launch observation',async()=>{
  let phase='old',postLaunchReads=0;
  const host:any={
    ensureDisplay:async()=>({status:'acquired',display_id:8}),
    releaseScreen:()=>{},dropPriorImages:()=>{},
    call:(_method:string,payload:any)=>{
      if(payload.name==='phone_open_app'){phase='launching';return {status:'requested',package_name:payload.args.package_name};}
      if(payload.name==='phone_observe'){
        if(phase==='old')return {snapshot_id:'before',package_name:'old.app',window_id:1,nodes:[{text:'old'}]};
        postLaunchReads++;
        if(postLaunchReads<3)return {snapshot_id:'stale-'+postLaunchReads,package_name:'old.app',window_id:1,nodes:[{text:'old'}]};
        return {snapshot_id:'target',package_name:'target.app',window_id:9,nodes:[{text:'ready'}]};
      }
      return {status:'ok'};
    },
  };
  const tools=nativeTools(host,'transition-owner');
  const observe=tools.find(t=>t.name==='phone_observe')!,open=tools.find(t=>t.name==='phone_open_app')!;
  assert.equal((await observe.execute('before',{})).details.package_name,'old.app');
  assert.equal((await open.execute('open',{package_name:'target.app',operation_id:'synthetic'})).isError,false);
  const after=await observe.execute('after',{});
  assert.equal(after.isError,false);
  assert.equal(after.details.package_name,'target.app');
  assert.equal(postLaunchReads,3);
});

test('generic URL readiness waits for a changed observation and cancellation interrupts the wait',async()=>{
  let opened=false,reads=0;
  const host:any={
    ensureDisplay:async()=>({status:'acquired',display_id:9}),
    releaseScreen:()=>{},dropPriorImages:()=>{},
    call:(_method:string,payload:any)=>{
      if(payload.name==='phone_open_url'){opened=true;return {status:'requested'};}
      if(payload.name==='phone_observe'){
        reads++;
        if(!opened||reads<4)return {snapshot_id:'same-'+reads,package_name:'browser.old',window_id:2,nodes:[{text:'same'}]};
        return {snapshot_id:'changed',package_name:'browser.new',window_id:3,nodes:[{text:'changed'}]};
      }
      return {status:'ok'};
    },
  };
  const tools=nativeTools(host,'url-owner');
  const observe=tools.find(t=>t.name==='phone_observe')!,open=tools.find(t=>t.name==='phone_open_url')!;
  await observe.execute('before',{});
  await open.execute('open',{url:'https://example.com',operation_id:'synthetic'});
  const changed=await observe.execute('after',{});
  assert.equal(changed.details.package_name,'browser.new');

  await tools.find(t=>t.name==='phone_open_app')!.execute('launch',{package_name:'never.arrives',operation_id:'synthetic-2'});
  const controller=new AbortController();
  const pending=observe.execute('cancelled',{},controller.signal);
  setTimeout(()=>controller.abort(),20);
  await assert.rejects(pending,/cancelled/i);
});

test('releasing the screen clears pending launch-readiness state for the next lease',async()=>{
  let packageName='old.app';
  const host:any={
    ensureDisplay:async()=>({status:'acquired',display_id:10}),
    releaseScreen:()=>{packageName='dev.pocket.agent';},dropPriorImages:()=>{},
    call:(_method:string,payload:any)=>{
      if(payload.name==='phone_open_app')return {status:'requested'};
      if(payload.name==='phone_observe')return {snapshot_id:'s',package_name:packageName,window_id:1,nodes:[]};
      return {status:'ok'};
    },
  };
  const tools=nativeTools(host,'release-owner');
  await tools.find(t=>t.name==='phone_observe')!.execute('before',{});
  await tools.find(t=>t.name==='phone_open_app')!.execute('open',{package_name:'target.app',operation_id:'synthetic'});
  await tools.find(t=>t.name==='phone_screen')!.execute('release',{action:'release'});
  const fresh=await tools.find(t=>t.name==='phone_observe')!.execute('fresh',{});
  assert.equal(fresh.isError,false);
  assert.equal(fresh.details.package_name,'dev.pocket.agent');
});

test('read-only observation timeout replaces the display lease once and succeeds on the fresh display',async()=>{
  let reads=0,acquires=0,releases=0;
  const host:any={
    ensureDisplay:async()=>{acquires++;return {status:'acquired',display_id:20+acquires};},
    releaseScreen:()=>{releases++;},dropPriorImages:()=>{},
    call:(_method:string,payload:any)=>{
      if(payload.name!=='phone_observe')return {status:'ok'};
      reads++;
      if(reads===1)return {isError:true,code:'RESULT_UNKNOWN',message:'Timed out; inspect before repeating the action'};
      return {snapshot_id:'recovered',package_name:'dev.pocket.agent',window_id:33,nodes:[]};
    },
  };
  const result=await nativeTools(host,'timeout-owner').find(t=>t.name==='phone_observe')!.execute('timeout',{});
  assert.equal(result.isError,false);
  assert.equal(result.details.package_name,'dev.pocket.agent');
  assert.equal(acquires,2);
  assert.equal(releases,1);
  assert.equal(reads,2);
});
