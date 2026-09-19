# StencilPro

Proyecto Android Kotlin + Jetpack Compose basado en los dos TXT proporcionados.

## Compilación en GitHub Actions
El workflow `.github/workflows/build.yml` ejecuta `:app:assembleDebug` con JDK 17 y Gradle 8.7 y publica el APK como artifact.

## Nota de fidelidad
Los archivos de código indicados en `documento completo.txt` se copiaron literalmente. Se añadió únicamente una llave `}` al final de `MainActivity.kt` porque el bloque `shareBitmap` del TXT queda sin cerrar y, tal como está escrito, Kotlin no puede compilarlo.

Además, se añadieron los archivos mínimos de infraestructura que el TXT referencia pero no contiene (root Gradle, settings, catálogo de versiones, iconos requeridos por el Manifest y workflow de GitHub Actions). No se modificó la lógica de la aplicación.
