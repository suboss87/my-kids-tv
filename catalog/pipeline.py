#!/usr/bin/env python3
"""Builds a vetted kids catalog for one age band.

  python3 catalog/pipeline.py --band 7-10 all        # candidates -> judge -> select -> expand -> judge -> select -> emit
  python3 catalog/pipeline.py --band 7-10 select     # re-apply policy + overrides only (no API calls)

Stages: 1) candidates from seeds, research lists and TMDB sweeps; 2) hard age-rating and quality filters (code);
3) Jev judges age fit, value, must-watch and fun; 4) policy in code plus overrides/<band>.json (a person's review);
5) app/assets/catalog/<band>.json, which the app reads, and the open dataset in data/. Needs $TYPESAFE_API_KEY for judging, a TMDB key for the rest.
"""
import argparse, concurrent.futures as cf, json, os, sys, time, urllib.parse, urllib.request
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tmdb import get
from bands import BANDS, questions, craft_question, audience, policy
import export_dataset
import hotstar

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
    for f in ('seeds/%s.json' % band, 'seeds/research-%s.json' % band, 'seeds/deep-%s.json' % band):
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

# Streaming services by region (TMDB provider ids): Netflix, Netflix Kids, Prime Video, JioHotstar in India.
REGION_PROVIDERS = {'IN': '8|175|119|2336'}

def stage_streaming(band, region='IN'):
    """Round 3, the other way round: what streams in the region on the family's apps, for this age, through the same
    hard rules, Jev and policy. Fills the catalog with titles a child there can actually play."""
    b = BANDS[band]; jobs = []
    common = {'watch_region': region, 'with_watch_providers': REGION_PROVIDERS[region],
              'with_watch_monetization_types': 'flatrate'}
    for sweep in b['movie_sweeps']:
        for sort in ('popularity.desc', 'vote_average.desc'):
            for p in range(1, 11):
                jobs.append(('movie', dict(sweep, **common, **{'vote_count.gte': 100, 'vote_average.gte': 6.8}, sort_by=sort, page=p)))
    for sweep in b['tv_sweeps']:
        for sort in ('popularity.desc', 'vote_average.desc'):
            for p in range(1, 9):
                jobs.append(('tv', dict(sweep, **common, **{'vote_count.gte': 30, 'vote_average.gte': 6.8}, sort_by=sort, page=p)))
    def run(j):
        try: return j[0], (get('/discover/' + j[0], **j[1]) or {}).get('results', [])
        except RuntimeError: return j[0], []
    keys = {}
    with cf.ThreadPoolExecutor(12) as ex:
        for kind, res in ex.map(run, jobs):
            for m in res: keys.setdefault((kind, m['id']), 'streaming-' + region)
    print('[%s] streaming in %s: %d titles found' % (band, region, len(keys)), flush=True)
    stage_candidates(band, keys)

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

def ask(band, c, key, qs=None):
    state = {'audience': audience(band), 'title': c['title'], 'year': c['year'],
             'type': 'movie' if c['kind'] == 'movie' else 'TV series', 'genres': c['genres'],
             'age_rating': c['rating'] or 'unknown', 'original_language': c['lang'],
             'tmdb_rating': '%.1f/10 from %d votes' % (c['vote_average'], c['vote_count']),
             'synopsis': c['overview'], 'keywords': c['keywords']}
    body = json.dumps({'model': 'jev-latest', 'state': state, 'questions': qs or questions(band)}).encode()
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

def stage_craft(band):
    """Jev's craft score, only for titles that could make the cut (the rest never needs it)."""
    key = os.environ.get('TYPESAFE_API_KEY')
    if not key: raise SystemExit('Set TYPESAFE_API_KEY to judge titles with Jev (or use the shipped catalogs).')
    cands = {'%s:%d' % (c['kind'], c['id']): c for c in load(work(band, 'candidates.json'), [])}
    judg = load(work(band, 'judgments.json'), {})
    path = work(band, 'craft.json'); done = load(path, {})
    todo = [cands[k] for k, a in judg.items() if k in cands and k not in done
            and a['age_fit']['probabilities'].get('just_right', 0) >= 0.35
            and (a['value']['score'] >= 1.8 or a['fun']['score'] >= 2.4)]   # the fun path needs craft too
    print('[%s] craft: judging %d (cached %d)' % (band, len(todo), len(done)), flush=True)
    with cf.ThreadPoolExecutor(8) as ex:
        for c, r in zip(todo, ex.map(lambda c: ask(band, c, key, craft_question(band)), todo)):
            if 'answers' in r: done['%s:%d' % (c['kind'], c['id'])] = r['answers']['craft']['score']
            else: print('  error:', c['title'], r.get('error'), flush=True)
    json.dump(done, open(path, 'w'))

