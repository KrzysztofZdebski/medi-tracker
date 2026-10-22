"""Buduje katalog leków (SQLite) z eksportu XML Rejestru Produktów Leczniczych.

Użycie: python build.py overall.xml out_dir/
Wynik:  out_dir/leki.sqlite.gz, out_dir/version.json
Opis pól źródłowych: docs/rpl.md, schemat wyniku: docs/catalog.md.
"""
import gzip
import hashlib
import json
import sqlite3
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SCHEMA_VERSION = 1  # zmieniać przy każdej zmianie schematu, zapisywane też jako PRAGMA user_version
MIN_PRODUCTS = 15000  # ponytail: ochrona przed opublikowaniem uciętego eksportu, dziś jest ~19 tys.
NS = '{http://rejestry.ezdrowie.gov.pl/rpl/eksport-danych-v6.0.0}'

SCHEMA = """
CREATE TABLE meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE produkt (
    id INTEGER NOT NULL PRIMARY KEY,      -- wewnętrzne ID RPL, to samo co w URL-ach ulotek
    nazwa TEXT NOT NULL,
    nazwa_powszechna TEXT,
    moc TEXT,
    postac TEXT,
    podmiot TEXT,
    procedura TEXT NOT NULL,              -- NAR/MRP/DCP/CEN/IR
    ulotka_url TEXT,
    chpl_url TEXT
);
CREATE TABLE atc (produkt_id INTEGER NOT NULL, kod TEXT NOT NULL, PRIMARY KEY (produkt_id, kod));
CREATE TABLE substancja (
    produkt_id INTEGER NOT NULL,
    nazwa TEXT NOT NULL,
    ilosc TEXT                            -- np. "4 mg/5 ml" albo innyOpisIlosci
);
CREATE TABLE opakowanie (
    id INTEGER NOT NULL PRIMARY KEY,      -- wewnętrzne ID opakowania w RPL
    produkt_id INTEGER NOT NULL,
    kategoria TEXT,                       -- OTC/Rp/Rpz/Rpw/Lz
    zawartosc TEXT NOT NULL,              -- np. "4 fiol. 5 ml", "60 tabl."
    ilosc REAL,                           -- ile sztuk w środku: 60 (tabl.), 4 (fiol.); NULL dla zestawów
    jednostka TEXT,                       -- jednostka do ilosc, np. "tabl.", "fiol."
    skasowane INTEGER NOT NULL,           -- 1 = wykreślone z rejestru, zostawiamy dla starych pudełek
    numer_eu TEXT
);
CREATE TABLE kod (
    gtin TEXT NOT NULL,                   -- zawsze 14 cyfr
    opakowanie_id INTEGER NOT NULL,
    zrodlo TEXT NOT NULL,                 -- 'rpl' albo 'zgoda_prezesa' (obcy kod z importu)
    PRIMARY KEY (gtin, opakowanie_id)
);
CREATE INDEX substancja_produkt ON substancja (produkt_id);
CREATE INDEX opakowanie_produkt ON opakowanie (produkt_id);
"""


def blank(v):
    v = (v or '').strip()
    return None if v in ('', '-', 'brak') else v


def num(v):
    try:
        return float(v.replace(',', '.'))
    except (AttributeError, ValueError):
        return None


def substance_amount(s):
    a = ' '.join(filter(None, (blank(s.get('iloscSubstancji')), blank(s.get('jednostkaMiaryIlosciSubstancji')))))
    b = ' '.join(filter(None, (blank(s.get('iloscPreparatu')), blank(s.get('jednostkaMiaryIlosciPreparatu')))))
    return '/'.join(filter(None, (a, b))) or blank(s.get('innyOpisIlosci'))


def pack_contents(units):
    parts = [' '.join(filter(None, (blank(u.get(k)) for k in
                                    ('liczbaOpakowan', 'rodzajOpakowania', 'pojemnosc', 'jednostkaPojemnosci'))))
             for u in units]
    return ' + '.join(p for p in parts if p)


