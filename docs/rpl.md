# Rejestr Produktów Leczniczych (RPL)

Oficjalny rejestr wszystkich leków dopuszczonych do obrotu w Polsce. Prowadzi go URPL, a udostępnia Centrum e-Zdrowia. To nasze główne źródło do mapowania **kod kreskowy → lek**.

Wszystkie liczby w tym dokumencie policzono z eksportu XML ze stanem na **2026-10-16**.

## Linki

| Co | URL |
|---|---|
| Wyszukiwarka (przeglądarka) | https://rejestry.ezdrowie.gov.pl/rpl/search/public |
| Strona rejestru | https://rejestry.ezdrowie.gov.pl/registry/rpl |
| Eksport XML (pełny, ~74 MB) | https://rejestry.ezdrowie.gov.pl/api/rpl/medicinal-products/public-pl-report/6.0.0/overall.xml |
| Eksport CSV (~17 MB, separator `;`) | https://rejestry.ezdrowie.gov.pl/api/rpl/medicinal-products/public-pl-report/get-csv |
| Eksport XLSX | https://rejestry.ezdrowie.gov.pl/api/rpl/medicinal-products/public-pl-report/get-xlsx |
| Karta zbioru na dane.gov.pl (licencja, metadane) | https://dane.gov.pl/pl/dataset/397,rejestr-produktow-leczniczych |
| Ulotka produktu (PDF) | `https://rejestry.ezdrowie.gov.pl/api/rpl/medicinal-products/{id}/leaflet` |
| ChPL produktu (PDF) | `https://rejestry.ezdrowie.gov.pl/api/rpl/medicinal-products/{id}/characteristic` |
| Wyszukiwanie JSON (nieoficjalne) | `https://rejestry.ezdrowie.gov.pl/api/rpl/medicinal-products/search/public?eanGtin=05909990296026` |

## Prawa dostępu i użycia

- **Dostęp:** publiczny. Nie trzeba klucza API, konta ani rejestracji. Eksporty są aktualizowane codziennie.
- **Licencja:** według dane.gov.pl zbiór jest udostępniany na licencji **CC BY 4.0**, z warunkiem **podawania czasu wytworzenia i pozyskania informacji** (`license_condition_timestamp`). Wolno go używać komercyjnie i przetwarzać. W aplikacji musimy:
  - wskazać źródło, np. „Źródło: Rejestr Produktów Leczniczych, URPL / Centrum e-Zdrowia, stan na RRRR-MM-DD”;
  - pokazywać datę danych; mamy ją w atrybucie `stanNaDzien`.
- **Podstawa prawna rejestru:** ustawa Prawo farmaceutyczne oraz rozporządzenie MZ z 17.04.2019 w sprawie prowadzenia i udostępniania RPL (określa też strukturę udostępnianych danych).
- **Ulotki i ChPL (PDF):** są dokumentami zatwierdzonymi przez URPL, ale ich autorami są firmy farmaceutyczne. Nie jest jasne, czy licencja CC BY zbioru obejmuje treść tych plików. Bezpiecznie jest **linkować do PDF-ów** zamiast hostować ich kopie. Wyciąganie z nich danych na własny użytek (np. dawkowania) to inna sprawa niż redystrybucja.
- **API JSON wyszukiwarki:** nie ma dokumentacji ani regulaminu. Służy frontendowi rejestru i może się zmienić bez uprzedzenia. Do importu używamy XML.
- **Regulacje (nie dotyczy samego RPL):** jeśli aplikacja zacznie *obliczać* dawki albo ostrzegać przed interakcjami, może podpaść pod MDR jako wyrób medyczny (oprogramowanie). Trzeba to sprawdzić przed dodaniem takich funkcji.

## Formaty

