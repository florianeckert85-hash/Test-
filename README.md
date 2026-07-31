# Leserkonto – Android-App für das Bibliothekskonto (Komm.ONE / BIBLIOTHECA)

Native Android-App (Kotlin + Jetpack Compose), die sich am Online-Leserkonto der
Stadtbibliothek anmeldet (Beispiel: **Stadtbibliothek Wehr**,
`https://bibliothek.komm.one/wehr/Leserkonto`), die ausgeliehenen Medien inkl.
**Fälligkeitsdatum** anzeigt, **rechtzeitig erinnert** und Medien **verlängern**
kann – manuell per Knopfdruck oder optional **automatisch** kurz vor Fälligkeit.

## Funktionen

- 🔐 **Login & sichere Speicherung** der Zugangsdaten (Ausweisnummer + Passwort)
  verschlüsselt im Android-Keystore (`EncryptedSharedPreferences`). Die Daten
  verlassen das Gerät nie und sind von Cloud-Backups ausgeschlossen.
- 📚 **Übersicht der geliehenen Medien** mit Titel, Verfasser, Fälligkeitsdatum
  und Restzahl der Verlängerungen. Überfällige / bald fällige Titel sind farblich
  hervorgehoben.
- ⏰ **Erinnerung** als lokale Push-Benachrichtigung, X Tage vor Fälligkeit
  (einstellbar 0–14 Tage). Läuft komplett auf dem Gerät, ohne Server.
- 🔄 **Verlängern** einzeln pro Medium oder „Alles verlängern". Optional
  **automatisch** (Schalter, standardmäßig aus), X Tage vor Fälligkeit.
- 🌙 Material-3-Oberfläche inkl. Dark Mode / dynamische Farben.

## Wie es funktioniert

Das Bibliotheksportal bietet keine öffentliche API. Die App meldet sich daher wie
ein Browser an und liest die Konto-Seite aus (HTML-Scraping):

- `data/opac/BibliothecaOpenClient.kt` – Login, Konto laden, Verlängern (OkHttp).
  Das **Login-Formular wird automatisch erkannt** (das Feld mit `type=password`
  ist das Passwort, das davorliegende Textfeld die Ausweisnummer), ebenso die
  Verlängern-Schaltflächen/Checkboxen. Dadurch funktioniert derselbe Code über
  die leicht unterschiedlichen Vorlagen verschiedener Büchereien.
- `data/opac/LoanParser.kt` – wandelt die Konto-HTML heuristisch in eine Liste
  von Ausleihen um (erkennt „Zeilen" anhand des Datumsmusters `TT.MM.JJJJ`).
- `work/SyncWorker.kt` – täglicher Hintergrund-Sync (WorkManager): aktualisiert
  das Konto, verlängert ggf. automatisch und löst die Erinnerung aus.

### Andere Bibliothek nutzen

In `LeserkontoApplication.kt` bzw. `data/model/Models.kt` die `LibraryConfig`
anpassen:

```kotlin
LibraryConfig(baseUrl = "https://bibliothek.komm.one", branch = "wehr")
```

Für eine andere Komm.ONE-Bücherei genügt es meist, `branch` zu ändern.

## Bauen

Voraussetzungen: Android Studio (Giraffe+) **oder** JDK 17 + Android SDK 34.

```bash
./gradlew assembleDebug        # Debug-APK bauen
./gradlew installDebug         # auf angeschlossenes Gerät installieren
./gradlew test                 # Unit-Tests (Parser) ausführen
```

> Hinweis: Der Gradle-Wrapper (`gradlew`) ist enthalten. Beim ersten Build lädt
> Gradle die Abhängigkeiten (AGP, AndroidX, OkHttp, Jsoup) herunter – dafür wird
> eine Internetverbindung benötigt.

## Selektoren am echten Konto feinjustieren

Login- und Verlängern-Erkennung sind bewusst heuristisch. Falls bei einer
bestimmten Bücherei einmal etwas nicht erkannt wird:

1. In der App anmelden → Einstellungen → (geplanter) **„HTML exportieren"**-Knopf
   bzw. `AccountRepository.captureHtml()` nutzen, um das echte Konto-HTML zu
   erhalten.
2. Das HTML als Fixture in `app/src/test/java/.../LoanParserTest.kt` einfügen und
   die Selektoren in `LoanParser.kt` / `BibliothecaOpenClient.kt` anpassen, bis
   der Test grün ist.

Die Parser-Logik ist von Android entkoppelt und damit als reiner JVM-Unit-Test
prüfbar (`LoanParserTest`).

## Sicherheit & rechtlicher Hinweis

- Die App greift ausschließlich auf **dein eigenes** Konto mit **deinen**
  Zugangsdaten zu – wie ein normaler Browser-Login.
- Zugangsdaten werden nur lokal verschlüsselt gespeichert, nichts wird an Dritte
  gesendet.
- Eine **automatische Verlängerung** kann fehlschlagen, wenn ein Medium
  vorgemerkt ist oder das Verlängerungslimit erreicht wurde – die App meldet das
  dann entsprechend. Bitte vor Verlass auf die Automatik einige Zyklen prüfen.

## Projektstruktur

```
app/src/main/java/de/leserkonto/app/
├── LeserkontoApplication.kt      # App + DI-Container
├── MainActivity.kt               # Compose-Einstieg, Notification-Permission
├── data/
│   ├── model/Models.kt           # LibraryConfig, Loan, AccountData, OpacResult
│   ├── opac/OpacClient.kt        # Schnittstelle
│   ├── opac/BibliothecaOpenClient.kt  # Login/Scraping/Verlängern (OkHttp)
│   ├── opac/LoanParser.kt        # HTML → Ausleihen (Jsoup, testbar)
│   ├── store/CredentialStore.kt  # verschlüsselte Zugangsdaten
│   ├── store/SettingsStore.kt    # Einstellungen (DataStore)
│   └── AccountRepository.kt      # Single Source of Truth
├── work/
│   ├── SyncWorker.kt             # täglicher Sync + Auto-Verlängerung
│   ├── SyncScheduler.kt          # WorkManager-Planung
│   └── NotificationHelper.kt     # Erinnerungs-Benachrichtigungen
└── ui/                           # Compose-Screens + ViewModel + Theme
```
