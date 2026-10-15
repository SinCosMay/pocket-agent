import test from 'node:test';
import assert from 'node:assert/strict';
import { Agent } from '@earendil-works/pi-agent-core';
import { Type } from 'typebox';
import {SCRIPT_MODEL,scriptedStream} from '../src/scripted.js';
test('stage 0: Pi core scripted model calls a typed tool and completes',async()=>{
  let calls=0;const events:any[]=[];
  const agent=new Agent({streamFn:scriptedStream(),initialState:{model:SCRIPT_MODEL,tools:[{name:'phone_capabilities',label:'Capabilities',description:'Read native capabilities',parameters:Type.Object({}),execute:async()=>{calls++;return {content:[{type:'text',text:'{"available":true}'}],details:{}};}}]}});
  agent.subscribe(e=>{events.push(e);});await agent.prompt('probe');
  assert.equal(calls,1);assert.equal(agent.state.messages.at(-1)?.role,'assistant');
  assert.ok(events.some(e=>e.type==='message_update'));
});
test('stage 0: pending streaming request observes AbortSignal',async()=>{
 const agent=new Agent({streamFn:scriptedStream('worker'),initialState:{model:SCRIPT_MODEL}});
 const run=agent.prompt('delay=30000');setTimeout(()=>agent.abort(),15);await run;
 assert.equal((agent.state.messages.at(-1) as any).stopReason,'aborted');
});