| Format | Struktura | Uwagi |
|---|---|---|
| **XML (v6.0.0)** | hierarchiczna: produkt → opakowania → jednostki | **Rekomendowany.** Jedyny format, w którym dane opakowań (GTIN, kategoria dostępności) są w osobnych polach. |
| CSV | jeden wiersz na produkt; opakowania sklejone w jednej komórce (`GTIN ¦ kategoria ¦ id` + opis, z przejściami do nowej linii) | Ma jedną kolumnę, której nie ma w XML: **„Komunikaty bezpieczeństwa”**. Linki w CSV wskazują na host `rejestrymedyczne.ezdrowie.gov.pl`. |
| XLSX | jak CSV | |
| JSON (search) | płaska lista produktów, stronicowana | bez opakowań; szczegóły produktu (`/medicinal-products/{id}`) zwracają 403 |

Namespace XML: `http://rejestry.ezdrowie.gov.pl/rpl/eksport-danych-v6.0.0`

## Zakres: „leki sprzedawane w Polsce”

RPL zawiera leki **dopuszczone** do obrotu, a nie leki, które faktycznie są w aptekach. Jako przybliżenie przyjmujemy:

- `rodzajPreparatu = "ludzki"` (bez weterynaryjnych),
- opakowanie z `skasowane = "NIE"` (nie wykreślone z rejestru),
- opakowanie z niepustym `kodGTIN`. Firma nadaje polski GTIN w zasadzie tylko opakowaniom, które wprowadza na polski rynek. Dotyczy to zwłaszcza leków CEN: rejestr UE zawiera wszystkie warianty opakowań, a GTIN mają tylko te sprzedawane w PL.

| | Cała baza | **W zakresie** |
|---|---|---|
| Produkty | 22 947 | **19 062** |
| Opakowania | 88 477 | **64 385** |

**Wszystkie procenty wypełnienia poniżej liczono tylko dla tego zakresu.** „Wypełnione” znaczy: atrybut istnieje i nie jest pustym napisem.

---

## Struktura XML i pola

```
produktyLecznicze @stanNaDzien
└─ produktLeczniczy  (1 rekord = nazwa + moc + postać + numer pozwolenia)
   ├─ kodyATC/kodATC
   ├─ drogiPodania/drogaPodania
   ├─ substancjeCzynne/substancjaCzynna
   ├─ opakowania/opakowanie               ← tu jest GTIN
   │  ├─ jednostkiOpakowania/jednostkaOpakowania
   │  └─ zgodyPrezesa/zgodaPrezesa
   │     ├─ nrZgodyPrezesa
   │     └─ GTINZagraniczne/GTINZagraniczny
   ├─ daneOWytworcy/wytworcy
   ├─ materialyEdukacyjne/{dlaPacjenta,dlaMedyka}/materialEdukacyjny
   ├─ gatunki/gatunek                     (tylko weterynaryjne)
   └─ okresyKarencji/okresKarencji        (tylko weterynaryjne)
```

### `produktyLecznicze` (korzeń)

| Pole | Znaczenie | Wypełnienie |
|---|---|---|
| `stanNaDzien` | Data, na którą wygenerowano eksport. Pokazujemy ją użytkownikowi (wymóg licencji). | 100% |

### `produktLeczniczy` — 19 062 rekordy

