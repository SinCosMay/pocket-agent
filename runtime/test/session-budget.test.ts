import test from 'node:test';
import assert from 'node:assert/strict';
import {MOBILE_SESSION_BUDGET,MobileSession} from '../src/session.js';

const session=()=>new MobileSession('test-operator',{mode:'scripted'},{},[]);

test('long workflows exceed former turn cap and stop at the new generation cap',()=>{
  const s=session(),finish=(s.agent as any).finishTurn;
  assert.equal(MOBILE_SESSION_BUDGET.turnsPerGeneration,200);
  for(let i=0;i<MOBILE_SESSION_BUDGET.turnsPerGeneration;i++)assert.equal(finish(),undefined);
  assert.throws(()=>finish(),/Mobile generation turn budget reached/);
});

test('tool budget supports hundreds of calls and remains bounded',async()=>{
  const s=session(),before=(s.agent as any).beforeToolCall;
  assert.equal(MOBILE_SESSION_BUDGET.toolCallsPerGeneration,800);
  for(let i=0;i<MOBILE_SESSION_BUDGET.toolCallsPerGeneration;i++)assert.equal(await before(),undefined);
  await assert.rejects(async()=>before(),/Mobile tool-call budget reached/);
});

test('saved transcript survives old message and byte limits, but enforces new caps',()=>{
  const s=session();
  s.agent.state.messages.push(...Array.from({length:300},(_,i)=>({role:'user',content:[{type:'text',text:'x'}],timestamp:i} as any)));
  s.agent.state.messages.push({role:'user',content:[{type:'text',text:'x'.repeat(1_100_000)}],timestamp:1} as any);
  assert.equal(s.full(),false,'old 256-message / 1-million-character limits must no longer block');
  while(s.messages.length<MOBILE_SESSION_BUDGET.maxMessages-1)s.agent.state.messages.push({role:'user',content:[{type:'text',text:'x'}],timestamp:2} as any);
  assert.equal(s.full(),false);
  s.agent.state.messages.push({role:'user',content:[{type:'text',text:'x'}],timestamp:3} as any);
  assert.equal(s.full(),true,'new count cap must still apply');
  s.agent.state.messages.length=0;
  s.agent.state.messages.push({role:'user',content:[{type:'text',text:'x'.repeat(MOBILE_SESSION_BUDGET.maxSerializedChars)}],timestamp:4} as any);
  assert.equal(s.full(),true,'new byte cap must still apply');
});
