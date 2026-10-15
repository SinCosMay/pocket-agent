import { Agent } from '@earendil-works/pi-agent-core';
import { makeStream, modelFor } from './provider.js';

/** The deliberately narrow Pi9 coding-AgentSession interface implemented by Pi Agent. */
export class MobileSession {
  readonly agent: Agent;
  private steering:string[]=[];
  private turns=0;
  private calls=0;
  constructor(readonly owner:string, config:any, credentials:any, tools:any[], messages:any[]=[] ,systemPrompt='') {
    this.agent=new Agent({streamFn:makeStream(config,credentials,owner),initialState:{
      model:modelFor(config,credentials),systemPrompt,tools,messages,
    },toolExecution:'sequential',sessionId:owner,
      beforeToolCall:()=>{if(++this.calls>48)throw new Error('Mobile tool-call budget reached (48 per generation)');return undefined;},
      finishTurn:()=>{if(++this.turns>24||this.full())throw new Error('Mobile conversation budget reached; saved context was retained');return undefined;},
    });
  }
  get messages():any[] {return this.agent.state.messages;}
  full():boolean {return this.messages.length>=256||JSON.stringify(this.messages).length>=1000000;}
  prompt(text:string):Promise<void> {if(this.full())return Promise.reject(new Error('Conversation is at the mobile context limit; history was retained'));this.turns=0;this.calls=0;return this.agent.prompt(text);}
  continue():Promise<void> {this.turns=0;this.calls=0;return this.agent.continue();}
  subscribe(fn:(event:any)=>void):()=>void {return this.agent.subscribe(fn);}
  async steer(text:string):Promise<void> {
    this.steering.push(text);this.agent.steer({role:'user',content:[{type:'text',text}],timestamp:Date.now()});
  }
  getSteeringMessages():string[] {return this.steering;}
  clearQueue():void {this.steering=[];this.agent.clearAllQueues();}
  async abort():Promise<void> {this.agent.abort();await this.agent.waitForIdle();}
  dispose():void {this.clearQueue();}
}
