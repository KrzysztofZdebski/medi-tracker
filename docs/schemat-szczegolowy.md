# Szczegółowy schemat bazy danych medi-tracker

Opis kolumn wszystkich tabel. Architektura, diagramy relacji i uzasadnienia decyzji są w [`baza-danych.md`](baza-danych.md), a wykonywalne definicje w [`schema/catalog_db.sql`](schema/catalog_db.sql) i [`schema/user_db.sql`](schema/user_db.sql).

## Konwencje (obie bazy)

- Typy SQLite: `INTEGER`, `TEXT`, `REAL`.
- `*_at`: INTEGER, epoch ms UTC.
- `*_date`: TEXT `YYYY-MM-DD`, czas lokalny.
- `time_of_day_min`: minuty od północy.
- `*_x1000`: ilość × 1000 jako INTEGER, np. 0,5 tabl. = 500. Eliminuje błędy float przy sumowaniu zapasu.
- W kolumnie „Klucz”:
  - PK = klucz główny;
  - FK = klucz obcy;
  - ref. = miękka referencja do drugiego pliku;
  - idx = indeks.

**Kolumny standardowe** każdej tabeli `user.db` (poza `barcode_lookup_cache`): `created_at INTEGER NOT NULL`, `updated_at INTEGER NOT NULL`, `deleted_at INTEGER NULL`. Nie powtarzam ich w tabelach poniżej.

## `catalog.db`

**`catalog_meta`** (WITHOUT ROWID)
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| key | TEXT | PK | `source_as_of`, `schema_version`, `built_at`, `source_url`, `license` |
| value | TEXT NOT NULL | | |

**`product`**
| Kolumna | Typ | Klucz | Opis (pole RPL) |
|---|---|---|---|
| id | INTEGER | PK | `@id`, np. 100006494 |
| rpl_internal_id | INTEGER NOT NULL | idx | `int(id[1:-1])`; łączy z API JSON i URL-ami |
| name | TEXT NOT NULL | | `nazwaProduktu` |
| common_name | TEXT | | `nazwaPowszechnieStosowana` |
| previous_name | TEXT | | `nazwaPoprzedniaProduktu` (`brak` → NULL) |
| strength_text | TEXT | | `moc` (`-` → NULL) |
| form | TEXT NOT NULL | | `nazwaPostaciFarmaceutycznej` |
| marketing_auth_holder | TEXT NOT NULL | | `podmiotOdpowiedzialny` |
| procedure_type | TEXT NOT NULL | | NAR / MRP / DCP / CEN / IR |
| authorization_no | TEXT | | `numerPozwolenia` (brak dla CEN) |
| authorization_valid_until | TEXT | | tylko informacyjnie; nie filtrować |
| leaflet_url, chpl_url, label_leaflet_url, ir_leaflet_url | TEXT | | linki do PDF (linkujemy, nie hostujemy) |
| ema_epar_url | TEXT | | CEN: PDF EMA z ChPL i ulotką |
| reference_product_id | INTEGER | FK → product.id, idx | IR → polski odpowiednik (źródło ChPL) |
| has_safety_messages | INTEGER NOT NULL | | z CSV („Komunikaty bezpieczeństwa”) |

**`package`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | INTEGER | PK | `opakowanie/@id` |
| product_id | INTEGER NOT NULL | FK → product.id, idx | |
| availability | TEXT | | Rp / OTC / Rpz / Lz / Rpw |
| is_deleted | INTEGER NOT NULL | | `skasowane`; wiersza nie usuwamy |
| eu_number | TEXT | | `numerEu` (CEN) |
| parallel_distributor | TEXT | | `dystrybutorRownolegly` |
| content_text | TEXT | | czytelnie: „60 tabl.”, „4 fiol. 5 ml” |
| pack_qty_x1000 | INTEGER | | liczba jednostek dawkowania (60 tabl. → 60000); NULL, gdy niejednoznaczne |
| pack_unit | TEXT | | TABLET / CAPSULE / ML / …; ten sam słownik co `medication.dose_unit` |
| is_kit | INTEGER NOT NULL | | więcej niż jedna `jednostkaOpakowania` |

