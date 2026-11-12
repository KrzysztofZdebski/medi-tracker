# Aplikacja Android

Aplikacja w Kotlinie i Jetpack Compose, z interfejsem Material 3.

- **Skaner** (`feature/scanner`): z ekranu dodawania leku otwiera skaner z Google Play Services (`play-services-code-scanner`, EAN-13/EAN-8/UPC-A i GS1 DataMatrix). Aplikacja nie potrzebuje uprawnienia `CAMERA`, ale telefon musi mieć Google Play Services. Z DataMatrix [`Gs1.kt`](app/src/main/kotlin/pl/agh/meditracker/feature/scanner/Gs1.kt) odczytuje też datę ważności, serię i numer seryjny. Po anulowaniu skanu albo nierozpoznanym kodzie otwiera się ekran ręcznego wpisania EAN. Obsługuje rozpoznawanie, brak wyniku i niedostępny katalog. Katalog synchronizuje się przy uruchomieniu; kod można wyszukać offline po zakończeniu pierwszej synchronizacji.
- **Szczegóły leku:** po dotknięciu karty w zakładce Leki. Uzupełnianie zapasu, edycja danych i harmonogramu oraz usuwanie leku z potwierdzeniem. Zmiana harmonogramu obejmuje przyszłe dawki.
- **Historia:** dziennik przyjęć i pominięć, grupowany datami, z filtrami leku, statusu i okresu. Wpis zachowuje nazwę, dawkę, godzinę potwierdzenia i zapas z chwili zdarzenia, także po zmianie lub usunięciu leku.
- **Przypomnienia** (`reminder/`): alarmy na dziś i jutro (`AlarmManager.setExactAndAllowWhileIdle`) przeliczane po każdej zmianie leków lub dawek, po przełączeniu w Ustawieniach, o północy oraz po restarcie telefonu i zmianie czasu. Powiadomienie ma przyciski „Przyjęte” (zdejmuje z zapasu, ukryty przy braku zapasu) i „Odłóż” (o domyślny czas z Ustawień). Bez zgody na dokładne alarmy Android może opóźnić przypomnienie o kilka minut.
- **Ustawienia:** powiadomienia (zgoda systemowa, ostrzeżenie o niedokładnych alarmach, powiadomienie testowe), domyślne odroczenie (10/15/30/60 minut), dźwięk i wibracje w kanale Androida, motyw systemowy/jasny/ciemny i informacje o aplikacji z ręczną aktualizacją katalogu. Konto (opcjonalne): rejestracja z kodem z e-maila, logowanie, kod odzyskiwania, zmiana i reset hasła, usunięcie konta. Szyfrowana kopia zapasowa jest wysyłana automatycznie kilka sekund po zmianie danych. Po zalogowaniu, gdy w telefonie są dane, aplikacja pyta: „Przywróć dane z konta” albo „Zachowaj dane z tego telefonu”. Konto może być aktywne tylko na jednym telefonie naraz; logowanie na innym telefonie przejmuje konto, a poprzedni dostaje komunikat o przejęciu. Wylogowanie usuwa dane z telefonu, a kopia zostaje na koncie. Bez konta aplikacja działa jak dotąd. Android Auto Backup jest wyłączony: kopią jest tylko szyfrowana kopia konta. Dzwonek na każdej głównej zakładce otwiera wspólny ekran powiadomień.
- **Katalog leków**: pobierany z serwera mediTracker (`{SERVER_URL}/catalog`), a nie z GitHub Release. Serwer buduje go codziennie z danych RPL.

Leki, wersje harmonogramów, dziennik dawek i historia są zapisywane w Room, w osobnej bazie `user.db` (`data/user/`), niezależnej od katalogu leków. `MedicationRepository` trzyma stan w pamięci dla UI i każdą zmianę zapisuje do bazy po kolei poza wątkiem UI. Zmiana statusu dawki (dziennik, historia, zapas) idzie w jednej transakcji. Przy starcie aplikacji, także gdy proces budzi alarm, stan jest wczytywany z bazy, więc przypomnienia działają po zamknięciu aplikacji i restarcie telefonu. Usunięty lek zostaje w bazie z datą `archived_at`, bo odwołuje się do niego historia. Schemat bazy jest eksportowany do `app/schemas/` (trzymamy go w repo na potrzeby przyszłych migracji). Preferencje są zapisane w SharedPreferences.

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

Testy obejmują m.in. zachowanie historii po edycji/usunięciu leku, trwałość danych, cofanie przyjęcia, filtrowanie, edycję przyszłych dawek, dostarczanie/anulowanie przypomnień i odroczenie przez północ. Testy ekranów w Robolectric sprawdzają nawigację dzwonka na każdej zakładce, ustawienia, motyw, walidację, uzupełnianie zapasu i zapis zmian. Wyrenderowane obrazy trafiają do `app/build/reports/design-screens/`, a raport testów do `app/build/reports/tests/testDebugUnitTest/`.

Odczyt prawdziwego opakowania należy dodatkowo sprawdzić na telefonie z Google Play Services.

Telefon musi mieć włączone debugowanie USB (Opcje programisty → Debugowanie USB). Sprawdzisz to komendą `adb devices`.

Gotowe kody do testu:
- `5909990296026`: Apap 500 mg
- `05909991389901`: Ozempic 0,25 mg
