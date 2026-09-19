package com.tattoostencil.pro

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tattoostencil.pro.processing.ImageProcessor
import com.tattoostencil.pro.processing.ImageProcessor.FilterType
import com.tattoostencil.pro.processing.ImageProcessor.ProcessingSettings
import com.tattoostencil.pro.ui.theme.StencilProTheme
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StencilProTheme {
                StencilProApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StencilProApp() {
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedFilter by remember { mutableStateOf(FilterType.CANNY_EDGE) }
    var settings by remember { mutableStateOf(ProcessingSettings()) }
    var showSettings by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    
    // Launcher para galería
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isLoading = true
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                originalBitmap = bitmap
                processedBitmap = ImageProcessor.processImage(bitmap, selectedFilter, settings)
            } catch (e: Exception) {
                Toast.makeText(context, "Error al cargar imagen", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }
    
    // Launcher para cámara
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        bitmap?.let {
            originalBitmap = it
            processedBitmap = ImageProcessor.processImage(it, selectedFilter, settings)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Brush,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "StencilPro",
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Ajustes",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Image, contentDescription = "Galería") },
                    label = { Text("Galería") },
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Cámara") },
                    label = { Text("Cámara") },
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = "Historial") },
                    label = { Text("Historial") },
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            when (currentTab) {
                0 -> GalleryTab(
                    originalBitmap = originalBitmap,
                    processedBitmap = processedBitmap,
                    isLoading = isLoading,
                    onImageSelected = { galleryLauncher.launch("image/*") }
                )
                1 -> CameraTab(
                    originalBitmap = originalBitmap,
                    processedBitmap = processedBitmap,
                    onCapture = { cameraLauncher.launch(null) }
                )
                2 -> HistoryTab()
            }
            
            if (originalBitmap != null) {
                Spacer(modifier = Modifier.height(16.dp))
                FilterSelector(
                    selectedFilter = selectedFilter,
                    onFilterSelected = { filter ->
                        selectedFilter = filter
                        isLoading = true
                        processedBitmap = ImageProcessor.processImage(
                            originalBitmap!!,
                            filter,
                            settings
                        )
                        isLoading = false
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                ActionButtons(
                    processedBitmap = processedBitmap,
                    onReset = {
                        originalBitmap = null
                        processedBitmap = null
                    },
                    onSave = {
                        saveBitmap(context, processedBitmap)
                    },
                    onShare = {
                        shareBitmap(context, processedBitmap)
                    }
                )
            }
        }
        
        // Panel de ajustes
        if (showSettings) {
            SettingsPanel(
                settings = settings,
                onSettingsChanged = { newSettings ->
                    settings = newSettings
                    if (originalBitmap != null) {
                        isLoading = true
                        processedBitmap = ImageProcessor.processImage(
                            originalBitmap!!,
                            selectedFilter,
                            newSettings
                        )
                        isLoading = false
                    }
                },
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
fun GalleryTab(
    originalBitmap: Bitmap?,
    processedBitmap: Bitmap?,
    isLoading: Boolean,
    onImageSelected: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (originalBitmap == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clickable { onImageSelected() },
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.AddPhotoAlternate,
                            contentDescription = "Seleccionar imagen",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Toca para seleccionar una imagen",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else {
            ImagePreview(originalBitmap, processedBitmap, isLoading)
        }
    }
}

@Composable
fun CameraTab(
    originalBitmap: Bitmap?,
    processedBitmap: Bitmap?,
    onCapture: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (originalBitmap == null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clickable { onCapture() },
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Tomar foto",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Toca para tomar una foto",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        } else {
            ImagePreview(originalBitmap, processedBitmap, false)
        }
    }
}

@Composable
fun HistoryTab() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = "Historial",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Historial de stencils",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Próximamente",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ImagePreview(
    originalBitmap: Bitmap?,
    processedBitmap: Bitmap?,
    isLoading: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp)
            )
        } else if (processedBitmap != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Image(
                    bitmap = processedBitmap.asImageBitmap(),
                    contentDescription = "Stencil procesado",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

@Composable
fun FilterSelector(
    selectedFilter: FilterType,
    onFilterSelected: (FilterType) -> Unit
) {
    val filters = FilterType.values()
    
    Column {
        Text(
            "Seleccionar filtro",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filters) { filter ->
                FilterCard(
                    filter = filter,
                    isSelected = filter == selectedFilter,
                    onClick = { onFilterSelected(filter) }
                )
            }
        }
    }
}

@Composable
fun FilterCard(
    filter: FilterType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primary 
            else 
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (filter) {
                    FilterType.CANNY_EDGE -> Icons.Default.Brush
                    FilterType.ADAPTIVE_THRESHOLD -> Icons.Default.Contrast
                    FilterType.SKETCH -> Icons.Default.Edit
                    FilterType.HIGH_CONTRAST -> Icons.Default.Tune
                    FilterType.INVERT -> Icons.Default.InvertColors
                    FilterType.CUSTOM -> Icons.Default.Settings
                },
                contentDescription = null,
                tint = if (isSelected) 
                    MaterialTheme.colorScheme.onPrimary 
                else 
                    MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                filter.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) 
                    MaterialTheme.colorScheme.onPrimary 
                else 
                    MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ActionButtons(
    processedBitmap: Bitmap?,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Button(
            onClick = onReset,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Resetear")
        }
        
        Button(
            onClick = onSave,
            enabled = processedBitmap != null
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Guardar")
        }
        
        Button(
            onClick = onShare,
            enabled = processedBitmap != null
        ) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Compartir")
        }
    }
}