| Pole | Co tak naprawdę oznacza | Wypełnienie | Uwagi / przykłady |
|---|---|---|---|
| `id` | Identyfikator produktu w RPL. Format: `1` + 7-cyfrowe wewnętrzne ID + cyfra kontrolna. Wewnętrzne ID (`int(id[1:-1])`) to liczba z URL-i ulotek i z API JSON. | 100% | `100006494` → wewnętrzne `649` (sprawdzone na 11 591 produktach z linkami) |
| `nazwaProduktu` | Nazwa handlowa z pudełka. | 100% | „Apap”. Jedna nazwa może mieć wiele rekordów (różne moce i postacie), np. Xanax ma 50. |
| `nazwaPowszechnieStosowana` | Nazwa substancji czynnej (INN, zwykle po łacinie). Dla produktów bez jednej substancji jest tu opis kategorii. | 100% | „Paracetamolum”; „Homeopatyczny produkt leczniczy” (244), „Produkt złożony” (196) |
| `nazwaPoprzedniaProduktu` | Nazwa sprzed zmiany nazwy produktu. | 100%, ale **w 93,7% to `"brak"`** | `"brak"` traktować jak null |
| `rodzajPreparatu` | `ludzki` / `weterynaryjny` | 100% | w zakresie zawsze `ludzki` |
| `moc` | Moc jako tekst, nie liczba. | 99,5% | „500 mg”, „4 mg/5 ml”. W 702 rekordach wartość to `"-"` (preparaty złożone, ziołowe, homeopatyczne). |
| `nazwaPostaciFarmaceutycznej` | Postać farmaceutyczna. | 100% | 389 wartości, np. „Tabletki powlekane”, „Kapsułki twarde”, „Krem” |
| `podmiotOdpowiedzialny` | Firma, która posiada pozwolenie i odpowiada za lek (MAH). To niekoniecznie producent. | 100% | Przy imporcie równoległym jest to importer (InPharm, Delfarma). |
| `typProcedury` | Tryb rejestracji leku (szczegóły niżej). | 100% | DCP 5381, NAR 5070, IR 4346, CEN 2605, MRP 1660 |
| `numerPozwolenia` | Numer pozwolenia na dopuszczenie do obrotu. | **86,3%** | **Brak dla 100% CEN.** Zamiast niego jest numer EU w opakowaniu (`numerEu`). IR ma własny format, np. `708/12`. |
| `waznoscPozwolenia` | Do kiedy ważne jest pozwolenie: data albo `"Bezterminowe"`. | **86,6%** | Brak w niemal całym CEN (49/2605). **2 266 dat jest już w przeszłości**, mimo że opakowania są aktywne (zwykle trwa przedłużanie). Pole nie nadaje się do filtrowania „czy lek jest w obrocie”. |
| `podstawaPrawna` | Artykuł Prawa farmaceutycznego, na podstawie którego wydano pozwolenie, czyli rodzaj wniosku (szczegóły niżej). | **62,9%** | Brak dla całego CEN i IR. |
| `zakazStosowaniaUZwierzat` | `TAK` = leku ludzkiego nie wolno stosować u zwierząt. Dotyczy antybiotyków i leków przeciwwirusowych zarezerwowanych dla ludzi (lista UE). | 1,0% (zawsze `TAK`) | Puste = brak zakazu. Teikoplanina, oseltamiwir, meropenem. Dla naszej aplikacji bez znaczenia. |
| `ulotka` | Link do PDF ulotki dla pacjenta. | **59,9%** | **Brak dla 100% CEN** (ulotki są na ema.europa.eu) i dla IR (IR ma osobne pole). |
| `charakterystyka` | Link do PDF ChPL (dokumentacja dla lekarza; w niej dawkowanie, interakcje itd.). | **60,5%** | Brak dla CEN i IR. |
| `etykietoUlotka` | Link do PDF etykieto-ulotki, czyli ulotki wydrukowanej na opakowaniu (małe opakowania, ampułki). | 0,3% | |
| `ulotkaImportRownolegly` | Ulotka w wersji importera równoległego. | 17,3% | tylko IR |
| `etykietoUlotkaImportRownolegly` | Etykieto-ulotka importu równoległego. | 0,2% | tylko IR |
| `oznaczenieOpakowanImportRownolegly` | PDF z oznakowaniem opakowania importu równoległego (jak wygląda polska naklejka lub opakowanie). | 16,9% | tylko IR |

**Ulotka dowolnego typu według procedury:** DCP 100%, MRP 100%, NAR 87%, IR 76%, **CEN 0%**.

#### Brak ChPL dla CEN i IR: co to znaczy dla projektu

Razem to ok. 23% opakowań w zakresie. **Skanowanie działa normalnie**, bo opakowania z obu grup mają własne GTIN-y. Problem dotyczy tylko tego, skąd wziąć ChPL, czyli dawkowanie i inne dane.

