package com.zzupocket.app.core

/**
 * 统一的失败类型。
 *
 * 刻意区分这几种，因为界面上要给完全不同的提示：
 *  - [ReLoginRequired]：令牌彻底失效 → 引导重新登录
 *  - [MfaRequired]    ：需要短信验证码 → 弹验证码输入
 *  - [Network]        ：网络问题 → 提示"数据截至 XX:XX"而不是报错
 *  - [Parse]          ：学校接口结构变了 → 记日志，提示"数据格式异常"
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 需要用户重新登录（令牌失效且无法自动刷新） */
    class ReLoginRequired(message: String = "登录已失效，请重新登录") : AppError(message)

    /** 需要短信验证码才能继续登录 */
    class MfaRequired(val state: String, val gid: String, val attestBaseUrl: String) :
        AppError("需要短信验证码")

    /** 网络不可达 / 超时 */
    class Network(message: String, cause: Throwable? = null) : AppError(message, cause)

    /** 学校接口返回的结构和预期不符（改版了） */
    class Parse(message: String, cause: Throwable? = null) : AppError(message, cause)

    /** 服务端明确返回业务错误（code != 0） */
    class Server(val code: Int, message: String) : AppError(message)

    /** 账号或密码错误 */
    class BadCredentials(message: String = "学号或密码错误") : AppError(message)
}
