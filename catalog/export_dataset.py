#!/usr/bin/env python3
"""Exports the vetted catalogs as an open dataset in data/ (CSV, JSON and a README with counts).

  python3 catalog/export_dataset.py

Reads app/assets/catalog/<band>.json (what ships), catalog/work/<band>/selected.json (Jev's judgments) and
catalog/work/<band>/candidates.json (genres, language). Writes only ids, titles, years, genres, ratings and our own
judgments: no TMDB overviews or images. Standard library only; pipeline.py's emit stage runs it too.
"""
import csv, json, os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DATA = os.path.join(ROOT, 'data')
BANDS = ('4-6', '7-10', '11-13')
BADGES = {'must': 'must-watch', 'learn': 'learn-something', 'loved': 'kids-love-it', '': ''}
COLUMNS = ('band', 'kind', 'tmdb_id', 'title', 'year', 'original_language', 'genres', 'age_rating', 'tmdb_url',
           'badge', 'age_fit', 'value', 'must_watch', 'fun', 'score')
NOTICE = 'This product uses the TMDB API but is not endorsed or certified by TMDB.'


def load(path, default):
    if not os.path.exists(path):
        return default
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def band_rows(band):
    app = load(os.path.join(ROOT, 'app', 'assets', 'catalog', band + '.json'), {}).get('titles', [])
    work = os.path.join(HERE, 'work', band)
    sel = {'%s:%d' % (r['kind'], r['id']): r for r in load(os.path.join(work, 'selected.json'), {}).get('keep', [])}
    cand = {'%s:%d' % (c['kind'], c['id']): c for c in load(os.path.join(work, 'candidates.json'), [])}
    rows = []
    for t in app:
        k = '%s:%d' % (t['kind'], t['id'])
        s, c = sel.get(k), cand.get(k, {})
        if s is None:
            raise SystemExit('%s: %s (%s) is in the app catalog but not in selected.json' % (band, t['title'], k))
        rows.append({
            'band': band, 'kind': 'movie' if t['kind'] == 'movie' else 'series', 'tmdb_id': t['id'],
            'title': t['title'], 'year': t.get('year') or '', 'original_language': s.get('lang') or c.get('lang') or '',
            'genres': ';'.join(c.get('genres', [])), 'age_rating': s.get('rating') or '',
            'tmdb_url': 'https://www.themoviedb.org/%s/%d' % (t['kind'], t['id']),
            'badge': BADGES.get(t.get('badge') or '', ''),
            'age_fit': round(s['jr'], 2), 'value': round(s['value'], 2), 'must_watch': round(s['must'], 2),
            'fun': round(s['fun'], 2), 'score': round(t['score'], 3)})
    rows.sort(key=lambda r: (-r['score'], r['title'], r['tmdb_id']))
    return rows


