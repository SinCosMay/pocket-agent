import { AgentRegistry } from '../vendor/pi9/agents.js';
import { SubagentRuntime } from '../vendor/pi9/runtime.js';
import { completedGeneration,errorGeneration,interruptedGeneration,generationKey } from '../vendor/pi9/conversation.js';
import { MobileSession } from './session.js';
import { nativeTools,subagentTool } from './tools.js';
import { messageText } from './scripted.js';
import { modelFor } from './provider.js';

const MAIN_PROMPT=`You are the user's persistent phone assistant. Delegate substantial multi-step phone navigation, screenshots and computer use promptly to phone-operator using subagent spawn. Give a concise assignment, relevant facts, outcome and verification criteria. End your turn after async dispatch, remain available and let the host deliver completion. Do not poll workers or block main with join on unfinished work. You may do a genuinely short lookup directly. Never claim requested/dispatched actions prove outcomes. Read phone_capabilities before mutation. Use its operation_prefix plus an underscore and a unique nonce for each mutation; inspect phone_operation after uncertain outcomes and reuse the same ID/arguments. Prefer direct APIs, then app actions, accessibility, screenshot fallback. Release screen after work. Main Stop does not stop workers.`;
const WORKER_PROMPT=`You are a bounded phone operator worker. Complete the assignment using the granted direct native tools, verify actual outcome, report evidence and uncertainties. Read phone_capabilities first. Never invent unavailable permissions or task success. Prefer APIs, then app actions, accessibility and image/gesture fallback. Use fresh observations and honor screen leases. Mutations require operation_prefix plus underscore and unique nonce; preserve operation IDs across uncertain retries and inspect receipts before repeating. Recursive delegation is available with a shared concurrency cap. Join child dependencies, and return concise verified results. If interrupted after process death inspect app state/receipts before any side-effect replay.`;
const stripImages=(messages:any[])=>messages.map(m=>({...m,content:Array.isArray(m.content)?m.content.map((c:any)=>c.type==='image'?{type:'text',text:'[Previous screenshot omitted; observe again for current state]'}:c):m.content}));
const textMessages=(messages:any[])=>messages.filter(m=>m.role==='user'||m.role==='assistant').map((m,i)=>({id:`${m.timestamp}-${i}`,role:m.role,text:messageText(m),timestamp:m.timestamp})).filter(m=>m.text);
const normalizeMode=(mode:string)=>mode==='api-key'?'openai':mode==='codex-relay'||mode==='codexRelay'?'codex':mode;

