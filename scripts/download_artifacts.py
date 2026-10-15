#!/usr/bin/env python3
"""Read selected files from an Actions ZIP with bounded HTTP ranges (no URL/token logs)."""
import argparse,io,json,subprocess,zipfile
from pathlib import Path
import requests
p=argparse.ArgumentParser();p.add_argument('--run',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--repo',default='ru-aish/pocket-agent');p.add_argument('--all',action='store_true');a=p.parse_args()
items=json.loads(subprocess.check_output(['gh','api',f'repos/{a.repo}/actions/runs/{a.run}/artifacts']))['artifacts'];artifact=next(x for x in items if x['name']=='pocket-agent-release')
token=subprocess.check_output(['gh','auth','token'],text=True).strip()
response=requests.get(artifact['archive_download_url'],headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json'},allow_redirects=False,timeout=30)
if response.status_code!=302:raise SystemExit(f'Artifact redirect failed ({response.status_code})')
url=response.headers['Location'];session=requests.Session()
head=session.head(url,timeout=30)
if head.status_code!=200 or not head.headers.get('Content-Length'):raise SystemExit('Artifact size unavailable')
size=int(head.headers['Content-Length'])
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
  r=session.get(url,headers={'Range':f'bytes={self.pos}-{self.pos+n-1}'},timeout=120)
  if r.status_code!=206:raise RuntimeError(f'Artifact range unavailable ({r.status_code})')
  if len(r.content)!=n:raise RuntimeError('Artifact range length mismatch')
  self.pos+=n;return r.content
wanted={'Pocket-Agent-x86_64.apk','Pocket-Agent-tests.apk','Pocket-Agent-fixture.apk','source-commit.txt','runtime-manifest.json','SHA256SUMS.txt','signature.txt','payload-report.jsonl','lint.txt','native-stripping.json'}
a.output.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(RemoteZip()) as archive:
 for item in archive.infolist():
  name=Path(item.filename).name
  if item.is_dir() or (not a.all and name not in wanted):continue
  if item.file_size>200_000_000:raise SystemExit('Artifact member exceeds bound')
  (a.output/name).write_bytes(archive.read(item));print(name,flush=True)
