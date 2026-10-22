# Katalog leków (SQLite)

Plik SQLite z lekami ludzkimi z RPL, które da się zeskanować, czyli mają GTIN. GitHub Actions buduje go codziennie i publikuje jako asset w release `katalog`. Aplikacja pobiera cały plik i wyszukuje lokalnie (offline, bez wysyłania na serwer informacji o lekach użytkownika).

## Adresy

| Plik | URL |
|---|---|
| Wersja | https://github.com/KrzysztofZdebski/medi-tracker/releases/download/katalog/version.json |
| Baza (gzip) | https://github.com/KrzysztofZdebski/medi-tracker/releases/download/katalog/leki.sqlite.gz |

`version.json`:
```json
{ "schema": 1, "stan_na_dzien": "2026-10-16", "sha256": "…", "size": 13475840 }
```
- `sha256` i `size` dotyczą pliku **po rozpakowaniu**.
- `schema` to wersja schematu; zapisana jest też w bazie jako `PRAGMA user_version`.

Rozmiar (stan na 2026-10-16): 13,5 MB, a po gzipie 3,8 MB. Zawiera 19 062 produkty, 67 917 opakowań i 68 393 kody (w tym 476 zagranicznych ze zgód Prezesa URPL).

## Jak aplikacja ma aktualizować

1. Pierwszą wersję bazy dołączyć do APK (Room `createFromAsset`), żeby skanowanie działało od razu po instalacji.
2. Raz dziennie (WorkManager, najlepiej tylko przez Wi-Fi) pobrać `version.json`.
3. Jeśli `stan_na_dzien` jest nowszy niż lokalny, a `schema` taki sam jak obsługiwany przez aplikację:
   - pobrać `.gz` do pliku tymczasowego i rozpakować (`GZIPInputStream`);
   - sprawdzić `sha256`;
   - dopiero wtedy podmienić plik.
4. Jeśli `schema` jest wyższy niż obsługiwany, nic nie robić: stara aplikacja zostaje na starym katalogu.

**Katalog trzymać w osobnej bazie niż dane użytkownika**, żeby podmiana pliku nie mogła dotknąć historii przyjęć. Z danych użytkownika odwołujemy się do katalogu przez `opakowanie.id` / `produkt.id` (stabilne ID z RPL).

## Schemat

Pełna definicja jest w [`catalog/build.py`](../catalog/build.py) (`SCHEMA`). Znaczenie pól źródłowych opisuje [rpl.md](rpl.md).

| Tabela | Klucz | Zawartość |
|---|---|---|
| `meta` | `key` | `stan_na_dzien`, `zrodlo` (do wyświetlenia w aplikacji, wymóg licencji CC BY) |
| `produkt` | `id` = wewnętrzne ID RPL | nazwa, nazwa powszechna, moc, postać, podmiot, procedura, linki do ulotki i ChPL |
| `atc` | (`produkt_id`, `kod`) | kody ATC |
| `substancja` | — | substancje czynne z ilością jako tekst („4 mg/5 ml”) |
| `opakowanie` | `id` = ID opakowania w RPL | zawartość jako tekst, `ilosc` + `jednostka` (ile sztuk w środku; wypełnione w 98%, NULL dla zestawów), kategoria dostępności, `skasowane`, `numer_eu` |
| `kod` | (`gtin`, `opakowanie_id`) | GTIN-14 → opakowanie; `zrodlo` = `rpl` albo `zgoda_prezesa` |

Skan pudełka:
```sql
SELECT p.*, o.*
FROM kod k
JOIN opakowanie o ON o.id = k.opakowanie_id
JOIN produkt p ON p.id = o.produkt_id
WHERE k.gtin = :gtin14;   -- GTIN z kodu uzupełnić zerami do 14 cyfr
```
Jeden GTIN może zwrócić **więcej niż jeden wiersz** (kilka duplikatów w RPL), więc aplikacja musi to obsłużyć.

Co jest odfiltrowane, a co zostawione:
- odfiltrowane: leki weterynaryjne i opakowania bez GTIN;
- zostawione: opakowania wykreślone (`skasowane = 1`), bo użytkownik może mieć stare pudełko.

**Room:** przy `createFromAsset` / `createFromFile` Room porównuje schemat pliku z encjami. Typy (`INTEGER`/`TEXT`/`REAL`) i `NOT NULL` w encjach muszą dokładnie odpowiadać `SCHEMA`.

## Pipeline

[`.github/workflows/catalog.yml`](../.github/workflows/catalog.yml) uruchamia się codziennie, po pushu zmian w `catalog/` i ręcznie (zakładka Actions → „Run workflow”). Kolejne kroki:
1. Test `catalog/test_build.py`.
2. Pobranie `overall.xml`.
3. `build.py`.
4. Upload do release `katalog`: najpierw baza, potem `version.json`.

Zabezpieczenia:
- Jeśli eksport ma mniej niż 15 000 produktów (ucięty albo zepsuty plik), build kończy się błędem i nic nie jest publikowane.
- Klienci zostają wtedy na poprzedniej wersji.

Lokalnie:
```bash
python catalog/test_build.py
python catalog/build.py overall.xml out
```

**Uwaga:** GitHub wyłącza zaplanowane workflow w publicznym repo po 60 dniach bez commitów. Wtedy trzeba je włączyć ponownie w zakładce Actions.

## Czego katalog jeszcze nie zawiera

Lista refundacyjna, ChPL dla CEN z EMA i dawkowania wyciągnięte z ChPL. Wszystkie te źródła opisuje [rpl.md](rpl.md).
