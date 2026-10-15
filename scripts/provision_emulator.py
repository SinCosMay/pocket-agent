#!/usr/bin/env python3
"""Provision only the emulator relay bearer for the opt-in release instrumentation."""
import argparse,json,subprocess,tempfile,os
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--port',type=int,default=8787);p.add_argument('--model',default='gpt-6-luna');a=p.parse_args()
if not a.serial.startswith('emulator-'):raise SystemExit('Only an explicit emulator serial is allowed')
def adb(*args,**kw):return subprocess.run(['adb','-s',a.serial,*args],check=True,capture_output=True,text=True,**kw)
if adb('shell','getprop','ro.kernel.qemu').stdout.strip()!='1':raise SystemExit('Not an emulator')
bearer=(Path.home()/'.local/state/pocket-agent/relay.token').read_text().strip()
config={'authMode':'codex','endpoint':f'http://10.0.2.2:{a.port}/v1','model':a.model,'apiKey':bearer}
directory='/sdcard/Android/data/dev.pocket.agent/files';destination=directory+'/provider-test.json'
with tempfile.NamedTemporaryFile(mode='w',prefix='pocket-relay-',suffix='.json',delete=False) as f:
 os.chmod(f.name,0o600);json.dump(config,f);name=f.name
try:
 adb('shell','mkdir','-p',directory);adb('push',name,destination)
 print('Emulator development bearer provisioned for one test; no account credentials copied.')
 print('Provider config path: '+destination)
finally:os.unlink(name)
