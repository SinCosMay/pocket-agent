import test from 'node:test';
import assert from 'node:assert/strict';
import {CODEX_CLIENT_ID,CODEX_TOKEN_URL,hasPendingCodexRefresh,codexAccessClaims,ensureCodexCredentials,registerCodexCredentialUpdater,redactProviderSecrets} from '../src/codex-auth.js';
const now=1800000000000;
const account='test-account';
function jwt(exp=now+3600000,id=account) {return [Buffer.from('{}').toString('base64url'),Buffer.from(JSON.stringify({exp:Math.floor(exp/1000),'https://api.openai.com/auth':{chatgpt_account_id:id}})).toString('base64url'),'synthetic-signature'].join('.');}
function credentials(exp=now-1000) {return {accessToken:jwt(exp),refreshToken:'old-refresh-secret',accountId:account,expiresAt:exp,idToken:'old-id-secret'};}
const tokenData=()=>({access_token:jwt(now+7200000),refresh_token:'rotated-refresh-secret',expires_in:7200});
const response=()=>Response.json(tokenData());

test('valid cached account credentials never refresh and check account/JWT expiry',async()=>{
 const creds=credentials(now+3600000);let calls=0;
 const result=await ensureCodexCredentials(creds,{now:()=>now,fetch:async()=>{calls++;throw Error('unexpected');}});
 assert.equal(result.accessToken,creds.accessToken);assert.equal(calls,0);
 await assert.rejects(ensureCodexCredentials({...creds,accountId:'different'},{now:()=>now}),/does not match/);
 await assert.rejects(ensureCodexCredentials({...creds,accessToken:'invalid-secret'},{now:()=>now}),/Invalid Codex/);
 await assert.rejects(ensureCodexCredentials({...creds,expiresAt:1.5},{now:()=>now}),/expiry/);
 assert.throws(()=>codexAccessClaims('header.'+Buffer.from(JSON.stringify({exp:Number.MAX_SAFE_INTEGER,'https://api.openai.com/auth':{chatgpt_account_id:account}})).toString('base64url')+'.signature'));
});

test('main and workers share one fixed-origin refresh and await secure rotation persistence',async()=>{
 const creds=credentials();let fetchCalls=0,persistCalls=0;let release!:()=>void;
 const persisted=new Promise<void>(resolve=>release=resolve);
 registerCodexCredentialUpdater(creds,async update=>{
   persistCalls++;assert.equal(update.oldRefreshToken,'old-refresh-secret');
   assert.equal(creds.refreshToken,'old-refresh-secret','shared object changes only after persistence');
   await persisted;return {...update};
 });
 const fetcher:typeof fetch=async(input,init)=>{
   fetchCalls++;assert.equal(String(input),CODEX_TOKEN_URL);assert.equal(init?.method,'POST');assert.equal(init?.redirect,'error');assert.ok(init?.signal);
   const body=new URLSearchParams(String(init?.body));assert.equal(body.get('grant_type'),'refresh_token');assert.equal(body.get('client_id'),CODEX_CLIENT_ID);assert.equal(body.get('refresh_token'),'old-refresh-secret');
   return response();
 };
 let resolved=false;
 const pending=Promise.all(Array.from({length:4},()=>ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher}))).then(result=>{resolved=true;return result;});
 await new Promise(resolve=>setTimeout(resolve,20));
 assert.equal(fetchCalls,1);assert.equal(persistCalls,1);assert.equal(resolved,false);
 release();const results=await pending;
 assert.ok(results.every(result=>result.refreshToken==='rotated-refresh-secret'));
 assert.equal(creds.refreshToken,'rotated-refresh-secret');
 const secrets=['old-refresh-secret','rotated-refresh-secret','old-id-secret',jwt(now-1000),jwt(now+7200000),account];
 const redacted=redactProviderSecrets(secrets.join('|'),creds);
 assert.ok(secrets.every(secret=>!redacted.includes(secret)));
});

