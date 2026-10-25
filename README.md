# My Kids TV

**A calm, ad-free TV home screen for children, with only the films and shows worth their time.**

My Kids TV replaces your Google TV or Android TV home screen with one made for kids. Instead of ads and whatever is
trending, it shows a rotating handful of titles chosen for your child's age: films and series that are fun *and*
leave something behind (curiosity, kindness, courage, science, nature, history, other cultures).

It is a fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT), rebuilt around children.

## For parents

- **Picks for your child's age.** Ages 4-6, 7-10 or 11-13. Every title in the catalog earned its place (see below).
- **Only what you can watch.** A title appears only when it's on a streaming app you have (Netflix, Prime Video,
  JioHotstar, ...), and opens straight in that app.
- **Always fresh.** Three picks at a time, new ones every hour, and nothing shown recently comes back for days.
- **No ads, no tracking.** Nothing about your child leaves the TV. No account, no analytics.
- **Fewer distractions.** Promo apps are turned off, and on supported remotes (Haier so far) the YouTube button can be
  made to do nothing.
- **Safe.** No root. Nothing is uninstalled. One button brings the TV's original home screen back.

## How the catalog is chosen

For each age band:

1. **Candidates** from research into best-of lists (Common Sense Media, BFI, national children's film awards, Indian
   children's cinema), wide sweeps of [TMDB](https://www.themoviedb.org), and TMDB's recommendations for every title kept.
2. **Hard rules in code:** the official age ratings (US and India), enough votes, a good average.
3. **Jev**, [TypeSafe](https://typesafe.ai)'s System One model, judges every title for that age: is it right for the
   age (not babyish, not too mature), how much lasting value it has, is it a must-watch, and will a child that age
   actually enjoy it.
4. **A person reviews** everything borderline before it ships.

The 7-10 catalog: 344 titles (241 films, 103 series) kept from 1,647 candidates, from *My Neighbor Totoro*, *Coco* and
*The Iron Giant* to *Planet Earth*, *Avatar: The Last Airbender*, *Taare Zameen Par* and Satyajit Ray's *Sonar Kella*.
The pipeline is in [`catalog/`](catalog/pipeline.py); the 4-6 and 11-13 catalogs are being built the same way.

## Set it up

About twenty minutes. You need a Google TV or Android TV (Android 8 or newer) and a computer on the same Wi-Fi.

1. **On the TV**, turn on developer mode: Settings, System, About, press *Build* 7 times. Then Developer options,
   **USB debugging** on. Note the TV's IP address (Settings, Network & Internet).
2. **On your computer**, open an AI coding assistant ([Claude Code](https://claude.com/claude-code), Codex, ...) in
   this folder and say:
   > Set up My Kids TV on our TV at 192.168.1.50 for my 8-year-old.

   It asks you a few questions (your child's age, your country and city, the streaming apps you pay for, whether to
   block the YouTube button), installs what it needs, builds the app and puts it on your TV. It asks before anything
   important.
3. **On the TV**, open *My Kids TV*, *Home Screen*, *My Kids TV*, and allow USB debugging when asked.

You'll need a free [TMDB API key](https://www.themoviedb.org/settings/api) for the picks; the assistant tells you
where to paste it.

## Status

Working and in daily use on a Haier MatrixTV BE (Google TV 14) with the 7-10 catalog. Next: the 4-6 and 11-13
catalogs, a parent PIN for settings and a new glass design. The LG (webOS) and Samsung (Tizen) versions use the same
kids catalogs but are not yet tested on a TV.

## Credits

- [Free My TV](https://github.com/simbasjungles/free-my-tv) by its contributors: the home screen, the switch and most
  of the code (MIT).
- Titles, images and availability: [TMDB](https://www.themoviedb.org). *This product uses the TMDB API but is not
  endorsed or certified by TMDB.*
- Title judgments: Jev by [TypeSafe](https://typesafe.ai).
- Weather: [Open-Meteo](https://open-meteo.com).

MIT License, see [LICENSE](LICENSE).
