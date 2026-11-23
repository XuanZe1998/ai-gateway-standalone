"""Download the pinned SVGs in the shared model icon catalog, with source hashes."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'src/main/resources/static/model-icons'


def fetch(url):
    with urlopen(Request(url, headers={'User-Agent': 'ModelIconLibrary/1.0'}), timeout=60) as response:
        return response.read()


def download(icon):
    data = fetch(icon['source'])
    root = ET.fromstring(data)
    if root.tag != '{http://www.w3.org/2000/svg}svg' or 'viewBox' not in root.attrib:
        raise ValueError(f"Invalid SVG: {icon['id']}")
    for element in root.iter():
        if element.tag.split('}')[-1] in ('script', 'foreignObject', 'image', 'use'):
            raise ValueError(f"Unexpected active or external SVG content: {icon['id']}")
        if any(key.lower().startswith('on') or key.split('}')[-1] == 'href' for key in element.attrib):
            raise ValueError(f"Unexpected SVG attribute: {icon['id']}")
    # An SVG loaded through <img> cannot inherit the page's currentColor.
    data = data.replace(b'currentColor', b'#18354d').replace(b'\r\n', b'\n')
    (DEST / icon['file']).write_bytes(data)
    return {'id': icon['id'], 'source': icon['source'], 'sha256': hashlib.sha256(data).hexdigest()}


if __name__ == '__main__':
    catalog = json.loads((DEST / 'catalog.json').read_text(encoding='utf-8'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        sources = list(pool.map(download, catalog['icons']))
    (DEST / 'sources.json').write_text(json.dumps(sources, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Downloaded and validated {len(sources)} SVG icons.')
