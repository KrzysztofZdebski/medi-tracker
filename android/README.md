# Aplikacja Android

Aplikacja w Kotlinie i Jetpack Compose, z interfejsem Material 3.

- **Skaner** (`feature/scanner`): z ekranu dodawania leku otwiera skaner z Google Play Services (`play-services-code-scanner`, EAN-13/EAN-8/UPC-A i GS1 DataMatrix). Aplikacja nie potrzebuje uprawnienia `CAMERA`, ale telefon musi mieć Google Play Services. Z DataMatrix [`Gs1.kt`](app/src/main/kotlin/pl/agh/meditracker/feature/scanner/Gs1.kt) odczytuje też datę ważności, serię i numer seryjny. Po anulowaniu skanu albo nierozpoznanym kodzie otwiera się ekran ręcznego wpisania EAN. Obsługuje rozpoznawanie, brak wyniku i niedostępny katalog. Katalog synchronizuje się przy uruchomieniu; kod można wyszukać offline po zakończeniu pierwszej synchronizacji.
- **Szczegóły leku:** po dotknięciu karty w zakładce Leki. Uzupełnianie zapasu, edycja danych i harmonogramu oraz usuwanie leku z potwierdzeniem. Zmiana harmonogramu obejmuje przyszłe dawki.
- **Historia:** dziennik przyjęć i pominięć, grupowany datami, z filtrami leku, statusu i okresu. Wpis zachowuje nazwę, dawkę, godzinę potwierdzenia i zapas z chwili zdarzenia, także po zmianie lub usunięciu leku.
- **Ustawienia:** powiadomienia, domyślne odroczenie (10/15/30/60 minut), dźwięk i wibracje w kanale Androida, motyw systemowy/jasny/ciemny i informacje o aplikacji. Ekrany konta są pominięte. Dzwonek na każdej głównej zakładce otwiera wspólny ekran powiadomień.

Leki, harmonogramy, zapas, odroczenia i historia są zapisywane atomowo w prywatnym pliku aplikacji i odtwarzane po ponownym uruchomieniu. Preferencje są zapisane w SharedPreferences. Katalog leków jest przechowywany lokalnie w SQLite.

Przypomnienia korzystają z kanału powiadomień i AlarmManager (`setAndAllowWhileIdle`). Są przeliczane po zmianie leku, harmonogramu, statusu dawki i ustawień oraz po restarcie telefonu lub zmianie czasu. Android może opóźniać je podczas oszczędzania baterii; aplikacja nie żąda uprawnień do alarmów dokładnych. Dostępny jest przycisk wysłania powiadomienia testowego.

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