**CEN (rejestracja centralna UE).** Lek zatwierdza Komisja Europejska po opinii EMA i pozwolenie obowiązuje w całej UE. URPL go nie rejestruje, tylko przepisuje do RPL, więc nie ma polskiego numeru pozwolenia, ulotki ani ChPL.
- Tą drogą idą nowe leki i wszystkie biologiczne: Ozempic, Mounjaro, Eliquis, Xarelto, Humira, insuliny.
- 6 952 opakowania (10,8%), prawie wyłącznie Rp i Rpz. To często leki przewlekłe, więc dla trackera ważne.
- **Źródło ChPL:** EMA publikuje jeden PDF po polsku z ChPL (aneks I), etykietą (aneks III A) i ulotką (aneks III B). Sprawdzone na Ozempicu:
  ```
  https://www.ema.europa.eu/pl/documents/product-information/{nazwa}-epar-product-information_pl.pdf
  ```
- Sekcja 4.2 ma ten sam układ co w polskich ChPL, więc parser będzie wspólny.
- Uwagi:
  - EMA szybko zwraca 429, więc pobierać powoli, z przerwami.
  - `{nazwa}` nie zawsze wynika z nazwy produktu. Linki lepiej brać z listy leków, którą EMA udostępnia do pobrania (jeszcze niesprawdzone).

**IR (import równoległy).** Firma (InPharm, Delfarma, Medezin…) kupuje lek w innym kraju UE i przepakowuje go albo okleja na rynek PL. To ten sam lek co polski oryginał.
- 7 640 opakowań (11,9%), zwykłe leki apteczne: 6 179 Rp i 1 290 OTC. Użytkownik może dostać takie pudełko zamiast oryginału.
- Ulotka jest w `ulotkaImportRownolegly` (76% produktów IR).
- Własnej ChPL IR nie ma, bo obowiązuje ChPL polskiego odpowiednika.
- **Źródło ChPL:** połączyć z produktem nie-IR po kluczu `nazwaPowszechnieStosowana` + `moc` (bez spacji) + `nazwaPostaciFarmaceutycznej`. Dopasowanie znajduje się dla **3 703 z 4 346** produktów IR (85%), w 2 237 przypadkach z identyczną nazwą handlową.

| Procedura | Skanowanie (GTIN) | Ulotka | ChPL (dawkowanie) |
|---|---|---|---|
| NAR / DCP / MRP (77%) | działa | z RPL | z RPL |
| IR (12%) | działa | z RPL, 76% | przez polski odpowiednik, 85% |
| CEN (11%) | działa | z PDF-u EMA | z PDF-u EMA |

#### `typProcedury`

| Wartość | Znaczenie |
|---|---|
| `NAR` | procedura narodowa (tylko Polska) |
| `MRP` | procedura wzajemnego uznania (lek zarejestrowany w innym kraju UE, uznany w PL) |
| `DCP` | procedura zdecentralizowana (rejestracja jednocześnie w kilku krajach UE) |
| `CEN` | procedura centralna (decyzja Komisji Europejskiej po opinii EMA, ważna w całej UE) |
| `IR` | import równoległy (lek kupiony w innym kraju UE i przepakowany lub oklejony na rynek PL) |
| `SRP`, `MRPh` | występują tylko poza zakresem (pojedyncze rekordy); nie weryfikowałem ich znaczenia |

#### `podstawaPrawna` (artykuły ustawy Prawo farmaceutyczne)

| Wartość | Liczba | Znaczenie |
|---|---|---|
| `art. 15 ust. 1 pkt 2` | 6184 | odpowiednik leku referencyjnego (generyk) |
| `art. 16 ust. 1` | 1923 | dobrze ugruntowane zastosowanie medyczne (dokumentacja z literatury) |
| `art. 10 ust. 1 i 2` | 1545 | pełna dokumentacja (własne badania) |
| `art. 15 ust. 12` | 975 | ? (nie weryfikowałem) |
| `art. 16 ust. 3` | 384 | ? (nie weryfikowałem) |
| `art. 21`, `art. 20 …` | 267 + 235 | produkty homeopatyczne |
| `art. 20a ust. 1` | 225 | tradycyjne produkty roślinne |
| pozostałe (`art. 17 ust. 3`, `art. 16a …`, `art. 15a …`, `art. 8a …`, `-`) | < 100 każda | ? (nie weryfikowałem) |

