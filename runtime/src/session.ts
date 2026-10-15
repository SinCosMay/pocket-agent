import { Agent } from '@earendil-works/pi-agent-core';
import { makeStream, modelFor } from './provider.js';

/** The deliberately narrow Pi9 coding-AgentSession interface implemented by Pi Agent. */
export class MobileSession {
  readonly agent: Agent;
  private steering:string[]=[];
  constructor(readonly owner:string, config:any, credentials:any, tools:any[], messages:any[]=[] ,systemPrompt='') {
    this.agent=new Agent({streamFn:makeStream(config,credentials,owner),initialState:{
      model:modelFor(config,credentials),systemPrompt,tools,messages,
    },toolExecution:'sequential',sessionId:owner});
  }
  get messages():any[] {return this.agent.state.messages;}
  prompt(text:string):Promise<void> {return this.agent.prompt(text);}
  subscribe(fn:(event:any)=>void):()=>void {return this.agent.subscribe(fn);}
  async steer(text:string):Promise<void> {
    this.steering.push(text);this.agent.steer({role:'user',content:[{type:'text',text}],timestamp:Date.now()});
  }
  getSteeringMessages():string[] {return this.steering;}
  clearQueue():void {this.steering=[];this.agent.clearAllQueues();}
  async abort():Promise<void> {this.agent.abort();await this.agent.waitForIdle();}
  dispose():void {this.clearQueue();}
}
