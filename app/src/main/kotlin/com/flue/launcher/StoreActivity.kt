package com.flue.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.Bundle
import android.util.LruCache
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flue.launcher.ui.theme.WatchColors
import com.flue.launcher.ui.theme.WatchLauncherTheme
import com.flue.launcher.watchface.jbwatch.JbWatchFaceStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

// ── Data model ────────────────────────────────────────────────────────────────

data class StoreItem(
    val id: String,
    val name: String,
    val previewUrl: String,
    val downloadCount: Int,
    val isDownloaded: Boolean,
    val uploadDate: String = "",
)

// ── Preview bitmap cache ──────────────────────────────────────────────────────

object StorePreviewCache {
    private const val TARGET_PX = 320

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun get(url: String): Bitmap? = cache.get(url)

    fun load(url: String, reqWidth: Int = TARGET_PX): Bitmap? {
        cache.get(url)?.let { return it }
        val bytes = runCatching {
            val conn = URL(url).openConnection() as HttpsURLConnection
            conn.connectTimeout = 5_000; conn.readTimeout = 10_000
            conn.inputStream.use { it.readBytes() }
        }.getOrNull() ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > reqWidth * 2) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        cache.put(url, bmp)
        return bmp
    }
}

// ── Activity ──────────────────────────────────────────────────────────────────

