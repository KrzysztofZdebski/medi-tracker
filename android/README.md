# Aplikacja Android

Na razie to szkielet do testów, z trzema funkcjami:
- **Synchronizuj:** pobiera `version.json` i, jeśli jest nowsza wersja, `leki.sqlite.gz` z release `katalog`. Wypisuje kody HTTP, host, typ, rozmiar i czas, a potem sprawdza sumę `sha256` i dopiero wtedy podmienia katalog.
- **Skanuj kod z pudełka:** otwiera skaner kodów z Google Play Services (EAN-13/EAN-8/UPC-A i GS1 DataMatrix). Z DataMatrix odczytuje też datę ważności, serię i numer seryjny ([`Gs1.kt`](app/src/main/kotlin/pl/agh/meditracker/Gs1.kt)), a potem wyszukuje GTIN w katalogu.
- **Szukaj:** wyszukuje GTIN/EAN (8–14 cyfr, uzupełniany zerami do 14) w lokalnym katalogu i wypisuje nazwę, moc, zawartość opakowania i kod.

Jedyna zależność poza Android SDK to `play-services-code-scanner` (skaner ML Kit z gotowym ekranem kamery; aplikacja nie potrzebuje uprawnienia `CAMERA`, ale telefon musi mieć Google Play Services). Reszta: UI tworzone w kodzie, `HttpURLConnection`, `SQLiteDatabase`.

Testy parsera GS1: `.\gradlew.bat testDebugUnitTest`.

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
