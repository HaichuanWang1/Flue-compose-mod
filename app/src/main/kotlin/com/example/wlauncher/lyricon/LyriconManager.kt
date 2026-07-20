package com.flue.launcher.lyricon

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo

/**
 * 管理 Lyricon 歌词订阅端生命周期，提供当前歌词文本。
 */
class LyriconManager(context: Context) {
    private val subscriber: LyriconSubscriber? = runCatching {
        LyriconFactory.createSubscriber(context.applicationContext)
    }.getOrNull()

    /** 当前歌词文本，null 表示无歌词 */
    var currentLyric: String? by mutableStateOf(null)
        private set

    /** 当前歌曲的带时间轴歌词行列表，用于按播放位置定位 */
    private var currentLyricLines: List<RichLyricLine> = emptyList()

    private val listener = object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {}
        override fun onSongChanged(song: io.github.proify.lyricon.lyric.model.Song?) {
            currentLyricLines = song?.lyrics.orEmpty()
            // 不在 onSongChanged 中设置 currentLyric，
            // 由 onPositionChanged / onReceiveText 根据实际播放位置更新
            if (currentLyricLines.isEmpty()) {
                currentLyric = null
            }
        }
        override fun onPlaybackStateChanged(isPlaying: Boolean) {}
        override fun onSeekTo(position: Long) {}
        override fun onPositionChanged(position: Long) {
            if (currentLyricLines.isEmpty()) return
            val line = currentLyricLines.firstOrNull { line ->
                position >= line.begin && position < line.end
            }
            currentLyric = line?.text?.takeIf { it.isNotBlank() }
        }
        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {}
        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {}
        override fun onReceiveText(text: String?) {
            currentLyric = text?.takeIf { it.isNotBlank() }
        }
    }

    fun register() {
        subscriber?.subscribeActivePlayer(listener)
        subscriber?.register()
    }

    fun destroy() {
        subscriber?.unsubscribeActivePlayer(listener)
        subscriber?.unregister()
        subscriber?.destroy()
    }
}

/**
 * Composable 中使用 Lyricon，自动管理生命周期。
 */
@Composable
fun rememberLyriconManager(): LyriconManager {
    val context = LocalContext.current
    val manager = remember { LyriconManager(context) }
    DisposableEffect(Unit) {
        manager.register()
        onDispose { manager.destroy() }
    }
    return manager
}
