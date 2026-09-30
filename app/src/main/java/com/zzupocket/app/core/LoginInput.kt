package com.zzupocket.app.core

/**
 * 登录输入的"体检"。
 *
 * 为什么需要它：学校统一认证只接受 **ASCII 半角**，而中文输入法在中文/全角状态下打出来的
 * 数字和字母是**全角**的（`２０２６` vs `2026`），屏幕上几乎看不出区别。这类输入会：
 *
 *  - 在服务端被判成"凭据不对"（表现为 `HTTP 401：Bad credentials`，用户完全无从理解），或
 *  - 在客户端加密阶段就炸：密码是整块 RSA 加密的，PKCS#1 v1.5 + 2048 位密钥**单块最多 245 字节**，
 *    中文每字 3 字节，超限时 `Cipher.doFinal` 直接抛 `IllegalBlockSizeException`（界面上是一句英文技术错误）。
 *
 * 这两种情况都必须在**提交之前**拦下来，并且告诉用户"到底是什么字符不对、怎么改"。
 */
object LoginInput {

    /** RSA/PKCS#1 v1.5（2048 位）单块上限 245 字节，留出余量 */
    const val MAX_RSA_BYTES = 200

    sealed class Issue {
        abstract val message: String

        /** 能否靠"转为半角"修好（只有全角问题可以） */
        open val fixableByWidening: Boolean = false

        /** 全角 ASCII（！…～）或全角空格 —— 几乎一定是输入法状态问题 */
        object FullWidth : Issue() {
            override val message =
                "检测到全角字符（输入法处于中文/全角状态时打出来的）。统一认证只认半角，" +
                    "请切到英文输入法重输，或点下面的「转为半角」"
            override val fixableByWidening = true
        }

        /** 中文等非 ASCII 字符 */
        object NonAscii : Issue() {
            override val message =
                "检测到中文或其他非英文字符。统一认证只接受英文、数字和半角符号；" +
                    "可能是输入法自动联想/自动纠正带进来的，请逐字核对"
        }

        /** 超过单块 RSA 能加密的长度 */
        class TooLong(val bytes: Int) : Issue() {
            override val message =
                "输入过长（$bytes 字节，认证接口单次上限 $MAX_RSA_BYTES 字节）。" +
                    "请检查是否粘贴了多余内容"
        }
    }

    /** 体检；返回 null 表示可以放心提交 */
    fun inspect(text: String): Issue? {
        if (text.isEmpty()) return null
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_RSA_BYTES) return Issue.TooLong(bytes)
        var hasNonAscii = false
        for (ch in text) {
            if (isFullWidthAscii(ch)) return Issue.FullWidth
            if (ch.code > 0x7F) hasNonAscii = true
        }
        return if (hasNonAscii) Issue.NonAscii else null
    }

    /** 全角 ASCII（！= U+FF01 … ～= U+FF5E）与全角空格（U+3000）；**中文不在此列** */
    private fun isFullWidthAscii(ch: Char): Boolean =
        ch.code in 0xFF01..0xFF5E || ch.code == 0x3000

    /**
     * 全角转半角：只动全角 ASCII 和全角空格，**中文原样保留**。
     *
     * 刻意做成"用户点一下才转"，不做自动转换 —— 万一真有人把全角字符设进密码里，
     * 自动转换会把正确的密码改坏。
     */
    fun toHalfWidth(text: String): String = buildString {
        text.forEach { ch ->
            when (val code = ch.code) {
                0x3000 -> append(' ')
                in 0xFF01..0xFF5E -> append((code - 0xFEE0).toChar())
                else -> append(ch)
            }
        }
    }

    /**
     * 短信发送失败时的友好化：学校对重发有频率限制（约 1 分钟），
     * 但它返回的原文常常只有一句含糊的话，用户不知道该干什么。
     */
    fun friendlySmsError(raw: String): String? =
        if (SMS_FREQUENT.containsMatchIn(raw)) "发送太频繁了，请等 1 分钟后再点「重新发送」" else null

    /**
     * 「重新发送验证码」的冷却还剩几秒。
     *
     * 抽成纯函数是因为这里有两个容易写错、又很难复现的点：
     *
     *  - **它是跨页面的**：发码时刻存在本地（`LoginPrefsStore`），用户退出登录页再进来时
     *    要按"已经过去了多久"接着算，而不是从 60 重新开始 —— 学校那边的限流不会因为我们
     *    重建了界面就重置，倒计时归零只会让用户以为可以重发、点下去却失败。
     *  - **两端都要夹住**：时钟倒退（用户改过系统时间/同步出错）会算出负数或超大值，
     *    直接拿去显示就成"请再等 -3 秒"或"请再等 999999 秒"。
     *
     * @param sentAtMillis 上一次成功发起发送的时刻；null 表示没发过
     * @return 剩余秒数，0 表示可以重发
     */
    fun cooldownLeftSec(sentAtMillis: Long?, nowMillis: Long, cooldownSec: Int): Int {
        if (sentAtMillis == null || cooldownSec <= 0) return 0
        val elapsed = (nowMillis - sentAtMillis) / 1000
        return (cooldownSec - elapsed).coerceIn(0, cooldownSec.toLong()).toInt()
    }

    private val SMS_FREQUENT = Regex("频繁|频率|过多|稍后再试|限流|too\\s*many|rate\\s*limit", RegexOption.IGNORE_CASE)
}