@Composable
fun SettingsPanel(
    settings: ProcessingSettings,
    onSettingsChanged: (ProcessingSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Ajustes de Procesamiento",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            SettingsSlider(
                label = "Brillo",
                value = settings.brightness,
                onValueChange = { newValue ->
                    onSettingsChanged(settings.copy(brightness = newValue))
                },
                valueRange = -100f..100f
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            SettingsSlider(
                label = "Contraste",
                value = settings.contrast,
                onValueChange = { newValue ->
                    onSettingsChanged(settings.copy(contrast = newValue))
                },
                valueRange = 0f..2f
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            SettingsSlider(
                label = "Umbral",
                value = settings.threshold.toFloat(),
                onValueChange = { newValue ->
                    onSettingsChanged(settings.copy(threshold = newValue.toInt()))
                },
                valueRange = 0f..255f
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Invertir colores")
                Switch(
                    checked = settings.invertColors,
                    onCheckedChange = { newValue ->
                        onSettingsChanged(settings.copy(invertColors = newValue))
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Suavizar bordes")
                Switch(
                    checked = settings.smoothEdges,
                    onCheckedChange = { newValue ->
                        onSettingsChanged(settings.copy(smoothEdges = newValue))
                    }
                )
            }
        }
    }
}

@Composable
fun SettingsSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                String.format("%.1f", value),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// Función para guardar bitmap
fun saveBitmap(context: android.content.Context, bitmap: Bitmap?) {
    bitmap?.let {
        try {
            val filename = "stencil_${System.currentTimeMillis()}.png"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), filename)
            FileOutputStream(file).use { out ->
                it.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(context, "Guardado en: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Error al guardar", Toast.LENGTH_SHORT).show()
        }
    }
}

// Función para compartir bitmap
fun shareBitmap(context: android.content.Context, bitmap: Bitmap?) {
    bitmap?.let {
        try {
            val filename = "stencil_${System.currentTimeMillis()}.png"
            val file = File(context.cacheDir, filename)
            FileOutputStream(file).use { out ->
                it.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(shareIntent, "Compartir stencil"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error al compartir", Toast.LENGTH_SHORT).show()
        }
    }
}
