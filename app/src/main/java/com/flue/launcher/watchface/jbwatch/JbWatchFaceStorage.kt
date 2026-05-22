package com.flue.launcher.watchface.jbwatch

import android.content.Context
import android.net.Uri
import com.flue.launcher.watchface.LunchWatchFaceDescriptor
import com.flue.launcher.watchface.LunchWatchFaceType
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

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

    fun rootDir(context: Context): File {
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
        val tempExtract = File(context.cacheDir, "jbwatch_extract_${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            extractArchive(archive, tempExtract)
            val watchRoot = findWatchRoot(tempExtract)
                ?: error("缺少 watch.xml / watch.pxml")
            runCatching { JbWatchParser.parse(watchRoot) }
                .getOrElse { throw IllegalArgumentException("JB 表盘解析失败: ${it.message ?: it.javaClass.simpleName}", it) }
            val metadata = parseMetadata(watchRoot)
            val finalDir = File(rootDir(context), sanitizeId(metadata.id))
            if (finalDir.exists()) {
                finalDir.deleteRecursively()
            }
            watchRoot.copyRecursively(finalDir, overwrite = true)
            val stored = parseMetadata(finalDir)
            return stored.toDescriptor()
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
        val root = rootDir(context)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.mapNotNull { dir ->
            runCatching { parseMetadata(dir).toDescriptor() }.getOrNull()
        }.sortedBy { it.displayName.lowercase(Locale.ROOT) }
    }

    fun hasJbWatchStructure(rootDir: File): Boolean {
        val watchXml = File(rootDir, "watch.xml")
        val watchPxml = File(rootDir, "watch.pxml")
        return watchXml.isFile && watchPxml.isFile
    }

    private fun parseMetadata(rootDir: File): JbWatchMetadata {
        require(hasJbWatchStructure(rootDir)) { "缺少 watch.xml / watch.pxml" }
        val watchXml = File(rootDir, "watch.xml")
        val document = newSecureDocumentBuilderFactory()
            .newDocumentBuilder()
            .parse(watchXml)
        val root = document.documentElement ?: error("watch.xml 无根节点")
        val title = root.getAttribute("name").trim().ifBlank { rootDir.name }
        val features = root.getAttribute("features").trim()
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
        return JbWatchMetadata(
            id = id,
            title = title,
            summary = summary,
            rootDir = rootDir,
            previewFileName = preview
        )
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

    private fun newSecureDocumentBuilderFactory(): DocumentBuilderFactory {
        return DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { isXIncludeAware = false }
            runCatching { isExpandEntityReferences = false }
        }
    }

    private fun extractArchive(zipFile: File, targetRoot: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                copyEntry(zip, entry, targetRoot)
            }
        }
    }

    private fun findWatchRoot(rootDir: File): File? {
        if (hasJbWatchStructure(rootDir)) return rootDir
        return rootDir
            .walkTopDown()
            .filter { it.isDirectory && hasJbWatchStructure(it) }
            .minByOrNull { dir ->
                dir.relativeTo(rootDir).invariantSeparatorsPath.count { it == '/' }
            }
    }

    private fun copyEntry(zip: ZipFile, entry: ZipEntry, targetRoot: File) {
        val normalized = entry.name.replace('\\', '/')
        val outFile = File(targetRoot, normalized)
        val canonicalTarget = outFile.canonicalFile
        val canonicalRoot = targetRoot.canonicalFile
        val insideRoot = canonicalTarget.path == canonicalRoot.path ||
            canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)
        require(insideRoot) { "归档路径越界: ${entry.name}" }
        if (entry.isDirectory) {
            canonicalTarget.mkdirs()
            return
        }
        canonicalTarget.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
            FileOutputStream(canonicalTarget).use { output -> input.copyTo(output) }
        }
    }
}
