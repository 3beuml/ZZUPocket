package com.zzupocket.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.nav.AppNav
import com.zzupocket.app.ui.nav.AppRoutes
import com.zzupocket.app.ui.theme.ZZUPocketTheme
import com.zzupocket.app.ui.widget.Widgets
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * 桌面小组件带进来的目标路由（点课表 → 课表页、点电量 → 电费页、点余额 → 校园卡页）。
     *
     * [routeToken] 每收到一次组件点击就 +1。**必须有个会变的 key**：同一个路由连点两次时
     * 只看路由字符串是看不出区别的，`LaunchedEffect` 不会重跑，用户会觉得"点组件没反应"。
     *
     * 路由本身**不置空**：转屏、切深浅色这类 Activity 重建时不该再跳一次 ——
     * effect 的 key 是 (路由, token)，重建后两者都没变，自然不会重跑。
     */
    private var widgetRoute by mutableStateOf<String?>(null)
    private var routeToken by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        consumeWidgetRoute(intent)

        setContent {
            // 配色与深浅色是全局设置，直接在这里喂给主题（改完立刻全应用生效）
            val prefs by AppGraph.uiPrefsStore.flow
                .collectAsStateWithLifecycle(initialValue = UiPrefs())
            ZZUPocketTheme(
                darkMode = prefs.darkMode,
                preset = prefs.themePreset,
            ) {
                AppNav(initialRoute = widgetRoute, routeToken = routeToken)
            }
        }

        // 打开 App 就顺手把桌面组件刷一遍。
        //
        // 为什么必须在这里做：组件自己不能随时刷新（系统的定时刷新下限是 30 分钟），
        // 所以"用户刚同步完课表 / 刚查完余额 / 刚改完配色"这些时刻，
        // 只有 App 醒着的时候才能把桌面推到最新。
        // 顺带一个好处：从组件点进来必然会走到这里，于是"点组件"同时完成了刷新和导航。
        // 失败也无所谓（组件保持上一次的内容），所以整个调用包在 runCatching 里 ——
        // 桌面组件的问题绝不该影响 App 本身。
        lifecycleScope.launch {
            runCatching { Widgets.refreshAll(this@MainActivity) }
        }
        // 开屏顺便问一次 GitHub 有没有新 release（更多页「检测更新」与 dock 角标用）。
        // 失败无声：红点不误报、不弹打扰；详见 data.update.UpdateCenter。失败也无所谓。
        runCatching { com.zzupocket.app.data.update.UpdateCenter.checkSilently() }
    }

    /**
     * 组件点击是 `PendingIntent.getActivity`，两种到达方式都要接：
     *  · App 没在运行时 → `onCreate`；
     *  · App 已经在运行时 → `onNewIntent`（组件的 Intent 带 CLEAR_TOP，系统会重建本 Activity）。
     * 只处理 `onCreate` 的话，第二种情况会被静默丢掉。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeWidgetRoute(intent)
    }

    private fun consumeWidgetRoute(intent: Intent?) {
        val route = intent?.getStringExtra(AppRoutes.EXTRA_ROUTE) ?: return
        // 读完就把 extra 从 Intent 里删掉：这条 Intent 会被系统留着，
        // 重建时不该再取到它（否则每次重建都要跳一次）
        intent.removeExtra(AppRoutes.EXTRA_ROUTE)
        widgetRoute = route
        routeToken++
    }
}
