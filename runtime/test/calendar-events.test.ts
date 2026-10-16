import test from 'node:test';
import assert from 'node:assert/strict';
import {validateToolArguments} from '@earendil-works/pi-ai';
import {nativeTools} from '../src/tools.js';

test('calendar events schema is read-only and needs no display or operation ID',async()=>{
 let acquired=false,dispatched=false;
 const host={
  ensureDisplay:async()=>{acquired=true;throw Error('Unexpected virtual display');},
  call:(method:string,params:any)=>{
   assert.equal(method,'tool');assert.equal(params.name,'phone_calendar_events');
   dispatched=true;return {events:[],truncated:false};
  },
 };
 const tool=nativeTools(host,'calendar-read').find((x:any)=>x.name==='phone_calendar_events')!;
 assert.ok(tool);
 const args={start_ms:1792090000000,end_ms:1792176400000};
 assert.deepEqual(validateToolArguments(tool,{type:'toolCall',id:'calendar1',name:tool.name,arguments:args}),args);
 assert.throws(()=>validateToolArguments(tool,{type:'toolCall',id:'calendar2',name:tool.name,arguments:{start_ms:1}}),/end_ms/);
 const result=await tool.execute('calendar1',args);
 assert.equal(acquired,false);
 assert.equal(dispatched,true);
 assert.equal(result.isError,false);
 assert.deepEqual(result.details,{events:[],truncated:false});
});
