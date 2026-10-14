# Auxio Hybrid: Audiophile Edition 🎵⚡

Un reproductor de audio de alto rendimiento para Android que combina:
1. **Interfaz Auxio (Kotlin / Material 3):** Experiencia visual minimalista, ligera y sin bloatware.
2. **Motor USB DAC Bit-Perfect (TrueSample):** Bypass completo del resampleo de Android (AudioFlinger a 48kHz) transmitiendo audio UAC2 crudo directamente a través de `libusbaudio.so` hacia tu DAC USB.
3. **Selector Dinámico de Salida (HybridAudioSink):**
   - DAC USB conectado $\rightarrow$ Activa el motor TrueSample bit-perfect.
   - DAC desconectado $\rightarrow$ Conmuta suavemente al motor estándar de Auxio (altavoces/auriculares/bluetooth).
4. **Módulo SpotiFLAC Integrado:** Búsqueda y descarga de archivos FLAC sin pérdida (16-bit / 24-bit Hi-Res) directo a tu carpeta de música con auto-escaneo inmediato en la biblioteca.
5. **Compilación Gratuita en la Nube con GitHub Actions:**
   - Flujo de trabajo automatizado en `.github/workflows/build-apk.yml`.
   - Cada vez que subes un cambio, GitHub compila el APK en 2 minutos y te lo deja listo para descargar.

---

## Estructura del Motor Híbrido

* `app/src/main/jniLibs/arm64-v8a/`:
  - `libusbaudio.so`: Driver nativo USB Audio Class 2.
  - `libusb-1.0.so`: Comunicación USB de bajo nivel.
  - `libdecoder.so`: Decodificador nativo de audio Hi-Res.
* `app/src/main/java/com/truesample/usbaudio/`:
  - `NativeBridge.kt`: Interfaz JNI que conecta Kotlin con las llamadas nativas de `libusbaudio.so`.
* `app/src/main/java/org/oxycblt/auxio/audio/`:
  - `UsbDacManager.kt`: Administrador de conexión de hardware USB y ciclo de vida del DAC.
  - `HybridAudioSink.kt`: Enrutador dinámico para AndroidX Media3 / ExoPlayer.
* `app/src/main/java/org/oxycblt/auxio/downloader/`:
  - `SpotiFlacDownloader.kt`: Cliente de resolución y descarga de pistas FLAC Hi-Fi.

---

## Cómo subirlo a tu cuenta de GitHub y compilar tu APK gratis

### Paso 1: Crea un repositorio vacío en GitHub
1. Entra a [github.com/new](https://github.com/new) desde tu navegador o la app de GitHub.
2. Nómbralo por ejemplo: `auxio-hybrid`.
3. Déjalo **Público** (para minutos de Actions 100% ilimitados) y **NO** marques "Add README".

### Paso 2: Subir el proyecto
Desde la terminal de tu teléfono (o indicándomelo a mí para hacerlo automáticamente):
```bash
cd /storage/emulated/0/Documents/auxio-hybrid
git add .
git commit -m "Initial Auxio Hybrid commit with TrueSample DAC & SpotiFLAC"
git branch -M main
git remote add origin https://github.com/TU_USUARIO/auxio-hybrid.git
git push -u origin main
```

### Paso 3: Descargar tu APK compilado
1. En tu repositorio de GitHub, pulsa en la pestaña **Actions**.
2. Verás la compilación **"Compile Android APK"** ejecutándose en tiempo real.
3. Al finalizar (en ~2 minutos), pulsa sobre la ejecución y en la sección **Artifacts** tendrás tu archivo:
   - `Auxio-Hybrid-Debug.apk` / `Auxio-Hybrid-Release.apk`.
4. Descárgalo e instálalo directamente en tu teléfono.
