import test from 'node:test';
import assert from 'node:assert/strict';
import { McpToolRegistry, boundMcpJsonResponse, mcpAuthHeaders, normalizeMcpSchema, validateMcpServer } from '../src/mcp.js';
import { PocketRuntime, type NativeBridge } from '../src/runtime.js';
import { delay } from '../src/scripted.js';

const server=(id='server-1')=>({id,name:'Example MCP',url:'https://mcp.example.test/mcp',headerName:'Authorization',headerValue:'Bearer secret-token'});

test('remote server validation requires HTTPS and safe header values',()=>{
  assert.doesNotThrow(()=>validateMcpServer(server()));
  for(const url of ['http://mcp.example.test/mcp','https://user:pass@mcp.example.test/mcp','https://mcp.example.test/mcp?token=secret','https://mcp.example.test/mcp#fragment'])
    assert.throws(()=>validateMcpServer({...server(),url}));
  assert.throws(()=>validateMcpServer({...server(),headerValue:'Bearer x\r\nX-Injected: yes'}),/header/);
  assert.throws(()=>validateMcpServer({...server(),headerName:'Invalid Header'}),/header/);
  assert.throws(()=>validateMcpServer({...server(),headerName:'',headerValue:'secret'}),/header/);
  assert.throws(()=>validateMcpServer({...server(),authMode:'headers',headers:[{name:'X-Key',value:'a'},{name:'x-key',value:'b'}]}),/unique/);
});

test('none, bearer and up to eight custom headers produce safe transport headers',()=>{
  assert.deepEqual(mcpAuthHeaders({...server(),authMode:'none'}),{});
  assert.deepEqual(mcpAuthHeaders({...server(),authMode:'bearer',bearerToken:'abc'}),{Authorization:'Bearer abc'});
  assert.deepEqual(mcpAuthHeaders({...server(),authMode:'bearer',bearerToken:'Bearer abc'}),{Authorization:'Bearer abc'});
  const headers=Array.from({length:8},(_,index)=>({name:`X-Key-${index}`,value:`v${index}`}));
  assert.deepEqual(mcpAuthHeaders({...server(),authMode:'headers',headers}),Object.fromEntries(headers.map(header=>[header.name,header.value])));
  assert.throws(()=>validateMcpServer({...server(),authMode:'headers',headers:[...headers,{name:'X-Extra',value:'x'}]}),/8 custom headers/);
});

test('tool schemas are bounded object schemas',()=>{
  assert.deepEqual(normalizeMcpSchema({type:'object',properties:{query:{type:'string'}}}),{type:'object',properties:{query:{type:'string'}}});
  assert.deepEqual(normalizeMcpSchema({}),{type:'object',properties:{}});
  assert.throws(()=>normalizeMcpSchema({type:'array'}),/object schema/);
  assert.throws(()=>normalizeMcpSchema({description:'x'.repeat(17_000)}),/16 KB/);
});

test('JSON transport responses are byte bounded even without Content-Length',async()=>{
  const small=await boundMcpJsonResponse(new Response(JSON.stringify({ok:true}),{headers:{'content-type':'application/json'}}));
  assert.deepEqual(await small.json(),{ok:true});
  const stream=new ReadableStream<Uint8Array>({start(controller){controller.enqueue(new Uint8Array(2*1024*1024));controller.enqueue(new Uint8Array(2*1024*1024+1));controller.close();}});
  await assert.rejects(boundMcpJsonResponse(new Response(stream,{headers:{'content-type':'application/json'}})),/exceeds 4 MB/);
  const text=new Response('x'.repeat(128),{headers:{'content-type':'text/plain'}});
  assert.equal(await (await boundMcpJsonResponse(text)).text(),'x'.repeat(128));
});

test('registry close is bounded during a pending refresh and closes a late client',async()=>{
  let release!:()=>void,closeCount=0;
  const gate=new Promise<void>(resolve=>{release=resolve;});
  const registry=new McpToolRegistry(undefined,async()=>{await gate;return {client:{close:async()=>{closeCount++;},callTool:async()=>({content:[]})},tools:[]} as any;});
  const refresh=registry.refresh([server()]);
  await delay(10);
  const started=Date.now();await registry.close();
  assert.ok(Date.now()-started<800,'close should not wait for a slow MCP refresh');
  release();await refresh;
  assert.equal(closeCount,1);
});

test('registry exposes bounded, redacted remote tools and closes clients on refresh',async()=>{
  const calls:any[]=[];let closeCount=0;
  const registry=new McpToolRegistry(value=>value.replaceAll('secret-token','[redacted]'),async configured=>{
    calls.push(configured);
    return {
      client:{close:async()=>{closeCount++;},callTool:async(_name,args)=>({content:[{type:'text',text:`echo ${JSON.stringify(args)} secret-token`}],isError:false})},
      tools:[{name:'lookup/search',title:'Search',description:'Look up a record',inputSchema:{type:'object',properties:{query:{type:'string'}}}}],
    } as any;
  });
  try {
    await registry.refresh([server()]);
    assert.equal(registry.tools.length,1);
    const tool=registry.tools[0];
    assert.match(tool.name,/^mcp_server-1_lookup_search$/);
    const result=await tool.execute('call-1',{query:'example'});
    assert.equal(result.isError,false);
    assert.match(result.content[0].text,/\[redacted\]/);
    assert.match(tool.description,/Example MCP: Look up a record/);
    assert.doesNotMatch(JSON.stringify(result),/secret-token/);
    assert.equal(calls[0].headerValue,'Bearer secret-token');
    await registry.refresh([]);
    assert.equal(registry.tools.length,0);
    assert.equal(closeCount,1);
  } finally {await registry.close();}
});

