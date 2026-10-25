#!/usr/bin/env python3
"""Builds a vetted kids catalog for one age band.

  python3 catalog/pipeline.py --band 7-10 all        # candidates -> judge -> select -> expand -> judge -> select -> emit
  python3 catalog/pipeline.py --band 7-10 select     # re-apply policy + overrides only (no API calls)

Stages: 1) candidates from seeds, research lists and TMDB sweeps; 2) hard age-rating and quality filters (code);
3) Jev judges age fit, value, must-watch and fun; 4) policy in code plus overrides/<band>.json (a person's review);
5) app/assets/catalog/<band>.json, which the app reads. Needs $TYPESAFE_API_KEY for judging, a TMDB key for the rest.
"""
import argparse, concurrent.futures as cf, json, os, sys, time, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tmdb import get
from bands import BANDS, questions, audience

HERE = os.path.dirname(os.path.abspath(__file__))
INDIAN = ('hi', 'ta', 'te', 'ml', 'bn', 'mr', 'kn', 'gu')

def work(band, name):
    d = os.path.join(HERE, 'work', band); os.makedirs(d, exist_ok=True)
    return os.path.join(d, name)

def load(path, default):
    return json.load(open(path)) if os.path.exists(path) else default

# --- Stage 1: candidates ------------------------------------------------------------------------------------------

def search(kind, title, year):
    dk = 'primary_release_year' if kind == 'movie' else 'first_air_date_year'
    try:
        r = ((get('/search/' + kind, query=title, **({dk: year} if year else {})) or {}).get('results')
             or (get('/search/' + kind, query=title) or {}).get('results') or [])
    except RuntimeError:
        return None
    return r[0]['id'] if r else None

def seeds(band):
    named = []
    for f in ('seeds/%s.json' % band, 'seeds/research-%s.json' % band):
        d = load(os.path.join(HERE, f), {})
        named += [('movie', m['title'], m.get('year')) for m in d.get('movies', [])]
        named += [('tv', m['title'], m.get('year')) for m in d.get('shows', [])]
    ids = {}
    with cf.ThreadPoolExecutor(12) as ex:
        for (kind, t, y), i in zip(named, ex.map(lambda n: search(*n), named)):
            if i: ids[(kind, i)] = 'seed'
    return ids

def sweeps(band):
    b = BANDS[band]; jobs = []
    for s in b['movie_sweeps']:
        for p in range(1, 6):
            jobs.append(('movie', dict(s, **{'vote_count.gte': 300, 'vote_average.gte': 7.0}, sort_by='vote_average.desc', page=p)))
    for lang in INDIAN[:7]:
        for p in range(1, 3):
            jobs.append(('movie', dict(with_original_language=lang, with_genres='10751|16|12', **{'vote_count.gte': 30, 'vote_average.gte': 7.0}, sort_by='vote_average.desc', page=p)))
    for s in b['tv_sweeps']:
        for p in range(1, 5):
            jobs.append(('tv', dict(s, **{'vote_count.gte': 50, 'vote_average.gte': 7.2}, sort_by='vote_average.desc', page=p)))
    def run(j):
        try: return j[0], (get('/discover/' + j[0], **j[1]) or {}).get('results', [])
        except RuntimeError: return j[0], []
    ids = {}
    with cf.ThreadPoolExecutor(12) as ex:
        for kind, res in ex.map(run, jobs):
            for m in res: ids.setdefault((kind, m['id']), 'sweep')
    return ids

# --- Stage 2: hard filters ----------------------------------------------------------------------------------------

def details(key):
    kind, i = key
    extra = 'release_dates,keywords' if kind == 'movie' else 'content_ratings,keywords'
    try:
        return key, get('/%s/%d' % (kind, i), append_to_response=extra, language='en-US')
    except RuntimeError:
        return key, None

