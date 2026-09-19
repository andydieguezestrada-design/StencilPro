package com.tattoostencil.pro

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tattoostencil.pro.processing.ImageProcessor
import com.tattoostencil.pro.processing.ImageProcessor.FilterType
import com.tattoostencil.pro.processing.ImageProcessor.LineColor
import com.tattoostencil.pro.processing.ImageProcessor.ProcessingSettings
import com.tattoostencil.pro.ui.theme.StencilProTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StencilProTheme { StencilProApp() } }
    }
}

data class HistoryItem(val uri: String, val name: String, val date: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StencilProApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var filter by remember { mutableStateOf(FilterType.CLEAN_LINES) }
    var settings by remember { mutableStateOf(ProcessingSettings()) }
    var tab by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var compareOriginal by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(loadHistory(context)) }
    var processingJob by remember { mutableStateOf<Job?>(null) }

    fun process(source: Bitmap, f: FilterType = filter, s: ProcessingSettings = settings) {
        processingJob?.cancel()
        loading = true
        processingJob = scope.launch {
            val processed = withContext(Dispatchers.Default) { ImageProcessor.processImage(source, f, s) }
            result = processed
            loading = false
        }
    }

    fun acceptBitmap(bitmap: Bitmap) {
        original?.let { if (it !== bitmap && !it.isRecycled) it.recycle() }
        original = ImageProcessor.normalize(bitmap, settings.maxOutputSize)
        process(original!!)
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                loading = true
                val bmp = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }
                loading = false
                if (bmp != null) acceptBitmap(bmp) else toast(context, "No se pudo abrir la imagen")
            }
        }
    }

    var pendingCamera by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCamera = true else toast(context, "Permiso de cámara denegado")
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        pendingCamera = false
        if (bmp != null) acceptBitmap(bmp)
    }
    LaunchedEffect(pendingCamera) {
        if (pendingCamera) cameraLauncher.launch(null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Brush, null); Spacer(Modifier.width(8.dp)); Text("StencilPro", fontWeight = FontWeight.Bold, fontSize = 22.sp) } },
                actions = { IconButton({ showSettings = true }) { Icon(Icons.Default.Tune, "Ajustes") } }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("Galería" to Icons.Default.Image, "Cámara" to Icons.Default.CameraAlt, "Historial" to Icons.Default.History).forEachIndexed { i, pair ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(pair.second, pair.first) }, label = { Text(pair.first) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            when (tab) {
                0 -> SourcePanel("Importar referencia", Icons.Default.AddPhotoAlternate) { galleryLauncher.launch("image/*") }
                1 -> SourcePanel("Tomar foto", Icons.Default.CameraAlt) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) pendingCamera = true
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                }
                2 -> HistoryPanel(history, onOpen = { uri ->
                    scope.launch {
                        val bmp = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }
                        if (bmp != null) { original = bmp; result = bmp; tab = 0 } else toast(context, "No se pudo abrir este historial")
                    }
                }, onShare = { uri -> shareUri(context, uri) }, onDelete = { item -> history = deleteHistory(context, item); })
            }

            if (original != null && tab != 2) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Vista", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(if (compareOriginal) "Original" else "Stencil")
                    Switch(checked = compareOriginal, onCheckedChange = { compareOriginal = it })
                }
                PreviewCard(if (compareOriginal) original else result, loading)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { result?.let { saveBitmap(context, it)?.let { uri -> history = addHistory(context, uri) } } },
                        enabled = result != null && !loading,
                        modifier = Modifier.weight(1.25f)
                    ) {
                        Icon(Icons.Default.SaveAlt, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Guardar en galería")
                    }
                    OutlinedButton(
                        onClick = { result?.let { shareBitmap(context, it) } },
                        enabled = result != null && !loading,
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Icon(Icons.Default.Share, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Compartir")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { original = null; result = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Limpiar imagen")
                }
                Spacer(Modifier.height(12.dp))
                Text("Tipo de plantilla", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                FilterSelector(filter) { filter = it; process(original!!) }
                Spacer(Modifier.height(12.dp))
            }
        }
        if (showSettings) SettingsPanel(settings, { new -> settings = new; original?.let { process(it, filter, new) } }, { showSettings = false })
    }
}

@Composable
fun SourcePanel(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { onClick() }, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp)); Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("JPG, PNG, WEBP • procesamiento local", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PreviewCard(bitmap: Bitmap?, loading: Boolean) {
    Card(Modifier.fillMaxWidth().height(360.dp), shape = RoundedCornerShape(16.dp)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            when { loading -> CircularProgressIndicator(); bitmap != null -> Image(bitmap.asImageBitmap(), "Vista previa", Modifier.fillMaxSize(), contentScale = ContentScale.Fit); else -> Text("Procesando…") }
        }
    }
}

@Composable
fun FilterSelector(selected: FilterType, onSelected: (FilterType) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(FilterType.values().toList()) { f ->
            FilterChip(selected = selected == f, onClick = { onSelected(f) }, label = { Column { Text(f.displayName); Text(f.description, style = MaterialTheme.typography.labelSmall) } }, leadingIcon = { if (selected == f) Icon(Icons.Default.Check, null) })
        }
    }
}

