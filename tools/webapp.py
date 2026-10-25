"""What the LG (webos/build.py) and Samsung (tizen/build.py) packages share: the settings and the web app's files.

The settings come from config.properties, the same file as the Android build. --generic leaves it out (the GitHub
release is built that way): no TMDB key, and the TV's time zone picks the city, the currency and the region.
The picks come from the age band's vetted catalog (app/assets/catalog/<band>.json, as on Android), packed as catalog.js.
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, 'tools'))
import translate  # noqa: E402  (the catalogues and the keys the web app uses)

GENERIC = {'tmdbKey': '', 'tmdbRegion': '', 'latitude': '', 'longitude': '', 'city': '', 'language': 'auto',
           'currency': '', 'temperature': 'auto', 'theme': 'auto', 'showCrypto': True, 'hideAnimation': False, 'hideApps': [],
           'shelfApps': [], 'ageBand': '7-10'}

AGE_BANDS = ('4-6', '7-10', '11-13')


def config_from_args(args):
    """The settings for these command-line arguments (--generic, --config PATH or config.properties)."""
    if '--generic' in args:
        return dict(GENERIC)
    path = os.path.join(ROOT, 'config.properties')
    if '--config' in args:
        path = os.path.abspath(args[args.index('--config') + 1])
    return read_config(path)


def read_config(path):
    if not os.path.exists(path):
        shutil.copy(os.path.join(ROOT, 'config.example.properties'), path)
        print('Created config.properties: add your TMDB key and your city there (optional), then build again.')
    values = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            values[key.strip()] = value.strip().strip('"')

    def number(key, default):
        v = values.get(key, '') or default
        if not re.match(r'^-?\d+(\.\d+)?$', v):
            sys.exit('config.properties: %s must be a number (got "%s")' % (key, v))
        return v

    return {
        'tmdbKey': values.get('tmdb_key', ''),
        'tmdbRegion': values.get('tmdb_region', 'US') or 'US',
        'latitude': number('latitude', '40.7128'),
        'longitude': number('longitude', '-74.0060'),
        'city': values.get('city', ''),
        'language': values.get('language', 'auto') or 'auto',
        'currency': values.get('currency', 'USD') or 'USD',
        'temperature': values.get('temperature', 'auto') or 'auto',
        'theme': values.get('theme', 'auto') or 'auto',
        'showCrypto': values.get('show_crypto', 'true').lower() != 'false',
        'hideAnimation': values.get('hide_animation', 'false').lower() == 'true',
        'hideApps': [s.strip() for s in values.get('hide_apps', '').split(',') if s.strip()],
        'shelfApps': [s.strip() for s in values.get('shelf_apps', '').split(',') if s.strip()],
        'ageBand': age_band(values.get('age_band', '')),
    }


def age_band(value):
    """The child's age band (age_band in config.properties): 4-6, 7-10 or 11-13; anything else is 7-10, as on Android."""
    value = (value or '').strip()
    if value and value not in AGE_BANDS:
        print('config.properties: age_band must be one of %s (got "%s"): using 7-10.' % (', '.join(AGE_BANDS), value))
    return value if value in AGE_BANDS else '7-10'


def catalog(band):
    """The vetted titles for this age band, [{kind, id, title}], from app/assets/catalog/<band>.json. A band whose
    catalog isn't there yet gives an empty list: the picks then come from the age-restricted TMDB fallback."""
    path = os.path.join(ROOT, 'app', 'assets', 'catalog', band + '.json')
    if not os.path.exists(path):
        print('No catalog for age band %s yet (app/assets/catalog/%s.json): the picks use the age-restricted fallback.'
              % (band, band))
        return []
    with open(path, encoding='utf-8') as f:
        titles = json.load(f).get('titles', [])
    return [{'kind': t['kind'], 'id': int(t['id']), 'title': t.get('title', '')}
            for t in titles if t.get('kind') in ('movie', 'tv') and t.get('id')]


def coins():
    """The coins' logos from the Android app's vector drawables, as SVG paths (BTC, ETH, SOL, USDT)."""
    out = []
    for name in ('btc', 'eth', 'sol', 'usdt'):
        with open(os.path.join(ROOT, 'app', 'res', 'drawable', 'coin_%s.xml' % name), encoding='utf-8') as f:
            out.append(re.findall(r'android:pathData="([^"]+)"', f.read()))
    return out


def catalogues():
    """Each language's catalogue (app/assets/i18n), with only the keys the web app uses: {lang: {key: text}}."""
    keys = translate.web_keys()
    return {lang: {k: v for k, v in sorted(translate.load(lang).items()) if k in keys} for lang in translate.languages()}


def files(config, made_by):
    """The web app's files, {path inside the app: bytes}: web/, the settings, the kids catalog, the catalogues, the app's symbols and font."""
    out = {}
    web = os.path.join(ROOT, 'web')
    for name in sorted(os.listdir(web)):
        if os.path.isdir(os.path.join(web, name)):
            continue
        with open(os.path.join(web, name), 'rb') as f:
            out[name] = f.read()
    icons = os.path.join(web, 'icons')
    for name in sorted(os.listdir(icons)):
        with open(os.path.join(icons, name), 'rb') as f:
            out['icons/' + name] = f.read()
    out['config.js'] = ('/* Generated by %s. */\nwindow.CONFIG = %s;\n'
                        % (made_by, json.dumps(config, ensure_ascii=False))).encode('utf-8')
    band = config.get('ageBand', '7-10')
    out['catalog.js'] = ('/* Generated by %s from app/assets/catalog/%s.json. */\nwindow.CATALOG = %s;\n'
                         % (made_by, band, json.dumps({'band': band, 'titles': catalog(band)}, ensure_ascii=False,
                                                      separators=(',', ':')))).encode('utf-8')
    out['coins.js'] = ('window.COINS = %s;\n' % json.dumps(coins())).encode('utf-8')
    out['i18n.js'] = ('/* Generated by %s from app/assets/i18n. */\nwindow.I18N_CATALOGUES = %s;\n'
                      % (made_by, json.dumps(catalogues(), ensure_ascii=False, separators=(',', ':')))).encode('utf-8')
    symbols = os.path.join(ROOT, 'app', 'assets', 'symbols')
    for name in sorted(os.listdir(symbols)):
        if name.endswith('.png') or name.startswith('LICENSE'):
            with open(os.path.join(symbols, name), 'rb') as f:
                out['symbols/' + name] = f.read()
    for name in ('InterVariable.ttf', 'OFL.txt'):
        with open(os.path.join(ROOT, 'app', 'assets', 'fonts', name), 'rb') as f:
            out['fonts/' + name] = f.read()
    return out


def preview(app, out_dir):
    """Leaves the app's files in out_dir, to open index.html in a browser."""
    if os.path.isdir(out_dir):
        shutil.rmtree(out_dir)
    for path, content in app.items():
        target = os.path.join(out_dir, path)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, 'wb') as f:
            f.write(content)
