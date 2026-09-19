"""Build the public Premium guide using the app's existing translations."""
import json
from pathlib import Path

site = Path(__file__).resolve().parents[1]
root = site.parent
out = site / 'premium'
out.mkdir(exist_ok=True)
keys = {
    'intro': 'Take your existing ARVIO setup to Windows, Mac, iPhone, iPad and smart-TV browsers. Your profiles, libraries, addons and progress stay connected through ARVIO Cloud.',
    'sync': 'Same profiles, libraries and watch progress',
    'play': 'Browser playback and one-click VLC',
    'free': 'Android and TV app remains completely free',
    'join': 'Subscribe on Ko-fi',
    'period': '/ month',
    'notice': 'ARVIO is a media hub for sources you configure. Catalog entries do not grant viewing rights. Connect only services and media you are authorized to use.',
    'language': 'App Language',
    'home': 'Home', 'library': 'Library', 'server': 'Homeserver', 'tv': 'Live TV', 'sports': 'Sports',
}
hosting = json.loads((site / 'scripts/premium-hosting.json').read_text(encoding='utf-8'))
manifest = json.loads((root / 'web/lib/i18n/manifest.json').read_text())
languages = json.loads((root / 'web/lib/i18n/languages.json').read_text(encoding='utf-8'))
languages.append({'code': 'en-GB', 'label': 'English (UK)'})
data = {}
for entry in languages:
    code = entry['code']
    base = code.split('-')[0]
    locale = code if code in manifest else ('nb' if base == 'no' else base)
    dictionary = {} if base == 'en' else json.loads((root / f'web/public/i18n/{locale}.json').read_text(encoding='utf-8'))
    normalized = {k.strip().lower(): v for k, v in dictionary.items()}
    translations = {key: normalized.get(value.lower(), value) for key, value in keys.items()}
    # The main copy must never silently fall back to English.
    if base != 'en':
        for key in ('intro', 'sync', 'play', 'free', 'join', 'period', 'notice'):
            assert keys[key].lower() in normalized, (code, key)
    translations['hosting'] = hosting.get(locale, hosting.get(base))
    assert translations['hosting'], code
    data[code] = {'label': entry['label'], **translations}
(out / 'languages.json').write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Built Premium copy for {len(data)} language variants.')