export interface NativeBridge {call(method:string,argsJson:string):string;emit(eventJson:string):void}
export class PocketRuntime {
  readonly registry=new AgentRegistry();
  readonly runtime:SubagentRuntime;
  readonly sessions=new Map<string,MobileSession>();
  main!:MobileSession;
  config:any={mode:'scripted'};
  credentials:any={};
  context:any;
  busy=false;
  mainInterrupted=false;
  private inbox:any[]=[];
  private delivered=new Set<string>();
  private mainTurns=0;
  private pendingSave?:ReturnType<typeof setTimeout>;
  private restoring=true;
  private pumping=false;
  private stoppingAll=false;
  private mainPaused=false;
  readonly reportError=(e:any)=>this.emit({type:'error',error:String(e?.message??e)});
  constructor(readonly native:NativeBridge,limits={maxExecuting:2,maxConversations:12}) {
    const phoneTools=nativeTools(this,'placeholder').map(x=>x.name);
    this.registry.agents.set('phone-operator',{name:'phone-operator',description:'Performs bounded Android workflows and verifies native outcomes.',source:'project',systemPrompt:WORKER_PROMPT,tools:[...phoneTools,'subagent']});
    this.registry.agents.set('researcher',{name:'researcher',description:'Reads phone capabilities and app data with no screen mutations.',source:'project',systemPrompt:'Read-only bounded worker. Report evidence and uncertainty.',tools:['phone_capabilities','phone_apps','phone_contacts','phone_calendars','phone_notifications','phone_operation','subagent']});
    this.reloadCredentials();
    this.runtime=new SubagentRuntime(this.registry,limits.maxExecuting,this.execute as any,limits.maxConversations);
    const saved=this.call('store.load',{}) ?? {};
    this.config={...this.config,...saved.config};
    // Fresh platform settings override old public runtime configuration.
    if(this.credentials.authMode) this.config.mode=normalizeMode(this.credentials.authMode);
    if(this.credentials.endpoint) this.config.endpoint=this.credentials.endpoint;
    if(this.credentials.model) this.config.model=this.credentials.model;
    this.context={cwd:'.',model:modelFor(this.config,this.credentials),modelRegistry:{}};
    this.inbox=saved.inbox??[];this.delivered=new Set(saved.delivered??[]);this.mainTurns=saved.mainTurns??0;
    this.mainInterrupted=Boolean(saved.busy);
    this.mainPaused=Boolean(saved.mainPaused);
    this.main=this.createSession('main',undefined,saved.mainMessages??[]);
    for(const snapshot of saved.workers??[]) {
      try {
        const session=this.createSession(snapshot.conversationId,undefined,saved.workerMessages?.[snapshot.conversationId]??[]);
        this.runtime.restoreCheckpoint(snapshot,session as any);
        // Restore the recursive tool owner now the retained Conversation exists.
        const owner=this.runtime.generationCaller({conversationId:snapshot.conversationId,generation:snapshot.generations.at(-1).generation}).conversation;
        this.configureSessionTools(session,owner);
        this.sessions.set(snapshot.conversationId,session);
      } catch(e){this.reportError(`Saved worker could not be restored: ${e}`);}
    }
    this.runtime.onConversationUpdate((conversation,kind)=>{
      if(this.restoring)return;
      const g=conversation.latestGeneration;
      if(g.state.kind==='done' && !conversation.parentConversationId && this.runtime.isModelSubscribed({conversationId:conversation.conversationId,generation:g.number}) && !g.receipts.model) this.enqueueCompletion(conversation.conversationId,g.number);
      if(kind==='status'||kind==='collection'||kind==='removed') {this.save();this.publish();}
      else this.coalesce();
    });
    this.restoring=false;
    // Repair completion inbox if death happened between terminal checkpoint and enqueue.
    for(const c of this.runtime.listConversations()) {
      const g=c.generations.at(-1)!;
      if(!c.parentConversationId && g.status.kind==='done' && g.initiatedBy==='model'&&!g.receipts.model)this.enqueueCompletion(c.conversationId,g.generation);
    }
    this.save();this.publish();this.emit({type:'ready',protocol:1});
    queueMicrotask(()=>void this.pump());
  }
  call(method:string,args:any):any {
    const result=JSON.parse(this.native.call(method,JSON.stringify(args)));
    if(!result.ok)throw new Error(result.error?.message??result.error??`${method} failed`);
    return result.result;
  }
  emit(event:any):void {this.native.emit(JSON.stringify(event));}
  private reloadCredentials():void {
    this.credentials=this.call('credentials.get',{})??{};
    this.config={mode:normalizeMode(this.credentials.authMode??'scripted'),endpoint:this.credentials.endpoint,model:this.credentials.model};
  }
  private configureSessionTools(session:MobileSession,parent?:any):void {
    const grant=parent?.definition.tools;
    session.agent.state.tools=[...nativeTools(this,session.owner,grant),...(!grant||grant.includes('subagent')?[subagentTool(this,parent)]:[])];
  }
  private createSession(owner:string,parent?:any,messages:any[]=[]):MobileSession {
    const session=new MobileSession(owner,this.config,this.credentials,[],messages,parent?.definition.systemPrompt??(owner==='main'?MAIN_PROMPT:WORKER_PROMPT));
    this.configureSessionTools(session,parent);
    session.subscribe(event=>{
      if(event.type==='message_end')this.coalesce();
      if(event.type==='tool_execution_start')this.emit({type:'log',owner,tool:event.toolName,message:'Tool started'});
    });
    return session;
  }
  private execute=async(_ctx:any,conversation:any,generation:any,signal?:AbortSignal):Promise<any>=>{
    let session:MobileSession|undefined;
    try {
      session=generation.kind==='resume'?conversation.sessionForResume():this.createSession(conversation.conversationId,conversation);
      if(!session)throw new Error('Missing saved worker session');
      this.configureSessionTools(session,conversation);this.sessions.set(conversation.conversationId,session);
      conversation.bindSession(generation,session);
      conversation.setEffectiveConfig({cwd:'.',model:this.context.model.id,skills:[],tools:session.agent.state.tools.map((x:any)=>x.name)});
      const abort=()=>void session?.abort();signal?.addEventListener('abort',abort,{once:true});
      try {await session.prompt(generation.prompt);} finally {signal?.removeEventListener('abort',abort);}
      const last=session.messages.at(-1);
      if(last?.stopReason==='aborted')return interruptedGeneration(conversation,generation,'Worker request aborted');
      if(last?.stopReason==='error')return errorGeneration(conversation,generation,last.errorMessage??'Provider error');
      return completedGeneration(conversation,generation,messageText(last));
    } catch(e){return errorGeneration(conversation,generation,String(e));}
    finally {this.releaseScreen(conversation.conversationId);this.save();this.publish();}
  };
  private releaseScreen(owner:string):void {try{this.call('tool',{name:'phone_screen',args:{action:'release'},owner});}catch{/* Missing lease is harmless. */}}
  private enqueueCompletion(conversationId:string,generation:number):void {
    const key=generationKey({conversationId:conversationId as any,generation});
    if(this.delivered.has(key)||this.inbox.some(x=>x.key===key))return;
    this.inbox.push({type:'completion',key,conversationId,generation});
    this.save();queueMicrotask(()=>void this.pump());
  }
  private async pump():Promise<void> {
    if(this.pumping||this.stoppingAll||this.mainPaused)return;
    this.pumping=true;
    try {
      while(this.inbox.length&&!this.stoppingAll&&!this.mainPaused) {
        const item=this.inbox.shift();
        let prompt=item.text;
        if(item.type==='completion') {
          const c=this.runtime.generationCaller(item).conversation,g=c.generation(item.generation)!;
          // Persist transcript delivery + exact-generation model receipt atomically before inference.
          prompt=`[Worker completion] ${item.conversationId} generation ${item.generation}: ${JSON.stringify(g.state)}`;
          this.delivered.add(item.key);c.markCollected(g,'model');
        }
        this.busy=true;this.mainInterrupted=false;this.mainTurns++;
        // Agent.prompt inserts user message synchronously before its first async boundary.
        const pending=this.main.prompt(prompt);this.save();this.publish();
        try {await pending;}catch(e){this.reportError(e);}
        finally {this.busy=false;this.releaseScreen('main');this.save();this.publish();}
      }
    } catch(e){this.reportError(e);}
    finally {this.pumping=false;this.publish();}
  }
  dispatch(commandJson:string):string {
    try {
      const cmd=JSON.parse(commandJson);
      if(!cmd||typeof cmd.action!=='string')throw new Error('Missing action');
      if(cmd.action==='send') {
        if(typeof cmd.text!=='string'||!cmd.text.trim()||cmd.text.length>50000)throw new Error('Message must contain 1..50000 characters');
        if(cmd.conversationId) void this.sendWorker(cmd.conversationId,cmd.text).catch(this.reportError);
        else {this.mainPaused=false;this.inbox.push({type:'user',text:cmd.text});this.save();void this.pump();}
      } else if(cmd.action==='stop_main') {this.main.agent.abort();}
      else if(cmd.action==='stop_all')void this.stopAll().catch(this.reportError);
      else if(cmd.action==='cancel_worker')void this.runtime.cancelSubagent(cmd.conversationId).catch(this.reportError);
      else if(cmd.action==='steer_worker')void this.runtime.steerSubagent(cmd.conversationId,cmd.text,undefined,'user').catch(this.reportError);
      else if(cmd.action==='inspect_worker') {
        const snapshot=this.runtime.conversation(cmd.conversationId);
        if(!snapshot.parentConversationId)this.runtime.collectSubagentForUser(cmd.conversationId);
        this.emit({type:'worker',conversationId:cmd.conversationId,snapshot,messages:textMessages(this.sessions.get(cmd.conversationId)?.messages??[])});
      } else if(cmd.action==='configure') {
        if(this.busy||this.runtime.listConversations().some(c=>c.currentGeneration))throw new Error('Stop active work before changing providers');
        this.reloadCredentials();
        if(cmd.config)this.config={...this.config,...{mode:cmd.config.mode??this.config.mode,model:cmd.config.model??this.config.model,endpoint:cmd.config.endpoint??this.config.endpoint}};
        this.context.model=modelFor(this.config,this.credentials);
        this.main=this.createSession('main',undefined,this.main.messages);
        // Existing sessions keep transcript but use fresh shared provider settings.
        for(const c of this.runtime.listConversations()) {
          const session=this.sessions.get(c.conversationId)!;
          session.agent.streamFunction=this.main.agent.streamFunction;
          session.agent.state.model=this.context.model;
        }
      } else if(cmd.action!=='state')throw new Error(`Unknown action ${cmd.action}`);
      this.save();this.publish();return JSON.stringify({ok:true,accepted:true});
    } catch(e){return JSON.stringify({ok:false,error:String((e as any)?.message??e)});}
  }
  private async sendWorker(id:string,text:string):Promise<void> {
    const c=this.runtime.conversation(id);
    if(c.currentGeneration) {await this.runtime.steerSubagent(id as any,text,undefined,'user');return;}
    this.runtime.collectSubagentForUser(id as any);
    // User may follow up once model-initiated completion was delivered/collected.
    const result=this.runtime.startTasks(this.context,[{kind:'resume',subagentId:id as any,prompt:text}],{initiatedBy:'user'});
    if(!result.starts[0]?.ok)throw new Error((result.starts[0] as any)?.error??'Resume rejected');
    this.save();this.publish();
  }
  private async stopAll():Promise<void> {
    this.stoppingAll=true;this.mainPaused=true;this.inbox=[];this.main.agent.abort();
    // Child ownership cancellation is explicit through each owning caller.
    await Promise.allSettled(this.runtime.listConversations().filter(c=>c.currentGeneration).map(c=>{
      const parent=c.parentConversationId?this.runtime.generationCaller({conversationId:c.parentConversationId,generation:this.runtime.conversation(c.parentConversationId).generations.at(-1)!.generation}):undefined;
      return this.runtime.cancelSubagent(c.conversationId,parent);
    }));
    this.stoppingAll=false;this.save();this.publish();
  }
  private coalesce():void {
    if(this.pendingSave||this.restoring)return;
    this.pendingSave=setTimeout(()=>{this.pendingSave=undefined;this.save();this.publish();},120);
  }
  save():void {
    if(this.restoring)return;
    const workerMessages:any={};
    for(const [id,s] of this.sessions)workerMessages[id]=stripImages(s.messages);
    this.call('store.save',{state:{version:1,config:this.config,busy:this.busy,mainPaused:this.mainPaused,mainMessages:stripImages(this.main.messages),workers:this.runtime.listConversations(),workerMessages,inbox:this.inbox,delivered:[...this.delivered],mainTurns:this.mainTurns}});
  }
  state():any {
    const tasks=this.runtime.listConversations().map(c=>{const g=c.generations.at(-1)!;return {...c,generation:g.generation,status:g.status.kind==='done'?({error:'failed',aborted:'cancelled',skipped:'cancelled'} as any)[g.status.outcome]??g.status.outcome:g.status.kind,output:g.status.kind==='done'?g.status.output:undefined};});
    return {messages:textMessages(this.main.messages),tasks,busy:this.busy,active:this.busy||tasks.some(t=>t.currentGeneration)||(!this.mainPaused&&this.inbox.length>0)||Boolean(this.pendingSave),mainPaused:this.mainPaused,mainInterrupted:this.mainInterrupted,provider:{...this.config},mainTurns:this.mainTurns,
      workerMessages:Object.fromEntries([...this.sessions].map(([id,s])=>[id,textMessages(s.messages)]))};
  }
  publish():void {if(!this.restoring){const state=this.state();this.emit({type:'state',state});this.emit({type:'activity',active:state.active});}}
}
