package com.zzupocket.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.phase) {
        if (state.phase == LoginPhase.Done) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "使用学校统一身份认证登录",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "账号密码与豫见郑大、校园网一致",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ⚠️ 副作用必须如实告知，否则用户会以为 App 把官方 App 弄坏了
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = "首次登录会使手机上的「豫见郑大」下线一次（学校统一认证机制导致），之后不会。\n" +
                        "登录后建议去“安全中心”把本设备设为可信设备，以后就不需要短信验证码了。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            if (state.phase == LoginPhase.NeedSms) {
                // 倒计时放在**输入框上方**：用户等着的那串数字就在他输入验证码的地方，
                // 不用去看下面的按钮；也是"学校那边多久才允许重发"的唯一提示。
                Text(
                    text = if (state.resendCooldownSec > 0) {
                        "验证码已发送，${state.resendCooldownSec} 秒后可重新发送。" +
                            "短信可能要 1 分钟才到，先别急着重发（重发会把先到的码作废）"
                    } else {
                        "还没收到？可以点下面的「重新发送」。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.resendCooldownSec > 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                OutlinedTextField(
                    value = state.smsCode,
                    onValueChange = vm::onSmsCodeChange,
                    label = { Text("短信验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::submitSms, enabled = !state.busy) { Text("提交验证码") }
                    Spacer(Modifier.width(12.dp))
                    // 冷却倒计时：学校约 1 分钟才允许重发，短信本身也可能要 1 分钟才到
                    TextButton(
                        onClick = vm::resendSms,
                        enabled = !state.busy && state.resendCooldownSec == 0,
                    ) {
                        Text(
                            if (state.resendCooldownSec > 0) {
                                "重新发送（${state.resendCooldownSec} 秒）"
                            } else {
                                "重新发送"
                            },
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = state.account,
                    onValueChange = vm::onAccountChange,
                    label = { Text("学号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = vm::onPasswordChange,
                    label = { Text("统一身份认证密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                // 输入体检的提示：中文/全角/过长都在这里当场说清，别等提交后拿到一句"凭据不对"
                state.inputIssue?.let { issue ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = issue.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        if (issue.fixableByWidening) {
                            TextButton(onClick = vm::fixFullWidth) { Text("转为半角") }
                        }
                    }
                }
                Button(
                    onClick = vm::submitCredentials,
                    enabled = !state.busy && state.inputIssue == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("登录")
                }
            }

            if (state.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("正在处理…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.notice?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
