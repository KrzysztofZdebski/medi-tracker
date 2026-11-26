# Przegląd akcji aplikacji — 6 listopada 2026

Aplikacja łączy się z serwerem mediTracker (`BuildConfig.SERVER_URL`): API konta i kopii zapasowej oraz pliki katalogu leków (`/catalog/version.json`, `/catalog/leki.sqlite.gz`). Endpointy i ich zachowanie opisuje [server/README.md](../server/README.md). Konto jest opcjonalne. Dostępne są: rejestracja z kodem wysłanym e-mailem, logowanie, kod odzyskiwania, zmiana hasła, reset hasła (kodem z e-maila i kodem odzyskiwania), usunięcie konta oraz wylogowanie, które usuwa dane z telefonu (kopia zostaje na koncie). Większość „endpointów” interfejsu to nadal akcje Kotlin/Compose oraz trasy Navigation.

## Naprawione braki

| Akcja | Wcześniej | Obecnie |
|---|---|---|
| Zakładka Ustawienia | Placeholder | Ekran z projektu, bez sekcji konta |
| Dzwonek: Dzisiaj, Leki, Historia | Pusta funkcja | Ekran powiadomień; dzwonek ustawień prowadzi do tego samego miejsca |
| Powiadomienia | Wyłącznie flaga preferencji | Zgoda systemowa, rzeczywisty stan kanału, włączenie/wyłączenie przypomnień, otwieranie ustawień Androida, testowe powiadomienie z potwierdzeniem |
| Przypomnij później w ustawieniach | Brak | Wybór domyślnego czasu, zapis albo anulowanie; używany przy odraczaniu dawek |
| Dźwięk i wibracje | Brak | Ustawienia właściwego kanału powiadomień Androida; aktualny stan po powrocie |
| Kalendarz | Brak | Eksport dawek do kalendarza telefonu: zgoda systemowa dopiero po włączeniu (odmowa → link do ustawień Androida), własny kalendarz „mediTracker” albo wybrany kalendarz użytkownika, przełącznik „Pokazuj w kalendarzu” w szczegółach leku, usuwanie wszystkich dodanych wydarzeń |
| Motyw | Brak | Systemowy, jasny, ciemny; natychmiastowa zmiana i trwały zapis |
| O aplikacji | Brak | Faktyczna wersja, źródło i data danych, link do RPL, aktualizacja katalogu ze stanem ładowania i wynikiem |
| Karta/status przyjętej lub pominiętej dawki | Kliknięcie ignorowane | Szczegóły danego leku |
| Status odroczonej dawki | Pasek wyglądał jak przycisk, nie miał akcji | Otwiera potwierdzenie dawki; odroczenie zwraca komunikat i nie zmienia zapasu |
| Potwierdzenie dawki → odroczenie / brak zapasu → uzupełnienie | Każdy krok tworzył osobne okno | Jeden panel zmienia treść, zachowując stan okna; przejścia są sprawdzane przez testy nawigacji |
| Stopka przypomnień | Zawsze „włączone” | Zgodna z preferencją i zgodą Androida |
| Alert niskiego zapasu | „Przypomnij później” tylko zamykał panel | Uczciwa etykieta „Zamknij”; uzupełnianie zapasu pozostaje osobną akcją |
| Ponowna próba wyszukania bez katalogu | Ponownie ten sam błąd | Najpierw pobranie katalogu; brak wyniku odróżniony od błędu pobierania |
| Aktualizacja katalogu | HTTP i błędny hash mogły zakończyć się cicho | Błąd trafia do interfejsu; zachowany stary katalog, weryfikacja SHA-256 i wielkości |
| Usuwanie leku na liście | Tylko w szczegółach, bez cofnięcia | Przesunięcie karty w lewo (albo akcja „Usuń” czytnika ekranu) usuwa od razu; snackbar „Usunięto {nazwa}” z „Cofnij” przywraca lek w całości |
| Usuwanie kilku leków | Brak | Długie przytrzymanie włącza zaznaczanie („Zaznaczono: N”, „Zaznacz wszystkie”, zamknięcie lub Wstecz); usunięcie po potwierdzeniu „Usunąć N leków?” i z jednym „Cofnij” dla wszystkich |
| „Usuń lek” w szczegółach | Powrót do poprzedniego ekranu bez możliwości cofnięcia | Powrót na listę leków (także ze szczegółów otwartych z Dzisiaj) ze snackbarem „Cofnij”; id usuniętego leku przechodzi przez `savedStateHandle` listy |

## Pozostałe przejrzane akcje

Dodawanie leku (skan i EAN, dodanie ręczne, znaleziony produkt, edycja danych, dawkowanie, wybór dni i godzin, daty, zapas, zapis i odrzucenie), edycja leku i harmonogramu, uzupełnienie zapasu, usuwanie (w szczegółach z potwierdzeniem, na liście przesunięciem lub zaznaczeniem, z „Cofnij”), przyjęcie/pominięcie/cofnięcie dawki, filtry historii i dolne zakładki mają podłączone operacje lub nawigację. Anulowanie skanera Google Play Services otwiera wpisywanie EAN.

Puste funkcje pozostały wyłącznie w podglądach `@Preview` i jako settery pól tylko do odczytu, których wybór obsługuje odrębne `onClick`. Wymagane akcje kart leków/dawek i dzwonka nie mają już domyślnych pustych callbacków.

## Adresy zewnętrzne

Sprawdzone żądaniem HEAD z przekierowaniami 6 listopada 2026; wszystkie zwróciły HTTP 200:

- `https://github.com/KrzysztofZdebski/medi-tracker/releases/download/katalog/version.json`
- `https://github.com/KrzysztofZdebski/medi-tracker/releases/download/katalog/leki.sqlite.gz`
- `https://rejestry.ezdrowie.gov.pl/rpl/search/public`

## Weryfikacja i granice

Testy JVM/Robolectric obejmują akcje interfejsu i nawigację między zakładkami, zapis i odtwarzanie preferencji/danych, planowanie, dostarczenie i anulowanie powiadomień oraz odroczenie przez północ. Obrazy nowych ekranów są renderowane do `android/app/build/reports/design-screens`.

Faktyczne opóźnienia dostarczenia powiadomień, ograniczenia oszczędzania baterii Xiaomi oraz zachowanie ustawień Androida wymagają końcowej próby na telefonie. Bez zgody na dokładne alarmy Android może opóźnić przypomnienie o kilka minut.
