/** Single-isolate FIFO semaphore: waiting UI tools yield without blocking other agents. */
export class DisplayPool {
  private readonly owners=new Set<string>();
  private readonly waiters:Array<{owner:string;resolve:()=>void;reject:(error:Error)=>void;signal?:AbortSignal;cancel:()=>void}>=[];
  constructor(readonly capacity=3) {
    if(!Number.isInteger(capacity)||capacity<1)throw new Error('Invalid display capacity');
  }
  get active():number{return this.owners.size;}
  get waiting():number{return this.waiters.length;}
  has(owner:string):boolean{return this.owners.has(owner);}

  async reserve(owner:string,signal?:AbortSignal):Promise<void>{
    if(signal?.aborted)throw new Error('Display request cancelled');
    if(this.owners.has(owner))return;
    if(this.owners.size<this.capacity&&this.waiters.length===0){
      this.owners.add(owner);return;
    }
    await new Promise<void>((resolve,reject)=>{
      const existing=this.waiters.find(w=>w.owner===owner);
      if(existing){reject(new Error('Already waiting for a display'));return;}
      const entry={owner,resolve:()=>{},reject,cancel:()=>{},signal};
      const cancel=()=>{
        const index=this.waiters.indexOf(entry);
        if(index>=0)this.waiters.splice(index,1);
        signal?.removeEventListener('abort',cancel);
        reject(new Error('Display request cancelled'));
        this.promote();
      };
      entry.cancel=cancel;
      entry.resolve=()=>{signal?.removeEventListener('abort',cancel);resolve();};
      signal?.addEventListener('abort',cancel,{once:true});
      if(signal?.aborted){cancel();return;}
      this.waiters.push(entry);
      this.promote();
    });
  }

  release(owner:string):void {
    this.owners.delete(owner);
    const waiting=this.waiters.find(w=>w.owner===owner);
    waiting?.cancel();
    this.promote();
  }
  private promote():void{
    while(this.owners.size<this.capacity&&this.waiters.length){
      const next=this.waiters.shift()!;
      if(next.signal?.aborted){next.reject(new Error('Display request cancelled'));continue;}
      this.owners.add(next.owner);
      next.resolve();
    }
  }
}
