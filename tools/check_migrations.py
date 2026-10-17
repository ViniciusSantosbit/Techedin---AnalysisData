"""Fail if the canonical SQL differs from the copies packaged by Maven."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
for source in sorted((root / 'database/migrations').glob('V*.sql')):
    packaged = root / 'backend/src/main/resources/db/migration' / source.name
    if source.read_bytes() != packaged.read_bytes():
        raise SystemExit(f'Migration divergente: {source.name}')
print('Migrations sincronizadas.')

if (root / 'ingestion/technologies.json').read_bytes() != (root / 'backend/src/main/resources/technology-catalog.json').read_bytes():
    raise SystemExit('Catálogo tecnológico do backend divergente da coleta.')
print('Catálogo de tecnologias sincronizado.')
