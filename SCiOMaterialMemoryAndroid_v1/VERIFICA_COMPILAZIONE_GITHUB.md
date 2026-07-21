# Verifica compilazione GitHub

Il workflow cerca automaticamente `gradlew`, entra nella stessa cartella e verifica che siano presenti:

- `settings.gradle.kts`
- `build.gradle.kts`
- `app/build.gradle.kts`

Prima della compilazione controlla inoltre che:

- `google()` e `gradlePluginPortal()` siano configurati;
- i plugin Android e Kotlin abbiano una versione nel file root;
- il modulo `app` usi `compilerOptions`;
- non sia rimasto il vecchio blocco `kotlinOptions`.

Il comando di compilazione è:

```bash
./gradlew clean testDebugUnitTest assembleDebug --stacktrace --no-daemon
```

L'APK viene caricato come artefatto con nome `SCiO-Material-Memory-debug-apk`.
