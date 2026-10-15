import { AgentRegistry } from '../vendor/pi9/agents.js';
import { SubagentRuntime } from '../vendor/pi9/runtime.js';
import { completedGeneration,errorGeneration,interruptedGeneration,generationKey } from '../vendor/pi9/conversation.js';
import { MobileSession } from './session.js';
import { nativeTools,subagentTool } from './tools.js';
import { messageText } from './scripted.js';
import { modelFor,makeStream } from './provider.js';

const MAIN_PROMPT=`You are the user's persistent phone assistant. Delegate substantial multi-step phone navigation, screenshots and computer use promptly to phone-operator using subagent spawn. Give a concise assignment, relevant facts, outcome and verification criteria. End your turn after async dispatch, remain available and let the host deliver completion. Do not poll workers or block main with join on unfinished work. You may do a genuinely short lookup directly. Never claim requested/dispatched actions prove outcomes. Read phone_capabilities before mutation. Use its operation_prefix plus an underscore and a unique nonce for each mutation; inspect phone_operation after uncertain outcomes and reuse the same ID/arguments. Prefer direct APIs, then app actions, accessibility, screenshot fallback. Release screen after work. Main Stop does not stop workers.`;
const WORKER_PROMPT=`You are a bounded phone operator worker. Complete the assignment using the granted direct native tools, verify actual outcome, report evidence and uncertainties. Read phone_capabilities first. Never invent unavailable permissions or task success. Prefer APIs, then app actions, accessibility and image/gesture fallback. Use fresh observations and honor screen leases. Mutations require operation_prefix plus underscore and unique nonce; preserve operation IDs across uncertain retries and inspect receipts before repeating. Recursive delegation is available with a shared concurrency cap. Join child dependencies, and return concise verified results. If interrupted after process death inspect app state/receipts before any side-effect replay.`;
const stripImages=(messages:any[])=>messages.map(m=>({...m,content:Array.isArray(m.content)?m.content.map((c:any)=>c.type==='image'?{type:'text',text:'[Previous screenshot omitted; observe again for current state]'}:c):m.content}));
const textMessages=(messages:any[],sanitize=(text:string)=>text)=>messages.filter(m=>m.role==='user'||m.role==='assistant').map((m,i)=>({id:`${m.timestamp}-${i}`,role:m.role,text:messageText(m)||(m.stopReason==='error'?`Request failed: ${sanitize(m.errorMessage??'Provider request failed')}`:''),timestamp:m.timestamp,...(m.stopReason?{stopReason:m.stopReason}:{}),...(m.errorMessage?{error:sanitize(m.errorMessage)}:{})})).filter(m=>m.text);
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
  lastError:string|null=null;
  private inbox:any[]=[];
  private delivered=new Set<string>();
  private mainTurns=0;
  private pendingSave?:ReturnType<typeof setTimeout>;
  private restoring=true;
  private pumping=false;
  private stoppingAll=false;
  private mainPaused=false;
  private totalStarts=0;
  private deliveryTransaction=false;
  private sanitize=(text:string)=>text.replaceAll(this.credentials.apiKey||'\0','[redacted]').slice(0,2000);
  readonly reportError=(e:any)=>{this.lastError=this.sanitize(String(e?.message??e));this.emit({type:'error',error:this.lastError});};
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
    this.totalStarts=saved.totalStarts??0;
    this.lastError=saved.lastError?this.sanitize(String(saved.lastError)):null;
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
      if(this.restoring||this.deliveryTransaction)return;
      const g=conversation.latestGeneration;
      if(g.state.kind==='done' && !conversation.parentConversationId && this.runtime.isModelSubscribed({conversationId:conversation.conversationId,generation:g.number}) && !g.receipts.model) this.enqueueCompletion(conversation.conversationId,g.number);
      if(kind==='status'||kind==='collection'||kind==='removed') {this.save();this.publish();}
      else this.coalesce();
    });
    this.restoring=false;
    // Repair completion inbox if death happened between terminal checkpoint and enqueue.
    for(const c of this.runtime.listConversations()) {
      const g=c.generations.at(-1)!;
      if(!c.parentConversationId && g.status.kind==='done' && this.runtime.isModelSubscribed({conversationId:c.conversationId,generation:g.generation})&&!g.receipts.model)this.enqueueCompletion(c.conversationId,g.generation);
    }
    this.save();this.publish();this.emit({type:'ready',protocol:1});
    queueMicrotask(()=>void this.pump());
  }
  call(method:string,args:any):any {
    const serialized=JSON.stringify(args);
    const result=JSON.parse(this.native.call(method,method==='store.save'?serialized.replaceAll(this.credentials.apiKey||'\0','[redacted]'):serialized));
    if(!result.ok)throw new Error(result.error?.message??result.error??`${method} failed`);
    return result.result;
  }
  emit(event:any):void {this.native.emit(JSON.stringify(event).replaceAll(this.credentials.apiKey||'\0','[redacted]'));}
  private reloadCredentials():void {
    this.credentials=this.call('credentials.get',{})??{};
    this.config={mode:normalizeMode(this.credentials.authMode??'scripted'),endpoint:this.credentials.endpoint,model:this.credentials.model};
  }
  private configureSessionTools(session:MobileSession,parent?:any):void {
    const grant=parent?.requestedConfig.tools??parent?.definition.tools;
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
      if(last?.stopReason==='error')return errorGeneration(conversation,generation,this.sanitize(last.errorMessage??'Provider error'));
      return completedGeneration(conversation,generation,messageText(last));
    } catch(e){return errorGeneration(conversation,generation,this.sanitize(String(e)));}
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
        if(this.main.full()){this.mainPaused=true;this.reportError('Main conversation reached the mobile context limit; pending results and history were retained.');this.save();break;}
        this.deliveryTransaction=true;
        const item=this.inbox.shift();
        let prompt=item.text;
        if(item.type==='completion') {
          const c=this.runtime.generationCaller(item).conversation,g=c.generation(item.generation)!;
          // Persist transcript delivery + exact-generation model receipt atomically before inference.
          prompt=`[Worker completion] ${item.conversationId} generation ${item.generation}: ${JSON.stringify(g.state)}`;
          this.delivered.add(item.key);c.markCollected(g,'model');
        }
        this.busy=true;this.mainInterrupted=false;this.lastError=null;this.mainTurns++;
        this.main.agent.state.messages=[...this.main.messages,{role:'user',content:[{type:'text',text:prompt}],timestamp:Date.now()}];
        this.deliveryTransaction=false;
        // Delivery transcript, receipt and queue removal are one native store checkpoint.
        this.save();this.publish();const pending=this.main.continue();
        try {
          await pending;
          const last=this.main.messages.at(-1);
          if(last?.role==='assistant'&&last.stopReason==='error')this.reportError(last.errorMessage??'Provider request failed');
        }catch(e){this.reportError(e);}
        finally {this.busy=false;this.releaseScreen('main');this.save();this.publish();}
      }
    } catch(e){this.reportError(e);}
    finally {this.deliveryTransaction=false;this.pumping=false;this.publish();}
  }
  dispatch(commandJson:string):string {
    try {
      const cmd=JSON.parse(commandJson);
      if(!cmd||typeof cmd.action!=='string')throw new Error('Missing action');
      if(cmd.action==='send') {
        if(typeof cmd.text!=='string'||!cmd.text.trim()||cmd.text.length>50000)throw new Error('Message must contain 1..50000 characters');
        if(this.inbox.length>=32)throw new Error('Pending message limit reached (32)');
        if(!cmd.conversationId&&this.main.full())throw new Error('Main conversation is at the mobile context limit; history and pending results were retained');
        if(cmd.conversationId) void this.sendWorker(cmd.conversationId,cmd.text).catch(this.reportError);
        else {this.mainPaused=false;this.inbox.push({type:'user',text:cmd.text});this.save();void this.pump();}
      } else if(cmd.action==='stop_main') {this.main.agent.abort();}
      else if(cmd.action==='stop_all')void this.stopAll().catch(this.reportError);
      else if(cmd.action==='cancel_worker')void this.runtime.cancelSubagent(cmd.conversationId,this.hostCaller(cmd.conversationId)).catch(this.reportError);
      else if(cmd.action==='steer_worker')void this.steer(cmd.conversationId,cmd.text,this.hostCaller(cmd.conversationId),'user').catch(this.reportError);
      else if(cmd.action==='inspect_worker') {
        const snapshot=this.runtime.conversation(cmd.conversationId);
        const own=this.runtime.generationCaller({conversationId:cmd.conversationId,generation:snapshot.generations.at(-1)!.generation});
        if(own.generation.state.kind==='done')own.conversation.markCollected(own.generation,'user');
        this.emit({type:'worker',conversationId:cmd.conversationId,snapshot,messages:textMessages(this.sessions.get(cmd.conversationId)?.messages??[],this.sanitize)});
      } else if(cmd.action==='configure') {
        if(this.busy||this.runtime.listConversations().some(c=>c.currentGeneration))throw new Error('Stop active work before changing providers');
        this.reloadCredentials();
        if(cmd.config)this.config={...this.config,...{mode:cmd.config.mode??this.config.mode,model:cmd.config.model??this.config.model,endpoint:cmd.config.endpoint??this.config.endpoint}};
        this.context.model=modelFor(this.config,this.credentials);
        this.main=this.createSession('main',undefined,this.main.messages);
        // Existing sessions keep transcript but use fresh shared provider settings.
        for(const c of this.runtime.listConversations()) {
          const session=this.sessions.get(c.conversationId)!;
          session.agent.streamFunction=makeStream(this.config,this.credentials,session.owner);
          session.agent.state.model=this.context.model;
        }
      } else if(cmd.action!=='state')throw new Error(`Unknown action ${cmd.action}`);
      this.save();this.publish();return JSON.stringify({ok:true,accepted:true});
    } catch(e){return JSON.stringify({ok:false,error:this.sanitize(String((e as any)?.message??e))});}
  }
  private async sendWorker(id:string,text:string):Promise<void> {
    const c=this.runtime.conversation(id);
    const caller=this.hostCaller(id);
    if(c.currentGeneration) {await this.steer(id,text,caller,'user');return;}
    const own=this.runtime.generationCaller({conversationId:id as any,generation:c.generations.at(-1)!.generation});
    own.conversation.markCollected(own.generation,'user');
    // User may follow up once model-initiated completion was delivered/collected.
    const result=this.startTasks([{kind:'resume',subagentId:id as any,prompt:text}],{initiatedBy:'user',caller});
    if(!result.starts[0]?.ok)throw new Error((result.starts[0] as any)?.error??'Resume rejected');
    this.save();this.publish();
  }
  startTasks(tasks:any[],options:any):any {
    if(this.totalStarts+tasks.length>1000)throw new Error('Mobile lifetime task budget reached (1000); retained work was preserved');
    for(const task of tasks) {
      if(task.kind==='resume'&&this.runtime.conversation(task.subagentId).generations.length>=20)throw new Error('Worker generation budget reached (20); history was retained');
    }
    this.totalStarts+=tasks.length;
    const result=this.runtime.startTasks(this.context,tasks,options);
    return result;
  }
  async steer(id:string,text:string,caller?:any,initiatedBy:any='model'):Promise<any> {
    if(typeof text!=='string'||!text.trim()||text.length>50000)throw new Error('Steer text must contain 1..50000 characters');
    const c=this.runtime.conversation(id);
    if(c.generations.at(-1)!.steers.length>=32)throw new Error('Generation steering budget reached (32 messages)');
    return this.runtime.steerSubagent(id as any,text,caller,initiatedBy);
  }
  private hostCaller(id:string):any {
    const parentId=this.runtime.conversation(id).parentConversationId;
    return parentId?this.runtime.generationCaller({conversationId:parentId,generation:this.runtime.conversation(parentId).generations.at(-1)!.generation}):undefined;
  }
  cleanupRemoved():void {
    const retained=new Set(this.runtime.listConversations().map(c=>c.conversationId));
    for(const [id,session] of this.sessions)if(!retained.has(id as any)){session.dispose();this.sessions.delete(id);}
    this.delivered=new Set([...this.delivered].filter(key=>retained.has(JSON.parse(key)[0])));
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
    this.call('store.save',{state:{version:1,config:this.config,busy:this.busy,lastError:this.lastError,mainPaused:this.mainPaused,totalStarts:this.totalStarts,mainMessages:stripImages(this.main.messages),workers:this.runtime.listConversations(),workerMessages,inbox:this.inbox,delivered:[...this.delivered],mainTurns:this.mainTurns}});
  }
  state():any {
    const tasks=this.runtime.listConversations().map(c=>{const g=c.generations.at(-1)!;return {...c,generation:g.generation,status:g.status.kind==='done'?({error:'failed',aborted:'cancelled',skipped:'cancelled'} as any)[g.status.outcome]??g.status.outcome:g.status.kind,output:g.status.kind==='done'?g.status.output:undefined};});
    return {messages:textMessages(this.main.messages,this.sanitize),tasks,busy:this.busy,lastError:this.lastError,active:this.busy||tasks.some(t=>t.currentGeneration||t.isStopping)||(!this.mainPaused&&this.inbox.length>0)||Boolean(this.pendingSave),mainPaused:this.mainPaused,mainInterrupted:this.mainInterrupted,provider:{...this.config},mainTurns:this.mainTurns,
      workerMessages:Object.fromEntries([...this.sessions].map(([id,s])=>[id,textMessages(s.messages,this.sanitize)]))};
  }
  publish():void {if(!this.restoring){const state=this.state();this.emit({type:'state',state});this.emit({type:'activity',active:state.active});}}
}
