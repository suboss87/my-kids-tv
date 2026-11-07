# Security

My Kids TV switches a TV's home screen through the TV's own ADB and installs itself as the home screen a child
uses, so a security bug here reaches a family's living room. Please report anything you find privately, not in a
public issue.

## How to report

Use GitHub's private vulnerability reporting: open the
[Security tab](https://github.com/suboss87/my-kids-tv/security) and choose **Report a vulnerability**. Only the
maintainer sees it.

Please include what is affected (the app, `tools/`, the switch, the catalog pipeline, the LG or Samsung app), the
steps to reproduce it, and what someone could do with it. A fix is usually out within days; you will be credited in
the release notes unless you prefer not to be.

## What is in scope

- The Android TV app (`app/`): the home screen, the settings and the parent PIN, the mode switch (`HomeMode`) and
  what it turns off, the content providers only ADB may write (`PhotoInbox`, `ConfigInbox`), the accessibility
  service for the remote's buttons, the Watch Next reader.
- The tools a parent runs on a computer (`tools/`), and what they send to the TV.
- The LG (webOS) and Samsung (Tizen) apps (`web/`).

## What is not a vulnerability here

- Needing USB debugging on, on the TV, for My Kids TV mode: that is how the switch works, and the README and
  [docs/privacy.md](docs/privacy.md) say so and say never to forward port 5555.
- A child who knows the parent PIN. Change it with `tools/push-config.py --set parent_pin=…`.

## Principles the code keeps

- Nothing is uninstalled; only `pm disable-user`, so *Original* and `tools/restore.py` can undo it.
- No account, no analytics, no server of ours. See [docs/privacy.md](docs/privacy.md) for every connection.
- Keys and the PIN never go to the log. The PIN is stored only as a salted hash.
