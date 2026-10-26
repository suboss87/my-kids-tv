# My Kids TV: the open dataset

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
| 4-6 | 72 | 34 | 106 |
| 7-10 | 268 | 104 | 372 |
| 11-13 | 324 | 129 | 453 |
| All | 664 | 267 | 931 |

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
carry this notice: *This product uses the TMDB API but is not endorsed or certified by TMDB.*