def hard_filter(band, keys):
    b = BANDS[band]; kept, dropped = [], {}
    with cf.ThreadPoolExecutor(12) as ex:
        for (kind, i), d in ex.map(details, list(keys)):
            if not d or d.get('adult'): continue
            lang = d.get('original_language'); why = None
            if kind == 'movie':
                certs = {c['iso_3166_1']: [r['certification'] for r in c['release_dates'] if r['certification']]
                         for c in d.get('release_dates', {}).get('results', [])}
                us = (certs.get('US') or [''])[0]; inc = (certs.get('IN') or [''])[0]
                if us in b['us_movie_bad'] and inc not in b['in_movie_ok']: why = 'rating'
                elif inc and inc not in b['in_movie_ok'] and us not in b['us_movie_ok']: why = 'rating'
                minv = 30 if lang in INDIAN else 150
                rating = us or inc
            else:
                rs = {c['iso_3166_1']: c['rating'] for c in d.get('content_ratings', {}).get('results', [])}
                us = rs.get('US', ''); rating = us or rs.get('IN', '') or rs.get('GB', '')
                if us in b['tv_bad']: why = 'rating'
                minv = 30
            if not why and d.get('vote_count', 0) < minv: why = 'votes'
            if not why and d.get('vote_average', 0) < 6.8: why = 'avg'
            if not why and not d.get('backdrop_path'): why = 'image'
            if why:
                dropped[why] = dropped.get(why, 0) + 1; continue
            kept.append({'kind': kind, 'id': i, 'title': d.get('title') or d.get('name'),
                         'year': (d.get('release_date') or d.get('first_air_date') or '')[:4],
                         'genres': [g['name'] for g in d.get('genres', [])], 'rating': rating, 'lang': lang,
                         'vote_average': d.get('vote_average'), 'vote_count': d.get('vote_count'),
                         'overview': (d.get('overview') or '')[:700],
                         'keywords': [k['name'] for k in (d.get('keywords', {}).get('keywords') or d.get('keywords', {}).get('results') or [])][:15]})
    return kept, dropped

def stage_candidates(band, extra=None):
    path = work(band, 'candidates.json')
    have = {(c['kind'], c['id']) for c in load(path, [])}
    keys = extra if extra is not None else {**sweeps(band), **seeds(band)}
    new = {k: v for k, v in keys.items() if k not in have}
    kept, dropped = hard_filter(band, new)
    json.dump(load(path, []) + kept, open(path, 'w'), indent=1)
    print('[%s] candidates +%d (dropped %s)' % (band, len(kept), dropped), flush=True)

def stage_expand(band):
    """Round 2: TMDB recommendations of every title kept so far."""
    keep = load(work(band, 'selected.json'), {}).get('keep', [])
    def recs(r):
        out = []
        for p in ('/%s/%d/recommendations', '/%s/%d/similar'):
            try: d = get(p % (r['kind'], r['id']), page=1, language='en-US') or {}
            except RuntimeError: continue
            out += [(r['kind'], m['id']) for m in d.get('results', []) if not m.get('adult')]
        return out
    keys = {}
    with cf.ThreadPoolExecutor(12) as ex:
        for lst in ex.map(recs, keep):
            for k in lst: keys.setdefault(k, 'recommended')
    stage_candidates(band, keys)

# --- Stage 3: Jev -------------------------------------------------------------------------------------------------

