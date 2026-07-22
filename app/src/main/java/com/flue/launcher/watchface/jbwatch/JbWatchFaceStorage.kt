package com.flue.launcher.watchface.jbwatch

import android.content.Context
import android.net.Uri
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceType
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

const val JBWATCH_ID_PREFIX = "jbwatch:"

data class JbWatchMetadata(
    val id: String,
    val title: String,
    val summary: String,
    val rootDir: File,
    val previewFileName: String? = null
)

object JbWatchFaceStorage {
    private const val ROOT_FOLDER = "jbwatch_faces"

    private fun rootDir(context: Context): File {
        return File(context.filesDir, ROOT_FOLDER).apply { mkdirs() }
    }

    fun importArchive(context: Context, uri: Uri): LunchWatchFaceDescriptor {
        val tempZip = File.createTempFile("jbwatch_import_", ".watch", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempZip).use { output -> input.copyTo(output) }
            } ?: error("无法读取 .watch 表盘归档")
            return importArchiveFile(context, tempZip)
        } finally {
            tempZip.delete()
        }
    }

    fun importArchiveFile(context: Context, archive: File): LunchWatchFaceDescriptor {
        // Step 1: Extract ZIP to temp dir to locate watch.xml / watch.pxml
        val tempExtract = File(context.cacheDir, "jbwatch_tmp_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            extractArchive(archive, tempExtract)
            val watchRoot = findWatchRoot(tempExtract)
                ?: error("缺少 watch.xml / watch.pxml — ZIP 可能损坏或不是有效表盘")
            val metadata = parseMetadata(watchRoot)

            // Step 2: Copy to final dir, preserving the sub-directory structure
            // that contains watch.xml (so fonts/, images/ are siblings of watch.xml)
            val finalDir = File(rootDir(context), sanitizeId(metadata.id))
            if (finalDir.exists()) finalDir.deleteRecursively()
            finalDir.mkdirs()

            // If watchRoot is nested (e.g. ZIP has MyWatch/watch.xml), figure out
            // the relative offset so the final dir mirrors the same layout.
            val relOffset = watchRoot.relativeTo(tempExtract).invariantSeparatorsPath
            val targetBase = if (relOffset == "." || relOffset.isEmpty()) finalDir
                             else File(finalDir, relOffset).apply { mkdirs() }

            watchRoot.walkTopDown().forEach { src ->
                if (src.isFile) {
                    val rel = src.relativeTo(watchRoot)
                    val dst = File(targetBase, rel.path)
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                }
            }

            // If assets landed in a subdirectory, move everything up to finalDir
            // so sourceDirPath points to the top-level (easier cleanup, cleaner path).
            if (targetBase != finalDir) {
                targetBase.listFiles()?.forEach { child ->
                    child.renameTo(File(finalDir, child.name))
                }
                targetBase.deleteRecursively()
            }

            val stored = parseMetadata(finalDir)
            Log.i(TAG, "Imported '${stored.title}' → ${finalDir.absolutePath}")
            return stored.toDescriptor()
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            throw e
        } finally {
            tempExtract.deleteRecursively()
        }
    }

    fun delete(context: Context, descriptor: LunchWatchFaceDescriptor): Boolean {
        val dir = descriptor.sourceDirPath?.let(::File) ?: return false
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    fun scan(context: Context): List<LunchWatchFaceDescriptor> {
        fixBrokenImports(context)
        migrateExternalToInternal(context)
        val results = mutableListOf<LunchWatchFaceDescriptor>()
        val seen = mutableSetOf<String>()

        // Scan /sdcard/jbwatch/ — faces still on external storage (migration
        // may not have moved them if the copy failed). These paths can cause
        // cc.FileUtils:isFileExist failures on Android 11+ scoped storage,
        // so they are deprioritised in favour of the internal copies created
        // by migrateExternalToInternal().
        val sdcard = android.os.Environment.getExternalStorageDirectory()
        val externalRoot = File(sdcard, "jbwatch")
        if (externalRoot.isDirectory) {
            externalRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                runCatching { parseMetadata(dir).toDescriptor() }.getOrNull()?.let { desc ->
                    if (seen.add(desc.sourceDirPath ?: "")) results.add(desc)
                }
            }
        }

        // Scan internal storage — preferred: cc.FileUtils can always read
        // from context.filesDir without scoped-storage restrictions.
        val internalRoot = File(context.filesDir, ROOT_FOLDER)
        if (internalRoot.isDirectory) {
            internalRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                runCatching { parseMetadata(dir).toDescriptor() }.getOrNull()?.let { desc ->
                    if (seen.add(desc.sourceDirPath ?: "")) results.add(desc)
                }
            }
        }

        return results.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    /**
     * Copy watchfaces from /sdcard/jbwatch/ to internal storage so that
     * cc.FileUtils:isFileExist() can reliably find watch.xml on Android 11+
     * (scoped storage blocks external-path access from native code).
     * Already-migrated faces (same ID exists internally) are skipped.
     */
    private fun migrateExternalToInternal(context: Context) {
        val sdcard = android.os.Environment.getExternalStorageDirectory()
        val externalRoot = File(sdcard, "jbwatch")
        if (!externalRoot.isDirectory) return

        val internalRoot = File(context.filesDir, ROOT_FOLDER).apply { mkdirs() }

        externalRoot.listFiles()?.filter { it.isDirectory }?.forEach { extDir ->
            val hasWatch = File(extDir, "watch.xml").isFile || File(extDir, "watch.pxml").isFile
            if (!hasWatch) return@forEach

            val metadata = runCatching { parseMetadata(extDir) }.getOrNull() ?: return@forEach
            val targetDir = File(internalRoot, sanitizeId(metadata.id))

            // Skip if already migrated
            if (targetDir.isDirectory && (File(targetDir, "watch.xml").isFile || File(targetDir, "watch.pxml").isFile)) {
                return@forEach
            }

            try {
                targetDir.deleteRecursively()
                targetDir.mkdirs()
                extDir.copyRecursively(targetDir, overwrite = true)
                // Verify the copy succeeded before removing the external source
                val copied = File(targetDir, "watch.xml").isFile || File(targetDir, "watch.pxml").isFile
                if (copied) {
                    extDir.deleteRecursively()
                    Log.i(TAG, "Migrated '${metadata.title}' external → internal: ${targetDir.absolutePath}")
                } else {
                    Log.w(TAG, "Migration copy verification failed for '${metadata.title}'")
                    targetDir.deleteRecursively()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Migration failed for '${metadata.title}': ${e.message}")
                targetDir.deleteRecursively()
            }
        }
    }

    /**
     * Fix watchfaces imported by old code where sourceDirPath pointed to a
     * temporary directory (jbwatch_tmp_*) that was later deleted.
     * Renames jbwatch_tmp_* dirs to their watch.xml title, deletes broken ones.
     */
    private fun fixBrokenImports(context: Context) {
        val root = File(context.filesDir, ROOT_FOLDER)
        if (!root.isDirectory) return
        root.listFiles()?.filter { it.isDirectory && it.name.startsWith("jbwatch_tmp_") }?.forEach { dir ->
            val hasWatchXml = File(dir, "watch.xml").isFile || File(dir, "watch.pxml").isFile
            if (!hasWatchXml) {
                Log.w(TAG, "Removing broken temp dir: ${dir.name}")
                dir.deleteRecursively()
                return@forEach
            }
            // Use watch.xml name attribute as the new directory name
            val metadata = runCatching { parseMetadata(dir) }.getOrNull()
            val title = metadata?.title?.takeIf { it.isNotBlank() && it != dir.name }
            val newName = sanitizeId(title ?: dir.name.replace("jbwatch_tmp_", "watchface_"))
            if (newName == dir.name) return@forEach  // nothing to fix
            val newDir = File(root, newName)
            if (newDir.exists() && newDir != dir) newDir.deleteRecursively()
            // renameTo fails silently across mount points (cache → files).
            // Fall back to copy + delete when rename doesn't stick.
            if (dir.renameTo(newDir)) {
                Log.i(TAG, "Fixed broken import: ${dir.name} → $newName")
            } else if (dir.copyRecursively(newDir, overwrite = true)) {
                dir.deleteRecursively()
                Log.i(TAG, "Fixed broken import (copy): ${dir.name} → $newName")
            } else {
                Log.w(TAG, "Failed to fix broken import: ${dir.name}")
            }
        }
    }

    private fun parseMetadata(rootDir: File): JbWatchMetadata {
        val watchXml = File(rootDir, "watch.xml")
        val watchPxml = File(rootDir, "watch.pxml")
        if (!watchXml.isFile && !watchPxml.isFile) error("缺少 watch.xml / watch.pxml")
        // 优先用 watch.xml 解析元数据；若只有 pxml，从文件名推断
        val title: String
        val features: String
        if (watchXml.isFile) {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(watchXml)
            val root = doc.documentElement ?: error("watch.xml 无根节点")
            title = root.getAttribute("name").trim().ifBlank { rootDir.name }
            features = root.getAttribute("features").trim()
        } else {
            // watch.pxml 加密表盘：从文件名推断标题
            title = rootDir.name
            features = ""
        }
        val summary = buildString {
            append(".watch 表盘")
            if (features.isNotBlank()) {
                append(" · ")
                append(features)
            }
        }
        val preview = listOf("preview.jpg", "preview_dim.jpg")
            .firstOrNull { name -> File(rootDir, name).isFile }
        val id = rootDir.name.ifBlank { title }
        return JbWatchMetadata(id = id, title = title, summary = summary, rootDir = rootDir, previewFileName = preview)
    }

    private fun JbWatchMetadata.toDescriptor(): LunchWatchFaceDescriptor {
        return LunchWatchFaceDescriptor(
            id = "$JBWATCH_ID_PREFIX$id",
            type = LunchWatchFaceType.JBWATCH,
            displayName = title,
            summary = summary,
            previewFilePath = previewFileName?.let { File(rootDir, it).absolutePath },
            sourceDirPath = rootDir.absolutePath,
            watchFaceName = id
        )
    }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "jbwatch_${System.currentTimeMillis()}" }
    }

    private const val TAG = "JbWatchFaceStorage"

    private fun extractArchive(zipFile: File, targetRoot: File) {
        Log.i(TAG, "Extracting ${zipFile.absolutePath} → ${targetRoot.absolutePath}")
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                runCatching { copyEntry(zip, entry, targetRoot) }
                    .onFailure { e ->
                        Log.w(TAG, "Failed to extract entry '${entry.name}': ${e.message}", e)
                    }
            }
        }
    }

    private fun findWatchRoot(rootDir: File): File? {
        if (File(rootDir, "watch.xml").isFile || File(rootDir, "watch.pxml").isFile) return rootDir
        return rootDir.walkTopDown()
            .filter { it.isDirectory && (File(it, "watch.xml").isFile || File(it, "watch.pxml").isFile) }
            .minByOrNull { dir -> dir.relativeTo(rootDir).invariantSeparatorsPath.count { it == '/' } }
    }

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, targetRoot: File) {
        val rawName = entry.name
        // Some ZIP tools URL-encode special chars in entry names (e.g. + → %2B).
        // Try the raw name first; if it contains '%' hex sequences, also decode.
        // Additionally, some ZIPs have entry names that differ from the internal
        // index name; fall back to getEntry() lookup by name if the entry-object
        // lookup fails.
        val names = mutableListOf(rawName)
        if (rawName.contains('%')) {
            try {
                val decoded = URLDecoder.decode(rawName, "UTF-8")
                if (decoded != rawName) names.add(0, decoded) // prefer decoded
            } catch (_: Exception) {}
        }
        // Also try the raw name as-is via getEntry() as a last resort
        // (handles encoding mismatches where the ZipEntry object references
        // a different internal name than entry.name reports).
        names.add(rawName + " [via getEntry]") // sentinel for the fallback path

        var success = false
        var lastError: Exception? = null
        for (name in names) {
            try {
                if (entry.isDirectory) {
                    outFileForName(name, targetRoot).mkdirs()
                    Log.v(TAG, "  DIR: $name")
                    success = true
                    break
                }
                val outFile = outFileForName(name, targetRoot)
                outFile.parentFile?.mkdirs()
                val input = if (name.endsWith(" [via getEntry]")) {
                    // entry-object lookup failed — retry by name
                    val resolved = zip.getEntry(rawName)
                        ?: error("Entry not found in ZIP by name: $rawName")
                    Log.v(TAG, "  resolved via getEntry: $rawName → ${resolved.name}")
                    zip.getInputStream(resolved)
                } else {
                    zip.getInputStream(entry)
                }
                input.use { src ->
                    FileOutputStream(outFile).use { dst -> src.copyTo(dst) }
                }
                Log.v(TAG, "  FILE: $name (${entry.size} bytes)")
                success = true
                break
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (!success) {
            throw lastError ?: RuntimeException("Failed to extract: $rawName")
        }
    }

    /** Resolve an output file for a name, stripping the sentinel suffix. */
    private fun outFileForName(name: String, targetRoot: File): File {
        val clean = name.replace(" [via getEntry]", "")
            .replace('\\', '/')
        return File(targetRoot, clean)
    }
}
