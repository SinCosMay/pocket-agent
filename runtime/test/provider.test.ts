import test from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {Agent} from '@earendil-works/pi-agent-core';
import {makeStream,modelFor} from '../src/provider.js';
import {SubagentParams} from '../vendor/pi9/schema.js';

test('Responses wire preserves optional Pi9 action fields by explicitly disabling strict normalization',async()=>{
 let wire:any;
 const server=createServer(async(req,res)=>{
   let body='';for await(const chunk of req)body+=chunk;
   wire=JSON.parse(body);
   res.writeHead(200,{'content-type':'text/event-stream'});
   res.end('event: response.completed\ndata: '+JSON.stringify({type:'response.completed',response:{id:'resp_contract',status:'completed',output:[],usage:{input_tokens:1,output_tokens:0,total_tokens:1,input_tokens_details:{cached_tokens:0}}}})+'\n\n');
 });
 await new Promise<void>(resolve=>server.listen(0,'127.0.0.1',resolve));
 const address=server.address();assert.ok(address&&typeof address!=='string');
 const config={mode:'codex' as const,endpoint:`http://127.0.0.1:${address.port}/v1`,model:'gpt-6-luna'};
 const agent=new Agent({streamFn:makeStream(config,{apiKey:'synthetic-contract-credential'},'main'),initialState:{model:modelFor(config),tools:[{name:'subagent',label:'Subagent',description:'Dispatch Pi9 work.',parameters:SubagentParams,execute:async()=>({content:[{type:'text',text:'unused'}],details:{}})}]}});
 try {
   await agent.prompt('Wire contract probe');
   assert.equal(agent.state.messages.at(-1)?.stopReason,'stop');
   const tool=wire.tools.find((item:any)=>item.name==='subagent');
   assert.ok(tool,'the actual Pi Responses request declares the selected Pi9 tool');
   assert.ok(Object.hasOwn(tool,'strict'),'strict must be explicit on the wire');
   assert.equal(tool.strict,false,'Pi9 discriminated actions rely on optional fields');
   assert.deepEqual(tool.parameters.required,['action']);
   for(const optional of ['statuses','collected','spawns','resumes','messages','subagentIds']) {
     assert.ok(Object.hasOwn(tool.parameters.properties,optional));
     assert.ok(!tool.parameters.required.includes(optional));
   }
   assert.deepEqual(tool.parameters.properties.spawns.items.required,['agent','prompt','label']);
 } finally {
   server.closeAllConnections();
   await new Promise<void>((resolve,reject)=>server.close(error=>error?reject(error):resolve()));
 }
});
