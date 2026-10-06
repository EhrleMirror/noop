# NOOP – persönlicher Fork (Android)

Basis: [ryanbr/noop](https://github.com/ryanbr/noop) Version 12.0.0 (Commit `9f98f81`, 4. Oktober 2026).
Lizenz unverändert: PolyForm Noncommercial 1.0.0 – private, nicht-kommerzielle Nutzung und Änderungen sind erlaubt.

## Was dieser Fork ändert

### 1. Körpermaße: Gewicht und Taillenumfang als Einträge
- Neuer Bildschirm **Körpermaße** (Mehr → Körper → Körpermaße, über das **+** oben rechts auf „Heute“ oder durch Tippen auf die Gewichts-Kachel).
- Pro Tag Gewicht und/oder Taillenumfang eintragen; ein zweiter Eintrag am selben Tag ersetzt den ersten. Datum mit Pfeilen zurückstellen (nie in die Zukunft), Komma und Punkt werden akzeptiert.
- Kacheln mit aktuellem Wert und Veränderung der letzten ~30 Tage, Verlaufsdiagramme (30 T / 90 T / 1 J / Alle), Verlaufsliste zum Bearbeiten und Löschen, dazu „Taille zu Größe“.
- Der neueste Eintrag aktualisiert automatisch das Profil (Gewicht und Taille), damit Kalorien und die VO₂max-Schätzung aktuell bleiben. Ausnahme: Ist „Gewicht aus Health Connect“ aktiv, bleibt das Profilgewicht bei Health Connect.
- Die Gewichts-Kachel auf „Heute“ zeigt das neueste Gewicht aus Einträgen und Importen.
- Speicherung nur auf dem Gerät (generische Messreihen-Tabelle, Quelle `noop-body`) – keine Datenbank-Migration, die Einträge sind im normalen `.noopbak`-Backup enthalten. Metrisch/imperial folgt der Einheiten-Einstellung.

### 2. Strap-Look (näher an der Original-App)
- Neue Diagrammfarbe **Strap**: echtes Schwarz, flache graphitgraue Karten, Erholung in den drei Bändern Rot (bis 33) / Gelb (34–66) / Grün (ab 67), Belastung in Blau, Schlaf in Hellblau.
- Startseite: Ringe in der Reihenfolge **Schlaf · Erholung · Belastung**, Prozentzeichen bei Schlaf und Erholung, Belastung auf der Skala 0–21, kein Hintergrund-Himmel.
- Zahlen, Titel und Überschriften in einer schmalen Schrift (Barlow Semi Condensed, SIL OFL 1.1).
- Deutsche Begriffe vereinheitlicht: Erholung (vorher Energie/Ladung), Belastung (vorher Anstrengung), Schlaf (vorher Erholung/Ruhe).
- Wird beim ersten Start einmalig aktiviert. Zurück zum Original-Look: Einstellungen → Erscheinungsbild → Vorlage (oder „Diagrammfarben“ → Standard).

## APK bauen

**Android Studio:** Ordner `android/` öffnen, Build-Variante `fullDebug` wählen, „Run“ – oder **Build → Build APK(s)**. Ergebnis: `android/app/build/outputs/apk/full/debug/app-full-debug.apk` (installiert als `com.noop.whoop.debug` neben einer eventuell vorhandenen Original-App).

**Kommandozeile** (JDK 17 + Android SDK 34/35):
```
cd android
./gradlew assembleFullDebug        # die echte App
./gradlew assembleDemoDebug        # Variante mit ~120 Tagen Beispieldaten zum Ausprobieren
```

**GitHub:** Im eigenen Fork baut der Workflow „Android APK (fork)“ (`.github/workflows/fork-android-apk.yml`) bei jedem Push automatisch beide APKs und legt sie unter *Releases → android-latest* ab. Die Upstream-Workflows (Python-Tools, Parity-Governance) melden bei diesem Fork erwartbar Fehler, weil er nur Android ändert – für die APK sind sie egal und können im Actions-Tab deaktiviert werden.

## WHOOP 4.0 koppeln
Das Band hält nur eine Bluetooth-Kopplung gleichzeitig. Vor dem Koppeln die offizielle WHOOP-App komplett schließen (oder Bluetooth auf dem Handy mit der WHOOP-App ausschalten), dann in NOOP unter Geräte verbinden. Danach muss sich die WHOOP-App eventuell neu koppeln.

## Geänderte Dateien (Android)
- neu: `data/BodyMeasurementStore.kt`, `ui/BodyMeasurementsScreen.kt`, `ui/ForkDefaults.kt`, Test `data/BodyMeasurementStoreTest.kt`, `res/font/barlow_semi_condensed_*.ttf`, `res/values*/strings_fork.xml`
- angepasst: `ui/PaletteTokens.kt`, `ui/Theme.kt`, `ui/Components.kt` (GlowRing), `ui/TodayScreen.kt` (Ringe, Gewichts-Kachel), `ui/TodayMetricsLogic.kt`, `ui/AppRoot.kt` (Navigation, Schnellaktion), `ui/SettingsScreen.kt`, `ui/MainActivity.kt`, `res/values-de/strings.xml`