@Composable
fun HistoryPanel(items: List<HistoryItem>, onOpen: (Uri) -> Unit, onShare: (Uri) -> Unit, onDelete: (HistoryItem) -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Text("Historial", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (items.isEmpty()) Text("Los stencils guardados aparecerán aquí.", Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            items(items, key = { it.uri }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Image, null, Modifier.size(38.dp)); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.Bold); Text(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.date)), style = MaterialTheme.typography.labelSmall) }
                        IconButton({ onOpen(Uri.parse(item.uri)) }) { Icon(Icons.Default.OpenInNew, "Abrir") }
                        IconButton({ onShare(Uri.parse(item.uri)) }) { Icon(Icons.Default.Share, "Compartir") }
                        IconButton({ onDelete(item) }) { Icon(Icons.Default.Delete, "Eliminar") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(settings: ProcessingSettings, onChange: (ProcessingSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)) {
            item { Text("Ajustes de stencil", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Los cambios se aplican automáticamente.", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(14.dp)) }
            item { SettingSlider("Brillo", settings.brightness, -80f..80f, "%.0f") { onChange(settings.copy(brightness = it)) } }
            item { SettingSlider("Contraste", settings.contrast, 0.7f..2.0f, "%.2f") { onChange(settings.copy(contrast = it)) } }
            item { SettingSlider("Sensibilidad de línea", settings.edgeStrength.toFloat(), 10f..90f, "%.0f") { onChange(settings.copy(edgeStrength = it.toInt())) } }
            item { SettingSlider("Umbral", settings.threshold.toFloat(), 5f..220f, "%.0f") { onChange(settings.copy(threshold = it.toInt())) } }
            item { SettingSlider("Grosor", settings.lineWidth.toFloat(), 1f..3f, "%.0f") { onChange(settings.copy(lineWidth = it.toInt())) } }
            item { SwitchRow("Suavizar bordes", settings.smoothEdges) { onChange(settings.copy(smoothEdges = it)) } }
            item { SwitchRow("Eliminar puntos aislados", settings.removeNoise) { onChange(settings.copy(removeNoise = it)) } }
            item { SwitchRow("Preservar detalle", settings.preserveDetail) { onChange(settings.copy(preserveDetail = it)) } }
            item { SwitchRow("Fondo blanco", settings.backgroundWhite) { onChange(settings.copy(backgroundWhite = it)) } }
            item { SwitchRow("Invertir", settings.invertColors) { onChange(settings.copy(invertColors = it)) } }
            item { Text("Color de línea", Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { LineColor.values().forEach { c -> FilterChip(selected = settings.lineColor == c, onClick = { onChange(settings.copy(lineColor = c)) }, label = { Text(c.displayName) }) } } }
            item { Spacer(Modifier.height(14.dp)); Button(onClick = { onChange(ProcessingSettings()); }, modifier = Modifier.fillMaxWidth()) { Text("Restaurar ajustes") } }
        }
    }
}

@Composable
fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(String.format(Locale.US, format, value), fontWeight = FontWeight.Bold) }; Slider(value, onValueChange = onChange, valueRange = range) }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onCheckedChange = onChange) } }

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

fun saveBitmap(context: Context, bitmap: Bitmap): Uri? {
    val name = "StencilPro_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
    val resolver = context.contentResolver
    var uri: Uri? = null
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StencilPro")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Galería no disponible")
        val wrote = resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } ?: false
        if (!wrote) throw IllegalStateException("No se pudo escribir el PNG")

        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
        }
        toast(context, "Stencil guardado en Galería → Pictures/StencilPro")
        uri
    } catch (e: Exception) {
        uri?.let { runCatching { resolver.delete(it, null, null) } }
        toast(context, "No se pudo guardar en la galería: ${e.message ?: "error desconocido"}")
        null
    }
}

fun shareBitmap(context: Context, bitmap: Bitmap) {
    val file = File(context.cacheDir, "stencil_share_${System.currentTimeMillis()}.png")
    try {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shareUri(context, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
    } catch (e: Exception) { toast(context, "No se pudo compartir") }
}

fun shareUri(context: Context, uri: Uri) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Compartir stencil"))
}

private fun loadHistory(context: Context): List<HistoryItem> {
    val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    return prefs.getStringSet("items", emptySet()).orEmpty().mapNotNull { raw ->
        val p = raw.split("|", limit = 3); if (p.size == 3) HistoryItem(p[0], p[1], p[2].toLongOrNull() ?: 0) else null
    }.sortedByDescending { it.date }
}

private fun persistHistory(context: Context, items: List<HistoryItem>) {
    context.getSharedPreferences("history", Context.MODE_PRIVATE).edit().putStringSet("items", items.take(30).map { "${it.uri}|${it.name}|${it.date}" }.toSet()).apply()
}

private fun addHistory(context: Context, uri: Uri): List<HistoryItem> {
    val items = loadHistory(context).filter { it.uri != uri.toString() }.toMutableList()
    items.add(0, HistoryItem(uri.toString(), "Stencil ${items.size + 1}", System.currentTimeMillis()))
    persistHistory(context, items); return items
}

private fun deleteHistory(context: Context, item: HistoryItem): List<HistoryItem> {
    runCatching { context.contentResolver.delete(Uri.parse(item.uri), null, null) }
    val items = loadHistory(context).filterNot { it.uri == item.uri }; persistHistory(context, items); return items
}