def build(xml_path, db_path):
    root = ET.parse(xml_path).getroot()
    db = sqlite3.connect(db_path)
    db.executescript(SCHEMA)
    db.execute(f'PRAGMA user_version = {SCHEMA_VERSION}')
    products = 0
    for p in root.iter(NS + 'produktLeczniczy'):
        if p.get('rodzajPreparatu') != 'ludzki':
            continue
        packs = [o for o in p.iter(NS + 'opakowanie') if blank(o.get('kodGTIN'))]
        if not packs:  # bez GTIN nie da się zeskanować, np. warianty CEN niesprzedawane w PL
            continue
        pid = int(p.get('id')[1:-1])  # "1" + ID + cyfra kontrolna, patrz docs/rpl.md
        products += 1
        db.execute('INSERT INTO produkt VALUES (?,?,?,?,?,?,?,?,?)', (
            pid, p.get('nazwaProduktu'), blank(p.get('nazwaPowszechnieStosowana')), blank(p.get('moc')),
            blank(p.get('nazwaPostaciFarmaceutycznej')), blank(p.get('podmiotOdpowiedzialny')), p.get('typProcedury'),
            blank(p.get('ulotka')) or blank(p.get('ulotkaImportRownolegly')) or blank(p.get('etykietoUlotka')),
            blank(p.get('charakterystyka'))))
        db.executemany('INSERT OR IGNORE INTO atc VALUES (?,?)',
                       [(pid, e.text.strip()) for e in p.iter(NS + 'kodATC') if blank(e.text)])
        db.executemany('INSERT INTO substancja VALUES (?,?,?)',
                       [(pid, s.get('nazwaSubstancji'), substance_amount(s)) for s in p.iter(NS + 'substancjaCzynna')])
        for o in packs:
            oid = int(o.get('id'))
            units = list(o.iter(NS + 'jednostkaOpakowania'))
            count = unit = None
            if len(units) == 1:  # "60 tabl." -> 60 tabl., "4 fiol. 5 ml" -> 4 fiol.; zestawy pomijamy
                u = units[0]
                if not blank(u.get('liczbaOpakowan')):
                    count, unit = num(u.get('pojemnosc')), blank(u.get('jednostkaPojemnosci'))
                else:
                    count, unit = num(u.get('liczbaOpakowan')), blank(u.get('rodzajOpakowania'))
            db.execute('INSERT INTO opakowanie VALUES (?,?,?,?,?,?,?,?)', (
                oid, pid, blank(o.get('kategoriaDostepnosci')), pack_contents(units), count, unit,
                int(o.get('skasowane') == 'TAK'), blank(o.get('numerEu'))))
            codes = [(o.get('kodGTIN').strip().zfill(14), 'rpl')]
            codes += [(g.get('numer').strip().zfill(14), 'zgoda_prezesa') for g in o.iter(NS + 'GTINZagraniczny')]
            db.executemany('INSERT OR IGNORE INTO kod VALUES (?,?,?)', [(g, oid, z) for g, z in codes])
    stan = root.get('stanNaDzien')
    db.executemany('INSERT INTO meta VALUES (?,?)', [
        ('stan_na_dzien', stan),
        ('zrodlo', 'Rejestr Produktów Leczniczych, URPL / Centrum e-Zdrowia, licencja CC BY 4.0')])
    db.commit()
    db.execute('VACUUM')
    db.close()
    return products, stan


def main(xml_path, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    db_path = out / 'leki.sqlite'
    db_path.unlink(missing_ok=True)
    products, stan = build(xml_path, db_path)
    if products < MIN_PRODUCTS:
        sys.exit(f'Za mało produktów ({products} < {MIN_PRODUCTS}), nie publikuję.')
    raw = db_path.read_bytes()
    (out / 'leki.sqlite.gz').write_bytes(gzip.compress(raw, 9, mtime=0))
    (out / 'version.json').write_text(json.dumps({
        'schema': SCHEMA_VERSION,
        'stan_na_dzien': stan,
        'sha256': hashlib.sha256(raw).hexdigest(),  # suma pliku PO rozpakowaniu
        'size': len(raw),
    }, indent=2))
    print(f'{products} produktów, stan na {stan}, {len(raw) / 1e6:.1f} MB')


if __name__ == '__main__':
    main(*sys.argv[1:3])
