# Android client (Stage 3)

Native Kotlin client that mirrors the Windows client: `VpnService` + sing-box
core, Quick Settings tile and update checks.

Planned layout (filled in during Stage 3):

```
android/
  app/
    src/main/                 # Kotlin + resources, application id com.shuma.globallink
  libs/                       # sing-box .aar per ABI (fetched via npm run fetch:android)
  build.gradle.kts
  settings.gradle.kts
  gradle/
```

To fetch the sing-box Android libraries early:

```
npm run fetch:android
```