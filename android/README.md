# Aplikacja Android

Aplikacja w Kotlinie i Jetpack Compose, z interfejsem Material 3.

- **Skaner** (`feature/scanner`): z ekranu dodawania leku otwiera skaner z Google Play Services (`play-services-code-scanner`, EAN-13/EAN-8/UPC-A i GS1 DataMatrix). Aplikacja nie potrzebuje uprawnienia `CAMERA`, ale telefon musi mieć Google Play Services. Z DataMatrix [`Gs1.kt`](app/src/main/kotlin/pl/agh/meditracker/feature/scanner/Gs1.kt) odczytuje też datę ważności, serię i numer seryjny. Po anulowaniu skanu albo nierozpoznanym kodzie otwiera się ekran ręcznego wpisania EAN. Obsługuje rozpoznawanie, brak wyniku i niedostępny katalog. Katalog synchronizuje się przy uruchomieniu; kod można wyszukać offline po zakończeniu pierwszej synchronizacji.
- **Szczegóły leku:** po dotknięciu karty w zakładce Leki. Uzupełnianie zapasu, edycja danych i harmonogramu oraz usuwanie leku z potwierdzeniem. Zmiana harmonogramu obejmuje przyszłe dawki.
- **Historia:** dziennik przyjęć i pominięć, grupowany datami, z filtrami leku, statusu i okresu. Wpis zachowuje nazwę, dawkę, godzinę potwierdzenia i zapas z chwili zdarzenia, także po zmianie lub usunięciu leku.
- **Przypomnienia** (`reminder/`): alarmy na dziś i jutro (`AlarmManager.setExactAndAllowWhileIdle`) przeliczane po każdej zmianie leków lub dawek, po przełączeniu w Ustawieniach, o północy oraz po restarcie telefonu i zmianie czasu. Powiadomienie ma przyciski „Przyjęte” (zdejmuje z zapasu, ukryty przy braku zapasu) i „Odłóż 15 min”. Bez zgody na dokładne alarmy Android może opóźnić przypomnienie o kilka minut.
- **Ustawienia:** włączanie przypomnień (z prośbą o zgodę na powiadomienia), ostrzeżenie o zablokowanych powiadomieniach lub niedokładnych alarmach z przejściem do ustawień Androida, stan katalogu z ręcznym sprawdzeniem aktualizacji, informacja o prywatności i źródle danych RPL (CC BY 4.0). Dzwonek w górnym pasku otwiera Ustawienia.

Leki, wersje harmonogramów, dziennik dawek i historia są zapisywane w Room, w osobnej bazie `user.db` (`data/user/`), niezależnej od katalogu leków. `MedicationRepository` trzyma stan w pamięci dla UI i każdą zmianę zapisuje do bazy po kolei poza wątkiem UI. Zmiana statusu dawki (dziennik, historia, zapas) idzie w jednej transakcji. Przy starcie aplikacji, także gdy proces budzi alarm, stan jest wczytywany z bazy, więc przypomnienia działają po zamknięciu aplikacji i restarcie telefonu. Usunięty lek zostaje w bazie z datą `archived_at`, bo odwołuje się do niego historia. Schemat bazy jest eksportowany do `app/schemas/` (trzymamy go w repo na potrzeby przyszłych migracji).

Względem pełnego projektu z [`docs/baza-danych.md`](../docs/baza-danych.md) to na razie podzbiór: zapas jest licznikiem w `medication`, a nie dziennikiem ruchów, i nie ma jeszcze profili, pudełek (`stock_batch`) ani recept. Katalog leków jest przechowywany lokalnie w SQLite.

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

## Weryfikacja

```powershell
cd android
.\gradlew.bat testDebugUnitTest assembleDebug lintDebug
```

Testy obejmują m.in. zachowanie historii po edycji/usunięciu leku, cofanie przyjęcia, filtrowanie i edycję przyszłych dawek. Testy ekranów w Robolectric sprawdzają nawigację, walidację, uzupełnianie zapasu i zapis zmian. Wyrenderowane obrazy trafiają do `app/build/reports/design-screens/`, a raport testów do `app/build/reports/tests/testDebugUnitTest/`.

Odczyt prawdziwego opakowania należy dodatkowo sprawdzić na telefonie z Google Play Services.

Telefon musi mieć włączone debugowanie USB (Opcje programisty → Debugowanie USB). Sprawdzisz to komendą `adb devices`.

Gotowe kody do testu:
- `5909990296026`: Apap 500 mg
- `05909991389901`: Ozempic 0,25 mg
