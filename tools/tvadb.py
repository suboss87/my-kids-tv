"""What tools/check.py and tools/restore.py share: adb to one TV, by IP or the one already connected."""
import shutil
import subprocess
import sys

SERIAL = []


def connect(args):
    """Takes an IP from the front of args (if there is one) and connects; exits with a message when adb can't."""
    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        sys.exit(2)
    if args and args[0][0].isdigit():
        ip = args.pop(0)
        target = ip if ':' in ip else ip + ':5555'
        r = run('connect', target)
        if 'connected' not in r.stdout:
            print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
            sys.exit(2)
        SERIAL[:] = ['-s', target]
    r = run('get-state')
    if r.returncode != 0 or 'device' not in r.stdout:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        sys.exit(2)


def run(*args, timeout=60):
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, text=True, timeout=timeout)


def shell(cmd, timeout=60):
    r = run('shell', cmd, timeout=timeout)
    if r.returncode != 0 and not r.stdout:
        raise RuntimeError((r.stderr or 'adb failed').strip())
    return r.stdout


def setting(scope, key):
    v = shell(f'settings get {scope} {key}').strip()
    return '' if v in ('', 'null') else v


def packages(flags=''):
    return {line.strip()[8:] for line in shell(f'pm list packages {flags}').splitlines() if line.strip().startswith('package:')}


def home_holder():
    """The package that is the home screen now, or ''."""
    v = shell('cmd role get-role-holders --user 0 android.app.role.HOME').strip()
    if v and all(c.isalnum() or c in '._;' for c in v):
        return v.split(';')[0]
    lines = shell('cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME').strip().splitlines()
    comp = lines[-1].strip() if lines else ''
    return comp.split('/')[0] if '/' in comp and not comp.startswith('android/') else ''


def java_hash(s):
    h = 0
    for ch in s:
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= (1 << 31) else h
