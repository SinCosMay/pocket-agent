#!/usr/bin/env python3
"""Read selected files from an Actions ZIP with bounded HTTP ranges (no URL/token logs)."""
import argparse,io,json,subprocess,zipfile,zlib
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import requests
p=argparse.ArgumentParser();p.add_argument('--run',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--repo',default='ru-aish/pocket-agent');p.add_argument('--all',action='store_true');p.add_argument('--include',action='append');a=p.parse_args()
items=json.loads(subprocess.check_output(['gh','api',f'repos/{a.repo}/actions/runs/{a.run}/artifacts']))['artifacts'];artifact=next(x for x in items if x['name']=='pocket-agent-release')
token=subprocess.check_output(['gh','auth','token'],text=True).strip()
response=requests.get(artifact['archive_download_url'],headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json'},allow_redirects=False,timeout=30)
if response.status_code!=302:raise SystemExit(f'Artifact redirect failed ({response.status_code})')
url=response.headers['Location'];session=requests.Session()
head=session.head(url,timeout=30)
if head.status_code!=200 or not head.headers.get('Content-Length'):raise SystemExit('Artifact size unavailable')
size=int(head.headers['Content-Length'])
def fetch_range(bounds):
 start,length=bounds
 try:r=requests.get(url,headers={'Range':f'bytes={start}-{start+length-1}'},timeout=120)
 except requests.RequestException:raise RuntimeError('Artifact transfer failed; rerun to resume completed files') from None
 if r.status_code!=206:raise RuntimeError(f'Artifact range unavailable ({r.status_code})')
 if len(r.content)!=length:raise RuntimeError('Artifact range length mismatch')
 return r.content
class RemoteZip(io.RawIOBase):
 def __init__(self):self.pos=0
 def seekable(self):return True
 def readable(self):return True
 def tell(self):return self.pos
 def seek(self,offset,whence=0):
  self.pos=offset if whence==0 else self.pos+offset if whence==1 else size+offset
  return self.pos
 def read(self,n=-1):
  n=max(0,min(size-self.pos,n if n>=0 else size-self.pos))
  if not n:return b''
  # Four bounded read-only transfers avoid one slow Azure stream stalling APK QA.
  if n>8*1024*1024:
   chunk=8*1024*1024
   ranges=[(start,min(chunk,self.pos+n-start)) for start in range(self.pos,self.pos+n,chunk)]
   with ThreadPoolExecutor(max_workers=4) as pool:data=b''.join(pool.map(fetch_range,ranges))
  else:data=fetch_range((self.pos,n))
  self.pos+=n;return data
wanted={'Pocket-Agent-x86_64.apk','Pocket-Agent-tests.apk','Pocket-Agent-fixture.apk','source-commit.txt','runtime-manifest.json','SHA256SUMS.txt','signature.txt','payload-report.jsonl','lint.txt','native-stripping.json'}
if a.include:wanted=set(a.include)
a.output.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(RemoteZip()) as archive:
 for item in archive.infolist():
  name=Path(item.filename).name
  if item.is_dir() or (not a.all and name not in wanted):continue
  if item.file_size>200_000_000:raise SystemExit('Artifact member exceeds bound')
  destination=a.output/name
  if destination.exists() and destination.stat().st_size==item.file_size and zlib.crc32(destination.read_bytes())==item.CRC:continue
  destination.write_bytes(archive.read(item));print(name,flush=True)