test('disabled saved servers never connect or expose tools',async()=>{
  let connects=0;
  const registry=new McpToolRegistry(undefined,async()=>{connects++;throw new Error('must not connect');});
  try {
    await registry.refresh([{...server(),enabled:false}]);
    assert.equal(connects,0);assert.deepEqual(registry.tools,[]);assert.deepEqual(registry.status(),[]);
    assert.deepEqual(await registry.discover([{...server(),enabled:false}],server().id),{id:'server-1',name:'Example MCP',tools:[]});
  } finally {await registry.close();}
});

test('single-server discovery preserves active clients and tools, and errors are reported',async()=>{
  let closeCount=0;
  const connector=async(configured:any)=>({client:{close:async()=>{closeCount++;},callTool:async()=>({content:[]})},tools:[{name:`tool_${configured.id}`,inputSchema:{type:'object'}}]}) as any;
  const registry=new McpToolRegistry(value=>value,connector);
  try {
    await registry.refresh([server()]);const active=registry.tools[0];
    const found=await registry.discover([{...server(),id:'temporary'}],'temporary');
    assert.equal(found.tools[0],'tool_temporary');assert.equal(registry.tools[0],active);assert.equal(closeCount,1);
    await assert.rejects(registry.discover([],server().id),/not found/);
    const failure=new McpToolRegistry(value=>value,async()=>{throw new Error('offline');});
    try {await assert.rejects(failure.discover([server()],server().id),/offline/);}finally{await failure.close();}
  } finally {await registry.close();}
});

test('tool names remain distinct for colliding sanitized identifiers',async()=>{
  const registry=new McpToolRegistry(undefined,async()=>({client:{close:async()=>{},callTool:async()=>({content:[]})},tools:[{name:'search/tool',inputSchema:{type:'object'}}]}) as any);
  const reversed=new McpToolRegistry(undefined,async()=>({client:{close:async()=>{},callTool:async()=>({content:[]})},tools:[{name:'search/tool',inputSchema:{type:'object'}}]}) as any);
  try {
    const configs=[{...server('a/b')},{...server('a?b')}];
    await registry.refresh(configs);
    assert.equal(new Set(registry.toolNames).size,2);
    assert.deepEqual(registry.toolNames,[...registry.toolNames].sort());
    await reversed.refresh([...configs].reverse());assert.deepEqual(reversed.toolNames,registry.toolNames);
  } finally {await registry.close();await reversed.close();}
});

test('registry rejects over-limit server lists before connecting',async()=>{
  let connected=false;
  const registry=new McpToolRegistry(undefined,async()=>{connected=true;throw new Error('unexpected connection');});
  await assert.rejects(registry.refresh(Array.from({length:11},(_,index)=>server(`server-${index}`))),/limit/);
  assert.equal(connected,false);
  await registry.close();
});

test('Pi runtime keeps native tools and adds a namespaced MCP tool with redacted serializable output',async()=>{
  const events:any[]=[];
  const bridge:NativeBridge={
    call(method,json){const args=JSON.parse(json);const result=method==='credentials.get'?{authMode:'scripted',mcpServers:[server()]}:method==='store.load'?{}:method==='store.save'?args.state:{available:true};return JSON.stringify({ok:true,result});},
    emit(json){events.push(JSON.parse(json));},
  };
  let closeCount=0;
  const runtime=new PocketRuntime(bridge,undefined,async()=>({
    client:{close:async()=>{closeCount++;},callTool:async(_name,args)=>({content:[{type:'text',text:`result ${JSON.stringify(args)} ${'x'.repeat(3000)} secret-token`}],isError:false})},
    tools:[{name:'lookup/search',title:'Search',inputSchema:{type:'object',properties:{query:{type:'string'}}}}],
  } as any));
  const deadline=Date.now()+2000;
  while(!runtime.main.agent.state.tools.some((tool:any)=>tool.name==='mcp_server-1_lookup_search')) {
    if(Date.now()>deadline)throw new Error('MCP tool did not reach the Pi session');
    await delay(5);
  }
  assert.ok(runtime.main.agent.state.tools.some((tool:any)=>tool.name==='phone_capabilities'));
  assert.ok(runtime.main.agent.state.tools.some((tool:any)=>tool.name==='subagent'));
  assert.ok(runtime.registry.agents.get('phone-operator')!.tools!.includes('mcp_server-1_lookup_search'));
  assert.ok(!runtime.registry.agents.get('researcher')!.tools!.some((name:string)=>name.startsWith('mcp_')));
  const remote=runtime.main.agent.state.tools.find((tool:any)=>tool.name==='mcp_server-1_lookup_search');
  const result=await remote.execute('call-1',{query:'example'});
  assert.equal(result.isError,false);
  assert.match(result.content[0].text,/\[redacted\]/);
  assert.ok(result.content[0].text.length>3000,'runtime must not truncate MCP text at the generic 2 KB sanitizer limit');
  assert.doesNotMatch(JSON.stringify(result),/secret-token/);
  assert.doesNotThrow(()=>JSON.stringify(result));
  runtime.dispatch(JSON.stringify({action:'prepare_shutdown'}));
  const closedDeadline=Date.now()+2000;
  while(!closeCount){if(Date.now()>closedDeadline)throw new Error('MCP connection was not closed on shutdown');await delay(5);}
});
