"""Read-only research snapshot. Usage and cost remain unavailable without phone export.

Run: python3 docs/dashboard/build_snapshot.py '/path/to/vault'
Requires the already-installed PyYAML. Does not change vault files.
This lightweight research parser is not a replacement for Sparkbox's Java parsers.
"""
import collections
import datetime as dt
import json
from pathlib import Path
import re
import sys
import yaml

def section(text, title):
    match = re.search(r'^## ' + re.escape(title) + r'\s*\n(.*?)(?=^## |\Z)', text, re.M | re.S)
    return match.group(1) if match else ''

def scan(root):
    now = dt.datetime.now(dt.timezone.utc)
    notes, errors, cards = [], [], []
    for folder in ('Knowledge', 'Sources'):
        for path in sorted((root / folder).rglob('*.md')):
            rel = path.relative_to(root).as_posix()
            try:
                text = path.read_text(encoding='utf-8')
                fm = {}
                if text.startswith('---\n'):
                    raw = text.split('---', 2)
                    try:
                        fm = yaml.safe_load(raw[1]) or {}
                    except yaml.YAMLError:
                        errors.append({'path': rel, 'error': 'Invalid YAML; scalar fallback used'})
                        fm = dict(re.findall(r'^([\w-]+):\s*(.*)$', raw[1], re.M))
                        fm = {k: v.strip('\"\'') for k, v in fm.items()}
                    if not isinstance(fm, dict):
                        raise ValueError('Frontmatter must be a mapping')
            except Exception as exc:
                errors.append({'path': rel, 'error': type(exc).__name__})
                continue
            special = path.stem.lower() in ('index', 'log', 'inbox', 'my cards')
            kind = 'Sources' if rel.startswith(('Knowledge/Sources/', 'Sources/')) else 'Topics' if rel.startswith('Knowledge/Topics/') else 'People' if rel.startswith('Knowledge/People/') else 'Entities' if rel.startswith('Knowledge/Entities/') else 'Learnings' if rel.startswith('Knowledge/Learnings/') else 'Other'
            captured = str(fm.get('captured', ''))[:10]
            valid_date = bool(re.fullmatch(r'\d{4}-\d{2}-\d{2}', captured))
            quality = str(fm.get('quality', 'unknown'))
            topics = fm.get('topics', []) or []
            if not isinstance(topics, list): topics = [str(topics)]
            count = 0
            for block in re.split(r'^### ', section(text, 'Cards'), flags=re.M)[1:]:
                lines = block.splitlines()
                fields = dict(re.findall(r'^([A-Za-z]+):\s*(.*)$', '\n'.join(lines[1:]), re.M))
                if not lines[0].strip() or not fields.get('Q') or not fields.get('A'): continue
                logs = [x.strip() for x in fields.get('Log', '').split(';') if x.strip()]
                state = fields.get('State', '')
                def integer(key):
                    m = re.search(r'\b' + key + r'\s+(\d+)', state)
                    return int(m.group(1)) if m else 0
                cards.append({'path': rel, 'id': lines[0].strip(), 'flagged': fields.get('Flag', '').lower() == 'yes',
                    'parked': fields.get('Parked', ''), 'reviewed': bool(state), 'interval': integer('interval'),
                    'lapses': integer('lapses'), 'logs': logs, 'take': bool(fields.get('Take')), 'type': fields.get('Type', 'classic')})
                count += 1
            if special: continue
            notes.append({'path': rel, 'title': path.stem, 'kind': kind, 'captured': captured if valid_date else '',
                'quality': quality, 'kept': str(fm.get('kept', '')).lower() == 'true', 'cards': count,
                'topics': topics, 'category': path.parts[-3] if kind == 'Sources' and re.fullmatch(r'\d{4}', path.parent.name) else 'Uncategorised',
                'hasWhy': bool(section(text, 'Why I captured this').strip()), 'hasIdeas': bool(section(text, 'My ideas').strip()),
                'bytes': path.stat().st_size})
    sources = [n for n in notes if n['kind'] == 'Sources']
    counts = dict(collections.Counter(n['kind'] for n in notes))
    categories = dict(collections.Counter(n['category'] for n in sources))
    dates = dict(sorted(collections.Counter(n['captured'] for n in sources if n['captured']).items()))
    grades = collections.Counter()
    review_days = collections.Counter()
    for c in cards:
        for log in c['logs']:
            parts = log.split()
            if len(parts) >= 2:
                grades[parts[1]] += 1
                review_days[parts[0][:10]] += 1
    inbox = root / 'Knowledge/Sources/Inbox.md'
    inbox_count = len(re.findall(r'^- (?:🔗|📝|🖼|📎)', inbox.read_text() if inbox.exists() else '', re.M))
    return {'generatedAt': now.isoformat(), 'repositoryRevision': 'c844471', 'scope': 'Knowledge/ and legacy Sources/ only',
        'counts': counts, 'total': len(notes), 'sourceCount': len(sources), 'categories': categories, 'captureDates': dates,
        'quality': dict(collections.Counter(n['quality'] for n in sources)),
        'kept': sum(n['kept'] for n in notes), 'inbox': inbox_count,
        'sourceCards': sum(n['cards'] > 0 for n in sources), 'sourceWhy': sum(n['hasWhy'] for n in sources),
        'sourceIdeas': sum(n['hasIdeas'] for n in sources), 'bytes': sum(n['bytes'] for n in notes),
        'cards': {'total': len(cards), 'reviewed': sum(c['reviewed'] for c in cards), 'flagged': sum(c['flagged'] for c in cards),
            'withTake': sum(c['take'] for c in cards), 'retainedReviews': sum(len(c['logs']) for c in cards), 'grades': dict(grades),
            'reviewDays': dict(sorted(review_days.items())), 'atLogLimit': sum(len(c['logs']) >= 50 for c in cards)},
        'notes': notes, 'parseErrors': errors,
        'limitations': ['Mac snapshot is not a phone sync check.', 'Research parser; production must reuse Vault, Cards and Study. Malformed YAML uses scalar fallback and is flagged.',
            'Capture dates describe surviving source records, not app usage.', 'Review logs retain at most 50 entries per surviving card.',
            'Phone usage counters and Scout session ledgers were not available.', 'Special files excluded from content counts; drill-file cards included in recall counts.']}

if __name__ == '__main__':
    result = scan(Path(sys.argv[1]).expanduser())
    assert result['total'] == sum(result['counts'].values())
    assert result['sourceCards'] <= result['sourceCount']
    assert sum(result['categories'].values()) == result['sourceCount']
    assert sum(result['cards']['grades'].values()) == result['cards']['retainedReviews']
    out = Path(__file__).parent
    (out / 'snapshot.json').write_text(json.dumps(result, indent=2, default=str))
    (out / 'snapshot.js').write_text('window.VAULT_SNAPSHOT=' + json.dumps(result, default=str).replace('</', '<\\/') + ';\n')
    print(json.dumps({k: result[k] for k in ('generatedAt', 'counts', 'sourceCount', 'quality', 'cards', 'inbox', 'parseErrors')}, default=str))
