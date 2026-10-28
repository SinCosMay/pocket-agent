import {mkdir,open,readFile,rename,chmod,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {randomBytes,randomUUID,createCipheriv,createDecipheriv} from 'node:crypto';

export async function createVault(directory){
  await mkdir(directory,{recursive:true,mode:0o700});await chmod(directory,0o700);
  const keyPath=join(directory,'vault.key'),dataPath=join(directory,'credentials.enc');
  let key;
  try {key=await readFile(keyPath);}
  catch(error){
    if(error.code!=='ENOENT')throw Error('Could not open the local credential key.');
    try {await readFile(dataPath);throw Error('The credential key is missing. Restore it before using saved connections.');}
    catch(missing){if(missing.code!=='ENOENT')throw missing;}
    key=randomBytes(32);const handle=await open(keyPath,'wx',0o600);
    try{await handle.writeFile(key);await handle.sync();}finally{await handle.close();}
  }
  if(key.length!==32)throw Error('The local credential key is invalid.');
  await chmod(keyPath,0o600);
  let state={};
  try {
    const data=await readFile(dataPath);if(data.length<29)throw Error();
    const decipher=createDecipheriv('aes-256-gcm',key,data.subarray(0,12));decipher.setAuthTag(data.subarray(12,28));
    state=JSON.parse(Buffer.concat([decipher.update(data.subarray(28)),decipher.final()]).toString('utf8'));
    if(!state||typeof state!=='object'||Array.isArray(state))throw Error();
    await chmod(dataPath,0o600);
  }catch(error){if(error.code!=='ENOENT')throw Error('Saved provider credentials could not be decrypted.');}
  let tail=Promise.resolve();
  return {
    get(provider){return state[provider];},
    update(change,check=()=>{}){
      const operation=tail.then(async()=>{
        check();const next=structuredClone(state);change(next);
        const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv);
        const encrypted=Buffer.concat([cipher.update(JSON.stringify(next),'utf8'),cipher.final()]);
        const temp=join(directory,`.credentials-${randomUUID()}`),handle=await open(temp,'wx',0o600);
        try{await handle.writeFile(Buffer.concat([iv,cipher.getAuthTag(),encrypted]));await handle.sync();}finally{await handle.close();}
        try{check();await rename(temp,dataPath);const folder=await open(directory,'r');try{await folder.sync();}finally{await folder.close();}state=next;}catch(error){await rm(temp,{force:true});throw error;}
      });
      tail=operation.catch(()=>{});return operation;
    }
  };
}