def ask(band, c, key):
    state = {'audience': audience(band), 'title': c['title'], 'year': c['year'],
             'type': 'movie' if c['kind'] == 'movie' else 'TV series', 'genres': c['genres'],
             'age_rating': c['rating'] or 'unknown', 'original_language': c['lang'],
             'tmdb_rating': '%.1f/10 from %d votes' % (c['vote_average'], c['vote_count']),
             'synopsis': c['overview'], 'keywords': c['keywords']}
    body = json.dumps({'model': 'jev-latest', 'state': state, 'questions': questions(band)}).encode()
    err = None
    for i in range(6):
        try:
            req = urllib.request.Request('https://api.typesafe.ai/v1/systemone', data=body,
                                         headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:
            err = e; time.sleep(1.5 * (i + 1))
    return {'error': str(err)[:200]}

def stage_judge(band):
    key = os.environ.get('TYPESAFE_API_KEY')
    if not key: raise SystemExit('Set TYPESAFE_API_KEY to judge titles with Jev (or use the shipped catalogs).')
    path = work(band, 'judgments.json'); done = load(path, {})
    todo = [c for c in load(work(band, 'candidates.json'), []) if '%s:%d' % (c['kind'], c['id']) not in done]
    print('[%s] judging %d (cached %d)' % (band, len(todo), len(done)), flush=True)
    with cf.ThreadPoolExecutor(8) as ex:
        for n, (c, r) in enumerate(zip(todo, ex.map(lambda c: ask(band, c, key), todo)), 1):
            if 'answers' in r: done['%s:%d' % (c['kind'], c['id'])] = r['answers']
            else: print('  error:', c['title'], r.get('error'), flush=True)
            if n % 100 == 0: json.dump(done, open(path, 'w')); print('  %d judged' % n, flush=True)
    json.dump(done, open(path, 'w'))

# --- Stage 4: policy ----------------------------------------------------------------------------------------------

def stage_select(band):
    cands = {'%s:%d' % (c['kind'], c['id']): c for c in load(work(band, 'candidates.json'), [])}
    judg = load(work(band, 'judgments.json'), {})
    over = load(os.path.join(HERE, 'overrides', band + '.json'), {})
    keep, review = [], []
    for k, a in judg.items():
        if k not in cands: continue
        c = cands[k]
        s = dict(jr=a['age_fit']['probabilities'].get('just_right', 0), value=a['value']['score'],
                 must=a['must_watch']['noul'], fun=a['fun']['score'])
        row = {**{x: c[x] for x in ('kind', 'id', 'title', 'year', 'rating', 'lang')}, **{x: round(v, 2) for x, v in s.items()}}
        row['score'] = round(0.45 * s['value'] / 3 + 0.30 * s['must'] + 0.25 * s['fun'] / 3, 3)
        if k in over:
            if over[k] == 'keep': keep.append(row)
            continue
        core = s['jr'] >= 0.6 and s['value'] >= 2.0 and s['fun'] >= 1.2 and (s['must'] >= 0.5 or s['value'] >= 2.4)
        solid = s['jr'] >= 0.75 and s['value'] >= 2.1 and s['fun'] >= 2.0
        near = (0.4 <= s['jr'] < 0.6 and s['value'] >= 2.2) or \
               (s['jr'] >= 0.6 and s['fun'] >= 1.2 and ((1.8 <= s['value'] < 2.0 and s['must'] >= 0.5) or (2.0 <= s['value'] < 2.4 and 0.35 <= s['must'] < 0.5)))
        if core or solid: keep.append(row)
        elif near: review.append(row)
    keep.sort(key=lambda r: -r['score']); review.sort(key=lambda r: -r['score'])
    json.dump({'keep': keep}, open(work(band, 'selected.json'), 'w'), indent=1)
    json.dump(review, open(work(band, 'review.json'), 'w'), indent=1)
    print('[%s] keep %d (movies %d, shows %d), to review %d' % (band, len(keep), sum(r['kind'] == 'movie' for r in keep),
          sum(r['kind'] == 'tv' for r in keep), len(review)), flush=True)

# --- Stage 5: the app's catalog -----------------------------------------------------------------------------------

def stage_emit(band):
    keep = load(work(band, 'selected.json'), {}).get('keep', [])
    out = os.path.join(HERE, '..', 'app', 'assets', 'catalog'); os.makedirs(out, exist_ok=True)
    rows = [{'kind': r['kind'], 'id': r['id'], 'title': r['title'], 'year': r['year'], 'score': r['score']} for r in keep]
    json.dump({'band': band, 'titles': rows}, open(os.path.join(out, band + '.json'), 'w'), indent=0, ensure_ascii=False)
    print('[%s] wrote app/assets/catalog/%s.json (%d titles)' % (band, band, len(rows)))

if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--band', required=True, choices=sorted(BANDS))
    ap.add_argument('stage', choices=['all', 'candidates', 'judge', 'select', 'expand', 'emit'])
    a = ap.parse_args()
    if a.stage == 'all':
        stage_candidates(a.band); stage_judge(a.band); stage_select(a.band)
        stage_expand(a.band); stage_judge(a.band); stage_select(a.band); stage_emit(a.band)
    else:
        {'candidates': stage_candidates, 'judge': stage_judge, 'select': stage_select,
         'expand': stage_expand, 'emit': stage_emit}[a.stage](a.band)
