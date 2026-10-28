import test from 'node:test';
import assert from 'node:assert/strict';
import {validateConnection} from '../src/connection-validation.js';
const draft={authMode:'nebius',apiKey:'draft-secret',model:'zai-org/GLM-5.3-Flash'};
function fetcher(status=200,model=draft.model): typeof fetch {
 return async(input,init)=>{
  assert.equal(new Headers(init?.headers).get('authorization'),'Bearer draft-secret');
  if(String(input).endsWith('/models'))return Response.json({data:[{id:model}]});
  assert.equal(String(input),'https://api.tokenfactory.nebius.com/v1/chat/completions');
  if(status!==200)return Response.json({error:{message:'draft-secret provider error'}},{status});
  assert.equal(JSON.parse(String(init?.body)).model,draft.model);
  return new Response('data: '+JSON.stringify({id:'test',choices:[{index:0,delta:{content:'OK'},finish_reason:'stop'}]})+'\n\ndata: [DONE]\n\n',{headers:{'content-type':'text/event-stream'}});
 };
}
test('draft connection verifies catalog and inference without mutating credentials',async()=>{
 const before=JSON.stringify(draft);
 const result=await validateConnection(draft,fetcher());
 assert.equal(result.model,draft.model);assert.equal(result.models.length,1);
 assert.equal(JSON.stringify(draft),before);
});
test('catalog authentication failures stop before inference',async()=>{
 let requests=0;
 await assert.rejects(validateConnection(draft,async()=>{requests++;return new Response(null,{status:401});}),/401/);
 assert.equal(requests,1);
});
test('inference authentication failures are safe and actionable',async()=>{
 await assert.rejects(validateConnection(draft,fetcher(401)),error=>{
  assert.match((error as Error).message,/401/);assert.doesNotMatch((error as Error).message,/draft-secret/);return true;
 });
});
test('unavailable models and empty credentials fail before inference',async()=>{
 await assert.rejects(validateConnection(draft,fetcher(200,'other-model')),/not in this key/);
 await assert.rejects(validateConnection({...draft,apiKey:''},fetcher()),/Enter an API key/);
});
