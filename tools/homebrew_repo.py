#!/usr/bin/env python3
"""Writes docs/webos/apps.json, the repository the webOS Homebrew Channel reads (https://freemytv.io/webos/apps.json).

    python3 tools/homebrew_repo.py FreeMyTV-webos.ipk

Run it at release time, on the exact .ipk uploaded to the GitHub release (built with `webos/build.py --generic`), and
commit what it writes with the release: the JSON and a copy of the .ipk in docs/webos/. The Channel downloads `ipkUrl`,
checks this SHA-256 and size, and installs it with the same Developer Mode installer webos/install.py uses.

Everything the TV fetches comes from freemytv.io. The Channel's service runs on the TV's own Node, whose HTTP parser
takes at most 8 KB of headers, and GitHub's release download answers with a redirect carrying about 10 KB (most of it a
Content-Security-Policy): the install stops at "Parse Error: Header overflow" (#234). GitHub Pages answers with under
1 KB. The manifest goes inline, and full_description.html is what the Channel shows on the app's page.
"""
import glob
import hashlib
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR = os.path.join(ROOT, 'docs', 'webos')
OUT = os.path.join(DIR, 'apps.json')
SITE = 'https://freemytv.io/webos/'
ICON = 'https://freemytv.io/assets/icon-512.png'
SOURCE = 'https://github.com/simbasjungles/free-my-tv'


def write(sha256, size, ipk):
    """The repository for the version in webos/appinfo.json, whose .ipk (in docs/webos, named `ipk`) has this SHA-256
    and size."""
    app = json.load(open(os.path.join(ROOT, 'webos', 'appinfo.json'), encoding='utf-8'))
    version = app['version']
    manifest = {
        'id': app['id'],
        'version': version,
        'type': 'web',
        'title': app['title'],
        'appDescription': 'A fast, ad-free home screen: your apps on a glass shelf, the weather and prices, day and night.',
        'iconUri': ICON,
        'sourceUrl': SOURCE,
        'rootRequired': False,
        'ipkUrl': SITE + ipk,
        'ipkHash': {'sha256': sha256},
        'ipkSize': size,
    }
    repo = {
        'paging': {'page': 1, 'count': 1, 'maxPage': 1, 'itemsTotal': 1, 'prevUrl': None, 'nextUrl': None},
        'packages': [{
            'id': app['id'],
            'title': app['title'],
            'iconUri': ICON,
            'shortDescription': manifest['appDescription'],
            'pool': 'main',
            'fullDescriptionUrl': 'full_description.html',
            'manifest': manifest,
        }],
    }
    os.makedirs(DIR, exist_ok=True)
    with open(OUT, 'w', encoding='utf-8') as f:
        json.dump(repo, f, indent=2)
        f.write('\n')
    print(f'Wrote {os.path.relpath(OUT, ROOT)}: {app["id"]} {version}, {size} bytes, sha256 {sha256[:12]}…')


def main():
    if len(sys.argv) != 2 or not sys.argv[1].endswith('.ipk'):
        print(__doc__.strip())
        return 2
    data = open(sys.argv[1], 'rb').read()
    version = json.load(open(os.path.join(ROOT, 'webos', 'appinfo.json'), encoding='utf-8'))['version']
    ipk = f'FreeMyTV-webos-{version}.ipk'
    # Only the current release is served: older copies go.
    for old in glob.glob(os.path.join(DIR, 'FreeMyTV-webos-*.ipk')):
        if os.path.basename(old) != ipk:
            os.remove(old)
    with open(os.path.join(DIR, ipk), 'wb') as f:
        f.write(data)
    write(hashlib.sha256(data).hexdigest(), len(data), ipk)
    return 0


if __name__ == '__main__':
    sys.exit(main())
