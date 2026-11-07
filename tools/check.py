#!/usr/bin/env python3
"""Is My Kids TV still as it was left? A TV update turns the promo apps back on and hands Home to the stock
launcher; the app checks itself at boot and once a day. This is the same check from a computer, for when you want
to know now, or from a cron job.

    python3 tools/check.py 192.168.1.50          # report; exit 1 when something drifted
    python3 tools/check.py 192.168.1.50 --fix    # ask the My Kids TV on the TV to put it right, then check again

It reads the TV's own records (what My Kids TV turned off, and the home screen it replaced), the packages that are
off now, the home screen and the screensaver in use. --fix runs the app's own check job (the one that knows every
brand's rules), waits, and reports again. Exit codes: 0 in sync (or fixed), 1 drift, 2 the TV could not be read,
3 the check job could not be started (the app is not in My Kids TV mode, or is too old).
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tvadb  # noqa: E402

PKG = 'io.mykidstv'
DAILY_JOB = 2001                                             # ModeJob.DAILY_ID: registered all the while My Kids TV is on
VERIFY_JOB = 1000 + abs(tvadb.java_hash('verify')) % 1000   # ModeJob.schedule's one-off "verify" (pending ~47 s after boot)


def check():
    record = tvadb.setting('global', 'free_my_tv_disabled')
    original = tvadb.setting('global', 'free_my_tv_original')
    if PKG not in tvadb.packages():
        print('My Kids TV is not installed on this TV.')
        return 0 if not record else 1
    if not record and not original:
        print('Original mode: My Kids TV has nothing turned off on this TV. Nothing to check.')
        return 0
    wanted = {p for p in record.split(',') if p}
    off = tvadb.packages('-d')
    present = tvadb.packages()
    back = sorted(p for p in wanted if p in present and p not in off)
    home = tvadb.home_holder()
    saver = tvadb.setting('secure', 'screensaver_components')
    drift = []
    if back:
        drift.append(f'{len(back)} app(s) are back on: ' + ', '.join(back))
    if home != PKG:
        drift.append(f'the home screen is {home or "unknown"}, not My Kids TV')
    if PKG + '/' not in saver:
        drift.append('the screensaver is not My Kids TV\'s')
    print(f'Turned off by My Kids TV: {len(wanted)}; off now: {len(wanted & off)}; home screen: {home or "unknown"}.')
    if drift:
        print('Drift: ' + '; '.join(drift) + '.')
        return 1
    print('In sync: everything is as My Kids TV left it.')
    return 0


def fix():
    started = False
    for job in (DAILY_JOB, VERIFY_JOB):
        out = tvadb.shell(f'cmd jobscheduler run -f {PKG} {job}')
        if 'Running' in out or 'running' in out.lower():
            started = True
            break
    if not started:
        print('Could not start the check job (is My Kids TV mode on, and the app a recent one?): ' + out.strip())
        return 3
    print('My Kids TV is checking the TV… (about 30 s)')
    time.sleep(30)
    return check()


def main():
    args = sys.argv[1:]
    if args and args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0
    do_fix = '--fix' in args
    args = [a for a in args if a != '--fix']
    tvadb.connect(args)
    try:
        code = check()
        if code == 1 and do_fix:
            code = fix()
        return code
    except RuntimeError as e:
        print(f'Could not read the TV: {e}')
        return 2


if __name__ == '__main__':
    sys.exit(main())
