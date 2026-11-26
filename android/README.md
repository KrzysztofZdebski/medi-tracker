# Aplikacja Android

Aplikacja w Kotlinie i Jetpack Compose, z interfejsem Material 3.

- **Skaner** (`feature/scanner`): z ekranu dodawania leku otwiera skaner z Google Play Services (`play-services-code-scanner`, EAN-13/EAN-8/UPC-A i GS1 DataMatrix). Aplikacja nie potrzebuje uprawnienia `CAMERA`, ale telefon musi mieć Google Play Services. Z DataMatrix [`Gs1.kt`](app/src/main/kotlin/pl/agh/meditracker/feature/scanner/Gs1.kt) odczytuje też datę ważności, serię i numer seryjny. Po anulowaniu skanu albo nierozpoznanym kodzie otwiera się ekran ręcznego wpisania EAN. Obsługuje rozpoznawanie, brak wyniku i niedostępny katalog. Katalog synchronizuje się przy uruchomieniu; kod można wyszukać offline po zakończeniu pierwszej synchronizacji.
- **Leki** (`feature/medications`): lista „Moje leki”. Przesunięcie karty w lewo odsłania czerwone tło „Usuń” i od razu usuwa lek, a snackbar „Usunięto {nazwa}” ma „Cofnij”, które przywraca lek w całości (to samo id, wersje harmonogramu, dziennik dawek, zapas i miejsce na liście). Czytnik ekranu ma na karcie akcję „Usuń” zamiast gestu. Długie przytrzymanie karty włącza zaznaczanie: pasek „Zaznaczono: N” z zamknięciem, „Zaznacz wszystkie” i usuwaniem zaznaczonych po potwierdzeniu w panelu („Usunąć N leków?”), także z „Cofnij” dla wszystkich naraz; Wstecz zamyka tryb zaznaczania. Usuwanie i przywracanie kart jest animowane (`Modifier.animateItem()`).
- **Szczegóły leku:** po dotknięciu karty w zakładce Leki. Uzupełnianie zapasu, edycja danych i harmonogramu oraz usuwanie leku z potwierdzeniem. Po usunięciu aplikacja wraca na listę leków (także gdy szczegóły otwarto z Dzisiaj) i pokazuje tam ten sam snackbar z „Cofnij”. Zmiana harmonogramu obejmuje przyszłe dawki.
- **Historia:** dziennik przyjęć i pominięć, grupowany datami, z filtrami leku, statusu i okresu. Wpis zachowuje nazwę, dawkę, godzinę potwierdzenia i zapas z chwili zdarzenia, także po zmianie lub usunięciu leku.
- **Przypomnienia** (`reminder/`): alarmy na dziś i jutro (`AlarmManager.setExactAndAllowWhileIdle`) przeliczane po każdej zmianie leków lub dawek, po przełączeniu w Ustawieniach, o północy oraz po restarcie telefonu i zmianie czasu. Powiadomienie ma przyciski „Przyjęte” (zdejmuje z zapasu, ukryty przy braku zapasu) i „Odłóż” (o domyślny czas z Ustawień). Bez zgody na dokładne alarmy Android może opóźnić przypomnienie o kilka minut.
- **Widżet „Dzisiejsze dawki”** (`widget/`): na ekranie głównym postęp dnia („1 z 3 przyjęte”) i najbliższa dawka z przyciskiem „Przyjęte”, który działa jak ten z powiadomienia; gdy na dziś nic nie zostało, pokazuje pierwszą dawkę jutro. Odświeża się po każdej zmianie leków i dawek oraz o północy; dotknięcie otwiera aplikację. Zwykłe RemoteViews, bez dodatkowych bibliotek; kolory jasne i ciemne według motywu systemu.
- **Kalendarz** (`calendar/`): po włączeniu w Ustawieniach → Kalendarz każda godzina dawki trafia do kalendarza telefonu jako wydarzenie cykliczne (`RRULE` DAILY albo WEEKLY z wybranymi dniami, od daty rozpoczęcia do daty zakończenia harmonogramu, 15 minut, strefa telefonu, bez alarmów kalendarza, bo przypomina aplikacja). Domyślnie do osobnego lokalnego kalendarza „mediTracker” tworzonego przez aplikację; można wybrać własny kalendarz z prawem zapisu. Zgoda `READ_CALENDAR`/`WRITE_CALENDAR` jest potrzebna dopiero po włączeniu; po odmowie ekran prowadzi do ustawień Androida, a po cofnięciu zgody eksport wyłącza się sam. W szczegółach leku przełącznik „Pokazuj w kalendarzu” ukrywa jego dawki. `CalendarSync` obserwuje repozytorium i uzgadnia wydarzenia z zapisanym mapowaniem (lek + godzina → id wydarzenia i skrót treści), więc zmienia tylko to, co się zmieniło. Zmiana harmonogramu kończy starą serię w chwili zapisu (przeszłe wystąpienia zostają) i zaczyna nową od najbliższej dawki; usunięcie lub ukrycie leku usuwa jego wydarzenia. Wyłączenie eksportu albo „Usuń wydarzenia z kalendarza” usuwa wszystko, co dodała aplikacja, razem z kalendarzem „mediTracker”.
- **Ustawienia:** powiadomienia (zgoda systemowa, ostrzeżenie o niedokładnych alarmach, powiadomienie testowe), domyślne odroczenie (10/15/30/60 minut), dźwięk i wibracje w kanale Androida, motyw systemowy/jasny/ciemny i informacje o aplikacji z ręczną aktualizacją katalogu. Konto (opcjonalne): rejestracja z kodem z e-maila, logowanie, kod odzyskiwania, zmiana i reset hasła, usunięcie konta. Szyfrowana kopia zapasowa jest wysyłana automatycznie kilka sekund po zmianie danych. Po zalogowaniu, gdy w telefonie są dane, aplikacja pyta: „Przywróć dane z konta” albo „Zachowaj dane z tego telefonu”. Konto może być aktywne tylko na jednym telefonie naraz; logowanie na innym telefonie przejmuje konto, a poprzedni dostaje komunikat o przejęciu. Wylogowanie usuwa dane z telefonu, a kopia zostaje na koncie. Bez konta aplikacja działa jak dotąd. Android Auto Backup jest wyłączony: kopią jest tylko szyfrowana kopia konta. Dzwonek na każdej głównej zakładce otwiera wspólny ekran powiadomień.
- **Animacje** (`ui/theme/Motion.kt`): krótkie (100–300 ms) przejścia Material 3: zakładki przenikają, ekrany szczegółów, ustawień, konta i dodawania leku wjeżdżają poziomo (powrót w drugą stronę), kroki kreatora przesuwają się zgodnie z kierunkiem, a pasek kroku wypełnia się płynnie. Na Dzisiaj status dawki zmienia kolor, a znaczek „Przyjęto” lekko się powiększa; karta najbliższej dawki przenika, a listy Dzisiaj i Historii animują dodanie i przesunięcie elementów. Alerty rozwijają się, arkusze dopasowują wysokość, paski zapasu dojeżdżają do nowej wartości. Bez animacji w pętli. Przy włączonym w Androidzie „Usuń animacje” (skala animacji 0) wszystkie przejścia są natychmiastowe (`LocalReducedMotion`).
- **Dostępność:** tytuły ekranów i sekcji są nagłówkami TalkBack, przyciski z samą ikoną mają polskie opisy, a ikony ozdobne są pomijane. Karty dawek, leków, wiersze ustawień i wpisy historii czyta się jako jeden element z rolą przycisku i opisem akcji; karta dawki podaje stan („przyjęta”, „pominięta”, „odroczona”, „najbliższa dawka”) i ma akcje „Przyjmij” oraz „Pomiń dawkę”. Dni tygodnia są polami wyboru z pełną nazwą dnia, opcje odroczenia mają stan zaznaczenia, a pasek zapasu podaje postęp. Niski i pusty zapas oraz ton komunikatu („Ostrzeżenie”, „Błąd”) są też podane słowami, nie tylko kolorem. Komunikaty (`AppAlert`) i błędy walidacji są regionami na żywo (błędy natychmiast), a pola formularzy mają semantykę błędu. Przyciski i pola mają minimalną zamiast stałej wysokości, więc tekst nie jest ucinany przy czcionce 200%, a obszary dotyku mają co najmniej 48 dp. Przycisk „Przyjęte” w widżecie mówi, której dawki dotyczy. Testy: `AccessibilityTest.kt`.
- **Katalog leków**: pobierany z serwera mediTracker (`{SERVER_URL}/catalog`), a nie z GitHub Release. Serwer buduje go codziennie z danych RPL.