def write_csv(path, rows):
    with open(path, 'w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS, lineterminator='\n')
        w.writeheader(); w.writerows(rows)


def readme(counts):
    table = '\n'.join('| %s | %d | %d | %d |' % (b, m, s, m + s) for b, (m, s) in counts.items())
    tm = sum(m for m, _ in counts.values()); ts = sum(s for _, s in counts.values())
    return f"""# My Kids TV: the open dataset

The films and series My Kids TV shows children, one row per age band and title, with the reasons each one was
kept. Use it to plan a family movie night, build a school or library list, or power your own app. You don't need to
run My Kids TV.

## Files

- [`kids-titles.csv`](kids-titles.csv) and [`kids-titles.json`](kids-titles.json): every band, sorted by band, then
  score (highest first).
- [`4-6.csv`](4-6.csv), [`7-10.csv`](7-10.csv), [`11-13.csv`](11-13.csv): one band each, same columns.

A title can appear in more than one band; each row is that title judged for that age.

## Counts

| Age band | Movies | Series | Total |
|---|---|---|---|
{table}
| All | {tm} | {ts} | {tm + ts} |

## How titles were chosen

1. **Candidates**: best-of research lists (Common Sense Media, BFI, national children's film awards, Indian
   children's cinema), wide sweeps of TMDB, and TMDB's recommendations for every title kept.
2. **Hard filters in code**: official age ratings (US and India), enough votes, a good average.
3. **Jev**, TypeSafe's System One model, judged every title for that age: is it just right for the age (not babyish,
   not too mature), its lasting value, whether it is a must-watch, and whether a child that age will enjoy it.
4. **A person reviewed** the borderline titles. Those decisions are in
   [`catalog/overrides/`](../catalog/overrides/).

## Columns

| Column | Meaning |
|---|---|
| `band` | Age band: `4-6`, `7-10` or `11-13` |
| `kind` | `movie` or `series` |
| `tmdb_id` | TMDB id (movie and series ids are separate number spaces: use it with `kind`) |
| `title` | Title in English on TMDB |
| `year` | Year of release or first episode |
| `original_language` | ISO 639-1 code, e.g. `en`, `ja`, `hi` |
| `genres` | TMDB genres, separated by `;` |
| `age_rating` | Official certificate as stored: US first (`G`, `PG`, `TV-Y7`, ...), else India (`U`, `UA`, ...) or UK for series; empty when there is none |
| `tmdb_url` | The title's TMDB page |
| `badge` | The reason shown on the card: `must-watch`, `learn-something`, `kids-love-it`, or empty |
| `age_fit` | Jev's probability (0-1) that the title is just right for this age |
| `value` | Jev's lasting value, 0-3 (what it leaves behind: curiosity, kindness, courage, knowledge) |
| `must_watch` | Jev's probability (0-1) that it is a must-watch for this age |
| `fun` | Jev's estimate, 0-3, of how much a child this age will enjoy it |
| `score` | Composite used for ordering: 0.45 x value/3 + 0.30 x must_watch + 0.25 x fun/3 (0-1, from unrounded judgments) |

## Regenerate

```bash
python3 catalog/export_dataset.py
```

It reads the shipped catalogs (`app/assets/catalog/`) and the pipeline's work files (`catalog/work/`). The
pipeline's `emit` stage runs it too, so `data/` follows every catalog change.

## Limitations

- The judgments are a model's, plus one reviewer's. They are a starting point, not a verdict.
- Streaming availability varies by country and changes often, so it is not included.
- Age ratings come from TMDB and can be missing or differ from your country's.
- Children differ. Check a title yourself before you show it to your child.

## License

The curation and judgments (which titles are in each band, the badges and the Jev scores) are licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/): credit "My Kids TV". See [LICENSE](LICENSE).

Title metadata (ids, titles, years, genres, ratings) comes from [TMDB](https://www.themoviedb.org). If you use it,
carry this notice: *{NOTICE}*
"""


LICENSE = f"""My Kids TV open dataset

Copyright (c) My Kids TV contributors.

The curation and judgments in this folder (the selection of titles per age band, the badges, and the age_fit,
value, must_watch, fun and score columns) are licensed under the Creative Commons Attribution 4.0 International
License (CC BY 4.0). You may share and adapt them for any purpose, including commercially, as long as you give
appropriate credit ("My Kids TV"), link to the license, and indicate if changes were made.

Full license: https://creativecommons.org/licenses/by/4.0/

Title metadata (TMDB ids, titles, years, genres and age ratings) comes from TMDB and is subject to TMDB's terms of
use: https://www.themoviedb.org/api-terms-of-use
{NOTICE}
"""


def export():
    os.makedirs(DATA, exist_ok=True)
    everything, counts = [], {}
    for band in BANDS:
        rows = band_rows(band)
        write_csv(os.path.join(DATA, band + '.csv'), rows)
        counts[band] = (sum(r['kind'] == 'movie' for r in rows), sum(r['kind'] == 'series' for r in rows))
        everything += rows
    write_csv(os.path.join(DATA, 'kids-titles.csv'), everything)
    with open(os.path.join(DATA, 'kids-titles.json'), 'w', encoding='utf-8') as f:
        json.dump({'name': 'My Kids TV kids titles', 'license': 'CC-BY-4.0 (curation and judgments); title metadata from TMDB',
                   'notice': NOTICE, 'columns': list(COLUMNS), 'titles': everything}, f, indent=1, ensure_ascii=False)
        f.write('\n')
    with open(os.path.join(DATA, 'README.md'), 'w', encoding='utf-8') as f:
        f.write(readme(counts))
    with open(os.path.join(DATA, 'LICENSE'), 'w', encoding='utf-8') as f:
        f.write(LICENSE)
    print('wrote data/ (%s; %d rows)' % (', '.join('%s: %d' % (b, m + s) for b, (m, s) in counts.items()), len(everything)))
    return counts


if __name__ == '__main__':
    export()
