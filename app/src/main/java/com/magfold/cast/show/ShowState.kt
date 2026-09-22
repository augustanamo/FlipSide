package com.magfold.cast.show

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.magfold.cast.media.MediaDecoder
import com.magfold.cast.media.MediaEntry
import com.magfold.cast.media.SelectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 已经解码好的当前帧。带 key 是为了丢弃过期的异步结果，避免快速切换时串图。 */
data class StageShot(val key: String, val bitmap: Bitmap)

/**
 * 展窗的状态中枢：素材队列、当前序号、适配方式、轮播、投屏与后台开关，以及唯一的播放器。
 *
 * 内外屏都 collect 这里的 StateFlow，所以内屏按一下「下一张」，外屏立刻跟着换。
 *
 * **播放器只有一个，且同一时刻只挂在一个 Surface 上。**
 * ExoPlayer 不支持两个视频视图同时接同一个实例；本工程靠 UI 层的互斥分支保证
 * （外屏会话开着时内屏只画控制台或预览栏，折起/不支持双屏时内屏才自己挂播放器）。
 *
 * 关于「什么时候该播」：闸门是**投屏态或预览态**，不是前台态。
 * 这个工具不是预览器 —— 它挂在桌上、锁在柜里，应用退到后台恰恰是它的正常工作状态，
 * 所以切出去不能停播。（早先版本用 Activity 的 onStart/onStop 当闸门，
 * 那是把它当成"临时预览"的错，已废弃。）
 *
 * 关于**轮播**（[intervalSec]）的四条约定，都是为了让行为可预期：
 *  1. 计时以「当前这件是什么时候上屏的」为准（[lastSwitch]），所以手动换片会**重新计时**，
 *     不会出现"刚翻过去半秒就被自动换走"。
 *  2. 只在**展出或预览中**、**没被暂停**、且素材多于一件时推进。控制台开着但没展出时它不动。
 *  3. **视频默认「播完再切」**（[videoFullPlay]，默认开）：轮播间隔对视频不生效，
 *     它一直放到尾由 `STATE_ENDED` 触发换片。理由很实际 —— 30 秒的间隔把 2 分钟的视频
 *     从中间切走，看着像坏了。关掉这个开关才回到"按间隔硬切"。
 *  4. 为此，轮播开着且素材多于一件时必须把重复模式从 REPEAT_MODE_ONE 改成 OFF：
 *     单曲循环的播放器永远不会报告"放完了"，视频也就永远切不走 ——
 *     这正是"轮播一开视频就卡住不换"的成因。
 */