Dla trackera leków to pole jest raczej bez znaczenia.

### `kodATC` — klasyfikacja terapeutyczna WHO

| Pole | Znaczenie | Wypełnienie |
|---|---|---|
| tekst elementu | Kod ATC, np. `N02BE01` = paracetamol. Pełny kod ma 7 znaków; krótszy oznacza wyższy poziom klasyfikacji. | produkt ma ≥1 kod: **94,4%** |

- Liczba kodów na produkt: 0 → 1 076, 1 → 17 942, 2+ → 44.
- Długość: 7 znaków w 17 437 przypadkach, a 604 kody są niepełne (3–5 znaków).
- Kodu brakuje głównie w homeopatii, preparatach ziołowych i złożonych.

### `drogaPodania`

| Pole | Znaczenie | Wypełnienie |
|---|---|---|
| `drogaPodaniaNazwa` | Droga podania. | produkt ma ≥1: **99,8%** |

124 wartości, np. doustna (12 545), dożylna, na skórę, podskórna, do oka.

### `substancjaCzynna` — 24 240 wpisów

Ilość substancji czytamy jako: **`iloscSubstancji jednostkaMiaryIlosciSubstancji` / `iloscPreparatu jednostkaMiaryIlosciPreparatu`**. Przykład: `4 mg` / `5 ml` znaczy 4 mg substancji w 5 ml preparatu.

| Pole | Znaczenie | Wypełnienie |
|---|---|---|
| `nazwaSubstancji` | Nazwa substancji (łacina, INN). | 100% |
| `iloscSubstancji` | Ilość substancji, jako **tekst z przecinkiem dziesiętnym** („0,5”). | 84,5% |
| `jednostkaMiaryIlosciSubstancji` | Jednostka: mg, g, mg/ml, mcg, j.m.… (81 wartości). | 84,4% |
| `iloscPreparatu` | W jakiej ilości preparatu jest podana ilość substancji. Puste znaczy „w jednej jednostce dawkowania” (np. w tabletce). | 3,5% |
| `jednostkaMiaryIlosciPreparatu` | ml, g, mg, dawka… | 3,5% |
| `innyOpisIlosci` | Opis tekstem, gdy ilości nie da się zapisać w polach wyżej. | 14,1% |

- Produkt ma ≥1 substancję w **95,5%** przypadków. Brakuje ich głównie w homeopatii, części szczepionek i preparatach złożonych.
- 351 wpisów nie ma żadnej informacji o ilości.
- Przykłady `innyOpisIlosci`: „nie mniej niż 40 j.m.”, „89 mg/g”.

### `opakowanie` — 64 385 opakowań w zakresie

**Jedno opakowanie = jeden GTIN = jedna rzecz, którą skanujemy.**

| Pole | Co tak naprawdę oznacza | Wypełnienie | Uwagi |
|---|---|---|---|
| `id` | Wewnętrzne ID opakowania w RPL. | 100% | unikalne |
| `kodGTIN` | Kod kreskowy opakowania (EAN/GTIN). | 100% (z definicji zakresu) | 64 342 kody mają 14 cyfr, 43 mają 13 cyfr. **Przed porównaniem normalizować:** `zfill(14)`. 92,9% zaczyna się od `059` (prefiks GS1 Polska). **5 kodów występuje dwa razy**, np. `05909991587987` przypisany do Opatanol i Nurofen; to błąd danych, trzeba go obsłużyć. |
| `kategoriaDostepnosci` | Kategoria dostępności (wyjaśnienie niżej). | 99,99% | 2 puste (Trulicity, Elucirem) |
| `skasowane` | `TAK` = opakowanie wykreślone z rejestru. | 100% | w zakresie zawsze `NIE`. Wykreślonych **nie usuwamy z naszej bazy**, bo użytkownicy mogą mieć stare opakowania. |
| `numerEu` | Numer pozwolenia UE dla opakowania, `EU/1/RR/NNNN/PPP`. Ostatni człon wskazuje wariant opakowania. | 10,7% | tylko CEN; jest też wydrukowany na pudełku |
| `dystrybutorRownolegly` | Firma prowadząca dystrybucję równoległą leku CEN (ten sam lek z innego kraju UE). | 0,3% | tylko CEN |

