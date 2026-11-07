#!/usr/bin/env python3
"""Puts the TV back the way it was, from a computer, with adb alone: for when My Kids TV is gone, broken, or the
remote can't reach its settings. The app's own Original button does the same from the TV.

    python3 tools/restore.py 192.168.1.50 --list   # show what the TV recorded, change nothing
    python3 tools/restore.py 192.168.1.50          # turn the apps back on, restore the home screen and screensaver

My Kids TV keeps two records in the TV's own settings at switch time: the apps it turned off (free_my_tv_disabled)
and the home screen, screensaver and screensaver setting it replaced (free_my_tv_original). This reads them, turns
the apps back on (the home screen first, Google TV's setup app last), hands Home back, puts the screensaver back,
takes My Kids TV's remote-keys service out of the accessibility list, takes back the Continue-watching permission,
and clears the records. My Kids TV stays installed; remove it in the TV's Settings → Apps if you want it gone.
Nothing is uninstalled. Exit codes: 0 done, 1 something didn't come back (listed), 2 the TV could not be read.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tvadb  # noqa: E402

PKG = 'io.mykidstv'
GOOGLE_HOME = 'com.google.android.apps.tv.launcherx/.home.HomeActivity'
GOOGLE_SETUP = 'com.google.android.tungsten.setupwraith'


def records():
    disabled = [p for p in tvadb.setting('global', 'free_my_tv_disabled').split(',') if p]
    original = tvadb.setting('global', 'free_my_tv_original').split('|') + ['', '', '']
    return disabled, original[0], original[1], original[2]


def show(disabled, home, dream, saver):
    print(f'Apps My Kids TV turned off ({len(disabled)}): ' + (', '.join(disabled) or 'none'))
    print(f'Original home screen: {home or "not recorded (Google TV\'s, or the system picks)"}')
    print(f'Original screensaver: {dream or "not recorded"}; setting: {saver or "none (the TV\'s default)"}')
    print(f'Home screen now: {tvadb.home_holder() or "unknown"}')


def restore(disabled, home, dream, saver):
    failed = []
    present = tvadb.packages()
    home_pkg = home.split('/')[0] if home else ''
    order = sorted(set(disabled) - {GOOGLE_SETUP})
    if home_pkg in order:
        order.remove(home_pkg)
        order.insert(0, home_pkg)
    for pkg in order:
        if pkg not in present:
            continue
        out = tvadb.shell(f'pm enable {pkg}')
        print(('  on   ' if 'enabled' in out else '  FAIL ') + pkg)
        if 'enabled' not in out:
            failed.append(pkg)
    # Home back: the recorded one, else Google TV's if it is there, else leave the role.
    ok = False
    for candidate in [home, GOOGLE_HOME if GOOGLE_HOME.split('/')[0] in present else '']:
        if not candidate:
            continue
        tvadb.shell(f'pm enable {candidate.split("/")[0]}')
        if 'Success' in tvadb.shell(f"cmd package set-home-activity '{candidate}'"):
            ok = True
            break
    if not ok:
        tvadb.shell(f'cmd role remove-role-holder --user 0 android.app.role.HOME {PKG}')
        ok = PKG not in tvadb.home_holder()
    print(('  on   ' if ok else '  FAIL ') + 'the home screen')
    if not ok:
        failed.append('the home screen')
    # Google TV's setup app only after its home screen is back (alone, its recovery screen takes Home).
    if GOOGLE_SETUP in disabled and GOOGLE_SETUP in present:
        out = tvadb.shell(f'pm enable {GOOGLE_SETUP}')
        print(('  on   ' if 'enabled' in out else '  FAIL ') + GOOGLE_SETUP)
        if 'enabled' not in out:
            failed.append(GOOGLE_SETUP)
        elif home:
            tvadb.shell(f"cmd package set-home-activity '{home}'")
    if saver:
        tvadb.shell(f"settings put secure screensaver_components '{saver}'")
    else:
        tvadb.shell('settings delete secure screensaver_components')
    services = [s for s in tvadb.setting('secure', 'enabled_accessibility_services').split(':') if s and not s.startswith(PKG + '/')]
    tvadb.shell("settings put secure enabled_accessibility_services '" + ':'.join(services) + "'" if services
                else 'settings delete secure enabled_accessibility_services')
    tvadb.shell(f'pm revoke {PKG} android.permission.READ_TV_LISTINGS')
    if not failed:
        tvadb.shell('settings delete global free_my_tv_disabled')
        tvadb.shell('settings delete global free_my_tv_original')
    tvadb.shell('am start -a android.intent.action.MAIN -c android.intent.category.HOME')
    return failed


def main():
    args = sys.argv[1:]
    if args and args[0] in ('-h', '--help'):
        print(__doc__.strip())
        return 0
    listing = '--list' in args
    args = [a for a in args if a != '--list']
    tvadb.connect(args)
    try:
        disabled, home, dream, saver = records()
        show(disabled, home, dream, saver)
        if listing:
            return 0
        if not disabled and not home:
            print('Nothing recorded: My Kids TV has not switched this TV, or Original already ran.')
            return 0
        print('Restoring…')
        failed = restore(disabled, home, dream, saver)
        if failed:
            print('Did not come back: ' + ', '.join(failed) + '. The TV\'s Settings → Apps can enable an app too.')
            return 1
        print('Done: the TV\'s own home screen, screensaver and apps are back. My Kids TV is still installed.')
        return 0
    except RuntimeError as e:
        print(f'Could not read the TV: {e}')
        return 2


if __name__ == '__main__':
    sys.exit(main())
