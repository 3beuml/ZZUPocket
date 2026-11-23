package com.zzupocket.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zzupocket.app.data.ai.AiProvider
import com.zzupocket.app.data.ai.AiProviderConfig
import com.zzupocket.app.ui.components.GlassCard

/**
 * 更多 → 账号 → AI 提供商。
 *
 * 这里只配置 OpenAI 兼容的摘要服务；不会自动调用，也不经过项目后端。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiProviderScreen(
    onBack: () -> Unit,
    vm: AiProviderViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 提供商") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("摘要服务", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "支持 DeepSeek 和任意 OpenAI 兼容接口。只有在资讯页点「AI 提炼 / AI 速览」时才会发送文章；不自动调用，也没有项目后端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        AiProvider.entries.forEachIndexed { index, provider ->
                            SegmentedButton(
                                selected = ui.provider == provider,
                                onClick = { vm.selectProvider(provider) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = AiProvider.entries.size,
                                ),
                            ) {
                                Text(provider.label)
                            }
                        }
                    }
                }
            }

            GlassCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = ui.baseUrl,
                        onValueChange = vm::setBaseUrl,
                        label = { Text("Base URL") },
                        placeholder = { Text(AiProviderConfig.DEFAULT_BASE_URL) },
                        supportingText = {
                            Text("留空默认 DeepSeek；保存时自动补 /chat/completions")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = ui.model,
                        onValueChange = vm::setModel,
                        label = { Text("模型") },
                        placeholder = { Text(AiProviderConfig.DEFAULT_MODEL) },
                        supportingText = {
                            Text("留空默认 deepseek-chat；自定义服务商请填写实际模型名")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = ui.keyInput,
                        onValueChange = vm::setKey,
                        label = { Text("API Key") },
                        placeholder = {
                            Text(if (ui.hasKey) "已保存；留空保持不变" else "sk-...")
                        },
                        supportingText = {
                            Text("仅保存在本机加密存储，不进入备份、不写日志")
                        },
                        singleLine = true,
                        visualTransformation = if (ui.showKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = vm::toggleShowKey) {
                                Icon(
                                    if (ui.showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (ui.showKey) "隐藏 Key" else "显示 Key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = vm::test,
                            enabled = !ui.testing,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (ui.testing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp).size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text("测试连接")
                        }
                        Button(
                            onClick = vm::save,
                            enabled = !ui.testing,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("保存")
                        }
                    }

                    if (ui.hasKey) {
                        TextButton(onClick = vm::clearKey) {
                            Text("清除已保存的 API Key", color = MaterialTheme.colorScheme.error)
                        }
                    }

                    ui.message?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ui.messageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }

            Text(
                "AI 生成内容可能有误；重要时间、地点与资格以官网原文为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