#### `kategoriaDostepnosci`

| Wartość | Liczba | Znaczenie |
|---|---|---|
| `Rp` | 38 190 | na receptę |
| `OTC` | 18 187 | bez recepty |
| `Rpz` | 5 244 | na receptę do zastrzeżonego stosowania (np. przepisywane przez specjalistę lub szpital) |
| `Lz` | 1 703 | tylko lecznictwo zamknięte (szpital); pacjent raczej nie kupi go w aptece |
| `Rpw` | 1 059 | na receptę; zawiera środek odurzający lub substancję psychotropową |
| `bRp`, `LzRp`, `xLz`, `BRAK`… | — | występują tylko poza zakresem |

### `jednostkaOpakowania` — co jest w pudełku

Zawartość czytamy jako: **`liczbaOpakowan` × `rodzajOpakowania` `pojemnosc` `jednostkaPojemnosci` (`informacjeDodatkowe`)**. Przykłady:
- `4 fiol. 5 ml` = 4 fiolki po 5 ml;
- `"" "" 60 tabl.` = 60 tabletek.

| Pole | Znaczenie | Wypełnienie |
|---|---|---|
| `liczbaOpakowan` | Ile pojemników wewnętrznych jest w środku. | 42,8% (puste = nie dotyczy, np. tabletki) |
| `rodzajOpakowania` | Rodzaj pojemnika: fiol., amp., poj., butelka, amp.-strzyk., wstrzykiwacz… | 42,8% |
| `pojemnosc` | Liczba (jako tekst). | 96,6% |
| `jednostkaPojemnosci` | tabl., ml, g, kaps., szt.… | 96,6% |
| `informacjeDodatkowe` | Opis dodatkowy: „w blistrze”, „granulek 9CH”… | 29,5% |

Opakowanie ma zwykle 1 jednostkę (63 294). Jeśli ma więcej, jest to zestaw, np. ampułko-strzykawka + igła.

**Przydatność dla trackera:** `pojemnosc` + `jednostkaPojemnosci` dają liczbę tabletek w opakowaniu, więc można policzyć „ile zostało”.

### `zgodaPrezesa` — obce kody kreskowe

Zgoda Prezesa URPL na obrót opakowaniem z zagranicznym oznakowaniem, np. przy brakach leku.

| Pole | Znaczenie | Wypełnienie |
|---|---|---|
| `nrZgodyPrezesa` | Numer zgody, np. `UR/Z/4c/204/24`. | 448 opakowań w zakresie ma ≥1 zgodę |
| `GTINZagraniczny @numer` | GTIN z zagranicznego pudełka. **Też indeksujemy go do wyszukiwania po skanie.** | |

### `wytworcy` — 28 444 wpisy

| Pole | Znaczenie | Wypełnienie |
|---|---|---|
| `nazwaWytworcyImportera` | Wytwórca, który zwalnia serie leku. | 84,7% (**brak dla całego IR**) |
| `krajWytworcyImportera` | Kraj wytwórcy. | 84,7% |
| `krajEksportu` | (IR) kraj, z którego importowano lek. | 15,3% (tylko IR) |
| `podmiotOdpowiedzialnywKrajuEksportu` | (IR) właściciel pozwolenia w kraju eksportu. | 15,3% (tylko IR) |

### `materialEdukacyjny`

Dodatkowe materiały, które firma musi przygotować dla leków z podwyższonym ryzykiem.

| Pole | Znaczenie | Wypełnienie |
|---|---|---|
| `nazwaMaterialu` | Rodzaj materiału: „Karta pacjenta”, „Formularz świadomości ryzyka”… | produkt ma materiał **dla pacjenta: 4,9%**, dla medyka: 4,3% |
| `material` | Link do pliku. | 100% wpisów |

