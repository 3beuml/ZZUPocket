package com.zzupocket.app.core

/**
 * 学期范围过滤：只显示入学年及以后的学期。
 *
 * 入学年从学号前 4 位推断（如 202400000001 → 2024）。**推断不出来就不过滤**。
 */
object Enrollment {

    /** 合理的年份区间，挡掉 "0000"、乱码这类误判 */
    private const val MIN_YEAR = 1980
    private const val MAX_YEAR = 2100

    private val LEADING_YEAR = Regex("^(\\d{4})")
    private val ANY_YEAR = Regex("(\\d{4})")

    /** 学号 → 入学年；解析不出返回 null */
    fun parseFromStudentId(account: String?): Int? = pick(LEADING_YEAR, account)

    /**
     * "2026-2027" / "2026-2027-1" / "1996-1997" → 起始年。
     *
     * ⚠️ 这里以前只认 `20` 开头的年份。于是学校下发的 1996–2000 那些学期解析成 null，
     * 而"解析不出就保留"的兜底会把它们全留下 —— 表现就是用户设了入学年 2024 之后，
     * 学期列表里**还能看到 1997 年的学期**，看着像设置没生效。
     */
    fun semesterStartYear(schoolYear: String?, nameZh: String? = null): Int? =
        pick(LEADING_YEAR, schoolYear)
            ?: pick(LEADING_YEAR, nameZh)
            ?: pick(ANY_YEAR, schoolYear)
            ?: pick(ANY_YEAR, nameZh)

    private fun pick(regex: Regex, text: String?): Int? {
        val value = regex.find(text?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (value in MIN_YEAR..MAX_YEAR) value else null
    }

    /**
     * 过滤学期列表。`enrollmentYear` 为 null 时原样返回。
     *
     * [hideUnknown] = 解析不出年份的也一并藏掉。**手动填了入学年就按用户说的来** ——
     * 否则总会有漏网的学期让人以为设置没生效。没填的时候才保留全部。
     */
    fun filterSince(
        semesters: List<com.zzupocket.app.domain.Semester>,
        enrollmentYear: Int?,
        hideUnknown: Boolean = false,
    ): List<com.zzupocket.app.domain.Semester> {
        if (enrollmentYear == null) return semesters
        return semesters.filter { s ->
            val y = semesterStartYear(s.schoolYear, s.nameZh)
            if (y == null) !hideUnknown else y >= enrollmentYear
        }
    }
}
