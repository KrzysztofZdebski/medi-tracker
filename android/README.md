# Aplikacja Android

Aplikacja w Jetpack Compose. Katalog leków synchronizuje się w tle przy starcie (`CatalogRepository`): pobiera `version.json` i, jeśli jest nowsza wersja, `leki.sqlite.gz` z release `katalog`, sprawdza sumę `sha256` i dopiero wtedy podmienia katalog.

**Skaner kodów** (`feature/scanner`): przycisk „Zeskanuj kod kreskowy” w kreatorze dodawania leku otwiera skaner z Google Play Services (`play-services-code-scanner`, EAN-13/EAN-8/UPC-A i GS1 DataMatrix). Aplikacja nie potrzebuje uprawnienia `CAMERA`, ale telefon musi mieć Google Play Services. Z DataMatrix [`Gs1.kt`](app/src/main/kotlin/pl/agh/meditracker/feature/scanner/Gs1.kt) odczytuje też datę ważności, serię i numer seryjny. GTIN trafia do wyszukiwania w katalogu; po anulowaniu, błędzie skanera albo nierozpoznanym kodzie otwiera się okno do ręcznego wpisania EAN.

Testy jednostkowe (m.in. parsera GS1): `.\gradlew.bat testDebugUnitTest`.

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

Telefon musi mieć włączone debugowanie USB (Opcje programisty → Debugowanie USB). Sprawdzisz to komendą `adb devices`.

Gotowe kody do testu:
- `5909990296026`: Apap 500 mg
- `05909991389901`: Ozempic 0,25 mg
