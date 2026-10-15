import { PocketRuntime } from './runtime.js';
declare const PocketNative:any;
try {
 const runtime=new PocketRuntime(PocketNative);
 (globalThis as any).PocketAgent={dispatch:(json:string)=>runtime.dispatch(json)};
} catch(error) {
 PocketNative.emit(JSON.stringify({type:'error',error:String(error)}));
 throw error;
}
