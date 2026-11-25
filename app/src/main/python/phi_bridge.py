"""Entry points called from Kotlin (via Chaquopy).

Wraps the original project's code (extract.py, catalog.py, chart.py, algo/*):
  * extract_charts() - the original "解包Apk" step, charts only, no GUI
  * list_songs()     - what main.py's load_songs()/song_selected() do
  * build_plan()     - original planning + coordinate mapping, then touch_plan
"""

import json
import os
import zipfile

from catalog import Catalog
from chart import Chart
from extract import AssetsManager, FileReader, TextAsset
import touch_plan

TRACKS = os.path.join('Assets', 'Tracks')


def extract_charts(apk_paths, out_root, progress=None):
    """Extract every Assets/Tracks/*/Chart_*.json from the given APK files.

    apk_paths: list of APK paths (base.apk plus any split APKs).
    progress:  optional object with onProgress(done: int, total: int).
    Returns the number of chart files written.
    """
    if isinstance(apk_paths, str):
        apk_paths = [p for p in apk_paths.split('\n') if p]
    written = 0
    for apk_path in apk_paths:
        with zipfile.ZipFile(str(apk_path)) as apk:
            names = set(apk.namelist())
            if 'assets/aa/catalog.json' not in names:
                continue
            with apk.open('assets/aa/catalog.json') as f:
                catalog = Catalog(f)

            wanted = []
            for name in sorted(names):
                if not name.startswith('assets/aa/Android'):
                    continue
                bundle = os.path.basename(name)
                asset = catalog.fname_map.get(bundle)
                if asset and asset.startswith('Assets/Tracks/') \
                        and os.path.basename(asset).startswith('Chart_'):
                    wanted.append((name, asset))

            total = len(wanted)
            for done, (name, asset) in enumerate(wanted, 1):
                manager = AssetsManager()
                manager.load_file(FileReader(apk.read(name), name))
                manager.read_assets()
                for file in manager.asset_files:
                    for obj in file.objects:
                        if isinstance(obj, TextAsset):
                            target = os.path.join(out_root, *asset.split('/'))
                            os.makedirs(os.path.dirname(target), exist_ok=True)
                            with open(target, 'w', encoding='utf-8', newline='') as out:
                                out.write(obj.text)
                            written += 1
                if progress is not None and (done % 5 == 0 or done == total):
                    progress.onProgress(done, total)
    return written


def list_songs(root):
    """JSON: {"songs": {song_id: [difficulty, ...]}}"""
    base = os.path.join(root, TRACKS)
    songs = {}
    if os.path.isdir(base):
        for song in sorted(os.listdir(base)):
            d = os.path.join(base, song)
            if not os.path.isdir(d):
                continue
            diffs = sorted(f[6:-5] for f in os.listdir(d)
                           if f.startswith('Chart_') and f.endswith('.json') and 'ans' not in f)
            if diffs:
                songs[song] = diffs
    return json.dumps({'songs': songs})


def build_plan(root, song, difficulty, algo, dev_w, dev_h, mirror):
    """Plan the touches for one chart; returns the plan as a JSON string."""
    chart_path = os.path.join(root, TRACKS, song, f'Chart_{difficulty}.json')
    with open(chart_path, encoding='utf-8') as f:
        chart = Chart.from_dict(json.load(f))

    if algo == 'algo1':
        import algo.algo1 as solver
    elif algo == 'algo2':
        import algo.algo2 as solver
    else:
        raise ValueError(f'unknown algorithm: {algo}')

    ans = solver.solve(chart)
    plan = touch_plan.make_plan(ans, int(dev_w), int(dev_h), bool(mirror))
    return touch_plan.plan_to_json(plan)
