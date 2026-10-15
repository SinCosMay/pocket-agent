#!/usr/bin/env python3
"""Provisioning checks with synthetic JWTs and fake ADB; no real auth is read."""
import base64
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import time
import unittest

try:
    from .provision_codex_account import SERIAL, DESTINATION, ProvisionError, account_config, provision
except ImportError:
    from provision_codex_account import SERIAL, DESTINATION, ProvisionError, account_config, provision


def auth_fixture():
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip('=')
    payload = {'exp': int(time.time()) + 3600, 'https://api.openai.com/auth': {'chatgpt_account_id': 'synthetic-account'}}
    access = encode({'alg': 'RS256'}) + '.' + encode(payload) + '.c2lnbmF0dXJl'
    return {'tokens': {'access_token': access, 'refresh_token': 'synthetic-refresh', 'account_id': 'synthetic-account'}, 'desktop_metadata': 'must-not-copy'}


class ProvisionTests(unittest.TestCase):
    def test_canonical_minimal_account_without_endpoint_and_expiry_in_milliseconds(self):
        auth = auth_fixture();result = account_config(auth)
        self.assertEqual(set(result), {'authMode', 'model', 'accessToken', 'refreshToken', 'accountId', 'expiresAt'})
        self.assertEqual(result['authMode'], 'codex-account');self.assertEqual(result['model'], 'gpt-6-luna')
        self.assertGreater(result['expiresAt'], int(time.time() * 1000))
        claims = json.loads(base64.urlsafe_b64decode(auth['tokens']['access_token'].split('.')[1] + '=='))
        self.assertEqual(result['expiresAt'], claims['exp'] * 1000)
        del auth['tokens']['account_id']
        self.assertEqual(account_config(auth)['accountId'], 'synthetic-account')

    def test_rejects_wrong_identity_expired_or_missing_refresh_without_exposing_values(self):
        auth = auth_fixture();auth['tokens']['account_id'] = 'wrong'
        with self.assertRaises(ProvisionError):account_config(auth)
        auth = auth_fixture();auth['tokens']['refresh_token'] = ''
        with self.assertRaises(ProvisionError):account_config(auth)
        with self.assertRaises(ProvisionError):account_config(auth_fixture(), now_ms=10**15)

    def test_serial_and_qemu_guards_precede_credential_access(self):
        def never(*args, **kwargs):raise AssertionError('ADB must not run for a different serial')
        for serial in ('emulator-5554', 'physical-phone', ''):
            with self.assertRaises(ProvisionError):provision(serial, Path('/missing-auth'), never)
        calls = []
        def physical(command, **kwargs):
            calls.append(command);return subprocess.CompletedProcess(command, 0, '0\n', '')
        with self.assertRaises(ProvisionError):provision(SERIAL, Path('/missing-auth'), physical)
        self.assertEqual(len(calls), 1)

    def test_transfer_uses_0600_then_unlinks_immediately_and_leaves_source_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'auth.json';source.write_text(json.dumps(auth_fixture()));before = source.read_bytes()
            pushed = {};temporary = None
            def adb(command, **kwargs):
                nonlocal temporary
                self.assertEqual(command[:3], ['adb', '-s', SERIAL])
                if command[3:5] == ['shell', 'getprop']:
                    return subprocess.CompletedProcess(command, 0, '1\n', '')
                if command[3] == 'push':
                    temporary = Path(command[4]);self.assertEqual(stat.S_IMODE(temporary.stat().st_mode), 0o600)
                    self.assertEqual(command[5], DESTINATION);pushed.update(json.loads(temporary.read_text()))
                if command[3:5] == ['shell', 'chmod']:
                    self.assertFalse(temporary.exists(), 'host credential copy must be removed before chmod')
                return subprocess.CompletedProcess(command, 0, '', '')
            provision(SERIAL, source, adb)
            self.assertFalse(temporary.exists());self.assertEqual(source.read_bytes(), before)
            self.assertEqual(pushed['authMode'], 'codex-account');self.assertNotIn('endpoint', pushed)

    def test_failed_push_removes_transient_files_and_suppresses_subprocess_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'auth.json';source.write_text(json.dumps(auth_fixture()))
            paths = [];commands = []
            def adb(command, **kwargs):
                commands.append(command)
                if command[3:5] == ['shell', 'getprop']:return subprocess.CompletedProcess(command, 0, '1', '')
                if command[3] == 'push':
                    paths.append(Path(command[4]));return subprocess.CompletedProcess(command, 1, 'synthetic-refresh', 'synthetic-refresh')
                return subprocess.CompletedProcess(command, 0, '', '')
            with self.assertRaises(ProvisionError) as error:provision(SERIAL, source, adb)
            self.assertNotIn('synthetic-refresh', str(error.exception));self.assertFalse(paths[0].exists())
            self.assertTrue(any(command[3:6] == ['shell', 'rm', '-f'] for command in commands))


if __name__ == '__main__':
    unittest.main()
