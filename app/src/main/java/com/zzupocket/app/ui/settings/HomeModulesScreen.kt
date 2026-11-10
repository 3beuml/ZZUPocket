package com.zzupocket.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.data.prefs.HomeModule
import com.zzupocket.app.data.prefs.UiPrefs
import com.zzupocket.app.ui.components.DescriptionText
import kotlinx.coroutines.launch

private val ROW_HEIGHT = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeModulesScreen(onBack: () -> Unit) {
    val prefs by AppGraph.uiPrefsStore.flow.collectAsStateWithLifecycle(initialValue = UiPrefs())
    val scope = rememberCoroutineScope()
    val order = remember { mutableStateListOf<HomeModule>() }
    var hidden by remember { mutableStateOf<Set<HomeModule>>(emptySet()) }
    var dragging by remember { mutableStateOf(false) }
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragRemainder by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    LaunchedEffect(prefs.homeModuleOrder, prefs.homeModulesHidden) {
        if (!dragging) {
            order.clear()
            order.addAll(prefs.homeModuleOrder)
            hidden = prefs.homeModulesHidden
        }
    }

    fun finishDrag() {
        draggedIndex = -1
        dragRemainder = 0f
        dragging = false
        scope.launch { AppGraph.uiPrefsStore.setHomeModuleOrder(order.toList()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("首页内容") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            DescriptionText("长按左侧手柄拖动排序；开关决定这张卡片是否在首页显示。")
            Spacer(Modifier.height(8.dp))

            order.forEach { module ->
                // 按模块 key 保住组合节点：重排时手势协程不会被 Compose 重启
                key(module) {
                    val isDragging = draggedIndex >= 0 && order.getOrNull(draggedIndex) == module
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_HEIGHT)
                            .background(
                                color = if (isDragging) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Menu,
                            contentDescription = "拖动排序",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(40.dp)
                                .pointerInput(module) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedIndex = order.indexOf(module)
                                            dragRemainder = 0f
                                            dragging = true
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            dragRemainder += delta.y
                                            while (dragRemainder >= rowPx && draggedIndex < order.lastIndex) {
                                                val i = draggedIndex
                                                val moved = order.removeAt(i)
                                                order.add(i + 1, moved)
                                                draggedIndex = i + 1
                                                dragRemainder -= rowPx
                                            }
                                            while (dragRemainder <= -rowPx && draggedIndex > 0) {
                                                val i = draggedIndex
                                                val moved = order.removeAt(i)
                                                order.add(i - 1, moved)
                                                draggedIndex = i - 1
                                                dragRemainder += rowPx
                                            }
                                        },
                                        onDragEnd = { finishDrag() },
                                        onDragCancel = { finishDrag() },
                                    )
                                },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            module.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = module !in hidden,
                            onCheckedChange = { visible ->
                                hidden = if (visible) hidden - module else hidden + module
                                scope.launch { AppGraph.uiPrefsStore.setHomeModuleVisible(module, visible) }
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            TextButton(
                onClick = { scope.launch { AppGraph.uiPrefsStore.resetHomeModules() } },
            ) { Text("恢复默认顺序与显示") }
        }
    }
}
