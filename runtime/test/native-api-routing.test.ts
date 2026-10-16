import test from 'node:test';
import assert from 'node:assert/strict';
import {validateToolArguments} from '@earendil-works/pi-ai';
import {nativeTools} from '../src/tools.js';

test('notification reads and calendar queries never request a virtual display',async()=>{
 let calls:string[]=[];let acquisitions=0;
 const host={
  ensureDisplay:async()=>{acquisitions++;return {status:'acquired'};},
  call:(_method:string,args:any)=>{calls.push(args.name);return {status:'ok'};},
 };
 const all=nativeTools(host,'api-worker');
 for(const name of ['phone_calendar_events','phone_calendars','phone_notification_read','phone_notifications','phone_contacts']) {
  const args=name==='phone_calendar_events'?{start_ms:10,end_ms:20}:name==='phone_contacts'?{query:'Test'}:{};
  const tool=all.find((t:any)=>t.name===name)!;
  assert.ok(tool,name);
  assert.deepEqual(validateToolArguments(tool,{type:'toolCall',id:name,name,arguments:args}),args);
  assert.equal((await tool.execute(name,args)).isError,false);
 }
 const notification=all.find((t:any)=>t.name==='phone_notification_read')!;
 assert.deepEqual(validateToolArguments(notification,{type:'toolCall',id:'page',name:'phone_notification_read',arguments:{offset:2147483647,limit:1}}),{offset:2147483647,limit:1});
 assert.equal(acquisitions,0);
 assert.deepEqual(calls,['phone_calendar_events','phone_calendars','phone_notification_read','phone_notifications','phone_contacts']);
});

test('URL and Clock intents use private-display lease and mutation IDs',async()=>{
 let acquired=0,dispatched=0;
 const host={
  ensureDisplay:async()=>{acquired++;return {status:'acquired'};},
  call:(_method:string,args:any)=>{
   assert.ok(['phone_open_url','phone_clock_screen'].includes(args.name));
   assert.equal(args.args.operation_id,args.name==='phone_open_url'?op:op+'_clock');
   dispatched++;return {status:'requested'};
  },
 };
 const all=nativeTools(host,'ui-worker');
 const op='123456789-abcdef1234567890_open_url001';
 const url=all.find((x:any)=>x.name==='phone_open_url')!;
 const clock=all.find((x:any)=>x.name==='phone_clock_screen')!;
 assert.ok(url&&clock);
 assert.deepEqual(validateToolArguments(url,{type:'toolCall',id:'u',name:url.name,arguments:{url:'https://example.org',operation_id:op}}),{url:'https://example.org',operation_id:op});
 assert.deepEqual(validateToolArguments(url,{type:'toolCall',id:'short',name:url.name,arguments:{url:'http://a',operation_id:op}}),{url:'http://a',operation_id:op});
 assert.throws(()=>validateToolArguments(url,{type:'toolCall',id:'u',name:url.name,arguments:{url:'https://example.org'}}),/operation_id/);
 assert.throws(()=>validateToolArguments(clock,{type:'toolCall',id:'c',name:clock.name,arguments:{screen:'emails',operation_id:op}}),/screen/);
 await url.execute('u',{url:'https://example.org',operation_id:op});
 await clock.execute('c',{screen:'alarms',operation_id:op+'_clock'});
 assert.equal(acquired,2);assert.equal(dispatched,2);
});