**`package_unit`** (WITHOUT ROWID)
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| package_id | INTEGER NOT NULL | PK (1), FK → package.id | |
| ordinal | INTEGER NOT NULL | PK (2) | |
| container_count | INTEGER | | `liczbaOpakowan` |
| container_type | TEXT | | `rodzajOpakowania`: fiol., amp., … |
| capacity_text | TEXT | | `pojemnosc` |
| capacity_unit | TEXT | | `jednostkaPojemnosci`: tabl., ml, … |
| extra_info | TEXT | | `informacjeDodatkowe` |

**`barcode`** (WITHOUT ROWID)
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| gtin | TEXT NOT NULL, długość 14 | PK (1) | znormalizowany GTIN |
| package_id | INTEGER NOT NULL | PK (2), FK → package.id, idx | |
| kind | TEXT NOT NULL | | `PRIMARY` (kodGTIN) / `FOREIGN_CONSENT` (GTINZagraniczny) |
| consent_no | TEXT | | `nrZgodyPrezesa` |

Złożony PK zaczynający się od `gtin` w tabeli WITHOUT ROWID sprawia, że wyszukanie po skanie to jedno przejście B-drzewa, bez osobnego indeksu. Sprawdzone przez `EXPLAIN QUERY PLAN`: `SEARCH b USING PRIMARY KEY (gtin=?)`.

**`active_substance`** (WITHOUT ROWID)
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| product_id | INTEGER NOT NULL | PK (1), FK → product.id | |
| ordinal | INTEGER NOT NULL | PK (2) | |
| name | TEXT NOT NULL | | `nazwaSubstancji` |
| amount_text | TEXT | | oryginał, np. „0,5” |
| amount_value | REAL | | sparsowana liczba |
| amount_unit | TEXT | | mg, mcg, j.m., … |
| per_quantity | REAL | | `iloscPreparatu` (NULL = na jednostkę dawkowania) |
| per_unit | TEXT | | ml, g, dawka, … |
| other_description | TEXT | | `innyOpisIlosci` |

**`product_atc`** (WITHOUT ROWID)
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| product_id | INTEGER NOT NULL | PK (1), FK → product.id | |
| atc_code | TEXT NOT NULL | PK (2), idx | 3–7 znaków |

**`product_route`** (WITHOUT ROWID)
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| product_id | INTEGER NOT NULL | PK (1), FK → product.id | |
| route | TEXT NOT NULL | PK (2) | `drogaPodaniaNazwa` |

**`educational_material`** (WITHOUT ROWID)
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| product_id | INTEGER NOT NULL | PK (1), FK → product.id | |
| ordinal | INTEGER NOT NULL | PK (2) | |
| audience | TEXT NOT NULL | | PATIENT / PROFESSIONAL |
| name | TEXT NOT NULL | | np. „Karta pacjenta” |
| url | TEXT NOT NULL | | |

**`dosage_guideline`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | INTEGER | PK | |
| product_id | INTEGER NOT NULL | FK → product.id, idx | |
| population | TEXT | | „Dorośli i młodzież > 12 lat”, „Dzieci 6–12 lat”, „Niewydolność nerek” |
| indication | TEXT | | gdy dawka zależy od wskazania |
| route | TEXT | | |
| dose_min_x1000, dose_max_x1000 | INTEGER | | dawka jednorazowa od–do |
| dose_unit | TEXT | | TABLET / ML / MG / … |
| doses_per_day_min, doses_per_day_max | INTEGER | | |
| min_interval_min | INTEGER | | minimalny odstęp między dawkami |
| max_single_dose_x1000, max_daily_dose_x1000 | INTEGER | | |
| max_duration_days | INTEGER | | „nie stosować dłużej niż 3 dni bez konsultacji” |
| meal_relation | TEXT | | BEFORE / WITH / AFTER / EMPTY_STOMACH / ANY |
| text_verbatim | TEXT NOT NULL | | dosłowny fragment, zawsze pokazywany |
| source_type | TEXT NOT NULL | | CHPL_4_2 / LEAFLET / EMA_EPAR |
| source_url, source_doc_date | TEXT | | |
| extraction | TEXT NOT NULL | | MANUAL / PARSER |
| verified | INTEGER NOT NULL | | czy ktoś sprawdził wynik parsera |

**`product_fts`**: tabela FTS4 (`name`, `common_name`, `substances`, `marketing_auth_holder`), `docid = product.id`.

## `user.db`

**`profile`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | UUIDv7 |
| name | TEXT NOT NULL | | |
| is_default | INTEGER NOT NULL | | |

