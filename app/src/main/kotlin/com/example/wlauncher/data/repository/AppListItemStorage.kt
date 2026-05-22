package com.flue.launcher.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class StoredAppListShortcut(
    val id: String,
    val label: String,
    val packageName: String,
    val intentUri: String,
    val iconPath: String? = null,
    val pinnedShortcutId: String? = null
) {
    val componentKey: String = "shortcut:$id"
}

data class StoredAppListFolder(
    val id: String,
    val name: String,
    val itemKeys: List<String>
) {
    val componentKey: String = "folder:$id"
}

class AppListItemStorage(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_list_items", Context.MODE_PRIVATE)
    private val iconDir = File(context.filesDir, "app_list_shortcut_icons").apply { mkdirs() }

    @Synchronized
    fun loadShortcuts(): List<StoredAppListShortcut> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val label = item.optString("label").takeIf(String::isNotBlank) ?: continue
                    val packageName = item.optString("packageName")
                    val intentUri = item.optString("intentUri").takeIf(String::isNotBlank) ?: continue
                    add(
                        StoredAppListShortcut(
                            id = id,
                            label = label,
                            packageName = packageName,
                            intentUri = intentUri,
                            iconPath = item.optString("iconPath").takeIf(String::isNotBlank),
                            pinnedShortcutId = item.optString("pinnedShortcutId").takeIf(String::isNotBlank)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun loadFolders(): List<StoredAppListFolder> {
        val raw = prefs.getString(KEY_FOLDERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val keys = item.optJSONArray("itemKeys") ?: JSONArray()
                    val itemKeys = buildList {
                        for (keyIndex in 0 until keys.length()) {
                            keys.optString(keyIndex).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }.distinct()
                    if (itemKeys.isEmpty()) continue
                    add(
                        StoredAppListFolder(
                            id = id,
                            name = item.optString("name").takeIf(String::isNotBlank) ?: "文件夹",
                            itemKeys = itemKeys
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun addShortcut(
        label: String,
        packageName: String,
        intentUri: String,
        icon: Bitmap?,
        pinnedShortcutId: String? = null
    ): StoredAppListShortcut {
        val shortcuts = loadShortcuts().toMutableList()
        val normalizedPinnedShortcutId = pinnedShortcutId?.takeIf { it.isNotBlank() }
        val duplicate = shortcuts.firstOrNull { shortcut ->
            if (normalizedPinnedShortcutId != null) {
                shortcut.packageName == packageName && shortcut.pinnedShortcutId == normalizedPinnedShortcutId
            } else {
                shortcut.pinnedShortcutId == null && shortcut.intentUri == intentUri
            }
        }
        if (duplicate != null) return duplicate
        val id = UUID.randomUUID().toString()
        val iconPath = icon?.let { bitmap ->
            val output = File(iconDir, "$id.png")
            runCatching {
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                output.absolutePath
            }.getOrNull()
        }
        val shortcut = StoredAppListShortcut(
            id = id,
            label = label.ifBlank { "快捷方式" },
            packageName = packageName,
            intentUri = intentUri,
            iconPath = iconPath,
            pinnedShortcutId = normalizedPinnedShortcutId
        )
        shortcuts += shortcut
        saveShortcuts(shortcuts)
        return shortcut
    }

    @Synchronized
    fun removeShortcut(componentKey: String): Boolean {
        val id = componentKey.removePrefix("shortcut:")
        val shortcuts = loadShortcuts().toMutableList()
        val removed = shortcuts.removeAll { it.id == id }
        if (removed) {
            saveShortcuts(shortcuts)
            File(iconDir, "$id.png").delete()
            val folders = loadFolders().mapNotNull { folder ->
                val nextKeys = folder.itemKeys.filterNot { it == componentKey }
                when {
                    nextKeys.isEmpty() -> null
                    nextKeys == folder.itemKeys -> folder
                    else -> folder.copy(itemKeys = nextKeys)
                }
            }
            saveFolders(folders)
        }
        return removed
    }

    @Synchronized
    fun createFolder(sourceKey: String, targetKey: String, targetName: String): StoredAppListFolder? {
        if (sourceKey == targetKey) return null
        val folders = loadFolders().toMutableList()
        val sourceFolderIndex = folders.indexOfFirst { it.componentKey == sourceKey }
        val targetFolderIndex = folders.indexOfFirst { it.componentKey == targetKey }
        val targetFolder = folders.getOrNull(targetFolderIndex)
        if (targetFolder != null) {
            val sourceKeys = if (sourceFolderIndex >= 0) folders[sourceFolderIndex].itemKeys else listOf(sourceKey)
            folders[targetFolderIndex] = targetFolder.copy(
                itemKeys = (targetFolder.itemKeys + sourceKeys).distinct()
            )
            if (sourceFolderIndex >= 0 && sourceFolderIndex != targetFolderIndex) {
                folders.removeAt(sourceFolderIndex)
            }
            saveFolders(folders)
            return folders.firstOrNull { it.componentKey == targetFolder.componentKey }
        }

        if (sourceFolderIndex >= 0) {
            val folder = folders[sourceFolderIndex]
            folders[sourceFolderIndex] = folder.copy(
                itemKeys = (folder.itemKeys + targetKey).distinct()
            )
            saveFolders(folders)
            return folders[sourceFolderIndex]
        }

        val folder = StoredAppListFolder(
            id = UUID.randomUUID().toString(),
            name = targetName.ifBlank { "文件夹" },
            itemKeys = listOf(targetKey, sourceKey).distinct()
        )
        folders += folder
        saveFolders(folders)
        return folder
    }

    @Synchronized
    fun renameFolder(folderKey: String, name: String): Boolean {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return false
        folders[index] = folders[index].copy(name = name.ifBlank { "文件夹" })
        saveFolders(folders)
        return true
    }

    @Synchronized
    fun dissolveFolder(folderKey: String): List<String> {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return emptyList()
        val keys = folders.removeAt(index).itemKeys
        saveFolders(folders)
        return keys
    }

    @Synchronized
    fun moveItemOutOfFolder(folderKey: String, itemKey: String): List<String> {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return emptyList()
        val folder = folders[index]
        val nextKeys = folder.itemKeys.filterNot { it == itemKey }
        if (nextKeys.size <= 1) {
            folders.removeAt(index)
            saveFolders(folders)
            return nextKeys
        }
        folders[index] = folder.copy(itemKeys = nextKeys)
        saveFolders(folders)
        return nextKeys
    }

    @Synchronized
    fun addItemsToFolder(folderKey: String, itemKeys: List<String>): List<String> {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return emptyList()
        val folder = folders[index]
        val additions = itemKeys
            .filter { key -> key != folderKey && key !in folder.itemKeys }
            .distinct()
        if (additions.isEmpty()) return folder.itemKeys
        val nextKeys = folder.itemKeys + additions
        folders[index] = folder.copy(itemKeys = nextKeys)
        saveFolders(folders)
        return nextKeys
    }

    @Synchronized
    fun setFolderItems(folderKey: String, itemKeys: List<String>): List<String> {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return emptyList()
        val nextKeys = itemKeys
            .filter { key -> key != folderKey }
            .distinct()
        val selectedKeySet = nextKeys.toSet()
        val nextFolders = folders.mapIndexedNotNull { folderIndex, candidate ->
            if (folderIndex == index) {
                if (nextKeys.size <= 1) null else candidate.copy(itemKeys = nextKeys)
            } else {
                val keptKeys = candidate.itemKeys.filterNot { it in selectedKeySet }
                when {
                    keptKeys == candidate.itemKeys -> candidate
                    keptKeys.size <= 1 -> null
                    else -> candidate.copy(itemKeys = keptKeys)
                }
            }
        }
        saveFolders(nextFolders)
        return nextKeys
    }

    @Synchronized
    fun reorderFolderItems(folderKey: String, orderedKeys: List<String>): List<String> {
        val folders = loadFolders().toMutableList()
        val index = folders.indexOfFirst { it.componentKey == folderKey }
        if (index < 0) return emptyList()
        val folder = folders[index]
        val existing = folder.itemKeys
        val nextKeys = (
            orderedKeys.filter { it in existing } +
                existing.filterNot { it in orderedKeys }
            ).distinct()
        if (nextKeys == existing) return existing
        folders[index] = folder.copy(itemKeys = nextKeys)
        saveFolders(folders)
        return nextKeys
    }

    fun loadShortcutIcon(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    private fun saveShortcuts(shortcuts: List<StoredAppListShortcut>) {
        val array = JSONArray()
        shortcuts.forEach { shortcut ->
            array.put(JSONObject().apply {
                put("id", shortcut.id)
                put("label", shortcut.label)
                put("packageName", shortcut.packageName)
                put("intentUri", shortcut.intentUri)
                shortcut.iconPath?.let { put("iconPath", it) }
                shortcut.pinnedShortcutId?.let { put("pinnedShortcutId", it) }
            })
        }
        prefs.edit().putString(KEY_SHORTCUTS, array.toString()).apply()
    }

    private fun saveFolders(folders: List<StoredAppListFolder>) {
        val array = JSONArray()
        folders.forEach { folder ->
            array.put(JSONObject().apply {
                put("id", folder.id)
                put("name", folder.name)
                put("itemKeys", JSONArray(folder.itemKeys))
            })
        }
        prefs.edit().putString(KEY_FOLDERS, array.toString()).apply()
    }

    private companion object {
        private const val KEY_SHORTCUTS = "shortcuts"
        private const val KEY_FOLDERS = "folders"
    }
}
