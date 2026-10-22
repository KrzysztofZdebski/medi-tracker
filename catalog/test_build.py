"""Uruchom: python catalog/test_build.py"""
import sqlite3
import tempfile
from pathlib import Path

from build import build

XML = """<?xml version="1.0" encoding="UTF-8"?>
<produktyLecznicze stanNaDzien="2026-10-22" xmlns="http://rejestry.ezdrowie.gov.pl/rpl/eksport-danych-v6.0.0">
  <produktLeczniczy nazwaProduktu="Apap" rodzajPreparatu="ludzki" nazwaPowszechnieStosowana="Paracetamolum"
      nazwaPoprzedniaProduktu="brak" moc="500 mg" nazwaPostaciFarmaceutycznej="Tabletki" podmiotOdpowiedzialny="US Pharmacia"
      typProcedury="NAR" ulotka="https://x/649/leaflet" charakterystyka="" id="100006494">
    <kodyATC><kodATC>N02BE01</kodATC></kodyATC>
    <substancjeCzynne>
      <substancjaCzynna nazwaSubstancji="Paracetamolum" iloscSubstancji="500" jednostkaMiaryIlosciSubstancji="mg"
          iloscPreparatu="" jednostkaMiaryIlosciPreparatu="" innyOpisIlosci=""/>
    </substancjeCzynne>
    <opakowania>
      <opakowanie kodGTIN="5909990296026" kategoriaDostepnosci="OTC" skasowane="NIE" numerEu="" id="962">
        <jednostkiOpakowania>
          <jednostkaOpakowania liczbaOpakowan="" rodzajOpakowania="" pojemnosc="60" jednostkaPojemnosci="tabl." informacjeDodatkowe=""/>
        </jednostkiOpakowania>
        <zgodyPrezesa><zgodaPrezesa><nrZgodyPrezesa>UR/Z/1</nrZgodyPrezesa>
          <GTINZagraniczne><GTINZagraniczny numer="05050459002044"/></GTINZagraniczne></zgodaPrezesa></zgodyPrezesa>
      </opakowanie>
      <opakowanie kodGTIN="05909990296033" kategoriaDostepnosci="OTC" skasowane="TAK" numerEu="" id="963">
        <jednostkiOpakowania>
          <jednostkaOpakowania liczbaOpakowan="4" rodzajOpakowania="fiol." pojemnosc="0,5" jednostkaPojemnosci="ml" informacjeDodatkowe=""/>
        </jednostkiOpakowania>
      </opakowanie>
      <opakowanie kodGTIN="" kategoriaDostepnosci="OTC" skasowane="NIE" numerEu="" id="964"><jednostkiOpakowania/></opakowanie>
    </opakowania>
  </produktLeczniczy>
  <produktLeczniczy nazwaProduktu="Vet" rodzajPreparatu="weterynaryjny" typProcedury="NAR" id="100000014">
    <opakowania><opakowanie kodGTIN="05909990000001" skasowane="NIE" id="1"/></opakowania>
  </produktLeczniczy>
</produktyLecznicze>"""

with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as d:  # Windows trzyma blokadę pliku SQLite
    xml, db_path = Path(d, 'in.xml'), Path(d, 'out.sqlite')
    xml.write_text(XML, encoding='utf-8')
    assert build(xml, db_path) == (1, '2026-10-22')
    db = sqlite3.connect(db_path)
    q = lambda sql, *a: db.execute(sql, a).fetchall()

    scan = 'SELECT p.nazwa, o.zawartosc, k.zrodlo FROM kod k JOIN opakowanie o ON o.id = k.opakowanie_id JOIN produkt p ON p.id = o.produkt_id WHERE k.gtin = ?'
    assert q(scan, '05909990296026') == [('Apap', '60 tabl.', 'rpl')], '13-cyfrowy GTIN ma być znormalizowany do 14'
    assert q(scan, '05050459002044') == [('Apap', '60 tabl.', 'zgoda_prezesa')]
    assert q(scan, '05909990296033') == [('Apap', '4 fiol. 0,5 ml', 'rpl')], 'wykreślone opakowania zostają'
    assert q(scan, '05909990000001') == [], 'bez weterynaryjnych'
    assert q('SELECT id, nazwa_powszechna, chpl_url FROM produkt') == [(649, 'Paracetamolum', None)]
    assert q('SELECT ilosc, jednostka, skasowane FROM opakowanie ORDER BY id') == [(60.0, 'tabl.', 0), (4.0, 'fiol.', 1)]
    assert q('SELECT ilosc FROM substancja') == [('500 mg',)]
    assert q('SELECT kod FROM atc') == [('N02BE01',)]
    assert q('PRAGMA user_version') == [(1,)]
    db.close()
print('ok')
