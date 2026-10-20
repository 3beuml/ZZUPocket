package com.zzupocket.app.ui.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.withTimeoutOrNull
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 导入背景图时的裁剪页。
 *
 * 裁剪框的比例是**整页**（屏宽 : 页面高）——因为壁纸是铺满整屏的，
 * 包括顶部栏、周次条和底部导航栏。框里画的虚线是网格区域实际落的位置，
 * 横线是节次、竖线是星期，所见即所得。双指缩放、拖动调整位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundCropScreen(
    sourceUri: Uri?,
    useSavedSource: Boolean,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 解码结果 + 失败原因。
    //
    // ⚠️ 必须记原因：把异常吞掉只留一句"读不出来"，用户和我们都无从下手
    // （华为 Mate 70 Pro 反馈就是"系统说文件在本地，App 说读不出来"）。
    // 现在把**失败发生在哪一步 + 异常类型**写进提示里，用户截图就能定位。
    var loadError by remember(sourceUri, useSavedSource) { mutableStateOf<String?>(null) }
    val source by produceState<Bitmap?>(initialValue = null, sourceUri, useSavedSource) {
        value = withContext(Dispatchers.IO) {
            if (useSavedSource) {
                decodeBitmapFile(ScheduleBackground.sourceFile(context), MAX_DECODE_WIDTH)
            } else if (sourceUri == null) {
                null
            } else {
                // 10 秒还没读完就放弃：正常本地图片远用不到这么久
                var result: DecodeResult? = null
                val decoded = withTimeoutOrNull(DECODE_TIMEOUT_MS) {
                    decodeSampled(context, sourceUri!!).also { result = it }
                }?.bitmap
                if (decoded == null) {
                    loadError = if (result == null) {
                        "读取超时（超过 ${DECODE_TIMEOUT_MS / 1000} 秒）。\n" +
                            "可以：换一张照片，或先在相册里另存一份再选。"
                    } else {
                        "这张图片读不出来。\n" +
                            "原因：${result!!.reason}\n" +
                            "可以：换一张截图/照片试试（截图一定能读）。"
                    }
                }
                decoded
            }
        }
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }

    // 裁剪框 = 整页尺寸（去掉系统栏的估算值），和铺满后的构图一致
    val config = LocalConfiguration.current
    val pageAspect = config.screenWidthDp.toFloat() /
        (config.screenHeightDp - TOP_BAR_DP - BOTTOM_HINT_DP).coerceAtLeast(240).toFloat()
    val availW = (config.screenWidthDp - 24).coerceAtLeast(200).toFloat()
    val availH = (config.screenHeightDp - TOP_BAR_DP - BOTTOM_HINT_DP).coerceAtLeast(240).toFloat()
    val frameWDp = min(availW, availH * pageAspect)
    val frameHDp = frameWDp / pageAspect

    val frameWpx = with(density) { frameWDp.dp.toPx() }
    val frameHpx = with(density) { frameHDp.dp.toPx() }

    // 网格区域在整页里的位置（顶部栏 + 周次条），用来画参考线
    val gridTopPx = with(density) { (TOP_BAR_DP + WEEK_HEADER_DP).dp.toPx() }
    val gridHeightPx = with(density) { (10 * prefs.cellHeightDp + 26).dp.toPx() }

    val bitmap = source
    val currentFrame = remember(bitmap, frameWpx, frameHpx, scale, offset) {
        bitmap?.let { coverFrame(it.width, it.height, frameWpx, frameHpx, scale, offset) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("裁剪课表背景") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.Close, contentDescription = "取消")
                    }
                },
                actions = {
                    TextButton(
                        enabled = bitmap != null && currentFrame != null && !saving,
                        onClick = {
                            val bmp = bitmap ?: return@TextButton
                            val frame = currentFrame ?: return@TextButton
                            saving = true
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    val cropped = renderCrop(bmp, frame) ?: return@withContext false
                                    // ① 存裁剪结果 ② 留一份原图供"重新裁剪" ③ 把路径写进设置
                                    val saved = ScheduleBackground.save(context, cropped)
                                    if (saved) {
                                        ScheduleBackground.saveSource(context, bmp)
                                        AppGraph.uiPrefsStore.setBackground(
                                            ScheduleBackground.file(context).absolutePath,
                                        )
                                    }
                                    saved
                                }
                                saving = false
                                if (ok) onSaved()
                            }
                        },
                    ) { Text(if (saving) "保存中…" else "完成") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (bitmap == null) {
                val err = loadError
                if (err == null) {
                    CircularProgressIndicator()
                } else {
                    // 失败时必须说清楚，并给出可操作的建议（华为云相册是最常见的坑）
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "点左上角关闭，重新选一张",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            Box(
                Modifier
                    .size(frameWDp.dp, frameHDp.dp)
                    .pointerInput(bitmap, frameWpx, frameHpx) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 8f)
                            val next = coverFrame(
                                bitmap.width, bitmap.height, frameWpx, frameHpx, nextScale,
                                Offset(offset.x + pan.x, offset.y + pan.y),
                            )
                            scale = nextScale
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-next.maxX, next.maxX),
                                (offset.y + pan.y).coerceIn(-next.maxY, next.maxY),
                            )
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val image = bitmap.asImageBitmap()
                    val frame = coverFrame(
                        bitmap.width, bitmap.height, size.width, size.height, scale, offset,
                    )
                    drawImage(
                        image = image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                        dstSize = IntSize(frame.dstW.roundToInt(), frame.dstH.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                    val line = Color.White.copy(alpha = 0.45f)
                    val cols = prefs.dayCount
                    // 网格区域在整页里的位置：先画一个虚线外框，再在框内画节次/星期参考线
                    val gridTop = gridTopPx.coerceIn(0f, size.height)
                    val gridBottom = (gridTop + gridHeightPx).coerceIn(gridTop, size.height)
                    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
                    drawRect(
                        color = Color.White.copy(alpha = 0.7f),
                        topLeft = Offset(0f, gridTop),
                        size = Size(size.width, gridBottom - gridTop),
                        style = Stroke(width = 2f, pathEffect = dash),
                    )
                    val innerW = size.width * (1f - GRID_LABEL_RATIO)
                    val innerLeft = size.width * GRID_LABEL_RATIO
                    for (c in 1 until cols) {
                        val x = innerLeft + innerW * c / cols
                        drawLine(line, Offset(x, gridTop), Offset(x, gridBottom), strokeWidth = 1f)
                    }
                    for (r in 1 until 10) {
                        val y = gridTop + (gridBottom - gridTop) * r / 10f
                        drawLine(line, Offset(innerLeft, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    drawRect(Color.White.copy(alpha = 0.9f), style = Stroke(width = 2f))
                }
            }

            Text(
                text = "双指缩放、拖动调整位置。虚线框是网格实际落的位置，框里横线是节次、竖线是星期。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("复位") }
        }
    }
}

/** 当前变换下图片被画到画布上的位置与尺寸（等比铺满 + 用户缩放平移，已夹紧不露白边） */
private data class CoverFrame(
    val left: Float,
    val top: Float,
    val dstW: Float,
    val dstH: Float,
    val baseScale: Float,
    val frameW: Float,
    val frameH: Float,
    val maxX: Float,
    val maxY: Float,
)

private fun coverFrame(
    imgW: Int,
    imgH: Int,
    frameW: Float,
    frameH: Float,
    scale: Float,
    offset: Offset,
): CoverFrame {
    val base = max(frameW / imgW, frameH / imgH) * scale
    val dstW = imgW * base
    val dstH = imgH * base
    val maxX = ((dstW - frameW) / 2f).coerceAtLeast(0f)
    val maxY = ((dstH - frameH) / 2f).coerceAtLeast(0f)
    val ox = offset.x.coerceIn(-maxX, maxX)
    val oy = offset.y.coerceIn(-maxY, maxY)
    return CoverFrame(
        left = (frameW - dstW) / 2f + ox,
        top = (frameH - dstH) / 2f + oy,
        dstW = dstW,
        dstH = dstH,
        baseScale = base,
        frameW = frameW,
        frameH = frameH,
        maxX = maxX,
        maxY = maxY,
    )
}

/** 把裁剪框里看到的那一块裁出来 */
private fun renderCrop(src: Bitmap, frame: CoverFrame): Bitmap? {
    val sx = (-frame.left / frame.baseScale).coerceIn(0f, (src.width - 1).toFloat())
    val sy = (-frame.top / frame.baseScale).coerceIn(0f, (src.height - 1).toFloat())
    val sw = (frame.frameW / frame.baseScale)
        .coerceIn(1f, (src.width - sx).coerceAtLeast(1f))
    val sh = (frame.frameH / frame.baseScale)
        .coerceIn(1f, (src.height - sy).coerceAtLeast(1f))
    return runCatching {
        Bitmap.createBitmap(src, sx.toInt(), sy.toInt(), sw.toInt(), sh.toInt())
    }.getOrNull() ?: runCatching { Bitmap.createBitmap(src) }.getOrNull()
}

/**
 * 解码相册图片。
 *
 * 关键点：**先把内容拷到本地临时文件，再从文件解码**。
 *
 * 为什么不能直接 `ImageDecoder.createSource(contentResolver, uri)`：部分机型（华为/荣耀等）
 * 相册返回的可能是云端图片，`openInputStream` 会阻塞在下载上，而且没有超时 ——
 * 表现就是"裁剪页空白 + 一直转圈"。拷到本地只需要一次顺序读，外层还有超时兜底；
 * 拷下来之后 `ImageDecoder` 依然会按 EXIF 自动摆正方向。
 */
/**
 * 解码结果：要么有图，要么带着"卡在哪一步 + 异常类型"的原因。
 *
 * 为什么要带原因：华为 Mate 70 Pro 反馈"系统说文件在本地，App 说读不出来"。
 * 只吞异常、只给一句"读不出来"，用户没法自救、我们没法定位。现在把失败阶段写进提示。
 */
private data class DecodeResult(val bitmap: Bitmap?, val reason: String?)

private const val TAG = "WallpaperDecode"

/**
 * 解码相册图片（带分阶段诊断）。
 *
 * 关键点：**先把内容拷到本地临时文件，再从文件解码**。相册 provider 的读取路径
 * 在部分机型上会有怪行为（阻塞、返回空流、只支持 openFileDescriptor 等），
 * 拷到本地只需要一次顺序读，外层还有超时兜底；拷下来后 `ImageDecoder` 仍会按 EXIF 摆正方向。
 */
private fun decodeSampled(context: Context, uri: Uri): DecodeResult {
    val temp = File(context.cacheDir, "wallpaper_src.bin")
    val readError = readToTempFile(context, uri, temp)
    if (readError == null) {
        val (bmp, decodeError) = decodeLocalFile(temp)
        if (bmp != null) return DecodeResult(bmp, null)
        val reason = buildString {
            append("本地文件解码失败")
            if (decodeError != null) append("（$decodeError）")
        }
        Log.w(TAG, "decode failed: $reason, size=${temp.length()}")
        return DecodeResult(null, reason)
    }

    Log.w(TAG, "read failed: $readError")
    // 兜底：极少数 provider 只有"直接读 URI"这条能走
    val direct = runCatching {
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(context.contentResolver, uri),
        ) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val width = info.size.width
            if (width > MAX_DECODE_WIDTH) {
                decoder.setTargetSampleSize(
                    (width / MAX_DECODE_WIDTH.toFloat()).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }.getOrNull()
    return if (direct != null) DecodeResult(direct, null) else DecodeResult(null, readError)
}

/** 把 URI 内容读进 [temp]；返回 null 表示成功，否则是失败原因 */
private fun readToTempFile(context: Context, uri: Uri, temp: File): String? = try {
    val input = context.contentResolver.openInputStream(uri)
    if (input != null) {
        input.use { ins -> temp.outputStream().use { out -> ins.copyTo(out, 64 * 1024) } }
    } else {
        // 部分 OEM 的 provider 用 openInputStream 返回 null，但 openFileDescriptor 可用
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return "无法打开文件（provider 返回空流）"
        pfd.use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { ins ->
                temp.outputStream().use { out -> ins.copyTo(out, 64 * 1024) }
            }
        }
    }
    if (temp.length() <= 0) "读到 0 字节" else null
} catch (e: Throwable) {
    "读取异常 ${e.javaClass.simpleName}"
}

/** 本地文件解码：ImageDecoder 优先（EXIF/HEIF），失败再 BitmapFactory 按需采样 */
private fun decodeLocalFile(file: File): Pair<Bitmap?, String?> {
    val imageDecoderError = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val width = info.size.width
            if (width > MAX_DECODE_WIDTH) {
                decoder.setTargetSampleSize(
                    (width / MAX_DECODE_WIDTH.toFloat()).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }.exceptionOrNull()

    val byFactory = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DECODE_WIDTH) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }.getOrNull()

    if (byFactory != null) return byFactory to null
    return null to imageDecoderError?.javaClass?.simpleName
}
private const val MAX_DECODE_WIDTH = 2048

/** 解码超时：正常本地图片远用不到这么久，超时基本就是"云相册原图没下载" */
private const val DECODE_TIMEOUT_MS = 10_000L

/** 顶部栏高度，用来算裁剪框（整页）比例和网格区域的位置 */
private const val TOP_BAR_DP = 64

/** 周次条高度 */
private const val WEEK_HEADER_DP = 46

/** 底部说明文字 + 复位按钮占的高度 */
private const val BOTTOM_HINT_DP = 150

/** 网格左侧节次栏占整页宽度的比例，用来画参考竖线 */
private const val GRID_LABEL_RATIO = 0.075f
