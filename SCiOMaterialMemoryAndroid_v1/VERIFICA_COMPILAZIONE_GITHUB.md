# Verifica compilazione GitHub

Il workflow entra in `SCiOMaterialMemoryAndroid_v1` e verifica che siano presenti:

- `settings.gradle.kts`
- `build.gradle.kts`
- `app/build.gradle.kts`
- `gradle/wrapper/gradle-wrapper.jar`

Il comando di compilazione è:

```bash
./gradlew testDebugUnitTest assembleDebug --stacktrace --no-daemon
```

Il workflow viene eseguito per ogni pull request e push verso `main`, oltre che manualmente.
Verifica che `app/build/outputs/apk/debug/app-debug.apk` esista e non sia vuoto,
quindi lo carica come artefatto `SCiO-Material-Memory-debug-apk`.
