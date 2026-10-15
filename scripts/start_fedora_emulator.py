#!/usr/bin/env python3
"""Reuse the existing Fedora AVD in read-only mode, isolated from the USB phone."""
import os, subprocess, time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SDK=Path(os.environ.get('POCKET_EMULATOR_SDK',str(Path.home()/'.cache/pocket-mcp/emulator-sdk')))
LOG=ROOT/'artifacts/emulator-runtime';LOG.mkdir(parents=True,exist_ok=True)
if not (SDK/'system-images/android-35/default/x86_64/system.img').exists(): raise SystemExit('Run scripts/bootstrap_fedora_emulator.py first')
socket=Path('/tmp/.X11-unix/X94')
if not socket.exists():
    with (LOG/'display.log').open('wb') as output:
        display=subprocess.Popen(['Xvfb',':94','-screen','0','1080x1920x24','-nolisten','tcp','-ac','+extension','GLX','+render','-noreset'],stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
    (LOG/'display.pid').write_text(str(display.pid))
    for _ in range(30):
        if socket.exists(): break
        if display.poll() is not None: raise SystemExit('Emulator display startup failed; see display.log')
        time.sleep(.1)
env=dict(os.environ,ANDROID_HOME=str(SDK),ANDROID_SDK_ROOT=str(SDK),DISPLAY=':94',QT_QPA_PLATFORM='xcb')
command=[str(SDK/'emulator/emulator'),'-avd','senp_api35','-port','5580','-read-only','-accel','on','-gpu','host','-feature','-Vulkan','-no-window','-no-boot-anim','-no-audio','-no-metrics','-no-snapshot-load','-no-snapshot-save','-netdelay','none','-netspeed','full','-memory','2048','-cores','2','-qemu','-cpu','host,pmu=off']
with (LOG/'emulator.log').open('wb') as output:
    process=subprocess.Popen(command,env=env,stdout=output,stderr=subprocess.STDOUT,start_new_session=True)
(LOG/'emulator.pid').write_text(str(process.pid))
print(f'Fedora emulator started as PID {process.pid}; serial emulator-5580; existing AVD read-only',flush=True)
deadline=time.monotonic()+480
adb=Path.home()/'.local/bin/adb'
while time.monotonic()<deadline:
    if process.poll() is not None: raise SystemExit('Emulator exited; inspect artifacts/emulator-runtime/emulator.log')
    result=subprocess.run([str(adb),'-s','emulator-5580','shell','getprop','sys.boot_completed'],capture_output=True,text=True,timeout=15)
    if result.returncode==0 and result.stdout.strip()=='1':
        for setting in ('window_animation_scale','transition_animation_scale','animator_duration_scale'):
            subprocess.run([str(adb),'-s','emulator-5580','shell','settings','put','global',setting,'0'],capture_output=True,timeout=15,check=True)
        print('PASS: Fedora emulator booted; KVM enabled, animations off, USB phone untouched',flush=True)
        break
    time.sleep(2)
else: raise SystemExit('Emulator boot timed out')
