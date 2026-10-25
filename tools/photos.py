#!/usr/bin/env python3
"""Sends your photos to the TV for Free My TV's screensaver and home screen (My Photos).

    python3 tools/photos.py 192.168.1.50 beach.jpg hills.jpg Holidays/
    python3 tools/photos.py beach.jpg              # the TV adb is already connected to

The photos go, in the order given (a folder's in name order), to the app's own folder on the TV
(Android/data/io.freemytv/files/photos: adb can write there, and the app needs no permission); where the TV keeps adb
out of it (Android 11, Fire OS 8), they go through the app itself (`content write` to its PhotoInbox, which only adb
may use). The next time the home screen, the screensaver or Free My TV's settings open, the app imports each one
(upright, cropped to 16:9, at the screensaver's size, without its GPS and other tags) and deletes what was sent. Up to
100 photos in all; remove them on the TV: Home Settings > My Photos > OK. JPEG, PNG and WebP; HEIC on Android 9 and
newer.
"""
import os
import shutil
import subprocess
import sys
import urllib.parse

INBOX = '/sdcard/Android/data/io.freemytv/files/photos'
KINDS = ('.jpg', '.jpeg', '.png', '.webp', '.heic', '.heif')
SERIAL = []   # -s <ip>:5555 once connected, so a phone or another TV on adb doesn't get in the way


def adb(*args):
    return subprocess.run(['adb', *SERIAL, *args], capture_output=True, text=True)


def photos(paths):
    out = []
    for p in paths:
        if os.path.isdir(p):
            for root, dirs, files in os.walk(p):
                dirs[:] = sorted(d for d in dirs if not d.startswith('.'))
                out += [os.path.join(root, f) for f in sorted(files)
                        if not f.startswith('.') and f.lower().endswith(KINDS)]
        elif os.path.isfile(p) and p.lower().endswith(KINDS):
            out.append(p)
        else:
            print(f'Skipped {p}: not a photo or a folder.')
    return out


def main():
    args = sys.argv[1:]
    if not args or args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0 if args else 2
    if not shutil.which('adb'):
        print('adb not found: install the Android platform tools (see the README).')
        return 1
    if not os.path.exists(args[0]) and args[0][0].isdigit():
        ip = args.pop(0)
        target = ip if ':' in ip else ip + ':5555'
        r = adb('connect', target)
        if 'connected' not in r.stdout:
            print(f'Could not connect to {target}: {(r.stdout + r.stderr).strip()}')
            return 1
        SERIAL[:] = ['-s', target]
    found = photos(args)
    if not found:
        print('No photos to send.')
        return 1
    listed = adb('shell', 'pm', 'list', 'packages', 'io.freemytv')
    if listed.returncode != 0:
        print('No TV connected: give its IP address first (adb devices lists the connected ones).')
        return 1
    if 'package:io.freemytv' not in listed.stdout.split():
        print('Free My TV is not installed on this TV.')
        return 1
    # Android 11 (Fire OS 8 too) keeps adb out of other apps' Android/data folders: then through the app itself.
    direct = adb('shell', 'mkdir', '-p', INBOX).returncode == 0
    if not direct:
        # exec-in brings back nothing the TV prints: check once that its Free My TV has the way in.
        probe = adb('shell', 'content', 'query', '--uri', 'content://io.freemytv.photos/')
        if 'Could not find provider' in probe.stdout + probe.stderr:
            print('This TV keeps adb out of the app\'s folder, and the Free My TV on it is too old for the other way '
                  'in: install the new FreeMyTV.apk (see Install), then send them again.')
            return 1
    for i, path in enumerate(found):
        # Numbered so the app imports them in this order.
        name = f'{i:03d}-' + os.path.basename(path).replace(' ', '_')
        if direct:
            r = adb('push', path, f'{INBOX}/{name}')
        else:
            # Encoded (a # or ? would cut the name), and with its size, so the app drops a send cut short.
            uri = f'content://io.freemytv.photos/{urllib.parse.quote(name)}?size={os.path.getsize(path)}'
            with open(path, 'rb') as f:
                r = subprocess.run(['adb', *SERIAL, 'exec-in', 'content', 'write', '--uri', uri], stdin=f,
                                   capture_output=True, text=True)
        if r.returncode != 0:
            print(f'Could not send {path}: {(r.stdout + r.stderr).strip()}')
            return 1
        print(f'Sent {path}')
    print(f'{len(found)} photo(s) on the TV. Open the home screen or Free My TV\'s settings to import them.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