**`day_period`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | |
| profile_id | TEXT NOT NULL | FK → profile.id, idx | |
| name | TEXT NOT NULL | | „Rano” |
| time_of_day_min | INTEGER NOT NULL | | 0–1439 |
| sort_order | INTEGER NOT NULL | | |

**`medication`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | UUIDv7 |
| profile_id | TEXT NOT NULL | FK → profile.id, idx (`profile_id`, `archived_at`) | |
| origin | TEXT NOT NULL | | RPL_CATALOG / RPL_API / MANUAL |
| rpl_product_id | INTEGER | ref. → product.id, idx | NULL dla leków spoza RPL |
| rpl_source_as_of | TEXT | | data danych RPL (licencja) |
| display_name | TEXT NOT NULL | | snapshot albo własna nazwa |
| common_name, strength_text, form_text | TEXT | | snapshot z katalogu |
| dose_unit | TEXT NOT NULL | | TABLET / CAPSULE / ML / DROP / IU / PUFF / SACHET / PATCH / SUPPOSITORY / APPLICATION / PIECE / MG |
| instructions_note | TEXT | | |
| stock_tracking | INTEGER NOT NULL | | czy liczyć zapas |
| refill_threshold_days | INTEGER | | alert, gdy zapasu zostanie mniej niż na N dni |
| refill_threshold_qty_x1000 | INTEGER | | albo mniej niż N jednostek |
| last_refill_alert_at | INTEGER | | ogranicza powtarzanie alertu |
| archived_at | INTEGER | | koniec terapii; historia zostaje |

**`schedule`**: zalecenie dawkowania, wersjonowane datami.
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | |
| medication_id | TEXT NOT NULL | FK → medication.id, idx (`medication_id`, `start_date`) | |
| recommended_by | TEXT NOT NULL | | DOCTOR / PHARMACIST / SELF |
| prescriber_name | TEXT | | |
| recurrence_type | TEXT NOT NULL | | DAILY / EVERY_N_DAYS / WEEKDAYS / CYCLE / AS_NEEDED |
| interval_days | INTEGER | | dla EVERY_N_DAYS (≥ 2) |
| weekdays_mask | INTEGER | | dla WEEKDAYS: bit 0 = poniedziałek … bit 6 = niedziela |
| cycle_on_days, cycle_off_days | INTEGER | | dla CYCLE, np. 21 + 7 |
| start_date | TEXT NOT NULL | | |
| end_date | TEXT | | NULL = bezterminowo |
| prn_max_doses_per_day, prn_min_interval_min | INTEGER | | dla AS_NEEDED („doraźnie”) |
| tz_mode | TEXT NOT NULL | | LOCAL (godzina według strefy, w której jest użytkownik) / FIXED |
| fixed_tz_id | TEXT | | np. `Europe/Warsaw` przy FIXED |
| reminders_enabled | INTEGER NOT NULL | | |
| snooze_min | INTEGER NOT NULL | | |
| nag_until_confirmed | INTEGER NOT NULL | | ponawiaj, dopóki użytkownik nie potwierdzi |
| supersedes_schedule_id | TEXT | FK → schedule.id | poprzednia wersja zalecenia |
| note | TEXT | | |

Zmiana dawki przez lekarza nie robi `UPDATE`. Ustawiamy `end_date` w starym wierszu i tworzymy nowy wiersz z `supersedes_schedule_id`. Dzięki temu historia zawsze wskazuje zalecenie obowiązujące w danym dniu.

**`schedule_slot`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | |
| schedule_id | TEXT NOT NULL | FK → schedule.id, idx | |
| day_period_id | TEXT | FK → day_period.id, idx | godzina z pory dnia… |
| time_of_day_min | INTEGER | | …albo godzina podana wprost (dokładnie jedno z dwóch) |
| dose_qty_x1000 | INTEGER NOT NULL | | > 0 |
| meal_relation | TEXT | | BEFORE / WITH / AFTER / EMPTY_STOMACH / ANY |