class StoreActivity : ComponentActivity() {
    companion object {
        private const val DOMAIN = "https://jbwatchface.ai-life.xyz"
        private const val BASE_API = "$DOMAIN/api"
        const val PAGE_SIZE = 20

        fun launch(context: Context) {
            context.startActivity(Intent(context, StoreActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchLauncherTheme {
                StoreScreen(onBack = { finish() }, apiBase = BASE_API, domain = DOMAIN)
            }
        }
    }
}

// ── Main screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreScreen(onBack: () -> Unit, apiBase: String, domain: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<StoreItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(true) }

    // Filters
    var currentCategory by remember { mutableStateOf("") }
    var currentSort by remember { mutableStateOf("") }
    var currentScreenType by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // Detail sheet
    var showDetail by remember { mutableStateOf<StoreItem?>(null) }
    var detailDescription by remember { mutableStateOf("") }
    var detailAuthor by remember { mutableStateOf("") }
    var detailDownloadUrl by remember { mutableStateOf("") }

    // Download state
    var downloading by remember { mutableStateOf(false) }
    var downloadStatus by remember { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current

    fun buildUrl(page: Int): String {
        val sb = StringBuilder("$apiBase/watchfaces?page=$page&limit=${StoreActivity.PAGE_SIZE}")
        if (searchQuery.isNotBlank()) sb.append("&search=${URLEncoder.encode(searchQuery, "UTF-8")}")
        if (currentCategory.isNotBlank()) sb.append("&category=$currentCategory")
        if (currentSort.isNotBlank()) sb.append("&sort=$currentSort")
        if (currentScreenType.isNotBlank()) sb.append("&screen_type=$currentScreenType")
        return sb.toString()
    }

    suspend fun loadPage(reset: Boolean) {
        if (reset) {
            items = emptyList()
            currentPage = 1
            hasMore = true
            isLoading = true
        } else {
            isLoadingMore = true
        }
        error = null
        try {
            val url = buildUrl(if (reset) 1 else currentPage)
            val json = withContext(Dispatchers.IO) { fetchJson(url) }
            val data = json.getJSONArray("data")
            val pagination = json.getJSONObject("pagination")
            hasMore = currentPage < pagination.getInt("total_pages")

            val existingDir = File(context.filesDir, "jbwatch_faces")
            val newItems = (0 until data.length()).map { i ->
                val obj = data.getJSONObject(i)
                val name = obj.getString("name")
                val previewUrl = "$domain${obj.optString("preview_url")}"
                StoreItem(
                    id = obj.getString("id"),
                    name = name,
                    previewUrl = previewUrl,
                    downloadCount = obj.optInt("download_count"),
                    isDownloaded = File(existingDir, name).exists() ||
                            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "watchface/$name.watch").exists(),
                    uploadDate = obj.optString("created_at", "").take(10).replace("-", "/")
                )
            }
            items = if (reset) newItems else items + newItems
            currentPage++
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
            isLoadingMore = false
        }
    }

    LaunchedEffect(Unit) { loadPage(reset = true) }

    // Load categories
    LaunchedEffect(Unit) {
        try {
            val json = withContext(Dispatchers.IO) { fetchJson("$apiBase/categories") }
            val data = json.getJSONArray("data")
            categories = (0 until data.length()).map { i ->
                val obj = data.getJSONObject(i)
                obj.getString("slug") to obj.getString("name")
            }
        } catch (_: Exception) {}
    }

    // Reload on filter change
    LaunchedEffect(searchQuery, currentCategory, currentSort, currentScreenType) {
        loadPage(reset = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("表盘商店") },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = WatchColors.Background,
                    titleContentColor = WatchColors.White,
                    navigationIconContentColor = WatchColors.White
                )
            )
        },
        containerColor = WatchColors.Background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("搜索表盘...", color = WatchColors.TextTertiary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = WatchColors.TextTertiary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = WatchColors.SurfaceGlass,
                    focusedContainerColor = WatchColors.SurfaceGlass,
                    unfocusedTextColor = WatchColors.White,
                    focusedTextColor = WatchColors.White,
                    cursorColor = WatchColors.ActiveCyan,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = WatchColors.ActiveCyan
                )
            )

            // Filter chips row
            FilterChipRow(
                categories = categories,
                currentCategory = currentCategory,
                currentSort = currentSort,
                currentScreenType = currentScreenType,
                onCategoryChange = { currentCategory = it },
                onSortChange = { currentSort = it },
                onScreenTypeChange = { currentScreenType = it }
            )

            // Content
            when {
                isLoading && items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = WatchColors.ActiveCyan)
                    }
                }
                error != null && items.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("加载失败: $error", color = WatchColors.ActiveRed, fontSize = 14.sp)
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            StoreCard(
                                item = item,
                                onClick = {
                                    showDetail = item
                                    // Fetch detail
                                    scope.launch {
                                        try {
                                            val json = withContext(Dispatchers.IO) {
                                                fetchJson("$apiBase/watchfaces/${item.id}")
                                            }
                                            detailDescription = json.optString("description", "")
                                            detailAuthor = json.optJSONObject("uploader")?.optString("username", "") ?: ""
                                            detailDownloadUrl = "$domain${json.optString("download_url")}"
                                        } catch (_: Exception) {}
                                    }
                                }
                            )
                        }
                        // Load more trigger
                        if (hasMore && !isLoading) {
                            item(span = { GridItemSpan(2) }) {
                                LaunchedEffect(Unit) { loadPage(reset = false) }
                                if (isLoadingMore) {
                                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = WatchColors.ActiveCyan, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Detail bottom sheet
    showDetail?.let { item ->
        StoreDetailSheet(
            item = item,
            description = detailDescription,
            author = detailAuthor,
            downloadUrl = detailDownloadUrl,
            downloading = downloading,
            downloadStatus = downloadStatus,
            onDismiss = {
                showDetail = null
                downloading = false
                downloadStatus = null
            },
            onDownload = {
                downloading = true
                downloadStatus = null
                scope.launch {
                    try {
                        val result = downloadWatchface(context, detailDownloadUrl, item.name)
                        downloadStatus = result
                        downloading = false
                        // Refresh list to update isDownloaded
                        loadPage(reset = true)
                    } catch (e: Exception) {
                        downloadStatus = "下载失败: ${e.message}"
                        downloading = false
                    }
                }
            }
        )
    }
}

// ── Filter chips ──────────────────────────────────────────────────────────────

@Composable
private fun FilterChipRow(
    categories: List<Pair<String, String>>,
    currentCategory: String,
    currentSort: String,
    currentScreenType: String,
    onCategoryChange: (String) -> Unit,
    onSortChange: (String) -> Unit,
    onScreenTypeChange: (String) -> Unit,
) {
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = WatchColors.SurfaceGlass,
        labelColor = WatchColors.TextSecondary,
        selectedContainerColor = WatchColors.ActiveCyan.copy(alpha = 0.2f),
        selectedLabelColor = WatchColors.ActiveCyan
    )

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Sort: popular
        FilterChip(
            selected = currentSort == "popular",
            onClick = { onSortChange(if (currentSort == "popular") "" else "popular") },
            label = { Text("最热", fontSize = 11.sp) },
            colors = chipColors
        )

        // Screen type chips
        listOf("round" to "圆形", "square" to "方形", "rectangular" to "矩形").forEach { (type, label) ->
            FilterChip(
                selected = currentScreenType == type,
                onClick = { onScreenTypeChange(if (currentScreenType == type) "" else type) },
                label = { Text(label, fontSize = 11.sp) },
                colors = chipColors
            )
        }

        // Category chips (dynamic)
        categories.forEach { (slug, name) ->
            FilterChip(
                selected = currentCategory == slug,
                onClick = { onCategoryChange(if (currentCategory == slug) "" else slug) },
                label = { Text(name, fontSize = 11.sp) },
                colors = chipColors
            )
        }
    }
}