class ShowState(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = SelectionStore(context)
    private val prefs = CastPrefs(context)

    /** 循环 + 静音起步：这是橱窗式展示，不是播放器。 */
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = if (prefs.muted) 0f else 1f
        playWhenReady = false
    }

    // ---- 素材队列 ----

    private val _entries = MutableStateFlow<List<MediaEntry>>(emptyList())
    val entries: StateFlow<List<MediaEntry>> = _entries.asStateFlow()

    private val _index = MutableStateFlow(0)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _current = MutableStateFlow<MediaEntry?>(null)
    val current: StateFlow<MediaEntry?> = _current.asStateFlow()

    private val _shot = MutableStateFlow<StageShot?>(null)
    val shot: StateFlow<StageShot?> = _shot.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ---- 展示偏好（全部落盘，见 CastPrefs） ----

    /** true = 填满（裁切多余部分），false = 适应（留黑边）。 */
    private val _fill = MutableStateFlow(prefs.fill)
    val fill: StateFlow<Boolean> = _fill.asStateFlow()

    /** 是否投到外屏。不支持双屏的机型上，这个开关等于「内屏全屏展示」。 */
    private val _casting = MutableStateFlow(prefs.casting)
    val casting: StateFlow<Boolean> = _casting.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _muted = MutableStateFlow(prefs.muted)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** 轮播间隔（秒）。0 = 关。 */
    private val _intervalSec = MutableStateFlow(prefs.intervalSec)
    val intervalSec: StateFlow<Int> = _intervalSec.asStateFlow()

    /** 视频是否「播完再切」。只在轮播开着时有意义。 */
    private val _videoFullPlay = MutableStateFlow(prefs.videoFullPlay)
    val videoFullPlay: StateFlow<Boolean> = _videoFullPlay.asStateFlow()

    /** 内屏分栏预览：一侧按外屏长宽比纯看，另一侧继续设置。 */
    private val _previewing = MutableStateFlow(prefs.previewing)
    val previewing: StateFlow<Boolean> = _previewing.asStateFlow()

    /** 预览窗格在哪一侧：0 = 左，1 = 右。 */
    private val _previewSide = MutableStateFlow(prefs.previewSide)
    val previewSide: StateFlow<Int> = _previewSide.asStateFlow()

    /** 应用不可见时是否维持进程存活（前台服务）。 */
    private val _keepAlive = MutableStateFlow(prefs.keepAlive)
    val keepAlive: StateFlow<Boolean> = _keepAlive.asStateFlow()

    /** 开机后是否自动回到展出状态。生效还需要系统「显示在其他应用上层」权限。 */
    private val _bootStart = MutableStateFlow(prefs.bootStart)
    val bootStart: StateFlow<Boolean> = _bootStart.asStateFlow()

    /**
     * 展出期间屏幕是否保持常亮。
     *
     * 这里只存开关，**真正的锁在保活服务那边**（`serve/ScreenWake`）——
     * 屏幕锁必须由活着的进程持有，而应用不可见时唯一确定活着的是那个服务。
     * 界面改这个开关时会重投一次服务，服务自己按落盘值对齐。
     */
    private val _screenOn = MutableStateFlow(prefs.screenOn)
    val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

    /** 当前这件是何时上屏的（单调时钟毫秒）。轮播计时的基准。 */
    private var lastSwitch = SystemClock.elapsedRealtime()

    private var ticker: Job? = null

    init {
        // 通知栏「停止展出」→ 界面侧的投屏态也要跟着落。
        scope.launch {
            CastControl.commands.collect { command ->
                when (command) {
                    CastControl.Command.STOP_SHOWCASE -> setCasting(false)
                }
            }
        }

        // 视频放完就切下一件。只在轮播开着时才有机会触发：关轮播时播放器是单曲循环，
        // 压根走不到 STATE_ENDED。（onPlaybackStateChanged 在 media3 1.11.1 里是
        // stable 成员，不需要 @UnstableApi —— 带标记的是那个废弃的 onPlayerStateChanged。）
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && canAutoAdvance()) next()
            }

            /**
             * 播不出来时必须**留痕**。外屏是纯展示，一个字的提示都没有，
             * 解码失败的结果只是一帧静止画面 —— 光看屏幕分不清"没在播"和"解不了码"。
             * 所以这里一定要打日志，排查时 `adb logcat | grep ShowState` 就能看到原因。
             * （用全限定名是为了不让这一处再牵动一次 import 改动。）
             */
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w(TAG, "播放失败：${_current.value?.uri}", error)
            }
        })

        applyRepeatMode()
        restartTicker()
        restore()
    }

    // ---- 启动恢复 ----

    /**
     * 恢复上次的素材队列。
     * 读不出信息的（照片选择器权限过期）就地丢弃，所以恢复后可能比上次少几件。
     */
    private fun restore() {
        val saved = store.load()
        if (saved.isEmpty()) {
            _loading.value = false
            return
        }
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                saved.mapNotNull { MediaDecoder.describe(context, it) }
            }
            val dropped = saved.size - decoded.size
            if (dropped > 0) {
                Log.d(TAG, "有 $dropped 条素材的读取权限已失效，已丢弃")
                store.save(decoded.map { it.uri })
            }
            _entries.value = decoded
            _loading.value = false
            applyRepeatMode()
            if (decoded.isNotEmpty()) {
                _index.value = 0
                syncCurrent()
            }
        }
    }

    // ---- 增删选 ----

    /** 追加选中的素材，自动去重。空队列时自动选中第一件。 */
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val known = _entries.value.map { it.uri }.toSet()
            val added = withContext(Dispatchers.IO) {
                uris.filterNot { it in known }.mapNotNull { MediaDecoder.describe(context, it) }
            }
            if (added.isEmpty()) return@launch

            store.persistPermissions(context, added.map { it.uri })
            val wasEmpty = _entries.value.isEmpty()
            val next = _entries.value + added
            _entries.value = next
            store.save(next.map { it.uri })
            _loading.value = false
            applyRepeatMode()

            if (wasEmpty) {
                _index.value = 0
                syncCurrent()
            }
        }
    }

    fun removeAt(position: Int) {
        val list = _entries.value
        if (position !in list.indices) return
        val next = list.toMutableList().apply { removeAt(position) }
        _entries.value = next
        store.save(next.map { it.uri })
        applyRepeatMode()

        if (next.isEmpty()) {
            _index.value = 0
            _current.value = null
            _shot.value = null
            stopVideo()
            return
        }
        // 删掉当前项时，序号原地不动就等于跳到下一张；删的是前面的项则整体前移一位
        val target = when {
            position < _index.value -> _index.value - 1
            else -> _index.value.coerceAtMost(next.lastIndex)
        }
        _index.value = target
        syncCurrent()
    }

    fun clearAll() {
        _entries.value = emptyList()
        _index.value = 0
        _current.value = null
        _shot.value = null
        store.save(emptyList())
        stopVideo()
    }

    fun select(position: Int) {
        val size = _entries.value.size
        if (size == 0) return
        val target = position.coerceIn(0, size - 1)
        if (target == _index.value) return
        _index.value = target
        syncCurrent()
    }

    fun next() = step(1)

    fun previous() = step(-1)

    private fun step(delta: Int) {
        val size = _entries.value.size
        if (size == 0) return
        _index.value = (_index.value + delta + size) % size
        syncCurrent()
    }

    // ---- 开关（全部落盘） ----

    fun toggleFill() {
        _fill.value = !_fill.value
        prefs.fill = _fill.value
    }

    fun toggleCasting() = setCasting(!_casting.value)

    /** 投屏态是播放闸门之一，所以改它必须同步播放器。 */
    fun setCasting(value: Boolean) {
        if (_casting.value == value) return
        _casting.value = value
        prefs.casting = value
        syncPlayer()
    }

    fun togglePlaying() {
        _playing.value = !_playing.value
        syncPlayer()
    }

    fun toggleMuted() {
        _muted.value = !_muted.value
        prefs.muted = _muted.value
        player.volume = if (_muted.value) 0f else 1f
    }

    /** 轮播间隔。0 = 关。改它要同时处理重复模式和计时器。 */
    fun setInterval(seconds: Int) {
        val value = seconds.coerceAtLeast(0)
        if (_intervalSec.value == value) return
        _intervalSec.value = value
        prefs.intervalSec = value
        applyRepeatMode()
        restartTicker()
    }

    /** 视频播完再切（默认开）。改它要重算计时策略，所以同样重启计时器。 */
    fun toggleVideoFullPlay() {
        _videoFullPlay.value = !_videoFullPlay.value
        prefs.videoFullPlay = _videoFullPlay.value
        restartTicker()
    }

    fun togglePreviewing() = setPreviewing(!_previewing.value)

    /** 预览态也是播放闸门之一 —— 只在预览栏里看视频时同样该播。 */
    fun setPreviewing(value: Boolean) {
        if (_previewing.value == value) return
        _previewing.value = value
        prefs.previewing = value
        syncPlayer()
    }

    /** 预览窗格换到另一侧（左 ↔ 右）。 */
    fun flipPreviewSide() {
        _previewSide.value = if (_previewSide.value == 0) 1 else 0
        prefs.previewSide = _previewSide.value
    }

    fun toggleKeepAlive() {
        _keepAlive.value = !_keepAlive.value
        prefs.keepAlive = _keepAlive.value
    }

    /** 外屏常亮。只改开关，真正的屏幕锁由保活服务持有 —— 界面改完重投一次服务即可。 */
    fun toggleScreenOn() {
        _screenOn.value = !_screenOn.value
        prefs.screenOn = _screenOn.value
    }

    fun toggleBootStart() {
        _bootStart.value = !_bootStart.value
        prefs.bootStart = _bootStart.value
    }

    fun release() {
        stopVideo()
        player.release()
    }

    // ---- 轮播 ----

    /**
     * 重复模式跟着轮播走。
     *
     * 轮播开着且素材多于一件时必须用 OFF：单曲循环的播放器永远不会报告"放完了"，
     * 于是视频会一直循环下去 —— 表面看就是"轮播对视频不生效"。
     */
    private fun applyRepeatMode() {
        val loop = _intervalSec.value <= 0 || _entries.value.size <= 1
        player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private fun restartTicker() {
        ticker?.cancel()
        ticker = null
        if (_intervalSec.value <= 0) return
        ticker = scope.launch {
            // 起跳用 500ms 而不是"直接 delay 整个间隔"：这样暂停/退出展出这类
            // 状态变化最多 0.5 秒就被发现，同时又不至于空转。
            while (true) {
                delay(TICK_MS)
                if (canAutoAdvance() && dueForNext()) next()
            }
        }
    }

    /**
     * 这一拍该不该换下一件。
     *
     * 图片只有一条规则：到点就换。
     * 视频分两条路，由 [videoFullPlay] 决定 ——
     *  - 关：也按间隔硬切（回到最简单那条规则）；
     *  - 开：**正常路径不在这里**。视频放到尾时播放器报 `STATE_ENDED`，
     *    监听器里立刻换片（见 init 里的 addListener），几乎总是比计时器更早、更准。
     *    这里只做兜底，防止"解不出来 / 播不动"的视频把整个轮播卡死：
     *    时长已知就等到时长之后再放弃它，连时长都没解析出来（还在 prepare）则继续等。
     */
    private fun dueForNext(): Boolean {
        val waited = SystemClock.elapsedRealtime() - lastSwitch
        val intervalMs = _intervalSec.value * 1000L
        if (_current.value?.isVideo != true) return waited >= intervalMs
        if (!_videoFullPlay.value) return waited >= intervalMs

        val duration = player.duration
        val guard = when {
            // IDLE = 还没 prepare，或出错后被打回 IDLE。两种情况都不该无限等下去
            player.playbackState == Player.STATE_IDLE -> intervalMs + VIDEO_STUCK_GRACE_MS
            duration > 0 -> duration + VIDEO_TAIL_GRACE_MS
            // 时长未知但播放器在跑（正在缓冲）：再等等，别抢在它前面把片子切走
            else -> Long.MAX_VALUE
        }
        return waited >= guard
    }

    /** 现在该不该自动换下一件。 */
    private fun canAutoAdvance(): Boolean =
        _intervalSec.value > 0 &&
            _entries.value.size > 1 &&
            (_casting.value || _previewing.value) &&
            _playing.value

    // ---- 内部 ----

    /**
     * 当前素材变了：先加载大图（视频即首帧海报、动图即第一帧），再决定播放器要不要动。
     *
     * **这里刻意不清空 [shot]**（早先会清，代价就是"切到视频先黑一下"）。
     * 视频出画要等解码器起播，是几百毫秒级的事；这期间 PlayerView 的 shutter 是透明的，
     * 透下去看到的是下面的位图层 —— 那一层要是空的，观众看到的就是黑屏。
     * 所以换片时**保留上一件的位图**，新位图解出来再替换：
     * 短促的一瞬是上一张画面，而不是黑屏，观感上就是从一张切到另一张。
     *
     * 唯一的例外是新位图**解不出来**（null），这时必须清掉 —— 留着上一件才是错的。
     */
    private fun syncCurrent() {
        lastSwitch = SystemClock.elapsedRealtime()
        _current.value = _entries.value.getOrNull(_index.value)
        val entry = _current.value

        if (entry == null) {
            _shot.value = null
            stopVideo()
            return
        }

        val key = entry.uri.toString()
        scope.launch {
            val bitmap = MediaDecoder.stage(context, entry)
            // 期间又切走了就把这次结果丢掉（否则会串图）
            if (_current.value?.uri?.toString() != key) return@launch
            _shot.value = if (bitmap != null) StageShot(key, bitmap) else null
        }

        if (entry.isVideo) {
            player.setMediaItem(MediaItem.fromUri(entry.uri))
            player.prepare()
        } else {
            stopVideo()
        }
        syncPlayer()
    }

    private fun syncPlayer() {
        val entry = _current.value
        val visible = _casting.value || _previewing.value
        player.playWhenReady = entry?.isVideo == true && _playing.value && visible
    }

    /** 切到图片或清空时，把播放器完全让出来（连带释放它的视频 Surface）。 */
    private fun stopVideo() {
        player.stop()
        player.clearMediaItems()
    }

    private companion object {
        const val TAG = "ShowState"

        /** 轮播计时器的起跳间隔。 */
        const val TICK_MS = 500L

        /** 「播完再切」时，时长之后再多给几毫秒余量，免得和 STATE_ENDED 抢在同一拍。 */
        const val VIDEO_TAIL_GRACE_MS = 2000L

        /** 播放器压根没跑起来（IDLE）时，等多久就放弃这件视频、翻下一件。 */
        const val VIDEO_STUCK_GRACE_MS = 20000L
    }
}
