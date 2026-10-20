package com.zzupocket.app.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.zzupocket.app.ui.about.AboutScreen
import com.zzupocket.app.ui.campus.CampusScreen
import com.zzupocket.app.ui.campus.CardScreen
import com.zzupocket.app.ui.campus.ElectricityScreen
import com.zzupocket.app.ui.campus.RoomScreen
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
import com.zzupocket.app.ui.schedule.WallpaperLayers
import com.zzupocket.app.ui.schedule.rememberScheduleBackground
import com.zzupocket.app.ui.today.TodayScreen

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    TODAY(AppRoutes.TODAY, "今天", Icons.Filled.Today),
    SCHEDULE(AppRoutes.SCHEDULE, "课表", Icons.Filled.CalendarMonth),
    FOCUS(AppRoutes.FOCUS, "专注", Icons.Filled.Timer),
    CAMPUS(AppRoutes.CAMPUS, "更多", Icons.Filled.Apps),
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
private const val ROUTE_ABOUT = "about"
private const val ROUTE_APPEARANCE = "appearance"

/** "重新裁剪"时用这个占位值代替相册 URI */
private const val CROP_SOURCE_SAVED = "saved"

@Composable
fun AppNav(initialRoute: String? = null, routeToken: Int = 0) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = Destination.entries.any { it.route == currentRoute }

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

    val context = LocalContext.current
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())

    // 壁纸只认「课表」这一页；切到别的 tab 就不画
    val wallpaperActive = currentRoute == Destination.SCHEDULE.route &&
        prefs.backgroundPath != null
    val wallpaper = rememberScheduleBackground(
        context = context,
        path = prefs.backgroundPath.takeIf { wallpaperActive },
        version = prefs.backgroundVersion,
    )
    val transparentBar = wallpaperActive && wallpaper != null && prefs.wallpaperNavBar

    Box(Modifier.fillMaxSize()) {
        // ①～③ 壁纸图层：铺满整屏，包括顶部栏和底部导航栏的下面
        if (wallpaperActive && wallpaper != null) {
            WallpaperLayers(image = wallpaper, prefs = prefs)
        }

        // ④ 页面内容。外壳和导航栏都要透明，否则会把壁纸盖掉。
        Scaffold(
            // ⚠️ 必须把状态栏内边距清零。
            // 外层 Scaffold 只负责底部导航栏；顶部内边距由各页面自己的 TopAppBar 处理。
            // 不置零时两处会各加一次状态栏内边距，实测顶部多出约 45dp（146px）的空白。
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color.Transparent,
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(
                        containerColor = if (transparentBar) {
                            Color.Transparent
                        } else {
                            NavigationBarDefaults.containerColor
                        },
                        tonalElevation = if (transparentBar) 0.dp else 3.dp,
                    ) {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = { switchTab(destination.route) },
                                icon = {
                                    Icon(destination.icon, contentDescription = destination.label)
                                },
                                // 按需求：底部 tab 不显示文字，只留图标（contentDescription 仍保留给无障碍）
                                label = null,
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Destination.TODAY.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Destination.TODAY.route) {
                    TodayScreen(
                        onOpenLogin = { navController.navigate(ROUTE_LOGIN) },
                        onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                        onOpenSchedule = { switchTab(Destination.SCHEDULE.route) },
                        onOpenElectricity = { navController.navigate(ROUTE_ELECTRICITY) },
                        onOpenCard = { navController.navigate(ROUTE_CARD) },
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
                    )
                }
                composable(ROUTE_APPEARANCE) {
                    AppearanceScreen(onBack = { navController.popBackStack() })
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
                    LibraryScreen(onBack = { navController.popBackStack() })
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
        }
    }
}