Leki, wersje harmonogramów, dziennik dawek i historia są zapisywane w Room, w osobnej bazie `user.db` (`data/user/`), niezależnej od katalogu leków. `MedicationRepository` trzyma stan w pamięci dla UI i każdą zmianę zapisuje do bazy po kolei poza wątkiem UI. Zmiana statusu dawki (dziennik, historia, zapas) idzie w jednej transakcji. Przy starcie aplikacji, także gdy proces budzi alarm, stan jest wczytywany z bazy, więc przypomnienia działają po zamknięciu aplikacji i restarcie telefonu. Usunięty lek zostaje w bazie z datą `archived_at`, bo odwołuje się do niego historia; „Cofnij” czyści `archived_at`, a w pamięci repozytorium przywraca lek z chwili usunięcia (`removeAll`/`restoreAll`: jedna zmiana stanu i jeden zapis na operację, więc kopia zapasowa, przypomnienia i widżet widzą też przywrócenie). Przypomnienia usuniętego leku są anulowane, a jego wyświetlone powiadomienie znika. Schemat bazy jest eksportowany do `app/schemas/` (trzymamy go w repo na potrzeby przyszłych migracji). Preferencje są zapisane w SharedPreferences.

Względem pełnego projektu z [`docs/baza-danych.md`](../docs/baza-danych.md) to na razie podzbiór: zapas jest licznikiem w `medication`, a nie dziennikiem ruchów, i nie ma jeszcze profili, pudełek (`stock_batch`) ani recept. Katalog leków jest przechowywany lokalnie w SQLite.

