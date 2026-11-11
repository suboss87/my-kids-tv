#!/usr/bin/env python3
"""Sends settings to the TV without a rebuild: a config.properties (the same file build.sh reads) over adb.

    python3 tools/push-config.py 192.168.1.50                 # sends ./config.properties
    python3 tools/push-config.py 192.168.1.50 other.properties
    python3 tools/push-config.py other.properties             # the TV adb is already connected to
    python3 tools/push-config.py 192.168.1.50 --set parent_pin=2468 --set age_band=11-13   # just these keys

What it sends goes where the TV's own settings screen saves, so it wins over what was built in: age_band, child_name,
language, temperature, theme, city, latitude, longitude, tmdb_region, tmdb_key, watchmode_key, hide_services,
hide_animation, disable_assistant, block_youtube_button, continue_watching, and parent_pin (sets the PIN; a forgotten
PIN is fixed this way, no rebuild, no `pm clear`). Keys the app doesn't know are ignored. Nothing is printed back.

The file goes through the app itself (`content write` to its ConfigInbox, which only adb may use) and is applied
the next time the home screen, the screensaver or My Kids TV's settings open; while the home screen is on it is
applied within 15 seconds. The TV keeps the values and deletes the file.
"""
import os
import shutil
import subprocess
import sys
import tempfile

SERIAL = []


def adb(*args, **kw):
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, text=True, **kw)


def main():
    args = sys.argv[1:]
    if args and args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0
    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        return 1
    sets = []
    rest = []
    i = 0
    while i < len(args):
        if args[i] == '--set' and i + 1 < len(args):
            sets.append(args[i + 1])
            i += 2
        else:
            rest.append(args[i])
            i += 1
    if rest and not os.path.exists(rest[0]) and rest[0][0].isdigit():
        ip = rest.pop(0)
        target = ip if ':' in ip else ip + ':5555'
        r = adb('connect', target)
        if 'connected' not in r.stdout:
            print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
            return 1
        SERIAL[:] = ['-s', target]
    if sets:
        for s in sets:
            if '=' not in s:
                print(f'--set needs key=value, got {s}')
                return 2
        text = '\n'.join(sets) + '\n'
    else:
        path = rest[0] if rest else 'config.properties'
        if not os.path.isfile(path):
            print(f'No such file: {path} (run ./build.sh once to create config.properties, or use --set key=value).')
            return 1
        with open(path, encoding='utf-8') as f:
            text = f.read()
    data = text.encode('utf-8')
    listed = adb('shell', 'pm', 'list', 'packages', 'io.mykidstv')
    if listed.returncode != 0:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        return 1
    if 'package:io.mykidstv' not in listed.stdout.split():
        print('My Kids TV is not installed on this TV.')
        return 1
    probe = adb('shell', 'content', 'query', '--uri', 'content://io.mykidstv.config/')
    if 'Could not find provider' in probe.stdout + probe.stderr:
        print('The My Kids TV on this TV is too old to take settings this way: install the new MyKidsTV.apk first.')
        return 1
    uri = f'content://io.mykidstv.config/config.properties?size={len(data)}'
    # Through a file only adb's shell can read (/data/local/tmp), not a stream: `adb exec-in content write` reaches
    # some TVs (Android 14 over Wi-Fi among them) with nothing on its stdin, and the app then sees an empty send.
    remote = f'/data/local/tmp/mykidstv-config-{os.getpid()}.properties'
    with tempfile.NamedTemporaryFile('wb', suffix='.properties', delete=False) as tmp:
        tmp.write(data)
    try:
        r = adb('push', tmp.name, remote)
        if r.returncode != 0:
            print('Sending failed: ' + (r.stderr.strip() or r.stdout.strip() or 'adb push returned an error'))
            return 1
        r = adb('shell', f"content write --uri '{uri}' < {remote}; rc=$?; rm -f {remote}; exit $rc")
        if r.returncode != 0 or 'Error' in r.stdout + r.stderr or 'Exception' in r.stdout + r.stderr:
            print('Sending failed: ' + ((r.stdout + r.stderr).strip() or 'the TV returned an error'))
            return 1
    finally:
        os.unlink(tmp.name)
    keys = sorted({line.split('=', 1)[0].strip() for line in text.splitlines()
                   if '=' in line and not line.lstrip().startswith('#')})
    shown = ', '.join(k for k in keys if k not in ('tmdb_key', 'watchmode_key', 'parent_pin'))
    secret = [k for k in keys if k in ('tmdb_key', 'watchmode_key', 'parent_pin')]
    print('Sent: ' + (shown or '(no plain settings)') + (f' and {", ".join(secret)} (not shown)' if secret else '') + '.')
    print('The TV applies them when the home screen, the screensaver or the settings next open (within 15 s on the home screen).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
