# StencilPro

Proyecto Android Kotlin + Jetpack Compose basado en los dos TXT proporcionados.

## Compilación en GitHub Actions
El workflow `.github/workflows/build.yml` ejecuta `:app:assembleDebug` con JDK 17 y Gradle 8.7 y publica el APK como artifact.

## Nota de fidelidad
Los archivos de código indicados en `documento completo.txt` se copiaron literalmente. Se añadió únicamente una llave `}` al final de `MainActivity.kt` porque el bloque `shareBitmap` del TXT queda sin cerrar y, tal como está escrito, Kotlin no puede compilarlo.

Además, se añadieron los archivos mínimos de infraestructura que el TXT referencia pero no contiene (root Gradle, settings, catálogo de versiones, iconos requeridos por el Manifest y workflow de GitHub Actions). No se modificó la lógica de la aplicación.

## AI engine (v9)

The default **IA · Stencil** mode now uses a learned ONNX image-to-line stage instead of the previous Canny-only implementation. On first use it downloads the Apache-2.0 `line-drawings.onnx` and `line-relifer.onnx` models from the published repository and stores them in app-private storage. Subsequent processing is local. The AI output is then passed through stencil-specific cleanup, component filtering, line bridging and rendering.

Reference model repository: `Luoaho/image-to-line-drawing-onnx`. See `MODEL_NOTES.md` for model provenance and the design rationale.

The larger ControlNet/MistoLine families were evaluated as architectural references; they are diffusion/ControlNet stacks rather than compact standalone line extractors. This version deliberately uses an actual on-device neural line-art model as the first production integration point, while keeping the engine replaceable for a future dedicated tattoo-stencil model.
