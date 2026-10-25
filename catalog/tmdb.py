"""TMDB client: retries (some ISPs reset many connections to TMDB) and an on-disk cache in work/cache.
The key comes from $TMDB_KEY or tmdb_key= in ../config.properties. It is never logged or written to the cache."""
import hashlib, json, os, time, urllib.error, urllib.parse, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, 'work', 'cache')

def _key():
    if os.environ.get('TMDB_KEY'):
        return os.environ['TMDB_KEY'].strip()
    cfg = os.path.join(HERE, '..', 'config.properties')
    if os.path.exists(cfg):
        for line in open(cfg):
            if line.strip().startswith('tmdb_key=') and line.split('=', 1)[1].strip():
                return line.split('=', 1)[1].strip()
    raise SystemExit('No TMDB key: set TMDB_KEY or tmdb_key= in config.properties (free at themoviedb.org/settings/api).')

KEY = None

def get(path, **params):
    """GET a TMDB v3 path; None on 404. Raises RuntimeError after repeated failures (message has no key)."""
    global KEY
    os.makedirs(CACHE, exist_ok=True)
    q = urllib.parse.urlencode(sorted(params.items()))
    f = os.path.join(CACHE, hashlib.sha1((path + '?' + q).encode()).hexdigest() + '.json')
    if os.path.exists(f):
        return json.load(open(f))
    KEY = KEY or _key()
    url = 'https://api.themoviedb.org/3' + path + '?' + urllib.parse.urlencode({**params, 'api_key': KEY})
    for i in range(15):
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                d = json.load(r)
            json.dump(d, open(f, 'w'))
            return d
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code == 401:
                raise SystemExit('TMDB rejected the key.')
            time.sleep(1 + i)
        except Exception:
            time.sleep(0.4 * (i + 1))
    raise RuntimeError('TMDB failed: ' + path)
