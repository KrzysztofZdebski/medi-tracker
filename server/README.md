# mediTracker server

Mały serwer (Rust, axum, SQLite) dla aplikacji mediTracker. Robi trzy rzeczy:

- konta z weryfikacją e-mail (kod 6 cyfr) i odzyskiwaniem hasła,
- przechowuje **zaszyfrowaną** kopię zapasową danych użytkownika (serwer nigdy nie zna haseł ani kluczy, tylko szyfrogram),
- serwuje publiczny katalog leków (`/catalog/...`), budowany codziennie z Rejestru Produktów Leczniczych.

Specyfikacja: `docs/superpowers/specs/2026-11-11-accounts-backup-server-design.md`.

## Endpointy

Błędy mają zawsze postać `{ "error": "<kod>" }`. Trasy z oznaczeniem Bearer wymagają nagłówka `Authorization: Bearer <token>`.

| Endpoint | Auth | Opis |
|---|---|---|
| `GET /health` | – | `ok` |
| `POST /auth/register`, `/auth/verify`, `/auth/verify/resend` | – | rejestracja i potwierdzenie e-mail |
| `POST /auth/prelogin`, `/auth/login` | – | sól + parametry KDF, logowanie (login zwiększa `active_epoch`) |
| `POST /auth/logout` | Bearer | wylogowanie |
| `POST /auth/password` | Bearer | zmiana hasła |
| `POST /auth/reset/request`, `/verify`, `/complete`, `/wipe` | – | odzyskiwanie hasła (kod z e-maila + kod odzyskiwania albo wyzerowanie kopii) |
| `GET /backup`, `PUT /backup`, `POST /backup/takeover` | Bearer | kopia zapasowa z wersjonowaniem i epoką aktywnego urządzenia |
| `DELETE /account` | Bearer | usunięcie konta |
| `GET /catalog/version.json`, `GET /catalog/leki.sqlite.gz` | – | statyczny katalog leków |

`PUT /backup` przyjmuje ciało do **7 MiB** (limit na trasie; 5 MiB zdekodowanego szyfrogramu mieści się po base64). Pozostałe trasy mają limit 5 MiB. Każde reverse proxy przed serwerem musi przepuszczać 7 MiB na `/backup`.

Konta niezweryfikowane są kasowane po 24 h. Co godzinę serwer usuwa też wygasłe `sessions` i `reset_tokens`.

## Zmienne środowiskowe

Ustawiane w `server/.env` na serwerze (patrz `.env.example`); `DATABASE_URL`, `CATALOG_DIR` i `PUBLIC_URL` ustawia `docker-compose.yml`.

| Zmienna | Domyślnie | Znaczenie |
|---|---|---|
| `SERVER_SECRET` | wymagana | hex, min. 32 znaki (`openssl rand -hex 32`); klucz HMAC dla tokenów i fałszywych soli |
| `EMAIL_MODE` | `log` | `log` albo `smtp` |
| `SMTP_HOST`, `SMTP_USER`, `SMTP_PASS`, `SMTP_FROM` | – | wymagane przy `EMAIL_MODE=smtp` |
| `DATABASE_URL` | `sqlite:meditracker.db` | w kontenerze `sqlite:///data/meditracker.db` |
| `CATALOG_DIR` | `catalog` | w kontenerze `/catalog` (wolumen tylko do odczytu) |
| `PUBLIC_URL` | `http://localhost:8080` | publiczny adres serwera |
| `BIND` | `0.0.0.0:8080` | adres nasłuchu |
| `BACKUP_DIR` | `/data/backups` | katalog nocnych kopii bazy |
| `RUST_LOG` | `info` | poziom logów |

**Uwaga: `EMAIL_MODE=log` jest tylko do developmentu.** Drukuje do logów kontenera (`docker logs meditracker-api-1`) pełne maile, czyli adresy odbiorców i kody weryfikacyjne. Dla prawdziwych użytkowników przełącz na SMTP.

### Przełączenie na Gmail SMTP

1. Na koncie Google włącz weryfikację dwuetapową i wygeneruj **hasło aplikacji** (nie używaj hasła do konta).
2. W `~/meditracker/server/.env` na serwerze ustaw:

   ```
   EMAIL_MODE=smtp
   SMTP_HOST=smtp.gmail.com
   SMTP_USER=twoj.adres@gmail.com
   SMTP_PASS=<hasło aplikacji>
   SMTP_FROM=mediTracker <twoj.adres@gmail.com>
   ```
