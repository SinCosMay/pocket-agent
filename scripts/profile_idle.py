#!/usr/bin/env python3
"""Sample an already-running app on an explicit rooted emulator; no app mutations."""
import argparse, json, subprocess, time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--seconds', type=int, default=30)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if not a.serial.startswith('emulator-') or not 5 <= a.seconds <= 60:
    raise SystemExit('Use an explicit emulator serial and a 5..60 second sample')

def adb(*args):
    return subprocess.check_output(['adb', '-s', a.serial, *args], text=True, timeout=30).strip()

if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
    raise SystemExit('Physical phones are excluded')
pid = adb('shell', 'pidof', 'dev.pocket.agent')
if not pid.isdecimal():
    raise SystemExit('Start Pocket Agent normally before sampling; expected one app process')
hz = int(adb('shell', 'getconf', 'CLK_TCK'))

def sample():
    if adb('shell', 'pidof', 'dev.pocket.agent') != pid:
        raise SystemExit('App process changed during sample')
    fields = adb('shell', 'su', '0', 'cat', f'/proc/{pid}/stat').rsplit(')', 1)[1].split()
    status = {}
    for line in adb('shell', 'su', '0', 'cat', f'/proc/{pid}/status').splitlines():
        key, _, value = line.partition(':')
        if key in ('VmRSS', 'VmSize', 'Threads'):
            status[key] = value.strip()
    return {'cpuTicks': int(fields[11]) + int(fields[12]), 'startTicks': int(fields[19]), 'status': status}

before = sample()
start = time.monotonic()
print(f'Sampling explicit emulator app PID {pid} for {a.seconds} seconds.', flush=True)
time.sleep(a.seconds)
after = sample()
wall = time.monotonic() - start
if before['startTicks'] != after['startTicks']:
    raise SystemExit('PID was reused during sample')
cpu_ms = (after['cpuTicks'] - before['cpuTicks']) * 1000 / hz
report = {'serial': a.serial, 'pid': int(pid), 'wallSeconds': wall, 'cpuMs': cpu_ms,
          'cpuPercentOfOneCore': cpu_ms / (wall * 10), 'before': before, 'after': after,
          'scope': 'One normal app process on a rooted x86 emulator. No instrumentation in the app. CPU/RSS only; no battery, wakeup, network or stock-runtime comparison claim.'}
a.output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report), flush=True)
