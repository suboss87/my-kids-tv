# My Kids TV

**A calm, ad-free TV home screen for children, with only the films and shows worth their time.**

My Kids TV replaces your TV's home screen with one made for kids. Instead of ads, autoplay and whatever is trending,
your child sees three titles at a time, chosen for their age from a catalog where every title earned its place:
fun to watch, and leaving something behind (curiosity, kindness, courage, science, nature, history, other cultures).

Runs on Google TV and Android TV, and as an app on LG (webOS) and Samsung (Tizen) TVs.

## What your child sees

- **A greeting** with their name: *Good morning, Maya*.
- **Three picks**, changing every hour, from the catalog for their age: **4-6**, **7-10** or **11-13**.
- **Why each one is worth it**: a small badge on the card, *Must-watch*, *Learn something* or *Kids love it*.
- **Only what you can play**: a title appears only when it's on a streaming app you have (Netflix, Prime Video,
  JioHotstar, Disney+, ...), and opens straight in that app.
- **Nothing on repeat**: what was shown lately stays away for days.
- **Your apps on a glass shelf**, big soft cards, a gentle bounce when something is picked, light by day and dark at
  night.

## What you control

- **A parent PIN** guards the settings and the way back to the TV's own home screen.
- **The age band**, switchable any time in the settings.
- **The YouTube button** on the remote can be made to do nothing (Haier, TCL, Hisense and Sony remotes); the YouTube
  app itself stays.
- **Apps to hide**: turned off when the TV switches, never uninstalled.
- **No ads, no tracking, no account.** Nothing about your child leaves the TV. The app only asks TMDB for titles and
  pictures and Open-Meteo for the weather.
- **One button back**: *Original* restores the TV's own home screen and everything that was turned off. No root.

## The catalogs

| Age | Titles | For example |
|---|---|---|
| 4-6 | 87 | *My Neighbor Totoro*, *Bluey*, *Paddington 2*, *The Magic School Bus*, *Finding Nemo* |
| 7-10 | 344 | *Coco*, *The Iron Giant*, *Planet Earth*, *Avatar: The Last Airbender*, *Taare Zameen Par*, *Sonar Kella* |
| 11-13 | 461 | *Princess Mononoke*, *Interstellar*, *To Kill a Mockingbird*, *The Man Who Knew Infinity*, *Dangal* |

How a title gets in:

1. **Candidates**: research into best-of lists (Common Sense Media, BFI, national children's film awards, Indian
   children's cinema), wide sweeps of [TMDB](https://www.themoviedb.org), and TMDB's recommendations for every title
   kept. About 4,800 titles across the three ages.
2. **Hard rules in code**: official age ratings (US and India), enough votes, a good average.
3. **Jev**, [TypeSafe](https://typesafe.ai)'s System One model, judges every title for that age: right for the age
   (not babyish, not too mature), lasting value, must-watch, and whether a child that age will actually enjoy it.
4. **A person reviews** every borderline title, and anything that slipped through, before it ships.

Everything is in [`catalog/`](catalog/): the pipeline, each band's candidates, Jev's judgments and the review
decisions (`catalog/overrides/`), so every choice can be read, questioned and changed.

## Set it up

About twenty minutes, guided by an AI assistant. You need the TV and a computer on the same Wi-Fi.

1. **On the TV**, turn on developer mode: Settings, System, About, press *Build* 7 times. Then Developer options,
   **USB debugging** on. Note the TV's IP address (Settings, Network & Internet). On LG or Samsung, the assistant
   walks you through their developer mode instead.
2. **On your computer**, open an AI coding assistant ([Claude Code](https://claude.com/claude-code), Codex, ...) in
   this folder and say:
   > Set up My Kids TV on our TV at 192.168.1.50 for my 8-year-old.

   It asks a few questions (your child's age and name, your city, the streaming apps you pay for, a parent PIN,
   whether to block the YouTube button, apps to hide), then installs what it needs, builds the app and puts it on your
   TV, asking before anything important.
3. **On the TV**, open *My Kids TV*, *Home Screen*, *My Kids TV*, and allow USB debugging when asked.

You'll need a free [TMDB API key](https://www.themoviedb.org/settings/api) for the picks; the assistant tells you
where to paste it, and it never leaves your computer and TV.

## Make your own catalog

The catalogs ship with the app. To refresh one, or build one for another age or culture, you need a TMDB key and a
TypeSafe key:

```bash
TYPESAFE_API_KEY=... python3 catalog/pipeline.py --band 7-10 all
```

Then review `catalog/work/<band>/review.json`, record decisions in `catalog/overrides/<band>.json`, and run
`python3 catalog/pipeline.py --band 7-10 select && python3 catalog/pipeline.py --band 7-10 emit`. Age bands and their
rules live in [`catalog/bands.py`](catalog/bands.py).

## Status

Built and checked (`tools/check.sh`: the Android build, all 25 translations, the LG and Samsung packages). The kids
picks were proven on a Haier MatrixTV BE (Google TV 14); the new design, the parent PIN and the LG and Samsung apps
still need their run on real TVs. Issues and pull requests
are welcome.

## Credits

- Titles, images and availability: [TMDB](https://www.themoviedb.org). *This product uses the TMDB API but is not
  endorsed or certified by TMDB.*
- Title judgments: Jev by [TypeSafe](https://typesafe.ai).
- Weather: [Open-Meteo](https://open-meteo.com).

MIT License, see [LICENSE](LICENSE).

Thanks to [Free My TV](https://github.com/simbasjungles/free-my-tv) for the inspiration and the open-source code My Kids TV grew from.
