# Photo Sphere

App Android per scattare e guardare foto a 360° (Photo Sphere), con metadati GPano compatibili con Google Foto.

## Come funziona

**Scatto guidato**
- Sensori: rotation vector "game" (giroscopio + accelerometro, senza bussola, quindi nessun disturbo magnetico) e giroscopio per capire quando sei fermo.
- Una griglia di ~26 punti copre tutta la sfera (orizzonte, due file inclinate, zenit e nadir) con ~28% di sovrapposizione, calcolata sul campo visivo reale dell'obiettivo (lunghezza focale + dimensione sensore).
- Porta il cerchio centrale su un punto e resta fermo: lo scatto parte da solo.
- Esposizione e bilanciamento del bianco bloccati all'inizio, per evitare differenze di luce tra uno scatto e l'altro.

**Cucitura**
- Ogni scatto viene proiettato sulla sfera con l'orientamento misurato dai sensori, poi fuso con pesi sfumati ai bordi (multi-thread).
- Risultato 4096×2048 (4K) o 6144×3072 (6K), salvato in `Immagini/PhotoSphere` con XMP GPano.

**Viewer 360°**
- OpenGL ES 2.0, trascinamento con inerzia, pinch per lo zoom, modalità giroscopio.
- Legge anche le foto 360° di Google Camera (anche panorami parziali) e qualsiasi JPEG equirettangolare.
- Condivisione dal viewer; la galleria dell'app trova da sola le foto con metadati GPano.

## Compilare l'APK

1. Crea un repo su GitHub e carica tutto il contenuto di questa cartella.
2. Vai su **Actions → Build APK → Run workflow** (parte anche a ogni push).
3. A fine build scarica l'artifact `PhotoSphere-apk` e installa `app-release.apk`.

Requisiti telefono: Android 8.0+, giroscopio consigliato.

## Consigli per scattare

- Tieni il telefono verticale, vicino al corpo, e ruota su te stesso senza spostarti.
- Parti con il telefono dritto, all'altezza dell'orizzonte.
- Evita soggetti in movimento e luce che cambia molto.