test('one cancelled waiter does not abandon a shared token rotation',async()=>{
 const creds=credentials();let release!:()=>void;let saves=0;
 registerCodexCredentialUpdater(creds,update=>{saves++;return update;});
 const gate=new Promise<void>(resolve=>release=resolve);
 const fetcher:typeof fetch=async()=>{await gate;return response();};
 const abort=new AbortController();
 const first=ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher,signal:abort.signal});
 const second=ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher});
 abort.abort();await assert.rejects(first,/cancelled/);release();
 assert.equal((await second).refreshToken,'rotated-refresh-secret');assert.equal(saves,1);
});

test('persistence failure prevents new credentials and latches failure without refresh replay',async()=>{
 const creds=credentials();let calls=0;
 registerCodexCredentialUpdater(creds,()=>{throw Error('raw private upstream secret');});
 const fetcher:typeof fetch=async()=>{calls++;return response();};
 for(let i=0;i<2;i++)await assert.rejects(ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher}),error=>{
   assert.ok(!String(error).includes('raw private'));return /securely completed/.test(String(error));
 });
 assert.equal(calls,1);assert.equal(creds.refreshToken,'old-refresh-secret');
 assert.equal(redactProviderSecrets('rotated-refresh-secret',creds),'[redacted]');
});

test('unavailable persistence blocks refresh before dispatch',async()=>{
 let calls=0;
 await assert.rejects(ensureCodexCredentials(credentials(),{now:()=>now,fetch:async()=>{calls++;return response();}}),/persistence/);
 assert.equal(calls,0);
});

test('failed and oversized refresh bodies are discarded, bounded and never retried',async()=>{
 for(const fixture of [()=>new Response('access-and-refresh-secret',{status:401}),()=>new Response('x'.repeat(32769)),()=>Response.json({...tokenData(),access_token:jwt(now+7200000,'unexpected-account')})]) {
   const creds=credentials();let calls=0,saves=0;
   registerCodexCredentialUpdater(creds,update=>{saves++;return update;});
   const fetcher:typeof fetch=async()=>{calls++;return fixture();};
   for(let i=0;i<2;i++)await assert.rejects(ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher}),error=>!String(error).includes('access-and-refresh-secret'));
   assert.equal(calls,1);assert.equal(saves,0);
 }
});


test('cancelled last waiter keeps refresh active until persistence settles and notifies host',async()=>{
 const creds=credentials();let release!:()=>void;const activity:boolean[]=[];
 const gate=new Promise<void>(resolve=>release=resolve);
 registerCodexCredentialUpdater(creds,async update=>{await gate;return update;},()=>activity.push(hasPendingCodexRefresh(creds)));
 const controller=new AbortController();
 const request=ensureCodexCredentials(creds,{now:()=>now,fetch:async()=>response(),signal:controller.signal});
 controller.abort();await assert.rejects(request,/cancelled/);
 assert.equal(hasPendingCodexRefresh(creds),true);assert.deepEqual(activity,[true]);
 release();await new Promise(resolve=>setTimeout(resolve,10));
 assert.equal(hasPendingCodexRefresh(creds),false);assert.deepEqual(activity,[true,false]);
 assert.equal(creds.refreshToken,'rotated-refresh-secret');
});


test('redaction covers raw and JSON-escaped credential values',()=>{
 const creds={apiKey:'api-"quoted\\key',refreshToken:'refresh-"private\\value',idToken:'id-"private\\value'};
 const serialized=JSON.stringify(creds);
 const redacted=redactProviderSecrets(serialized,creds);
 assert.deepEqual(JSON.parse(redacted),{apiKey:'[redacted]',refreshToken:'[redacted]',idToken:'[redacted]'});
 assert.equal(redactProviderSecrets(creds.refreshToken,creds),'[redacted]');
});


