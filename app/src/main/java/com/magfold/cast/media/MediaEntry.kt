package com.magfold.cast.media

import android.net.Uri

/** 素材类型。 */
enum class MediaKind { IMAGE, VIDEO }

/**
 * 一条待展示的素材。
 *
 * uri 来自系统照片选择器（SAF/MediaProvider），不是文件路径 —— 全程只当 uri 用，
 * 不要试图 File(uri.path) 去开流。
 */
data class MediaEntry(
    val uri: Uri,
    val kind: MediaKind,
    /** 展示用名字，取自 OpenableColumns.DISPLAY_NAME，已去掉扩展名。 */
    val name: String,
    /**
     * 是否**动图**（GIF / 动态 WebP）。
     *
     * 不单独开一个 [MediaKind] 的原因：动图在"是视频吗""要不要走播放器"这两件事上
     * 的答案都是"不"，跟静态图完全一致 —— 它只是**画法**不同（要交给
     * AnimatedImageDrawable 逐帧播，而不是画一张 Bitmap）。所以它是在 IMAGE 上
     * 加的一个画法标记，不是第三种形态。
     *
     * 判定只看 MIME/扩展名（见 [MediaDecoder.describe]），不预先解一遍头 ——
     * 网格里几十件素材逐件解头太贵。万一是静态 WebP 被标成了动图也没关系：
     * 解码出来的就是个普通 Drawable，画出来还是对的。
     */
    val animated: Boolean = false,
) {
    val isVideo: Boolean get() = kind == MediaKind.VIDEO

    /** 动图：按 Drawable 逐帧画，不走 Bitmap。 */
    val isAnimatedImage: Boolean get() = kind == MediaKind.IMAGE && animated
}
