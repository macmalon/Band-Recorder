# Band Recorder (Android)

Base MVP Android pour l'application de répétition décrite dans `Récapitulatif fonctionnel.docx`.

## MVP inclus dans cette base
- Capture audio (architecture prête, format WAV/FLAC à implémenter)
- Calibration (RMS/peak)
- Monitoring en temps réel (vu-mètre logique)
- Contrôle distant local via WebSocket (protocole de base)
- Découpage automatique par silence (service d'analyse)
- Player avec scrubbing (structure UI prête)

## Stack
- Kotlin
- Jetpack Compose
- Coroutines + Flow
- Modules `core` (audio, analysis, network)

## Roadmap (ordre recommandé)
1. Finaliser `AudioRecordEngine` avec source `UNPROCESSED` et fallback.
2. Ajouter writer WAV 48kHz / 24 bits.
3. Brancher calibration pré-session sur 20-30 secondes.
4. Ajouter service WebSocket local pour start/stop/marker/status.
5. Implémenter découpage par silence > 8s.
6. Implémenter player + molette virtuelle.

## Build
- Ouvrir avec Android Studio Giraffe+ / Koala+
- Sync Gradle
- Run sur Android 10+