# --- Stage 4: policy ----------------------------------------------------------------------------------------------

# Official ratings safe for each band's "classic a little above the age" path.
SAFE_RATINGS = {'4-6': {'G', 'TV-Y', 'TV-G', 'U'},
                '7-10': {'G', 'PG', 'TV-Y', 'TV-Y7', 'TV-Y7-FV', 'TV-G', 'TV-PG', 'U'},
                '11-13': {'G', 'PG', 'PG-13', 'TV-Y7', 'TV-G', 'TV-PG', 'TV-14', 'U', 'UA', 'U/A', 'U/A 7+', 'U/A 13+'}}

def stage_select(band):
    cands = {'%s:%d' % (c['kind'], c['id']): c for c in load(work(band, 'candidates.json'), [])}
    judg = load(work(band, 'judgments.json'), {})
    over = load(os.path.join(HERE, 'overrides', band + '.json'), {})
    craft = load(work(band, 'craft.json'), {})
    keep, review = [], []
    for k, a in judg.items():
        if k not in cands: continue
        c = cands[k]
        s = dict(jr=a['age_fit']['probabilities'].get('just_right', 0), value=a['value']['score'],
                 must=a['must_watch']['noul'], fun=a['fun']['score'])
        row = {**{x: c[x] for x in ('kind', 'id', 'title', 'year', 'rating', 'lang')}, **{x: round(v, 2) for x, v in s.items()}}
        # Jev's judgments plus what audiences think of it (TMDB's rating, close to IMDb's): 6.5 counts 0, 8.5 and up 1.
        quality = min(1, max(0, (c.get('vote_average', 7) - 6.5) / 2))
        row['score'] = round(0.35 * s['value'] / 3 + 0.25 * s['must'] + 0.25 * s['fun'] / 3 + 0.15 * quality, 3)
        row['craft'] = round(craft[k], 2) if k in craft else None
        if k in over:
            if over[k] == 'keep': keep.append(row)
            continue
        pol = policy(band)
        core = s['jr'] >= pol['jr'] and s['value'] >= pol['value'] and s['fun'] >= pol['fun'] \
            and (s['must'] >= pol['must'] or s['value'] >= 2.4)
        solid = s['jr'] >= 0.75 and s['value'] >= 2.1 and s['fun'] >= 2.0
        near = (0.4 <= s['jr'] < 0.6 and s['value'] >= 2.2) or \
               (s['jr'] >= 0.6 and s['fun'] >= 1.2 and ((1.8 <= s['value'] < 2.0 and s['must'] >= 0.5) or (2.0 <= s['value'] < 2.4 and 0.35 <= s['must'] < 0.5)))
        # Ages 7 and up: well-made films and series, not filler cartoons (missing craft judgment: no say).
        crafted = band == '4-6' or k not in craft or craft[k] >= 1.9 or s['must'] >= 0.8
        # Mostly English-language titles; others only when they're outstanding (Ghibli, the best of world cinema),
        # Indian ones only exceptionally (the family's own picks come from overrides).
        lang = c.get('lang', 'en')
        foreign_ok = lang == 'en' or (s['must'] >= 0.85 and c.get('vote_average', 0) >= 7.8 if lang in INDIAN
                                       else s['must'] >= 0.7 or (quality >= 0.6 and s['fun'] >= 2.4))
        cr = craft.get(k)
        # Fun and well made: right for the age, children love it, a little lasting value, solid craft (Home Alone,
        # Cars, Shaun the Sheep). Not every good watch has to teach something.
        fun_path = s['jr'] >= 0.6 and s['fun'] >= 2.5 and s['value'] >= 1.4 and cr is not None and cr >= 2.0
        # Beautifully made and enjoyable at that age (Elemental, Onward): Jev rates the craft high.
        made_path = s['jr'] >= 0.75 and cr is not None and cr >= 2.4 and s['fun'] >= 2.0 and s['value'] >= 1.7
        # A classic a little above the age, rated safe for it (Porco Rosso, City Lights, Remember the Titans): Jev's
        # "too mature" also covers "too complex", which a parent can judge; the official rating covers the content.
        safe = SAFE_RATINGS[band]
        classic_path = s['jr'] >= 0.35 and (c.get('rating') or '') in safe and (s['must'] >= 0.6 or s['value'] >= 2.4) \
            and s['fun'] >= 2.0 and (cr is None or cr >= 2.0)
        if (core or solid or fun_path or made_path or classic_path) and crafted and foreign_ok: keep.append(row)
        elif (core or solid or near) and crafted: review.append(row)
    keep.sort(key=lambda r: -r['score']); review.sort(key=lambda r: -r['score'])
    json.dump({'keep': keep}, open(work(band, 'selected.json'), 'w'), indent=1)
    json.dump(review, open(work(band, 'review.json'), 'w'), indent=1)
    print('[%s] keep %d (movies %d, shows %d), to review %d' % (band, len(keep), sum(r['kind'] == 'movie' for r in keep),
          sum(r['kind'] == 'tv' for r in keep), len(review)), flush=True)

