import { AssistantMessageEventStream } from '@earendil-works/pi-ai';
import type { StreamFn } from '@earendil-works/pi-agent-core';

export const SCRIPT_MODEL: any = {id:'scripted',name:'Scripted fixture',api:'pocket-scripted',provider:'pocket',baseUrl:'',reasoning:false,input:['text'],cost:{input:0,output:0,cacheRead:0,cacheWrite:0},contextWindow:32768,maxTokens:2048};
const usage = () => ({input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{input:0,output:0,cacheRead:0,cacheWrite:0,total:0}});
export const messageText = (m:any):string => typeof m?.content === 'string' ? m.content : (m?.content ?? []).filter((x:any)=>x.type==='text').map((x:any)=>x.text).join('\n');
export function scriptedStream(owner='main'): StreamFn {
  return ((model:any, context:any, options:any={}) => {
    const stream = new AssistantMessageEventStream();
    const m:any={role:'assistant',content:[],api:model.api,provider:model.provider,model:model.id,usage:usage(),stopReason:'stop',timestamp:Date.now()};
    queueMicrotask(async()=>{
      try {
        const last=context.messages.filter((x:any)=>x.role!=='system').at(-1), text=messageText(last);
        let answer=''; let call:any;
        const fixture=owner!=='main' && !/nested/i.test(context.messages.find((m:any)=>m.role==='user') ? messageText(context.messages.find((m:any)=>m.role==='user')) : '')
          ? await fixtureStep(context,owner,options.signal):undefined;
        if(fixture) {call=fixture.call;answer=fixture.answer??'';}
        else if (last?.role==='toolResult') {
          if(last.toolName==='subagent' && owner==='main') answer='I delegated the task. You can keep talking to me while the worker runs.';
          else if(last.toolName==='subagent' && text.includes('"action":"spawn"')) {
            const result=JSON.parse(text); const ids=result.results.filter((x:any)=>x.ok).map((x:any)=>x.conversationId);
            call={name:'subagent',arguments:{action:'join',subagentIds:ids}};
          } else answer=`${owner==='main'?'Tool result':'Verified worker result'}: ${text.slice(0,3000)}`;
        } else if(text.startsWith('[Worker completion]')) answer=`Worker finished: ${text.slice(20)}`;
        else if(owner==='main' && /\b(delegate|long|worker|fixture|nested)\b/i.test(text)) {
          call={name:'subagent',arguments:{action:'spawn',spawns:[{agent:'phone-operator',label:'Complete phone fixture',prompt:text}]}};
        } else if(owner!=='main' && /nested/i.test(text) && !text.startsWith('Leaf:')) {
          call={name:'subagent',arguments:{action:'spawn',spawns:[{agent:'phone-operator',label:'Nested fixture child',prompt:'Leaf: observe phone capabilities'}]}};
        } else if(owner!=='main' || /capabilities|probe/i.test(text)) {
          const wait=Number(text.match(/delay[=: ](\d+)/i)?.[1] ?? (owner==='main'?0:300));
          await delay(Math.min(wait,30000),options.signal);
          call={name:'phone_capabilities',arguments:{}};
        } else answer=`Main is responsive: ${text}`;
        if(options.signal?.aborted) throw new Error('Aborted');
        stream.push({type:'start',partial:m});
        if(call) {
          m.content=[{type:'toolCall',id:`script-${owner}-${Date.now()}`, ...call}];m.stopReason='toolUse';
        } else {
          m.content=[{type:'text',text:answer}];
          stream.push({type:'text_start',contentIndex:0,partial:m});
          stream.push({type:'text_delta',contentIndex:0,delta:answer,partial:m});
          stream.push({type:'text_end',contentIndex:0,content:answer,partial:m});
        }
        stream.push({type:'done',reason:m.stopReason,message:m}); stream.end();
      } catch(e) {m.stopReason=options.signal?.aborted?'aborted':'error';m.errorMessage=String(e);stream.push({type:'error',reason:m.stopReason,error:m});stream.end();}
    });
    return stream;
  }) as StreamFn;
}

/** Deterministic fixture model still traverses the real Pi tool loop and native bridge. */
async function fixtureStep(context:any,owner:string,signal?:AbortSignal):Promise<any> {
 const index=context.messages.findLastIndex((m:any)=>m.role==='user' && /fixture/i.test(messageText(m)));
 if(index<0)return;
 const scope=context.messages.slice(index),task=messageText(scope[0]);
 const tools=scope.filter((m:any)=>m.role==='toolResult');
 if(scope.some((m:any)=>m.role==='assistant'&&/Verified fixture outcome:|Fixture failed:/.test(messageText(m))))return;
 const last=tools.at(-1),data=last?JSON.parse(messageText(last)):undefined;
 const call=(name:string,args:any)=>({call:{name,arguments:args}});
 if(data?.isError)return {answer:`Fixture failed: ${JSON.stringify(data)}`};
 const caps=tools.find((m:any)=>m.toolName==='phone_capabilities');
 if(!caps) {await delay(Math.min(Number(task.match(/delay[=: ](\d+)/i)?.[1]??300),30000),signal);return call('phone_capabilities',{});}
 const prefix=JSON.parse(messageText(caps)).operation_prefix;
 if(!prefix)return {answer:'Fixture failed: capabilities did not provide an operation prefix.'};
 const nonce=`${owner.replaceAll('-','').slice(0,12)}${scope[0].timestamp}`;
 const op=(kind:string)=>`${prefix}_${nonce}${kind}`;
 if(!tools.some((m:any)=>m.toolName==='phone_batch'))return call('phone_batch',{calls:[{name:'phone_apps',args:{}},{name:'phone_capabilities',args:{}}]});
 if(!tools.some((m:any)=>m.toolName==='phone_open_app'))return call('phone_open_app',{package_name:'dev.pocket.agent.fixture',operation_id:op('open')});
 const acts=tools.filter((m:any)=>m.toolName==='phone_act');
 if(last.toolName==='phone_open_app'||last.toolName==='phone_act'){await delay(250,signal);return call('phone_observe',{max_nodes:150});}
 if(last.toolName!=='phone_observe')return {answer:'Fixture failed: unexpected tool sequence.'};
 if(data.package_name!=='dev.pocket.agent.fixture')return {answer:`Fixture failed: foreground package was ${data.package_name??'unavailable'}`};
 if(!acts.length) {
  if(!data.nodes?.some((n:any)=>n.description==='AGENT_INPUT'))return {answer:'Fixture failed: input selector missing.'};
  return call('phone_act',{kind:'type',snapshot_id:data.snapshot_id,selector:{description:'AGENT_INPUT'},text:'PocketWorks',replace:true,operation_id:op('type')});
 }
 if(acts.length===1)return call('phone_act',{kind:'click',snapshot_id:data.snapshot_id,selector:{text:'AGENT_CLICK'},operation_id:op('click')});
 const verified=data.nodes?.some((n:any)=>n.text==='STATUS_CLICKED:PocketWorks COUNT:1');
 return {answer:verified?'Verified fixture outcome: STATUS_CLICKED:PocketWorks COUNT:1':`Fixture failed: expected status absent; observed ${JSON.stringify(data.nodes)}`};
}
export function delay(ms:number,signal?:AbortSignal):Promise<void> {
  return new Promise((resolve,reject)=>{
    const abort=()=>{clearTimeout(timer);signal?.removeEventListener('abort',abort);reject(new Error('Aborted'));};
    const timer=setTimeout(()=>{signal?.removeEventListener('abort',abort);resolve();},ms);
    if(signal?.aborted) abort();else signal?.addEventListener('abort',abort,{once:true});
  });
}
