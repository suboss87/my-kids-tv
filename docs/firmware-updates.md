# After a TV update

[Back to the README](../README.md)

A TV update installs over the cleaned-up system: the promo apps My Kids TV turned off can come back on, the Home
button can go back to the stock launcher, and the screensaver can change. My Kids TV notices and puts it right.

## What the app does by itself

| When | What |
|---|---|
| **The TV boots** (every update ends with a restart) | 45 seconds after boot, in My Kids TV mode, the app checks that everything is as you left it: the listed apps off, its home screen and screensaver in place, the remote's button service on, the Continue-watching permission granted. What came back on goes off again. |
| **Once a day** while My Kids TV is on | The same check, as a daily background job, for updates that arrive in pieces and maker services that turn their apps back on without a restart. |
| **My Kids TV itself was updated** | The same check, right away (Android takes back a runtime permission and the accessibility service at an app update). |

Each check writes a notice you can read in the app's settings (NOTICES) and, when something was put back, a bubble on
the home screen: "The TV update turned back on …". The *Home Screen* page shows when the last check ran.

Two things a check never does: it never turns off an app that saved a Wi-Fi network (the TV would lose the network),
and it never turns off Google TV's setup app while the stock home screen is on (its recovery screen would take Home).
Those wait for the next check.

## From a computer

```bash
python3 tools/check.py 192.168.1.50          # report; exit 1 when something drifted
python3 tools/check.py 192.168.1.50 --fix    # run the app's own check now, then report again
```

`check.py` reads the TV's records (what was turned off, the home screen that was replaced) and compares them with the
TV: which of those apps are on again, who holds the Home button, which screensaver is set. `--fix` asks the My Kids TV
on the TV to run its check job (the one that knows every brand's rules), waits half a minute and reports again. Exit
codes: 0 in sync, 1 drift, 2 the TV could not be read, 3 the check could not be started, so it fits a cron job.

## If the TV update broke more than that

- Press Home. If a strange screen appears instead of My Kids TV or the stock launcher, open the My Kids TV app from the
  TV's apps and choose *Home Screen → Original*, then switch back.
- If My Kids TV will not open, [docs/restore.md](restore.md) puts the TV back from a computer with adb alone.