**`dose_log`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | UUIDv7 |
| medication_id | TEXT NOT NULL | FK → medication.id, idx (`medication_id`, `taken_at`) | |
| schedule_id | TEXT | FK → schedule.id | |
| slot_id | TEXT | FK → schedule_slot.id | NULL = dawka doraźna |
| scheduled_date | TEXT | idx | lokalna data wystąpienia; **UNIQUE (`slot_id`, `scheduled_date`)** |
| scheduled_at | INTEGER | | planowany moment (UTC) |
| tz_id | TEXT NOT NULL | | strefa czasowa w chwili zdarzenia |
| status | TEXT NOT NULL | | TAKEN / SKIPPED / MISSED |
| planned_qty_x1000, taken_qty_x1000 | INTEGER | | |
| taken_at | INTEGER | | faktyczny moment przyjęcia (można podać wstecz) |
| recorded_at | INTEGER NOT NULL | | moment zapisu |
| source | TEXT NOT NULL | | NOTIFICATION / APP / WIDGET / AUTO |
| skip_reason, note | TEXT | | |

**`stock_batch`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | |
| medication_id | TEXT NOT NULL | FK → medication.id, idx (`medication_id`, `expiry_date`) | |
| rpl_package_id | INTEGER | ref. → package.id | |
| gtin | TEXT | | |
| batch_no, serial_no | TEXT | | z kodu 2D; **UNIQUE (`gtin`, `serial_no`)**, żeby tego samego pudełka nie dodać dwa razy |
| expiry_date | TEXT | | z kodu 2D albo wpisana ręcznie |
| initial_qty_x1000 | INTEGER NOT NULL | | z `package.pack_qty_x1000`; użytkownik może poprawić |
| opened_date | TEXT | | |
| discard_after_opening_days | INTEGER | | np. krople do oczu: 28 dni |
| status | TEXT NOT NULL | | SEALED / OPEN / FINISHED / DISCARDED |
| acquired_at | INTEGER NOT NULL | | |

**`stock_movement`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | |
| medication_id | TEXT NOT NULL | FK → medication.id, idx pokrywający (`medication_id`, `deleted_at`, `delta_qty_x1000`) | |
| batch_id | TEXT | FK → stock_batch.id | |
| delta_qty_x1000 | INTEGER NOT NULL | | wartość dodatnia = przychód, ujemna = rozchód |
| reason | TEXT NOT NULL | | INITIAL / PURCHASE / DOSE / ADJUSTMENT / DISCARD |
| dose_log_id | TEXT | FK → dose_log.id, **UNIQUE** | jedna dawka = jeden ruch; cofnięcie dawki = soft delete ruchu |
| occurred_at | INTEGER NOT NULL | | |
| note | TEXT | | |

**`leaflet_dosage`**: kolumny merytoryczne takie same jak `catalog.dosage_guideline` (od `population` do `text_verbatim`), poza tym:
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | |
| medication_id | TEXT NOT NULL | FK → medication.id, idx | |
| catalog_guideline_id | INTEGER | ref. → dosage_guideline.id | jeśli skopiowano z katalogu |
| source_type | TEXT NOT NULL | | CATALOG / USER_FROM_LEAFLET / PHARMACIST / OTHER |
| source_url, source_date | TEXT | | |

**`prescription`**
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| id | TEXT | PK | |
| medication_id | TEXT NOT NULL | FK → medication.id, idx | |
| issued_date | TEXT NOT NULL | | |
| valid_until_date | TEXT | | |
| packages_total | INTEGER | | |
| packages_redeemed | INTEGER NOT NULL | | |
| prescriber_name, note | TEXT | | |

**`user_barcode`** (WITHOUT ROWID)
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| profile_id | TEXT NOT NULL | PK (1), FK → profile.id | |
| gtin | TEXT NOT NULL | PK (2) | |
| medication_id | TEXT NOT NULL | FK → medication.id | |
| rpl_package_id | INTEGER | ref. → package.id | |
| pack_qty_x1000 | INTEGER | | nadpisana wielkość opakowania |

**`barcode_lookup_cache`** (WITHOUT ROWID, bez kolumn standardowych)
| Kolumna | Typ | Klucz | Opis |
|---|---|---|---|
| gtin | TEXT | PK | |
| status | TEXT NOT NULL | idx (`status`, `next_attempt_at`) | FOUND / NOT_FOUND / PENDING / ERROR |
| rpl_internal_id | INTEGER | ref. → product.rpl_internal_id | |
| payload_json | TEXT | | przycięta odpowiedź (nazwa, moc, postać, INN, procedura) |
| catalog_version | TEXT | | `source_as_of` katalogu w chwili zapytania |
| fetched_at, expires_at | INTEGER | | |
| attempts, next_attempt_at | INTEGER | | ponawianie z wykładniczo rosnącym odstępem |
| last_error | TEXT | | |
