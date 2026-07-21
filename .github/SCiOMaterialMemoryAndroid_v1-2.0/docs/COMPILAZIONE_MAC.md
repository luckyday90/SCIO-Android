# Compilazione su macOS

## Metodo A — Android Studio
1. Installare Android Studio stabile per Apple Silicon.
2. Aprire la cartella che contiene `settings.gradle.kts`.
3. Attendere la sincronizzazione Gradle.
4. Collegare il telefono Android con Debug USB attivo.
5. Premere Run.
6. Per ottenere l'APK: Build > Build APK(s).
7. Percorso: `app/build/outputs/apk/debug/app-debug.apk`.

## Metodo B — senza installare strumenti sul Mac
1. Creare un repository GitHub.
2. Caricare l'intera cartella del progetto.
3. Aprire Actions > Build Android APK > Run workflow.
4. Al termine scaricare l'artefatto `SCiO-Material-Memory-debug-apk`.
5. Estrarre e trasferire `app-debug.apk` sul telefono.

Il workflow è in `.github/workflows/build-apk.yml`.