// ── Store card ────────────────────────────────────────────────────────────────

@Composable
private fun StoreCard(item: StoreItem, onClick: () -> Unit) {
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = WatchColors.SurfaceGlass
        )
    ) {
        Column {
            // Preview image
            Box(
                modifier = Modifier.fillMaxWidth().height(140.dp).background(WatchColors.Background),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = remember(item.previewUrl) { StorePreviewCache.load(item.previewUrl) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(item.name.take(8), color = WatchColors.TextTertiary, fontSize = 14.sp)
                }
                // Downloaded badge
                if (item.isDownloaded) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "已下载",
                        tint = WatchColors.ActiveGreen,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    item.name,
                    color = WatchColors.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${item.downloadCount} ⬇",
                    color = WatchColors.TextTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

// ── Detail bottom sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreDetailSheet(
    item: StoreItem,
    description: String,
    author: String,
    downloadUrl: String,
    downloading: Boolean,
    downloadStatus: String?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = WatchColors.Background,
        contentColor = WatchColors.White
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Preview
            val bitmap = remember(item.previewUrl) { StorePreviewCache.load(item.previewUrl, 512) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.FillWidth
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text(item.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = WatchColors.White)

            if (author.isNotBlank()) {
                Text("by $author", fontSize = 13.sp, color = WatchColors.ActiveCyan)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${item.downloadCount} 次下载", fontSize = 12.sp, color = WatchColors.TextTertiary)
                if (item.uploadDate.isNotBlank()) {
                    Text("上传于 ${item.uploadDate}", fontSize = 12.sp, color = WatchColors.TextTertiary)
                }
            }

            if (description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(description, fontSize = 14.sp, color = WatchColors.TextSecondary)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Download status
            if (downloadStatus != null) {
                val isSuccess = downloadStatus.startsWith("✓")
                Text(
                    downloadStatus,
                    color = if (isSuccess) WatchColors.ActiveGreen else WatchColors.ActiveRed,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Download button
            if (!downloading && downloadStatus == null) {
                TextButton(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        containerColor = WatchColors.ActiveGreen,
                        contentColor = WatchColors.Black
                    )
                ) {
                    Text("下载并安装", fontWeight = FontWeight.Bold)
                }
            } else if (downloading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = WatchColors.ActiveCyan,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("下载中...", color = WatchColors.TextSecondary, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Download logic ────────────────────────────────────────────────────────────

private suspend fun downloadWatchface(context: Context, downloadUrl: String, fileName: String): String {
    return withContext(Dispatchers.IO) {
        // Step 1: Download to /sdcard/Download/watchface/
        val watchfaceDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "watchface")
        watchfaceDir.mkdirs()
        val destFile = File(watchfaceDir, "$fileName.watch")

        val tmp = File.createTempFile("wf_dl_", ".watch", context.cacheDir)
        try {
            URL(downloadUrl).openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp.copyTo(destFile, overwrite = true)
        } finally {
            tmp.delete()
        }

        // Step 2: Import from the downloaded file
        val descriptor = JbWatchFaceStorage.importArchiveFile(context, destFile)
        "✓ ${descriptor.displayName}"
    }
}

// ── HTTP helper ───────────────────────────────────────────────────────────────

private fun fetchJson(url: String): JSONObject {
    val conn = URL(url).openConnection() as HttpsURLConnection
    conn.connectTimeout = 10_000; conn.readTimeout = 15_000
    return conn.inputStream.use { JSONObject(it.reader().readText()) }
}

// ── Icon alias (Compose Material Icons import) ────────────────────────────────

@Composable
private fun Icon(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    tint: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Icon(imageVector = imageVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}
