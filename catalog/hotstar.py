"""JioHotstar (India): the address of each catalog title in the app, from the sitemaps JioHotstar publishes for search
engines (robots.txt lists them; only /search and /play are disallowed there, and neither is read).

A title matches a sitemap entry by its URL name (the "slug", "the-jungle-book"), from its English or original title,
in a few spellings ("&" as "and" or dropped, an apostrophe dropped or as "-"). Only a name with exactly one page is
linked: two pages of the same name (a remake, a namesake) get no link, so a wrong film is never opened. The app uses
the link only when TMDB says the title streams on JioHotstar in India. Movies link to their "watch" address, so the
film starts; shows to their page.
"""
import os, re, time, unicodedata, urllib.request

SITEMAP = 'https://www.hotstar.com/in/new-sitemap-%s.xml'
UA = 'MyKidsTV-catalog/1.0 (+https://github.com/suboss87/my-kids-tv)'


def fetch(url):
    for i in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=60) as r:
                return r.read().decode('utf-8', 'replace')
        except Exception:
            time.sleep(2 * (i + 1))
    return ''


def slugs(title):
    """The spellings JioHotstar's URL names use for a title."""
    t = unicodedata.normalize('NFKD', title).encode('ascii', 'ignore').decode().lower()
    out = set()
    for amp in (' and ', ' '):
        for apos in ('', '-'):
            s = t.replace('&', amp).replace("'", apos).replace('’', apos).replace('·', '-')
            out.add(re.sub(r'[^a-z0-9]+', '-', s).strip('-'))
    out |= {s[4:] for s in out if s.startswith('the-')}
    return out


def index():
    """{('movie'|'tv', slug): {page url, ...}} and {movie page url: its watch url}."""
    pages, watch = {}, {}
    for name, kind in (('MOVIE-1', 'movie'), ('SHOWS-1', 'tv')):
        for url in re.findall(r'<loc>([^<]+)</loc>', fetch(SITEMAP % name)):
            m = re.match(r'https://www\.hotstar\.com/in/(?:movies|shows)/([^/]+)/(\d+)$', url)
            if m: pages.setdefault((kind, m.group(1)), set()).add(url)
    for url in re.findall(r'<loc>([^<]+)</loc>', fetch(SITEMAP % 'MOVIE_WATCH_PAGES-1')):
        if url.endswith('/watch'): watch[url[:-len('/watch')]] = url
    return pages, watch


def links(titles):
    """{'movie:12': url} for the titles ([{kind, id, title, original}]) with exactly one JioHotstar India page."""
    pages, watch = index()
    out = {}
    for t in titles:
        found = set()
        for name in [t['title']] + ([t['original']] if t.get('original') else []):
            for s in slugs(name):
                found |= pages.get((t['kind'], s), set())
        # Two pages of the same name: Wikidata's JioHotstar id for the title, when it has one, says which.
        if len(found) > 1 and t.get('hotstar_id'):
            found = {u for u in found if u.rstrip('/').endswith('/' + t['hotstar_id'])}
        if len(found) == 1:
            url = found.pop()
            out['%s:%d' % (t['kind'], t['id'])] = watch.get(url, url)
    return out
