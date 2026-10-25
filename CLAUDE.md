# My Kids TV: setup guide for AI assistants

You are helping a **parent** set up My Kids TV on their family's TV. Most parents are not technical. Keep every
message short and friendly, explain what you are about to do on their TV before you do it, and let them do the taps
on the TV. Ask before anything that changes their TV or their computer.

The deep technical reference (builds, adb, every TV brand's quirks) is [docs/TECHNICAL.md](docs/TECHNICAL.md). Read
the parts you need; this file is the flow.

## 1. The interview

Ask these in one or two short batches, in plain words, with the choices shown. Every answer goes into
`config.properties` (created by the first `./build.sh`; never committed).

| Ask | Why | Goes to |
|---|---|---|
| How old is your child? (several children: the youngest) Show the bands: **4-6**, **7-10**, **11-13**. | Picks the vetted catalog | `age_band` |
| Your child's first name? (optional) | The home screen greets them: "Good morning, Viva" | `child_name` |
| A photo of your child for the settings? (optional) | Shown on the parents' screen only | save it as `child-photo.png` next to `build.sh` (git-ignored; never commit it) |
| Which country and city do you live in? | Which titles are on which app in their country; weather and the evening dark theme | `tmdb_region`, `latitude`, `longitude`, `city` |
| Which streaming apps do you pay for? (Netflix, Prime Video, JioHotstar, Disney+, ...) | Apps on the TV they don't subscribe to are left out of the picks | `hide_services` |
| Should the remote's **YouTube button** do nothing? | Children can't jump into YouTube with one press (the YouTube app stays) | `block_youtube_button` |
| A 4-digit **parent PIN** for the settings (recommended: otherwise whoever opens the settings first creates it) | Children can't change the settings or switch back to the TV's own home screen | `parent_pin` |
| Are there apps on the TV your child shouldn't see at all? | Turned off (never uninstalled) when the TV switches to My Kids TV | ask, then see step 4 |
| Your TV's brand and model, and its IP address (Settings, Network & Internet) | Install | - |

Geocode the city with `https://geocoding-api.open-meteo.com/v1/search?name=<city>&count=5` and confirm the match with
them. Temperature: `C` outside the US unless they say otherwise.
A `parent_pin` is built into the app file: tell them to keep `MyKidsTV.apk` to themselves.

**The TMDB key is theirs and secret.** Ask them to create a free one (themoviedb.org, Settings, API) and paste it after
`tmdb_key=` in `config.properties` themselves. Never print it, never commit it. If they paste it in the chat anyway,
put it only in `config.properties`. Without a key everything works except the picks.

## 2. Tools and build

A JDK 17+, the Android SDK command-line tools (build-tools 34, platform android-34) and adb: check what is there and
install only what is missing (docs/TECHNICAL.md, "Installing it for someone"). Then `./build.sh` must end with
`Built MyKidsTV.apk`. Keep `mykidstv.keystore`: updates must be signed with the same key.

## 3. Install

On the TV (they do it): Settings, System, About, press *Build* 7 times; then Developer options, **USB debugging** on.
Then `adb connect <IP>:5555` (the TV asks *Allow USB debugging?*: *Always allow*) and `adb install -r MyKidsTV.apk`.
On Android 14 Google Play Protect may hold the install with *Risky app blocked*: they choose *More details*, *Install
anyway*. If `adb install` hangs, look at the TV first: that prompt is usually why.

## 4. Switch on the TV

Tell them first: the home screen and screensaver change, a short list of promo apps is turned off (never uninstalled),
and *Original* in the My Kids TV app brings everything back. Then they open **My Kids TV, Home Screen, My Kids TV**
and accept *Allow USB debugging?* once more (this time for the app itself). USB debugging must stay on: the app uses it
to switch modes. Never forward port 5555 on the router.

Apps they asked to hide: with their OK, `adb shell pm disable-user --user 0 <package>` for each, and write the list to
`kids-disabled.txt` (not committed) so `pm enable <package>` can bring them back. Never uninstall or clear data.

## 5. Check

`adb logcat -s MyKidsTV` shows what the switch did. Press Home: My Kids TV opens. Wait a minute: the picks load (some
ISPs, India's included, reset many connections to TMDB; the app retries, so the first load can take a while). Have
them try the remote's Home, Back, microphone and app buttons, and the YouTube button if they blocked it.

## Parent PIN

The My Kids TV settings (the app's icon, the home screen's gear) ask for a 4-digit parent PIN every time they open;
Back leaves to the home screen, and 5 wrong tries wait 30 seconds. The TV keeps only a salted hash of it. Age and the
YouTube button can also be changed there (Home Settings).

**Forgotten PIN:** with their OK, put a new one after `parent_pin=` in `config.properties`, run `./build.sh` and
`adb install -r MyKidsTV.apk`: a new `parent_pin` replaces the PIN on the TV, and the other settings stay. Never use
`adb shell pm clear io.mykidstv` (or clear the app's data in the TV's settings) for this: it wipes every setting, the
home screen choice and the photos too.

## Catalogs

`app/assets/catalog/<band>.json` holds the vetted titles per age band. They come from `catalog/pipeline.py`:

1. candidates from seed lists, research lists and TMDB sweeps;
2. hard filters in code: age ratings (US and India), enough votes, a good average;
3. **Jev** (TypeSafe's System One model) judges each title for that age: age fit, lasting value, must-watch, fun and,
   from age 7, craft (`python3 catalog/pipeline.py --band <band> craft`);
4. a policy in code keeps the strong ones, and a person reviews the borderline ones (`catalog/overrides/<band>.json`).

Parents don't need to run it: the catalogs ship with the app. To refresh or build one:
`TYPESAFE_API_KEY=... python3 catalog/pipeline.py --band 7-10 all` (needs their TMDB key too), then review
`catalog/work/<band>/review.json` and record decisions in `catalog/overrides/<band>.json`.

## Always

- Never commit `config.properties`, `*.keystore`, `kids-disabled.txt` or any key.
- No analytics, no accounts, nothing about the child leaves the TV. The app talks only to TMDB (titles and images),
  Open-Meteo (weather) and, if set, Watchmode.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, so it can come back. Never
  `pm clear` My Kids TV either (a forgotten PIN: see "Parent PIN").
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: *Original* mode and the watchdog.
