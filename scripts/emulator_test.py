#!/usr/bin/env python3
"""Validate exact Actions APKs only on an explicitly selected emulator."""
import argparse, hashlib, json, subprocess, time
from pathlib import Path

def run(*args, timeout=240, check=True):
    result=subprocess.run(args,capture_output=True,text=True,timeout=timeout)
    if check and result.returncode:raise RuntimeError(result.stdout+result.stderr)
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--artifacts',type=Path,required=True);p.add_argument('--live',action='store_true');p.add_argument('--codex-account',action='store_true',help='Opt in to direct Luna account inference using existing local Codex auth on emulator-5580');p.add_argument('--account-auth-tests',action='store_true',help='Include synthetic native account import, storage, rotation and sign-out tests');p.add_argument('--fresh',action='store_true');p.add_argument('--case',action='append',default=[]);a=p.parse_args()
    if a.codex_account:
        if a.serial!='emulator-5580':raise SystemExit('Direct account provisioning is restricted to emulator-5580')
        a.live=True
    if not a.serial.startswith('emulator-'):raise SystemExit('Explicit emulator serial required; physical phones are excluded.')
    def adb(*args,**kw):return run('adb','-s',a.serial,*args,**kw)
    if adb('shell','getprop','ro.kernel.qemu').stdout.strip()!='1':raise SystemExit('Selected serial is not an emulator')
    apk=a.artifacts/'Pocket-Agent-x86_64.apk';fixture=a.artifacts/'Pocket-Agent-fixture.apk';tests=a.artifacts/'Pocket-Agent-tests.apk'
    adb('uninstall','dev.pocket.agent.fixture',check=False)
    for file in (apk,fixture,tests):adb('install','-r',str(file))
    if a.fresh:adb('shell','pm','clear','dev.pocket.agent')
    # This read-only AVD copy belongs to this task; preserve other accessibility components.
    existing=adb('shell','settings','get','secure','enabled_accessibility_services').stdout.strip()
    component='dev.pocket.agent/.AgentAccessibility'
    services=[] if existing in ('null','') else existing.split(':')
    services=[s for s in services if s!=component]
    if services:adb('shell','settings','put','secure','enabled_accessibility_services',':'.join(services))
    else:adb('shell','settings','delete','secure','enabled_accessibility_services')
    services.append(component)
    adb('shell','settings','put','secure','enabled_accessibility_services',':'.join(services));adb('shell','settings','put','secure','accessibility_enabled','1')
    adb('shell','pm','grant','dev.pocket.agent','android.permission.POST_NOTIFICATIONS')
    report={'serial':a.serial,'apkSha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'cases':[]}
    cases=[('RuntimeDeviceTest','nodeEmbeddingStreamingAndCancellation'),('RuntimeDeviceTest','directNativeFixtureAndMutationLedger'),('AsyncAgentDeviceTest','delegatedFixtureKeepsMainResponsiveAndCompletesOnce'),('LifecycleDeviceTest','activityRecreationKeepsWorker'),('LifecycleDeviceTest','reportFootprint'),('LifecycleDeviceTest','prepareProcessDeath'),('LifecycleDeviceTest','verifyProcessDeath')]
    cases.extend([('VoiceAssistantDeviceTest','assistantIsProtectedAndDiscoverable'),('VoicePipelineDeviceTest','fluxOnlyDispatchesConfirmedNonemptyTurns'),('VoicePipelineDeviceTest','streamedReplyDoesNotSplitWordsAndDrainsTrailingText')])
    if a.account_auth_tests:cases.extend([('CodexAuthDeviceTest','importEncryptAndSignOut'),('CodexAuthDeviceTest','rotationCompareAndSaveRejectsStaleAndSignedOut')])
    if a.live:cases.append(('LiveProviderDeviceTest','liveCodexDelegationAndFixture'))
    if a.case:cases=[case for case in cases if '#'.join(case) in a.case]
    if not cases:raise SystemExit('No matching device test case')
    (a.artifacts/'emulator-results.json').write_text(json.dumps(report,indent=2)+'\n')
    for cls,method in cases:
        adb('shell','am','force-stop','dev.pocket.agent.fixture');
        if method=='verifyProcessDeath':adb('shell','am','force-stop','dev.pocket.agent')
        args=['shell','am','instrument','-w','-r','-e','class',f'dev.pocket.agent.{cls}#{method}']
        if cls=='LiveProviderDeviceTest':
            provision='provision_codex_account.py' if a.codex_account else 'provision_emulator.py'
            run('python3',str(Path(__file__).with_name(provision)),'--serial',a.serial)
            args+=['-e','providerConfigPath','/sdcard/Android/data/dev.pocket.agent/files/provider-test.json']
        adb('logcat','-c')
        result=None
        try:result=adb(*args,'dev.pocket.agent.test/androidx.test.runner.AndroidJUnitRunner',timeout=240)
        finally:
            if cls=='LiveProviderDeviceTest':
                adb('shell','rm','-f','/sdcard/Android/data/dev.pocket.agent/files/provider-test.json',check=False)
                if a.codex_account and (result is None or 'OK (' not in result.stdout or 'FAILURES!!!' in result.stdout):
                    adb('shell','am','force-stop','dev.pocket.agent',check=False)
                    try:
                        cleanup=adb('shell','am','instrument','-w','-r','-e','class','dev.pocket.agent.CodexAuthDeviceTest#clearProvisionedAccount','dev.pocket.agent.test/androidx.test.runner.AndroidJUnitRunner',check=False,timeout=30)
                        if 'OK (' not in cleanup.stdout:print('Account cleanup could not be verified; app remains stopped. Sign out in app settings before reuse.',flush=True)
                    except Exception:print('Account cleanup could not run; app remains stopped. Sign out in app settings before reuse.',flush=True)
        evidence=adb('logcat','-d','-s','System.out:I','*:S').stdout
        (a.artifacts/f'{cls}-{method}-evidence.txt').write_text(evidence)
        log=a.artifacts/f'{cls}-{method}.txt';log.write_text(result.stdout+result.stderr)
        passed='OK (' in result.stdout and 'FAILURES!!!' not in result.stdout
        report['cases'].append({'class':cls,'method':method,'passed':passed,'log':log.name})
        (a.artifacts/'emulator-results.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report['cases'][-1]),flush=True)
        if not passed:raise RuntimeError(result.stdout+result.stderr)
    (a.artifacts/'emulator-results.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':main()
