# Plan d'implémentation MVP

## 1. Capture audio
- API: `AudioRecord`
- Source: `MediaRecorder.AudioSource.UNPROCESSED` puis fallback `MIC`
- Cible: 48kHz, mono, PCM 16 en V1 (puis 24 bits conteneur WAV/FLAC)

## 2. Calibration pré-session
- Fenêtre 20 à 30 secondes
- Metrics: RMS, peak, headroom
- Sortie: gain recommandé + verrouillage pendant session

## 3. Monitoring
- VU meter: vert/orange/rouge
- Alertes:
  - peak > -3 dBFS -> risque saturation
  - RMS < -38 dBFS -> niveau faible

## 4. Contrôle distant
- WebSocket local
- Messages: start, stop, marker, status

## 5. Découpage auto
- silence > 8s -> nouveau segment
- option fade court sur transitions

## 6. Player
- lecture/pause
- marqueurs
- scrubbing molette (composant Compose dédié)
