package com.zzupocket.app.ui.schedule

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zzupocket.app.data.prefs.UiPrefs

/**
 * 课表壁纸的图层。**画在外壳层**（而不是课表页内部）——
 * 这样它才能铺到顶部栏、周次条、网格，乃至底部导航栏的下面。
 *
 * 四层自下而上：
 *  ① 图片（浓淡 + 模糊）
 *  ② 底色遮罩：把照片统一压进主题色调，保证任何浓淡下正文都读得清
 *  ③ 顶/底渐变：顶部栏和周次条、底部导航栏那两条带子再多一点底色，
 *     文字就不用垫难看的实色块
 *  ④ 页面内容（由调用方叠在上面）
 */
@Composable
fun WallpaperLayers(image: ImageBitmap, prefs: UiPrefs) {
    Box(Modifier.fillMaxSize()) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = prefs.backgroundOpacity,
            modifier = Modifier
                .fillMaxSize()
                // 稍微放大一点：模糊会把边缘算成半透明，放大后这圈虚边就跑到屏幕外了
                .graphicsLayer {
                    scaleX = 1.06f
                    scaleY = 1.06f
                }
                // 模糊的是**静态底图**，不是滚动内容，所以不会掉帧
                .then(
                    if (prefs.backgroundBlur > 0f) {
                        Modifier.blur(
                            radius = prefs.backgroundBlur.dp,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded,
                        )
                    } else {
                        Modifier
                    },
                ),
        )

        val surface = MaterialTheme.colorScheme.surface
        if (prefs.backgroundScrim > 0f) {
            Box(Modifier.fillMaxSize().background(surface.copy(alpha = prefs.backgroundScrim)))
        }

        // 顶部（状态栏 + 顶部栏 + 周次条）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to surface.copy(alpha = 0.55f),
                        0.28f to Color.Transparent,
                    ),
                ),
        )
        // 底部（导航栏那一条）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.72f to Color.Transparent,
                        1f to surface.copy(alpha = 0.6f),
                    ),
                ),
        )
    }
}
