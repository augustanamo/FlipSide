package com.magfold.cast.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * 选中素材的轻量持久化。
 *
 * 权限这一环有讲究：照片选择器给的读权限默认只活到「设备重启」。
 * Android 14 起可以对它调用 takePersistableUriPermission 转成长期授权，
 * 13 及以下调用会抛（那些 uri 没带 FLAG_GRANT_PERSISTABLE_URI_PERMISSION）。
 * 所以这里一律 try 住 —— 成功就当赚到，失败下次启动时 describe() 返回 null，
 * 由 ShowState 把失效条目剔掉，不会崩。
 */
class SelectionStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<Uri> = prefs.getString(KEY, null)
        ?.split(SEPARATOR)
        ?.filter { it.isNotBlank() }
        ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        ?: emptyList()

    fun save(uris: List<Uri>) {
        prefs.edit().putString(KEY, uris.joinToString(SEPARATOR)).apply()
    }

    /** 尽力把读权限转成持久授权。失败静默 —— 见类注释。 */
    fun persistPermissions(context: Context, uris: List<Uri>) {
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure {
                Log.d(TAG, "该 uri 不支持持久授权（正常）：$uri")
            }
        }
    }

    private companion object {
        const val PREFS = "cast.selection"
        const val KEY = "uris"
        const val SEPARATOR = "\n"
        const val TAG = "SelectionStore"
    }
}