3. Wdróż ponownie (`bash server/deploy.sh`) albo na serwerze: `cd ~/meditracker/server && docker compose -p meditracker up -d`. `deploy.sh` nie nadpisuje istniejącego `.env`.

## Testy lokalnie

Na tym komputerze nie ma `cargo` ani Dockera; testy uruchamiają się na serwerze domowym w kontenerze `rust:1-slim-bookworm`:

```
bash server/scripts/test.sh                 # wszystkie
bash server/scripts/test.sh catalog_files   # filtr po nazwie testu
```

Skrypt wysyła `server/` i `catalog/` przez `ssh kryzys-server` i uruchamia `cargo test`.

## Wdrożenie

```
bash server/deploy.sh
```

Skrypt pakuje `server/` (bez `target` i `.env`) oraz `catalog/` przez `ssh kryzys-server` do `~/meditracker` (układ: `~/meditracker/server` i `~/meditracker/catalog`), przy pierwszym uruchomieniu tworzy `server/.env` z `.env.example` (świeży `SERVER_SECRET`, `EMAIL_MODE=log`) i robi `docker compose -p meditracker up -d --build`. **Nigdy nie używaj `docker compose down -v`**: usunąłby wolumeny z bazą.

Projekt Compose `meditracker`:

- `api` – obraz z `server/Dockerfile` (użytkownik nie-root, uid 10001), port **tylko** `127.0.0.1:8765` -> 8080, wolumeny `meditracker-data:/data` i `meditracker-catalog:/catalog:ro`,
- `catalog` – obraz z `server/catalog-job/Dockerfile` (budowany z korzenia repozytorium, kopiuje `catalog/`).

### Katalog leków

Kontener `catalog` buduje katalog przy pierwszym starcie (jeśli brak `version.json`), a potem codziennie o **05:17 UTC**: `test_build.py`, pobranie eksportu RPL (~100 MB), `build.py`. Pliki w `/catalog` podmieniane są atomowo, najpierw `leki.sqlite.gz`, potem `version.json`, i tylko gdy wszystkie kroki się udały. Błąd budowania jest logowany (`catalog build FAILED, keeping previous files`), a stare pliki zostają. Logi: `ssh kryzys-server docker logs meditracker-catalog-1`.

### Funnel (dostęp z internetu)

Telefon łączy się z `https://kryzys-server.tail9dde62.ts.net:10000`. Tailscale Funnel na porcie **10000** przekazuje ruch do `127.0.0.1:8765`. Port **443** (Vaultwarden) ma zostać dostępny wyłącznie w tailnecie, bez Funnela. Konfiguracji Tailscale ani Caddy ten projekt nie zmienia.

### Logi i kopie bazy

- Logi API: `ssh kryzys-server docker logs meditracker-api-1`. Serwer loguje tylko metodę, ścieżkę, status i czas; nigdy ciał żądań, e-maili, kodów, tokenów ani szyfrogramów (wyjątek: `EMAIL_MODE=log`).
- Baza: wolumen `meditracker-data`, plik `/data/meditracker.db`.
- Kopie bazy: proces API robi `VACUUM INTO /data/backups/YYYY-MM-DD.db` przy starcie i co 24 h, zachowuje 7 najnowszych. Lista: `docker exec meditracker-api-1 ls -l /data/backups`. Skopiowanie na hosta: `docker cp meditracker-api-1:/data/backups/2026-11-11.db .`

## Test przekaźników Funnel (2026-11-11)

`curl --resolve kryzys-server.tail9dde62.ts.net:10000:<ip> https://kryzys-server.tail9dde62.ts.net:10000/health`:

| Przekaźnik | Wynik |
|---|---|
| 185.40.234.172 | `ok`, HTTP 200, 0,43 s |
| 185.40.234.210 | `ok`, HTTP 200, 0,47 s |
| 185.40.234.37 | `ok`, HTTP 200, 0,39 s |

Wszystkie trzy działają. Wcześniej tego samego dnia `.37` zrywał handshake TLS; przy wdrożeniu już nie, więc problem bywa przejściowy i warto go sprawdzić ponownie, gdy aplikacja zgłasza błędy sieci.