Opis przejrzanych akcji i endpointów: [przegląd akcji](../docs/przeglad-akcji.md).

## Wymagania

- JDK 17+ na `PATH`
- Android SDK z `platforms/android-35` i `build-tools/35.0.0`
- plik `android/local.properties` z linią `sdk.dir=C:/Users/<ty>/AppData/Local/Android/Sdk` (nie jest w repo)

## Budowanie i instalacja (PowerShell)

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n pl.agh.meditracker/.MainActivity
```

Domyślny adres serwera to `https://kryzys-server.tail9dde62.ts.net:10000`. Inny serwer ustawia się właściwością Gradle `serverUrl` (bez końcowego ukośnika). Serwer uruchomiony na komputerze jest z emulatora pod adresem `10.0.2.2`:

```powershell
.\gradlew.bat assembleDebug -PserverUrl=http://10.0.2.2:8765
```

Ruch HTTP bez TLS jest dozwolony tylko w buildzie debug i tylko do `10.0.2.2` oraz `192.168.0.75`. W buildzie release ruch HTTP nie jest dozwolony.

## Weryfikacja

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Testy obejmują m.in. zachowanie historii po edycji/usunięciu leku, cofnięcie usunięcia (ten sam stan w pamięci i w bazie, kolejność listy, przywrócone przypomnienia), trwałość danych, cofanie przyjęcia, filtrowanie, edycję przyszłych dawek, dostarczanie/anulowanie przypomnień odroczenie przez północ i uzgadnianie wydarzeń kalendarza (na atrapie dostawcy kalendarza). Testy ekranów w Robolectric sprawdzają nawigację dzwonka na każdej zakładce, przesunięcie i akcję „Usuń” na liście leków, tryb zaznaczania, snackbar „Cofnij” (także po usunięciu w szczegółach), ustawienia, motyw, walidację, uzupełnianie zapasu i zapis zmian. Wyrenderowane obrazy trafiają do `app/build/reports/design-screens/`, a raport testów do `app/build/reports/tests/testDebugUnitTest/`.

Odczyt prawdziwego opakowania należy dodatkowo sprawdzić na telefonie z Google Play Services.

Telefon musi mieć włączone debugowanie USB (Opcje programisty → Debugowanie USB). Sprawdzisz to komendą `adb devices`.

Gotowe kody do testu:
- `5909990296026`: Apap 500 mg
- `05909991389901`: Ozempic 0,25 mg
