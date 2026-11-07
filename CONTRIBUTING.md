# Contributing

Thank you. My Kids TV is one parent's project that other parents can use; the most useful contributions are the
ones that make it work on one more TV, in one more country, or for one more family.

## Where does my idea go?

| Your idea | Where it goes |
|---|---|
| A TV model that needs a quirk (a remote button, a package to keep on, a home screen to record) | A pull request: `HomeMode` lists (`DEBLOAT`, `PROTECTED`), `RemoteKeys`, and a line in [docs/TECHNICAL.md](docs/TECHNICAL.md) |
| A streaming app the picks should know | `SERVICES` in `Recs.java` (TMDB's provider name, our id, the TV package) and the `<queries>` list in the manifest |
| A title that should (or should not) be in a catalog | An issue with the title, the age band and why; or a line in `catalog/overrides/<band>.json` and a rerun of the pipeline |
| A translation fix | The language's file in `app/assets/i18n/`; `python3 tools/translate.py --check` must pass |
| A design change to the home screen | An issue first, with a screenshot or a sketch: the design principles are in [PRODUCT.md](PRODUCT.md) |
| A whole other launcher or a port | Your own repo; tell us and we will link it |

## Before you open a pull request

- Read [PRODUCT.md](PRODUCT.md): calm, few choices, the artwork is the hero, every badge tells the truth.
- Keep the way back working: nothing uninstalled, only `pm disable-user` through `HomeMode`; *Original* and
  `tools/restore.py` must still undo everything. Never turn off an app that saved a Wi-Fi network.
- No new dependencies in the app (plain Java, the Android SDK only; no Gradle). `web/` stays ES5.
- Run `tools/check.sh`: it builds the app, checks the 25 translations and the LG and Samsung packages. CI runs the
  same.
- Say which TV you tested on (brand, model, Android version), or that you could not. A change to the switch needs a
  real TV.
- Never commit `config.properties`, a keystore, a key, a child's name or photo, or your LAN addresses.
- One topic per pull request. Small is fine.

## Reporting a problem

Open an issue with the TV's brand and model, its Android version, what you did, what happened, and what you
expected. `adb logcat -s MyKidsTV` has the app's own log; it never contains keys or the PIN, so it is safe to paste.
For a security problem, see [SECURITY.md](SECURITY.md).

## Working with an AI assistant

[CLAUDE.md](CLAUDE.md) and [AGENTS.md](AGENTS.md) are written for coding assistants and hold the same rules. A pull
request made with one is welcome; please read the diff yourself before you send it, and test it on a TV where you can.
