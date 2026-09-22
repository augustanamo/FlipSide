package com.magfold.cast.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 素材读取：探测信息 + 两级尺寸解码 + 内存缓存。
 *
 * 全部走 ContentResolver，uri 一律来自照片选择器。
 * 解码一律在 IO 线程，且**按目标尺寸降采样** —— 手机相机原图 4000×3000 直出
 * 是 48 MB 的 ARGB_8888，连开几张就 OOM。
 */
object MediaDecoder {

    private const val TAG = "MediaDecoder"

    /** 网格缩略图最长边。三列布局，单格在屏幕上不超过 360px。 */
    private const val GRID_PX = 384

    /** 舞台（外屏/全屏）最长边。Fold 外屏内屏都远小于这个值。 */
    private const val STAGE_PX = 2048

    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts", "avi")

    /**
     * 可能是动图的扩展名。
     *
     * 只要 gif / webp：APNG（image/apng）在照片选择器里几乎见不到，不值得为它多开一条路。
     * 注意判定只看扩展名/MIME —— 静态 WebP 也会被标成"动图"，但它的画法（ImageView +
     * Drawable）对静态图同样正确，代价只是没走降采样缓存，风险可接受。
     */
    private val ANIMATED_EXT = setOf("gif", "webp")

    /** 位图缓存按「总内存的 1/8」计价，超出自动淘汰最久未用的。 */
    private val cache: LruCache<String, Bitmap> =
        object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }

    /**
     * 问出素材的类型和显示名。
     * uri 权限已经失效（比如重启后没拿到持久授权）时会返回 null，调用方应当把这条剔掉。
     */
    fun describe(context: Context, uri: Uri): MediaEntry? = runCatching {
        var display: String? = null
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) display = cursor.getString(0) }

        val mime = context.contentResolver.getType(uri)
        val raw = display?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "未命名素材"
        val ext = raw.substringAfterLast('.', "").lowercase()

        val kind = when {
            mime?.startsWith("video/") == true -> MediaKind.VIDEO
            mime?.startsWith("image/") == true -> MediaKind.IMAGE
            // mime 拿不到的少数情况（个别云盘 provider）靠扩展名兜底
            ext in VIDEO_EXT -> MediaKind.VIDEO
            else -> MediaKind.IMAGE
        }

        MediaEntry(
            uri = uri,
            kind = kind,
            name = raw.substringBeforeLast('.').ifBlank { raw },
            // 动图判定：MIME 优先，拿不到 MIME 才看扩展名。视频一律不是动图。
            animated = kind == MediaKind.IMAGE && (
                mime?.equals("image/gif", ignoreCase = true) == true ||
                    mime?.equals("image/webp", ignoreCase = true) == true ||
                    ext in ANIMATED_EXT
                ),
        )
    }.onFailure {
        Log.w(TAG, "读取素材信息失败：$uri", it)
    }.getOrNull()

    /** 网格用的小图。视频取首帧缩略图。 */
    suspend fun thumbnail(context: Context, entry: MediaEntry): Bitmap? =
        load(context, entry, GRID_PX, "grid")

    /** 舞台用的大图。视频同样给一张大缩略图当海报帧，遮住 ExoPlayer 出画前的黑屏。 */
    suspend fun stage(context: Context, entry: MediaEntry): Bitmap? =
        load(context, entry, STAGE_PX, "stage")

    /**
     * 动图用的 Drawable（GIF / 动态 WebP）。
     *
     * **刻意不进缓存**：AnimatedImageDrawable 内部攥着解码状态和帧回调，
     * 同一时刻只能挂在一个 View 上（分栏预览 + 真外屏会同时各画一份），
     * 共享实例会让其中一边被"抢走"。所以每次调用都新解一个 —— 代价是一次解码，
     * 换来的是两处都能正常动。
     *
     * 两个 API 细节（都在 android-36 的 jar 上核过）：
     *  - **动图不能 setTargetSize**，所以在 onHeaderDecoded 里先问 `info.isAnimated`，
     *    只有静态图才降采样。动图体积本来就小（GIF 基本在 1MB 内），不降采样不会 OOM。
     *  - **repeatCount 必须在 start() 之前设**（在 [AnimatedStage] 里 start）。
     *    展窗要的是"一直动"，所以统一设成无限循环 —— 原图只播一遍的那种 GIF
     *    在橱窗里等于一张不动的图。
     */
    suspend fun animatedDrawable(context: Context, uri: Uri): Drawable? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    if (info.isAnimated) {
                        // 动图：这里什么都别设，逐帧解就是它的全部
                    } else {
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val width = info.size.width
                        val height = info.size.height
                        val longest = maxOf(width, height)
                        if (longest > STAGE_PX) {
                            val ratio = longest.toFloat() / STAGE_PX
                            decoder.setTargetSize(
                                maxOf(1, (width / ratio).toInt()),
                                maxOf(1, (height / ratio).toInt()),
                            )
                        }
                    }
                }
                (drawable as? AnimatedImageDrawable)?.repeatCount =
                    AnimatedImageDrawable.REPEAT_INFINITE
                drawable
            }.onFailure {
                Log.w(TAG, "动图解码失败：$uri", it)
            }.getOrNull()
        }

    private suspend fun load(
        context: Context,
        entry: MediaEntry,
        maxPx: Int,
        tag: String,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${entry.uri}|$tag"
        cache.get(key)?.let { return@withContext it }

        val bitmap = runCatching { decode(context, entry, maxPx) }
            .onFailure { Log.w(TAG, "解码失败：${entry.uri}", it) }
            .getOrNull()
            ?: return@withContext null

        cache.put(key, bitmap)
        bitmap
    }

    private fun decode(context: Context, entry: MediaEntry, maxPx: Int): Bitmap =
        when (entry.kind) {
            MediaKind.IMAGE -> decodeImage(context, entry.uri, maxPx)
            MediaKind.VIDEO -> context.contentResolver
                .loadThumbnail(entry.uri, Size(maxPx, maxPx), null)
        }

    /**
     * 图片解码。ImageDecoder 会自动应用 EXIF 旋转，不需要自己读 orientation。
     * allocator 显式指定 SOFTWARE —— 默认给 HARDWARE 位图，虽然 Compose 画得出来，
     * 但后续想复制/裁剪就受限，展示场景没必要省这点内存。
     *
     * 这里也是**动图（GIF）的首帧出处**：动图走 [animatedDrawable] 逐帧播，
     * 但下面那层兜底位图要的就是它的第一帧。`decodeBitmap` 对动图返回首帧。
     * 降采样用 runCatching 包着：动图有可能拒绝 setTargetSize，被拒就退回原尺寸 ——
     * 一张没降采样的首帧，总好过整件素材解不出来。
     */
    private fun decodeImage(context: Context, uri: Uri, maxPx: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            runCatching {
                val width = info.size.width
                val height = info.size.height
                val longest = maxOf(width, height)
                if (longest > maxPx) {
                    val ratio = longest.toFloat() / maxPx
                    decoder.setTargetSize(
                        maxOf(1, (width / ratio).toInt()),
                        maxOf(1, (height / ratio).toInt()),
                    )
                }
            }.onFailure { Log.d(TAG, "该素材不支持降采样，退回原尺寸：$uri") }
        }
    }
}
