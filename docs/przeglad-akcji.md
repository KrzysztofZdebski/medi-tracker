# Przegląd akcji aplikacji — 6 listopada 2026

W aplikacji nie ma własnego serwera HTTP. Większość „endpointów” interfejsu to akcje Kotlin/Compose oraz trasy Navigation. Jedynymi pobieranymi zasobami są metadane i baza katalogu leków. Funkcje konta, logowania, hasła i wylogowania zostały pominięte zgodnie z zakresem.

## Naprawione braki

| Akcja | Wcześniej | Obecnie |
|---|---|---|
| Zakładka Ustawienia | Placeholder | Ekran z projektu, bez sekcji konta |
| Dzwonek: Dzisiaj, Leki, Historia | Pusta funkcja | Ekran powiadomień; dzwonek ustawień prowadzi do tego samego miejsca |
| Powiadomienia | Wyłącznie flaga preferencji | Zgoda systemowa, rzeczywisty stan kanału, włączenie/wyłączenie przypomnień, otwieranie ustawień Androida, testowe powiadomienie z potwierdzeniem |
| Przypomnij później w ustawieniach | Brak | Wybór domyślnego czasu, zapis albo anulowanie; używany przy odraczaniu dawek |
| Dźwięk i wibracje | Brak | Ustawienia właściwego kanału powiadomień Androida; aktualny stan po powrocie |
| Motyw | Brak | Systemowy, jasny, ciemny; natychmiastowa zmiana i trwały zapis |
| O aplikacji | Brak | Faktyczna wersja, źródło i data danych, link do RPL, aktualizacja katalogu ze stanem ładowania i wynikiem |
| Karta/status przyjętej lub pominiętej dawki | Kliknięcie ignorowane | Szczegóły danego leku |
| Status odroczonej dawki | Pasek wyglądał jak przycisk, nie miał akcji | Otwiera potwierdzenie dawki; odroczenie zwraca komunikat i nie zmienia zapasu |
| Potwierdzenie dawki → odroczenie / brak zapasu → uzupełnienie | Każdy krok tworzył osobne okno | Jeden panel zmienia treść, zachowując stan okna; przejścia są sprawdzane przez testy nawigacji |
| Stopka przypomnień | Zawsze „włączone” | Zgodna z preferencją i zgodą Androida |
| Alert niskiego zapasu | „Przypomnij później” tylko zamykał panel | Uczciwa etykieta „Zamknij”; uzupełnianie zapasu pozostaje osobną akcją |
| Ponowna próba wyszukania bez katalogu | Ponownie ten sam błąd | Najpierw pobranie katalogu; brak wyniku odróżniony od błędu pobierania |
| Aktualizacja katalogu | HTTP i błędny hash mogły zakończyć się cicho | Błąd trafia do interfejsu; zachowany stary katalog, weryfikacja SHA-256 i wielkości |

## Pozostałe przejrzane akcje

Dodawanie leku (skan i EAN, dodanie ręczne, znaleziony produkt, edycja danych, dawkowanie, wybór dni i godzin, daty, zapas, zapis i odrzucenie), edycja leku i harmonogramu, uzupełnienie zapasu, usuwanie z potwierdzeniem, przyjęcie/pominięcie/cofnięcie dawki, filtry historii i dolne zakładki mają podłączone operacje lub nawigację. Anulowanie skanera Google Play Services otwiera wpisywanie EAN.

Puste funkcje pozostały wyłącznie w podglądach `@Preview` i jako settery pól tylko do odczytu, których wybór obsługuje odrębne `onClick`. Wymagane akcje kart leków/dawek i dzwonka nie mają już domyślnych pustych callbacków.

## Adresy zewnętrzne

Sprawdzone żądaniem HEAD z przekierowaniami 6 listopada 2026; wszystkie zwróciły HTTP 200:

- `https://github.com/KrzysztofZdebski/medi-tracker/releases/download/katalog/version.json`
- `https://github.com/KrzysztofZdebski/medi-tracker/releases/download/katalog/leki.sqlite.gz`
- `https://rejestry.ezdrowie.gov.pl/rpl/search/public`

## Weryfikacja i granice

Testy JVM/Robolectric obejmują akcje interfejsu i nawigację między zakładkami, zapis i odtwarzanie preferencji/danych, planowanie, dostarczenie i anulowanie powiadomień oraz odroczenie przez północ. Obrazy nowych ekranów są renderowane do `android/app/build/reports/design-screens`.

Faktyczne opóźnienia dostarczenia powiadomień, ograniczenia oszczędzania baterii Xiaomi oraz zachowanie ustawień Androida wymagają końcowej próby na telefonie. Bez zgody na dokładne alarmy Android może opóźnić przypomnienie o kilka minut.
