package com.zzupocket.app.ui.schedule

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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

    val source by produceState<Bitmap?>(initialValue = null, sourceUri, useSavedSource) {
        value = withContext(Dispatchers.IO) {
            if (useSavedSource) {
                decodeBitmapFile(ScheduleBackground.sourceFile(context), MAX_DECODE_WIDTH)
            } else {
                sourceUri?.let { decodeSampled(context, it) }
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
                CircularProgressIndicator()
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

/** 解码相册图片：先按需采样，再交给 ImageDecoder（它会自动按 EXIF 把方向摆正） */
private fun decodeSampled(context: Context, uri: Uri): Bitmap? = runCatching {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
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

private const val MAX_DECODE_WIDTH = 2048

/** 顶部栏高度，用来算裁剪框（整页）比例和网格区域的位置 */
private const val TOP_BAR_DP = 64

/** 周次条高度 */
private const val WEEK_HEADER_DP = 46

/** 底部说明文字 + 复位按钮占的高度 */
private const val BOTTOM_HINT_DP = 150

/** 网格左侧节次栏占整页宽度的比例，用来画参考竖线 */
private const val GRID_LABEL_RATIO = 0.075f