test('uncertain refresh network or transient HTTP failures stay latched without rotation replay',async()=>{
 for(const fail of [async()=>{throw new TypeError('fetch failed');},async()=>new Response('private refresh diagnostic',{status:503})]) {
   const creds=credentials();let calls=0,saves=0;
   registerCodexCredentialUpdater(creds,update=>{saves++;return update;});
   for(let attempt=0;attempt<3;attempt++)await assert.rejects(ensureCodexCredentials(creds,{now:()=>now,fetch:async()=>{calls++;return fail();}}),error=>{
     assert.ok(!String(error).includes('private refresh diagnostic'));return /sign in again/.test(String(error));
   });
   assert.equal(calls,1);assert.equal(saves,0);
 }
});

test('native voice-first rotation is adopted before dispatch and remembers both token generations for redaction',async()=>{
 const creds=credentials(),original=creds;let calls=0,saves=0;
 const saved={...credentials(now+7200000),refreshToken:'voice-rotated-refresh',idToken:'voice-rotated-id'};
 registerCodexCredentialUpdater(creds,update=>{saves++;return update;},undefined,()=>saved);
 const result=await ensureCodexCredentials(creds,{now:()=>now,fetch:async()=>{calls++;return response();}});
 assert.equal(creds,original);assert.equal(result.refreshToken,'voice-rotated-refresh');
 assert.equal(calls,0);assert.equal(saves,0);
 assert.equal(redactProviderSecrets('old-refresh-secret voice-rotated-refresh voice-rotated-id',creds),'[redacted] [redacted] [redacted]');
});

test('a concurrent native rotation recovers a rejected Pi refresh without another request or lost waiters',async()=>{
 const creds=credentials();let saved={...creds},calls=0,saves=0,release!:()=>void;
 const gate=new Promise<void>(resolve=>release=resolve);
 registerCodexCredentialUpdater(creds,update=>{saves++;return update;},undefined,()=>saved);
 const fetcher:typeof fetch=async()=>{calls++;await gate;return new Response('private rejected-refresh body',{status:401});};
 const controller=new AbortController();
 const cancelled=ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher,signal:controller.signal});
 const worker=ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher});
 controller.abort();await assert.rejects(cancelled,/cancelled/);
 saved={...credentials(now+7200000),refreshToken:'native-won-refresh'};release();
 assert.equal((await worker).refreshToken,'native-won-refresh');
 assert.equal(calls,1);assert.equal(saves,0);
 assert.equal((await ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher})).refreshToken,'native-won-refresh');
 assert.equal(calls,1);
});

test('a latched failure recovers only after a valid changed native token is securely stored',async()=>{
 const creds=credentials();let saved={...creds},calls=0;
 registerCodexCredentialUpdater(creds,update=>update,undefined,()=>saved);
 const fetcher:typeof fetch=async()=>{calls++;return new Response('',{status:401});};
 for(let attempt=0;attempt<2;attempt++)await assert.rejects(ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher}),/No retry/);
 assert.equal(calls,1);
 saved={...credentials(now+7200000),refreshToken:'recovered-native-refresh'};
 assert.equal((await ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher})).refreshToken,'recovered-native-refresh');
 assert.equal(calls,1);
});

test('saved rotations with another account, invalid claims, old expiry or unchanged refresh cannot bypass the failure latch',async()=>{
 for(const saved of [
   {...credentials(now+7200000),accessToken:jwt(now+7200000,'other-account'),accountId:'other-account',refreshToken:'different-refresh'},
   {...credentials(now+7200000),accessToken:'invalid-token',refreshToken:'different-refresh'},
   {...credentials(),refreshToken:'different-refresh'},
   credentials(now+7200000),
 ]) {
   const creds=credentials();let calls=0;
   registerCodexCredentialUpdater(creds,update=>update,undefined,()=>saved);
   const fetcher:typeof fetch=async()=>{calls++;return new Response('',{status:401});};
   for(let attempt=0;attempt<2;attempt++)await assert.rejects(ensureCodexCredentials(creds,{now:()=>now,fetch:fetcher}),/No retry/);
   assert.equal(calls,1);assert.equal(creds.refreshToken,'old-refresh-secret');
 }
});
