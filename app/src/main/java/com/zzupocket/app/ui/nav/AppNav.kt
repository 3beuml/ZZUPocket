package com.zzupocket.app.ui.nav

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.data.update.AppUpdate
import com.zzupocket.app.data.update.UpdateCenter
import com.zzupocket.app.ui.about.AboutScreen
import com.zzupocket.app.ui.theme.rememberEffectiveDark
import com.zzupocket.app.ui.components.LocalShowDescriptions
import com.zzupocket.app.ui.campus.CampusScreen
import com.zzupocket.app.ui.campus.CardScreen
import com.zzupocket.app.ui.campus.ElectricityScreen
import com.zzupocket.app.ui.campus.RoomScreen
import com.zzupocket.app.ui.library.LibRecordsScreen
import com.zzupocket.app.ui.library.LibraryScreen
import com.zzupocket.app.ui.diag.DiagnosticsScreen
import com.zzupocket.app.ui.focus.FocusScreen
import com.zzupocket.app.ui.focus.FocusStatsScreen
import com.zzupocket.app.ui.login.LoginScreen
import com.zzupocket.app.ui.schedule.BackgroundCropScreen
import com.zzupocket.app.ui.schedule.CourseOverviewScreen
import com.zzupocket.app.ui.schedule.DisplaySettingsScreen
import com.zzupocket.app.ui.schedule.ScheduleScreen
import com.zzupocket.app.ui.settings.AppearanceScreen
import com.zzupocket.app.ui.settings.HomeModulesScreen
import com.zzupocket.app.ui.schedule.WallpaperLayers
import com.zzupocket.app.ui.schedule.rememberScheduleBackground
import com.zzupocket.app.ui.today.TodayScreen

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    /** 未选中态用线性版本（M3 惯例：outlined ↔ filled 区分选中），悬浮胶囊 dock 用 */
    val outlinedIcon: ImageVector,
) {
    TODAY(AppRoutes.TODAY, "首页", Icons.Filled.Today, Icons.Outlined.Today),
    SCHEDULE(AppRoutes.SCHEDULE, "课表", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    FOCUS(AppRoutes.FOCUS, "专注", Icons.Filled.Timer, Icons.Outlined.Timer),
    CAMPUS(AppRoutes.CAMPUS, "设置", Icons.Filled.Apps, Icons.Outlined.Apps),
}

/** 非 tab 的全屏页面：这些路由下不显示底部导航栏 */
private const val ROUTE_LOGIN = "login"
private const val ROUTE_DIAGNOSTICS = "diagnostics"
private const val ROUTE_OVERVIEW = "course_overview"
private const val ROUTE_DISPLAY = "display_settings"
private const val ROUTE_FOCUS_STATS = "focus_stats"
private const val ROUTE_BG_CROP = "background_crop"
private const val ROUTE_ROOMS = "room_occupancy"

// 这两个路由同时被桌面小组件用到（点电量去电费页、点余额去校园卡页），
// 所以统一从 AppRoutes 取，避免两边各写一份字面量、改名时静默失效。
private const val ROUTE_ELECTRICITY = AppRoutes.ELECTRICITY
private const val ROUTE_CARD = AppRoutes.CARD
private const val ROUTE_LIBRARY = "library_web"
private const val ROUTE_LIB_RECORDS = "library_records"
private const val ROUTE_ABOUT = "about"
private const val ROUTE_APPEARANCE = "appearance"
private const val ROUTE_HOME_MODULES = "home_modules"

/** "重新裁剪"时用这个占位值代替相册 URI */
private const val CROP_SOURCE_SAVED = "saved"

@Composable
fun AppNav(initialRoute: String? = null, routeToken: Int = 0) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val context = LocalContext.current
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())
    // 「专注」可以从 dock 里摘掉；隐藏后当前页若正是专注，底栏也随之收起
    val dockDestinations = Destination.entries.filter {
        it != Destination.FOCUS || prefs.showFocusInDock
    }
    val showBottomBar = dockDestinations.any { it.route == currentRoute }

    /**
     * 切到某个 tab。
     *
     * ⚠️ **所有"进 tab"的路径都必须走这里**，包括桌面小组件带进来的路由。
     *
     * 曾经小组件是用裸 `navController.navigate("schedule")` 把课表压栈的，
     * 绕过了下面这套 saveState/restoreState 记账；结果"今天"那一格保存的回退栈里
     * 夹着一个课表条目，之后每次点「今天」都会被 `restoreState` 原样恢复成课表 ——
     * 表现出来就是"从组件进来以后点今天没反应"（真机复现：课表页点今天仍在课表页，
     * 从专注页点今天又被弹回课表页）。对照组：正常从今天页点课表再点回来，一切正常。
     */
    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // 桌面小组件点进来时带的路由（点课表 → 课表页、点电量 → 电费页、点余额 → 校园卡页）。
    //
    // 刻意**不把它当成 NavHost 的 startDestination**：那样返回栈里就只剩目标页一个，
    // 按返回键会直接退出 App。这里是先落到今天页（startDestination），再把目标页压上去 ——
    // 和用户从「更多 → 电费」点进去的手感一致，返回键回到今天页。
    //
    // routeToken 每收到一次组件点击就 +1：同一个路由连点两次也得能再跳一次
    // （只拿路由字符串当 key 的话值没变、effect 不重跑，看起来就是"点组件没反应"）。
    LaunchedEffect(initialRoute, routeToken) {
        if (initialRoute == null || initialRoute == Destination.TODAY.route) return@LaunchedEffect
        if (Destination.entries.any { it.route == initialRoute }) {
            // tab 路由：必须和点底栏走同一条路径，否则会污染 tab 的 save/restore 状态（见 switchTab）
            switchTab(initialRoute)
        } else {
            // 非 tab 的整页（电费/校园卡）：就是普通压栈，和 App 内点进去一样
            navController.navigate(initialRoute)
        }
    }

    // 壁纸只认「课表」这一页；切到别的 tab 就不画
    val wallpaperActive = currentRoute == Destination.SCHEDULE.route &&
        prefs.backgroundPath != null
    val wallpaper = rememberScheduleBackground(
        context = context,
        path = prefs.backgroundPath.takeIf { wallpaperActive },
        version = prefs.backgroundVersion,
    )
    val transparentBar = wallpaperActive && wallpaper != null && prefs.wallpaperNavBar
    // dock 胶囊的透明度：壁纸模式尊重"透明导航栏"偏好（更透），其余页面 0.5 ——
    // 半透明 + 细描边 + 无阴影，是所有页面同一套配方：胶囊融进页面底色，
    // 不再出现"一圈不是背景色的色带"（surfaceContainerHighest 实底 + 渐变衬底的教训）。
    val dockAlpha = if (transparentBar) 0.35f else 0.5f
    // dock 同样跟「生效深浅」同口径：深色非壁纸档直接用页面的 surface（保留颜色/
    // 对比度染色后也一致），无手工混合。rc6 又拆掉两处深色暗源：
    // ① 6dp 投影在深底上是一圈"更黑的晕"，深色非壁纸档降到 1dp tonal 微浮起；
    // ② 壁纸透明档是刻意浮在照片上的，保留 6dp 自由浮起。
    val dockDark = rememberEffectiveDark(prefs.darkMode)
    val dockColor = when {
        transparentBar -> MaterialTheme.colorScheme.surface.copy(alpha = dockAlpha)
        dockDark -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surface.copy(alpha = dockAlpha)
    }
    val dockElevation = when {
        transparentBar -> 6.dp
        dockDark -> 1.dp
        else -> 6.dp
    }

    // 「更多」图标的更新红点：开屏静默查一次（MainActivity），更多页手动查也共享同一份状态。
    // 判定口径在 AppUpdate.shouldShowDot：屏蔽只认具体版本，之后出的新版仍会提醒。
    val currentVersion = remember { AppUpdate.localVersion(context) }
    val remoteRelease by UpdateCenter.release.collectAsState(initial = null)
    val mutedVersion by UpdateCenter.mutedVersion.collectAsState(initial = null)
    val hasUpdate = AppUpdate.shouldShowDot(currentVersion, remoteRelease?.version, mutedVersion)

    // 「显示描述」下发到整棵 Compose 树（含各页面自己的 AlertDialog）：
    // 开关一拨 → prefs 发新值 → 这里重组 → 所有 DescriptionText 立刻随之出现/消失。
    CompositionLocalProvider(LocalShowDescriptions provides prefs.showDescriptions) {
    Box(
        Modifier
            .fillMaxSize()
            // 悬浮胶囊四周露出的 window 底色默认纯白，和动态取色后的页面 surface
            // （带主题色调）对不上——尤其 dock 脚下那条，看着像"另一块白色"。
            // 浅色壁纸模式下这层被壁纸盖住，无感；普通页面统一成主题 surface。
            .background(MaterialTheme.colorScheme.surface),
    ) {
        // ①～③ 壁纸图层：铺满整屏，包括顶部栏和底部导航栏的下面
        if (wallpaperActive && wallpaper != null) {
            WallpaperLayers(image = wallpaper, prefs = prefs)
        }

        // ④ 页面内容。外壳透明；**不再挂 Scaffold.bottomBar** —— 那会把内容区
        // 扣掉 dock 高度，滚动到底就能看出底部"被占用了一块"（用户 10-26 反馈）。
        // dock 改为下面的覆盖层：内容全高铺满，滚动时从胶囊底下穿过，才是真悬浮。
        Scaffold(
            // ⚠️ 把外层内边距全量清零：顶部内边距由各页面自己的 TopAppBar 处理，
            // 底部由覆盖层自己的 navigationBarsPadding 处理。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color.Transparent,
            // 透明底的 contentColorFor 会回落到 LocalContentColor 的默认值黑色；
            // 不钉成 onSurface，深色下裸 Text（课表课名/节次序号等）全是黑字
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                NavHost(
                    navController = navController,
                    startDestination = Destination.TODAY.route,
                ) {
                composable(Destination.TODAY.route) {
                    TodayScreen(
                        onOpenLogin = { navController.navigate(ROUTE_LOGIN) },
                        onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                        onOpenSchedule = { switchTab(Destination.SCHEDULE.route) },
                        onOpenElectricity = { navController.navigate(ROUTE_ELECTRICITY) },
                        onOpenCard = { navController.navigate(ROUTE_CARD) },
                        onOpenLibrary = { navController.navigate(ROUTE_LIBRARY) },
                        onOpenRooms = { navController.navigate(ROUTE_ROOMS) },
                    )
                }
                composable(Destination.SCHEDULE.route) {
                    ScheduleScreen(
                        onOpenLogin = { navController.navigate(ROUTE_LOGIN) },
                        onOpenOverview = { semesterId ->
                            navController.navigate("$ROUTE_OVERVIEW/$semesterId")
                        },
                        onOpenDisplaySettings = { navController.navigate(ROUTE_DISPLAY) },
                    )
                }
                composable(Destination.FOCUS.route) {
                    FocusScreen(onOpenStats = { navController.navigate(ROUTE_FOCUS_STATS) })
                }
                composable(Destination.CAMPUS.route) {
                    CampusScreen(
                        onOpenLogin = { navController.navigate(ROUTE_LOGIN) },
                        onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                        onOpenElectricity = { navController.navigate(ROUTE_ELECTRICITY) },
                        onOpenCard = { navController.navigate(ROUTE_CARD) },
                        onOpenRooms = { navController.navigate(ROUTE_ROOMS) },
                        onOpenLibrary = { navController.navigate(ROUTE_LIBRARY) },
                        onOpenAbout = { navController.navigate(ROUTE_ABOUT) },
                        onOpenAppearance = { navController.navigate(ROUTE_APPEARANCE) },
                        onOpenHomeModules = { navController.navigate(ROUTE_HOME_MODULES) },
                    )
                }
                composable(ROUTE_APPEARANCE) {
                    AppearanceScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_HOME_MODULES) {
                    HomeModulesScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_ABOUT) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = "$ROUTE_OVERVIEW/{semesterId}",
                    arguments = listOf(navArgument("semesterId") { type = NavType.IntType }),
                ) { entry ->
                    val semesterId = entry.arguments?.getInt("semesterId") ?: return@composable
                    CourseOverviewScreen(
                        semesterId = semesterId,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_DISPLAY) {
                    DisplaySettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenCrop = { uri ->
                            navController.navigate("$ROUTE_BG_CROP/${Uri.encode(uri.toString())}")
                        },
                        onRecrop = {
                            navController.navigate("$ROUTE_BG_CROP/$CROP_SOURCE_SAVED")
                        },
                        // 「课程总览」从顶栏移到设置页里（顶栏只留一个设置入口）
                        onOpenOverview = { semesterId ->
                            navController.navigate("$ROUTE_OVERVIEW/$semesterId")
                        },
                    )
                }
                composable(
                    route = "$ROUTE_BG_CROP/{source}",
                    arguments = listOf(navArgument("source") { type = NavType.StringType }),
                ) { entry ->
                    val raw = entry.arguments?.getString("source") ?: return@composable
                    BackgroundCropScreen(
                        sourceUri = if (raw == CROP_SOURCE_SAVED) {
                            null
                        } else {
                            Uri.parse(Uri.decode(raw))
                        },
                        useSavedSource = raw == CROP_SOURCE_SAVED,
                        // 裁剪完回设置页，能立刻看到缩略图和三个滑块的效果
                        onSaved = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_FOCUS_STATS) {
                    FocusStatsScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_ELECTRICITY) {
                    ElectricityScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_CARD) {
                    CardScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_ROOMS) {
                    RoomScreen(onBack = { navController.popBackStack() })
                }
                composable(ROUTE_LIBRARY) {
                    LibraryScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRecords = { navController.navigate(ROUTE_LIB_RECORDS) },
                    )
                }
                composable(ROUTE_LIB_RECORDS) {
                    LibRecordsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenLibrary = {
                            navController.navigate(ROUTE_LIBRARY) { launchSingleTop = true }
                        },
                    )
                }
                composable(ROUTE_LOGIN) {
                    LoginScreen(
                        onDone = { navController.popBackStack() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_DIAGNOSTICS) {
                    DiagnosticsScreen(
                        onBack = { navController.popBackStack() },
                        onLoggedOut = { navController.popBackStack() },
                    )
                }
                }

                // ⑤ 悬浮胶囊 dock —— 覆盖层（对齐内容 Box 的底部）：
                // 所有页面同一配方：半透明面板 + 玻璃边 + 窄软落地影。
                // 之前的深度线索教训仍有效（阴影=tonal 大面积泛光的错，不是阴影本身的错；
                // 窄软方向影 + 上缘受光的 bevel 才是"物体浮在纸上"的读法）。
                // 现在它不再吃掉内容区，滚动内容从胶囊底下穿过 —— 真正的悬浮。
                if (showBottomBar) {
                    // dock 覆盖层化（0.33.0）后内容从胶囊底下穿过：胶囊以外的带子
                    // （左右 20dp、上下 8dp、四个圆角切角）既不绘制也不吃事件，
                    // 点击会穿透到底下的内容（issue #12：更多页滚到底，点胶囊上缘
                    // 留白会把「检测更新」弹窗拉起来）。给外层 Box 吸一层只吞 tap
                    // 的手势——不用 clickable，那会进语义树、被 TalkBack 读成
                    // 一个可点控件；detectTapGestures 也不消费 move，带子上起手
                    // 滑列表仍交给父级 scrollable。fillMaxWidth 是保险带：bounds
                    // 若随内部 Surface 改动缩水，吞 tap 也不漏。
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                detectTapGestures { /* 消费掉 tap，不下传 */ }
                            },
                    ) {
                        Surface(
                            shape = RoundedCornerShape(28.dp),
                            color = dockColor,
                            tonalElevation = dockElevation,
                            shadowElevation = 0.dp,
                            border = BorderStroke(
                                0.5.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.25f),
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                    ),
                                ),
                            ),
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                                .fillMaxWidth()
                                .height(56.dp)
                                .shadow(
                                    elevation = dockElevation,
                                    shape = RoundedCornerShape(28.dp),
                                    clip = false,
                                ),
                        ) {
                            Row(
                                Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                dockDestinations.forEach { destination ->
                                    val selected = currentRoute == destination.route
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clickable { switchTab(destination.route) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    when {
                                                        !selected -> Color.Transparent
                                                        // 深色下选中泡用 primaryContainer：比默认的
                                                        // secondaryContainer（深色调 30）亮一档，且与
                                                        // 「保留颜色」主色底是同一色相家族，更协调；
                                                        // 浅色维持 secondaryContainer 原配方
                                                        dockDark -> MaterialTheme.colorScheme.primaryContainer
                                                        else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                                                    },
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                if (selected) destination.icon else destination.outlinedIcon,
                                                contentDescription = destination.label,
                                                tint = when {
                                                    !selected -> MaterialTheme.colorScheme.onSurfaceVariant
                                                    dockDark -> MaterialTheme.colorScheme.onPrimaryContainer
                                                    else -> MaterialTheme.colorScheme.primary
                                                },
                                                modifier = Modifier.size(23.dp),
                                            )
                                            // 「检测更新」红点只挂在「更多」图标上：
                                            // 有新 release 时用户不点进更多页也能看到，
                                            // 点了就走 switchTab —— 比弹窗打扰温和得多。
                                            if (destination == Destination.CAMPUS && hasUpdate) {
                                                Box(
                                                    Modifier
                                                        .align(Alignment.TopEnd)
                                                        .offset(x = 1.dp, y = (-1).dp)
                                                        .size(9.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.error),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    } // CompositionLocalProvider
}

/**
 * 悬浮 dock 为「滚动内容」预留下的底部余量：胶囊 56 + 上下边距 16 = 72，再加 16 呼吸。
 * dock 是覆盖层后内容全高滚动（从胶囊底下穿过），达各滚动容器把这个值挂到
 * contentPadding 上，**最后一条内容才能完整滚出胶囊、不再永远压在 dock 底下**。
 */
val DOCK_CLEARANCE = 88.dp
