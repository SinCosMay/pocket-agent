#!/usr/bin/env python3
"""Copy an existing Codex sign-in to the one authorized test emulator, once.

This script never refreshes credentials or modifies the desktop auth file. The
opt-in instrumentation imports the transient file and immediately deletes it.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

SERIAL = 'emulator-5580'
DESTINATION = '/sdcard/Android/data/dev.pocket.agent/files/provider-test.json'


class ProvisionError(Exception):
    pass


def account_config(auth, now_ms=None):
    """Select the minimal native account fields; do not return desktop metadata."""
    tokens = auth.get('tokens') if isinstance(auth, dict) else None
    if not isinstance(tokens, dict):
        raise ProvisionError('Existing Codex account sign-in is unavailable')
    access, refresh = tokens.get('access_token'), tokens.get('refresh_token')
    if not all(isinstance(value, str) and value and len(value) <= 32768 for value in (access, refresh)):
        raise ProvisionError('Existing Codex account tokens are unavailable')
    try:
        pieces = access.split('.')
        if len(pieces) != 3:
            raise ValueError()
        claims = json.loads(base64.urlsafe_b64decode(pieces[1] + '=' * (-len(pieces[1]) % 4)))
        if not isinstance(claims, dict):
            raise ValueError()
        expiry = claims.get('exp')
        if isinstance(expiry, bool) or not isinstance(expiry, (int, float)):
            raise ValueError()
        expires_at = int(expiry * 1000)
    except (ValueError, TypeError, KeyError, UnicodeError, OverflowError):
        raise ProvisionError('Codex access-token expiry could not be determined') from None
    account_claim = claims.get('https://api.openai.com/auth', {})
    claimed_id = account_claim.get('chatgpt_account_id') if isinstance(account_claim, dict) else None
    account = tokens.get('account_id') or claimed_id
    if not isinstance(account, str) or not account or len(account) > 256:
        raise ProvisionError('Existing Codex account identity is unavailable')
    if claimed_id and claimed_id != account:
        raise ProvisionError('Codex account identity does not match its access token')
    if expires_at <= (int(time.time() * 1000) if now_ms is None else now_ms):
        raise ProvisionError('Existing access token has expired; sign in with Codex normally before provisioning')
    return {'authMode': 'codex-account', 'model': 'gpt-6-luna', 'accessToken': access,
            'refreshToken': refresh, 'accountId': account, 'expiresAt': expires_at}


def provision(serial, auth_path=None, runner=subprocess.run):
    # Intentionally narrower than the generic runner: no physical phone, other
    # emulator, implicit default device, model override or remote ADB target.
    if serial != SERIAL:
        raise ProvisionError('Account provisioning is restricted to explicit --serial emulator-5580')

    def adb(*args):
        result = runner(['adb', '-s', serial, *args], capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise ProvisionError('ADB provisioning operation failed; credential contents were not printed')
        return result.stdout.strip()

    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise ProvisionError('Selected serial did not identify itself as an emulator')
    source = auth_path or Path.home() / '.codex/auth.json'
    try:
        if source.stat().st_size > 131072:
            raise ProvisionError('Existing Codex auth file is too large')
        config = account_config(json.loads(source.read_text()))
        if len(json.dumps(config).encode()) > 16384:
            raise ProvisionError('Existing account configuration exceeds the test import size bound')
    except (OSError, ValueError):
        raise ProvisionError('Existing Codex auth file could not be read safely') from None
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode='w', prefix='pocket-account-', suffix='.json', delete=False) as file:
            temporary = Path(file.name)
            os.fchmod(file.fileno(), 0o600)
            json.dump(config, file, separators=(',', ':'))
        adb('shell', 'mkdir', '-p', str(Path(DESTINATION).parent))
        adb('push', str(temporary), DESTINATION)
        # The host-side credential copy is removed before any further command.
        temporary.unlink()
        temporary = None
        adb('shell', 'chmod', '600', DESTINATION)
    except BaseException:
        # Best-effort cleanup if push/chmod fails. Never echo subprocess output.
        try:
            adb('shell', 'rm', '-f', DESTINATION)
        except Exception:
            pass
        raise
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, choices=[SERIAL])
    args = parser.parse_args()
    try:
        provision(args.serial)
    except ProvisionError as error:
        raise SystemExit(str(error)) from None
    except Exception:
        raise SystemExit('Account provisioning failed; credential contents were not printed') from None
    print('Existing Codex account copied to emulator-5580 for one opt-in test; host auth was unchanged.')


if __name__ == '__main__':
    main()
