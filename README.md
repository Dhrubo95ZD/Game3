# Neon Rift

Neon Rift is a premium, landscape Android action-RPG vertical slice. It is the
first foundation for a future online RPG with cooperative play, PvP, leaderboards,
accounts, progression, and a persistent shared world.

## Version 0.1 — Slayer orientation

- Three-class selection hall: Slayer, Gunslayer, and Mecha
- Slayer is playable; Gunslayer and Mecha are clearly marked **Coming Soon**
- Three increasingly difficult combat waves and a Warden unit finale
- Touch movement, three-hit sword chain, Breakline heavy strike, and invulnerable dash
- High score persistence, pause/resume, haptics, win/defeat flows, and responsive 16:9 UI
- Original AI-authored character, enemy, environment, effect, and launcher artwork
- Offline, no ads, no analytics, no authentication, and no network permissions

## Content direction

The world is a grounded fantasy/sci-fi frontier. Technology, training machines,
engineering, courage, cooperation, and physical skill drive the fiction. The
project intentionally excludes magic, deities, worship, occult imagery, gambling,
alcohol, sexualized presentation, blood, gore, and false-belief systems.

## Build

Requirements: JDK 17, Android SDK 35, and Gradle 8.7.

```bash
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Every push to
`main` also builds an installable APK in GitHub Actions under the
`neon-rift-debug-apk` artifact.

## Roadmap

1. Combat feel, animation expansion, audio, equipment, and accessibility
2. Gunslayer and Mecha gameplay kits
3. Local character progression and additional zones
4. Backend accounts and Google authentication
5. Authoritative co-op, PvP, matchmaking, and leaderboards

Online features will be added only after the offline combat foundation is stable.
