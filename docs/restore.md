# The way back

[Back to the README](../README.md)

Everything My Kids TV changes on a TV can be undone, from the TV or from a computer, and nothing is ever uninstalled.

## From the TV: Original

Open *My Kids TV → Home Screen → Original* (behind the parent PIN). The app turns the apps back on, hands the Home
button back to the TV's own home screen, puts the screensaver back, takes its remote-button service out of the
accessibility list, and takes back the Continue-watching permission. The notice says what came back, and names
anything that did not.

## What the TV records at switch time

Before the switch turns anything off, the app writes down what it found, in two places: its own state file and the
TV's own settings. The second copy is what makes a restore possible with the app gone or broken.

| Record (TV settings, global) | Holds |
|---|---|
| `free_my_tv_disabled` | The packages My Kids TV turned off, comma separated. An app you had turned off yourself before My Kids TV is not on it, so a restore never turns it on. |
| `free_my_tv_original` | The home screen it replaced, the TV's screensaver service, and the screensaver setting, as `home|dream|screensaver`. |

Both hold package and component names only. *Original* deletes them when everything is back.

## From a computer: tools/restore.py

```bash
python3 tools/restore.py 192.168.1.50 --list   # show the records, change nothing
python3 tools/restore.py 192.168.1.50          # restore
```

It needs only adb (the Android platform tools) and USB debugging on, on the TV. It reads the two records, turns the
apps back on (the stock home screen first, Google TV's setup app last, after its home screen is back), hands Home to
the recorded home screen (or Google TV's, or lets the system pick), puts the screensaver back, removes the
remote-button service, takes back the permission, and clears the records. Exit code 1 lists whatever did not come
back; the TV's own *Settings → Apps* can enable an app too.

My Kids TV stays installed either way. Remove it in the TV's *Settings → Apps* if you want it gone; the records are
already cleared by then.

## Without a computer and without the app

The TV's own *Settings → Apps → See all apps → Show system apps* lists the disabled apps; *Enable* brings each back.
The home screen: *Settings → Apps → Default apps → Home app* on Google TV, or press Home and pick when asked.
