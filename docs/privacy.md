# What leaves the TV, and what never does

[Back to the README](../README.md)

My Kids TV has no accounts, no analytics and no ads. This page says exactly what the app sends out, to whom, and
what stays on the TV, so a parent can decide with the facts.

## What never leaves the TV

- **Your child.** Their name, their age band, their photo (if you added one), what they opened and when: all of it is
  kept in the app's own storage on the TV and nowhere else. No server of ours exists.
- **The parent PIN.** The TV keeps only a salted SHA-256 hash of it. The PIN itself is never stored, never sent,
  never logged.
- **Your keys.** The TMDB key (and the optional Watchmode key) are yours. They are sent only to that service, with its
  own requests, and never written to the log.
- **Your Wi-Fi, your other apps' data.** The app never reads them. The switch to My Kids TV mode talks to the TV's
  own Android Debug Bridge on the TV itself (127.0.0.1); nothing listens on the network.

## What the app asks the internet for

| Service | What is sent | Why | When |
|---|---|---|---|
| **TMDB** (themoviedb.org) | Your TMDB key, your country code, the TV's language, and the ids of catalog titles | Titles, pictures, which app streams each title where you live | A few times a day, and when the age band or the apps change |
| **TMDB images** (image.tmdb.org) | The path of a picture | The artwork on the cards | When a title first shows |
| **Open-Meteo** (open-meteo.com) | Your city's coordinates (from `config.properties`, the settings, or the TV's time zone) | The weather on the home screen and the sunset that turns it dark | About every 30 minutes while the home screen is on; off with the weather widget |
| **Watchmode** (optional, only if you set a key) | Your Watchmode key and a title's TMDB id | A title's own page in its app, where the catalog has no address | Only for titles that need it |
| **Open-Meteo geocoding** | The city name you type | The City picker in Home Settings | Only when you search a city |

That is the whole list. The app opens no other connection. Each of these is a plain HTTPS request with no cookie,
no device id and nothing about your child.

## What stays on the TV, and can be read with ADB

The TV's own settings keep two small records so the way back works even without the app: the apps My Kids TV
turned off (`free_my_tv_disabled`) and the home screen it replaced (`free_my_tv_original`). They hold package
names, nothing else. [docs/restore.md](restore.md) explains how they are used.

## Continue watching

With *Continue watching* on (Home Settings), the app reads the rows the streaming apps publish to Android TV's own
"Watch Next" list, on the TV, with the `READ_TV_LISTINGS` permission the switch grants. It keeps only rows that are
titles in your child's catalog and shows at most one per tab. Nothing about those rows leaves the TV, and turning
the setting off stops the reading.

## The one network risk to know about

My Kids TV mode needs USB debugging on, on the TV, so the app can switch modes through the TV's own ADB. ADB on a
TV listens on the local network. Keep the TV on your home Wi-Fi and never forward port 5555 on your router: anyone
who can reach that port can control the TV. *Original* mode does not need it; you can turn USB debugging off then.

## Sending settings from a computer

`tools/push-config.py` sends a `config.properties` to the app over ADB. The file can carry the TMDB key and a new
parent PIN. They go straight into the app's storage on the TV; the tool prints their names, never their values.
