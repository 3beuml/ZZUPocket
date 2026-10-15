package com.zzupocket.app.domain

/**
 * 学籍层次 —— 决定课表去哪个系统取。
 *
 * 背景：郑州大学的课表分两套系统。
 *  - **本科教务**（`jwxt.zzu.edu.cn`，树维 eams）：本科生。课程、节次、教室都在这里。
 *  - **研究生系统**（`gmsstu.zzu.edu.cn/stu`，走学校 CAS 跳转）：研究生。
 *    本科教务对研究生账号**根本没有学生角色**，请求会被网关拒绝（401/403）。
 *
 * 怎么判断：**别去问"你是不是研究生"**，而是直接拿用户自己的令牌去问本科教务 ——
 * 200 说明它是本科账号；被拒说明不是。这条信号校外可用，而学校那个身份接口
 * （`authx-service.s.zzu.edu.cn`）只在校园网内可解析（实测 UnknownHostException）。
 */
enum class AcademicTrack {
    /** 本科教务接受这个账号 ⇒ 走现有课表链路 */
    UNDERGRAD,

    /** 本科教务拒绝 ⇒ 研究生（或其他非本科身份）⇒ 课表在另一个系统，本版未接入 */
    NOT_UNDERGRAD,

    /** 判断不出来（网络问题、状态码意外）⇒ 保持原有提示，不要瞎猜 */
    UNKNOWN,
    ;

    companion object {
        /**
         * 由"本科教务用户信息"请求的结果推断学籍层次。**纯函数，便于单测。**
         *
         * @param httpStatus 该请求的 HTTP 状态码（网络失败传 0）
         * @param identity   响应里的身份字段（`identity` / `defaultIdentity`，如 `STUDENT`）
         * @param account    学号（**仅用于判断，不参与任何展示**）
         */
        fun fromUndergradProbe(httpStatus: Int, identity: String? = null, account: String? = null): AcademicTrack = when {
            httpStatus in 200..299 -> UNDERGRAD
            httpStatus == 401 || httpStatus == 403 -> NOT_UNDERGRAD
            else -> UNKNOWN
        }
    }
}