# --- Stage 4b: direct links into the streaming apps -----------------------------------------------------------------

# Each service's id for a title, from Wikidata (CC0), and the address its app opens the title's page with.
LINK_PROPS = {'netflix': ('P1874', 'https://www.netflix.com/title/%s'),
              'disney': ('P7595', 'https://www.disneyplus.com/movies/wd/%s'),
              'disney_tv': ('P7596', 'https://www.disneyplus.com/series/wp/%s'),
              'appletv': ('P9586', 'https://tv.apple.com/movie/%s'),
              'appletv_tv': ('P9751', 'https://tv.apple.com/show/%s'),
              # Prime Video's TV app takes app.primevideo.com only: the global id (GTI) first, the store's ASIN after.
              'prime': ('P14440', 'https://app.primevideo.com/detail?gti=%s'),
              'prime_gti': ('P14462', 'https://app.primevideo.com/detail?gti=%s'),
              'prime_asin': ('P8055', 'https://app.primevideo.com/detail?asin=%s'),
              'max': ('P8298', 'https://play.hbomax.com/%s'),
              'hotstar': ('P11049', 'https://www.hotstar.com/%s')}

def wikidata(sparql):
    req = urllib.request.Request('https://query.wikidata.org/sparql',
                                 data=urllib.parse.urlencode({'query': sparql, 'format': 'json'}).encode(),
                                 headers={'User-Agent': 'MyKidsTV-catalog/1.0 (github.com/suboss87/my-kids-tv)',
                                          'Content-Type': 'application/x-www-form-urlencoded'})
    for i in range(4):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.load(r)['results']['bindings']
        except Exception:
            time.sleep(3 * (i + 1))
    raise RuntimeError('Wikidata did not answer')

def stage_links(band):
    """Links straight to each kept title's page in the streaming apps, by service: work/<band>/links.json."""
    keep = load(work(band, 'selected.json'), {}).get('keep', [])
    links = {}
    for kind, tmdb_prop in (('movie', 'P4947'), ('tv', 'P4983')):
        ids = [str(r['id']) for r in keep if r['kind'] == kind]
        for at in range(0, len(ids), 200):
            values = ' '.join('"%s"' % i for i in ids[at:at + 200])
            opt = ' '.join('OPTIONAL { ?item wdt:%s ?%s . }' % (p, k) for k, (p, _) in LINK_PROPS.items())
            rows = wikidata('SELECT ?tmdb %s WHERE { VALUES ?tmdb { %s } ?item wdt:%s ?tmdb . %s }'
                            % (' '.join('?' + k for k in LINK_PROPS), values, tmdb_prop, opt))
            for r in rows:
                out = links.setdefault('%s:%s' % (kind, r['tmdb']['value']), {})
                for k, (_, url) in LINK_PROPS.items():
                    service = k.split('_')[0]
                    if k in r and service not in out: out[service] = url % r[k]['value']
    # JioHotstar India: its own sitemaps (catalog/hotstar.py), then checked against TMDB below.
    cands = {'%s:%d' % (c['kind'], c['id']): c for c in load(work(band, 'candidates.json'), [])}
    todo = []
    for r in keep:
        k = '%s:%d' % (r['kind'], r['id'])
        t = {'kind': r['kind'], 'id': r['id'], 'title': r['title'], 'year': r['year'],
             'hotstar_id': links.get(k, {}).get('hotstar', '').rstrip('/').split('/')[-1]}
        if cands.get(k, {}).get('lang', 'en') != 'en':
            try:
                d = get('/%s/%d' % (r['kind'], r['id'])) or {}
                t['original'] = d.get('original_title') or d.get('original_name')
            except RuntimeError:
                pass
        todo.append(t)
    for k, url in hotstar.links(todo).items():
        links.setdefault(k, {})['hotstar'] = url   # the page's full address beats Wikidata's bare id

    # Never a wrong film: a JioHotstar link stays only when TMDB says this very title (not a remake or namesake of
    # another year) streams on JioHotstar in India, and no two titles share a page (DuckTales 1987 and 2017).
    def on_hotstar(k):
        kind, tid = k.split(':')
        try:
            d = get('/%s/%s/watch/providers' % (kind, tid)) or {}
        except RuntimeError:
            return False
        flat = ((d.get('results') or {}).get('IN') or {}).get('flatrate') or []
        return any('hotstar' in p.get('provider_name', '').lower() for p in flat)
    # The sitemap matches before any check, kept per band, so the shared-page test sees every band the same way.
    raw = {k: v['hotstar'] for k, v in links.items() if 'hotstar' in v}
    json.dump(raw, open(work(band, 'hotstar-raw.json'), 'w'), indent=1, sort_keys=True)
    pages = {}   # page -> the different titles pointing at it, across all the age bands
    for b in BANDS:
        for k, url in (raw if b == band else load(work(b, 'hotstar-raw.json'), {})).items():
            pages.setdefault(url, set()).add(k)
    for k, v in links.items():
        h = v.get('hotstar')
        if not h: continue
        if not h.startswith('https://www.hotstar.com/in/') or len(pages.get(h, ())) > 1 or not on_hotstar(k):
            del v['hotstar']
    # Prime Video: Wikidata's ASIN property sometimes holds a global id; those go by ?gti=.
    for v in links.values():
        if v.get('prime', '').startswith('https://app.primevideo.com/detail?asin=amzn1.'):
            v['prime'] = v['prime'].replace('?asin=', '?gti=')
    json.dump(links, open(work(band, 'links.json'), 'w'), indent=1, sort_keys=True)
    print('[%s] links for %d of %d titles' % (band, sum(1 for v in links.values() if v), len(keep)), flush=True)

