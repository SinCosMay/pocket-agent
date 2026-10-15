#!/usr/bin/env python3
"""Validate exact Actions APKs only on an explicitly selected emulator."""
import argparse, hashlib, json, subprocess, time
from pathlib import Path

def run(*args, timeout=240, check=True):
    result=subprocess.run(args,capture_output=True,text=True,timeout=timeout)
    if check and result.returncode:raise RuntimeError(result.stdout+result.stderr)
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--artifacts',type=Path,required=True);p.add_argument('--live',action='store_true');a=p.parse_args()
    if not a.serial.startswith('emulator-'):raise SystemExit('Explicit emulator serial required; physical phones are excluded.')
    def adb(*args,**kw):return run('adb','-s',a.serial,*args,**kw)
    if adb('shell','getprop','ro.kernel.qemu').stdout.strip()!='1':raise SystemExit('Selected serial is not an emulator')
    apk=a.artifacts/'Pocket-Agent-x86_64.apk';fixture=a.artifacts/'Pocket-Agent-fixture.apk';tests=a.artifacts/'Pocket-Agent-tests.apk'
    for file in (apk,fixture,tests):adb('install','-r',str(file))
    # This read-only AVD copy belongs to this task; preserve other accessibility components.
    existing=adb('shell','settings','get','secure','enabled_accessibility_services').stdout.strip()
    component='dev.pocket.agent/.AgentAccessibility'
    services=[] if existing in ('null','') else existing.split(':')
    if component not in services:services.append(component)
    adb('shell','settings','put','secure','enabled_accessibility_services',':'.join(services));adb('shell','settings','put','secure','accessibility_enabled','1')
    adb('shell','pm','grant','dev.pocket.agent','android.permission.POST_NOTIFICATIONS')
    report={'serial':a.serial,'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'cases':[]}
    cases=[('RuntimeDeviceTest','nodeEmbeddingStreamingAndCancellation'),('RuntimeDeviceTest','directNativeFixtureAndMutationLedger'),('AsyncAgentDeviceTest','delegatedFixtureKeepsMainResponsiveAndCompletesOnce')]
    if a.live:cases.append(('LiveProviderDeviceTest','liveCodexDelegationAndFixture'))
    for cls,method in cases:
        adb('shell','am','force-stop','dev.pocket.agent.fixture');
        args=['shell','am','instrument','-w','-r','-e','class',f'dev.pocket.agent.{cls}#{method}']
        if cls=='LiveProviderDeviceTest':raise SystemExit('Provision live auth with scripts/provision_emulator.py, then invoke the documented live test.')
        result=adb(*args,'dev.pocket.agent.test/androidx.test.runner.AndroidJUnitRunner',timeout=240)
        log=a.artifacts/f'{cls}-{method}.txt';log.write_text(result.stdout+result.stderr)
        passed='OK (' in result.stdout and 'FAILURES!!!' not in result.stdout
        report['cases'].append({'class':cls,'method':method,'passed':passed,'log':log.name})
        print(json.dumps(report['cases'][-1]),flush=True)
        if not passed:raise RuntimeError(result.stdout+result.stderr)
    (a.artifacts/'emulator-results.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':main()
