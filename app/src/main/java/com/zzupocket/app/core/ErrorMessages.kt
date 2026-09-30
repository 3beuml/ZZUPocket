package com.zzupocket.app.core

/** 把各类异常翻译成用户看得懂的一句话（界面层统一用它） */
fun Throwable.toUserMessage(): String = when (this) {
    is AppError.BadCredentials -> message ?: "学号或密码错误"
    is AppError.MfaRequired -> "需要短信验证码"
    is AppError.Network -> "网络不可达，请检查网络后重试"
    // ⚠️ 必须带上具体信息：只显示"可能已改版"会让任何解析问题都无法定位
    is AppError.Parse -> message?.let { "数据解析异常：$it" }
        ?: "学校接口返回了意料之外的数据（可能已改版）"
    is AppError.Server -> "学校服务返回错误：$message"
    is AppError.ReLoginRequired -> message ?: "登录已失效"
    else -> message ?: javaClass.simpleName
}