# --- Stage 5: the app's catalog -----------------------------------------------------------------------------------

def badge(r, genres):
    """The one reason shown on the card, from Jev's judgments: must-watch, learn something, or kids love it."""
    if r['must'] >= 0.75: return 'must'
    if 'Documentary' in genres: return 'learn'
    if r['fun'] >= 2.75: return 'loved'
    return ''

# Titles nearly every family has already seen (TMDB vote counts this high): the app shows them only now and then.
FAMOUS_VOTES = {'movie': 8000, 'tv': 1600}

def famous(c):
    return bool(c) and c.get('vote_count', 0) >= FAMOUS_VOTES[c['kind']]

def stage_emit(band):
    keep = load(work(band, 'selected.json'), {}).get('keep', [])
    cands = {'%s:%d' % (c['kind'], c['id']): c for c in load(work(band, 'candidates.json'), [])}
    genres = {k: c['genres'] for k, c in cands.items()}
    links = load(work(band, 'links.json'), {})
    out = os.path.join(HERE, '..', 'app', 'assets', 'catalog'); os.makedirs(out, exist_ok=True)
    rows = [{'kind': r['kind'], 'id': r['id'], 'title': r['title'], 'year': r['year'], 'score': r['score'],
             'badge': badge(r, genres.get('%s:%d' % (r['kind'], r['id']), [])),
             **({'famous': True} if famous(cands.get('%s:%d' % (r['kind'], r['id']))) else {}),
             **({'links': links['%s:%d' % (r['kind'], r['id'])]} if links.get('%s:%d' % (r['kind'], r['id'])) else {})}
            for r in keep]
    json.dump({'band': band, 'titles': rows}, open(os.path.join(out, band + '.json'), 'w'), indent=0, ensure_ascii=False)
    print('[%s] wrote app/assets/catalog/%s.json (%d titles)' % (band, band, len(rows)))
    export_dataset.export()  # keep the open dataset in data/ in step with the app's catalogs

if __name__ == '__main__':
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--band', required=True, choices=sorted(BANDS))
    ap.add_argument('stage', choices=['all', 'candidates', 'judge', 'craft', 'select', 'expand', 'streaming', 'links', 'emit'])
    a = ap.parse_args()
    if a.stage == 'all':
        stage_candidates(a.band); stage_judge(a.band); stage_select(a.band)
        stage_expand(a.band); stage_judge(a.band); stage_craft(a.band); stage_select(a.band); stage_links(a.band); stage_emit(a.band)
    else:
        {'candidates': stage_candidates, 'judge': stage_judge, 'craft': stage_craft, 'select': stage_select, 'links': stage_links, 'streaming': stage_streaming,
         'expand': stage_expand, 'emit': stage_emit}[a.stage](a.band)