**Przydatność dla trackera:** „Karta pacjenta” (184 wpisy) to coś, co warto pokazać użytkownikowi.

### `gatunek`, `okresKarencji`

Dotyczą tylko leków weterynaryjnych: gatunki docelowe i czas karencji dla tkanek (`nazwaTkanki`, `wartoscMiary`, `jednostkaMiary`). W naszym zakresie są puste w 100%.

### Tylko w CSV/XLSX: „Komunikaty bezpieczeństwa”

Linki do komunikatów bezpieczeństwa, np. listów do lekarzy o nowych ryzykach. Ma je **565 z 19 062** produktów (3,0%). W API JSON odpowiada im flaga `hasSecurityMessages`.

---

## API JSON wyszukiwarki (nieoficjalne)

`GET /api/rpl/medicinal-products/search/public?{parametr}=…&page=0&size=10`

**Działające parametry:** `eanGtin`, `name`, `commonName`, `atcCode`, `page`, `size`.

**Pułapka:** nieznany parametr jest **po cichu ignorowany** i wtedy dostajemy całą bazę (`totalElements = 22947`). Zawsze sprawdzać `totalElements`.

Odpowiedź ma format Spring Page: `content[]`, `totalElements`, `totalPages`, `number`, `size`… Pola elementu `content[]`:

| Pole JSON | Odpowiednik w XML / znaczenie |
|---|---|
| `id` | wewnętrzne ID (to z URL-i), **nie** `id` z XML |
| `specimenType` | `rodzajPreparatu` („Ludzki”) |
| `vetClassification` | czy preparat weterynaryjny |
| `hasDownloadableMaterials` | czy są materiały edukacyjne |
| `hasSecurityMessages` | czy są komunikaty bezpieczeństwa |
| `medicinalProductName` | `nazwaProduktu` |
| `commonName` | `nazwaPowszechnieStosowana` |
| `pharmaceuticalFormName` | `nazwaPostaciFarmaceutycznej` |
| `medicinalProductPower` | `moc` |
| `activeSubstanceName` | substancje czynne (tekst) |
| `subjectMedicinalProductName` | `podmiotOdpowiedzialny` |
| `registryNumber` | `numerPozwolenia` |
| `procedureTypeName` | `typProcedury` |
| `expirationDateString` | `waznoscPozwolenia` |
| `atcCode` | `kodATC` |
| `targetSpecies` | gatunki (weterynaryjne) |

Szczegóły produktu (`/medicinal-products/{id}`) zwracają **403**. Danych opakowań nie da się więc pobrać przez JSON i trzeba je brać z XML.

## Czego w RPL nie ma

- dawkowania, interakcji, przeciwwskazań w formie danych (są tylko w PDF ChPL, sekcje 4.2–4.5);
- cen i refundacji (są w obwieszczeniu refundacyjnym MZ, w XLSX);
- informacji, czy lek jest faktycznie dostępny w aptekach, oraz o wycofaniach serii (te publikuje GIF);
- zdjęć opakowań;
- suplementów diety i wyrobów medycznych;
- ulotek i ChPL dla leków CEN (są na ema.europa.eu).

## Pułapki — podsumowanie

1. Normalizować GTIN do 14 cyfr (`zfill(14)`).
2. Kilka kodów GTIN jest zduplikowanych między różnymi produktami; obsłużyć wiele wyników dla jednego kodu.
3. `"brak"` i `"-"` traktować jak null.
4. Liczby są tekstem z przecinkiem dziesiętnym.
5. Nie filtrować po `waznoscPozwolenia`; daty bywają nieaktualne.
6. CEN: nie ma numeru pozwolenia, ulotki ani ChPL; identyfikatorem jest `numerEu` w opakowaniu.
7. Każdy ekran z danymi z RPL musi pokazywać źródło i datę `stanNaDzien` (warunek licencji).
