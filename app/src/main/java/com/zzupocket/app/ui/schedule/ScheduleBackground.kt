package com.zzupocket.app.ui.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 课表背景图。
 *
 * **复制一份进应用私有目录**，不记相册的 URI：否则用户删了原图、或者换台手机，
 * 背景就静默变成白板了。文件名固定，换图直接覆盖，另外用一个版本号让内存缓存失效。
 */
object ScheduleBackground {

    private const val FILE_NAME = "schedule_bg.jpg"

    /** 原图另存一份，"重新裁剪"时不用再跑一趟相册 */
    private const val SOURCE_NAME = "schedule_bg_src.jpg"

    /** 存之前先缩到这个宽度：够 2K 屏铺满，又不至于占用几十兆内存 */
    private const val MAX_WIDTH = 1440

    @Volatile
    private var cache: Pair<Int, ImageBitmap>? = null

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun sourceFile(context: Context): File = File(context.filesDir, SOURCE_NAME)

    fun exists(context: Context): Boolean = file(context).exists()

    fun hasSource(context: Context): Boolean = sourceFile(context).exists()

    /** 保存裁剪结果；失败返回 false（磁盘满、图片损坏等） */
    fun save(context: Context, bitmap: Bitmap): Boolean = runCatching {
        val scaled = scaleDown(bitmap, MAX_WIDTH)
        FileOutputStream(file(context)).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        cache = null
        true
    }.getOrDefault(false)

    /** 把原始图（已采样到 2048 宽）留一份，供之后重新裁剪 */
    fun saveSource(context: Context, bitmap: Bitmap): Boolean = runCatching {
        FileOutputStream(sourceFile(context)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        true
    }.getOrDefault(false)

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        runCatching { sourceFile(context).delete() }
        cache = null
    }

    /**
     * 读背景图。[version] 变了就重新解码 —— 换图后不用重启 App 也能看到。
     * 用 RGB_565 解码：背景图不需要高保真，内存省一半。
     */
    fun load(context: Context, version: Int): ImageBitmap? {
        cache?.let { (cachedVersion, image) -> if (cachedVersion == version) return image }
        val target = file(context)
        if (!target.exists()) return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val bitmap = runCatching { BitmapFactory.decodeFile(target.absolutePath, options) }
            .getOrNull() ?: return null
        val image = bitmap.asImageBitmap()
        cache = version to image
        return image
    }

    private fun scaleDown(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val height = (src.height.toFloat() * maxWidth / src.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, maxWidth, height, true)
    }
}

/** 组合式读取：解码放到 IO 线程，别卡住首帧 */
@Composable
fun rememberScheduleBackground(context: Context, path: String?, version: Int): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path, version) {
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) { ScheduleBackground.load(context, version) }
        }
    }.value

/** 按需采样解码一个文件（裁剪页读回原图用） */
internal fun decodeBitmapFile(file: File, maxWidth: Int): Bitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > maxWidth * 2) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
}
