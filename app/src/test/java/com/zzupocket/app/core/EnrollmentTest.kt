package com.zzupocket.app.core

import com.zzupocket.app.domain.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentTest {

    private fun semester(schoolYear: String, nameZh: String = schoolYear) = Semester(
        id = schoolYear.hashCode(),
        nameZh = nameZh,
        schoolYear = schoolYear,
        startDate = null,
        endDate = null,
        weekStartOnSunday = false,
        weekIndices = (1..18).toList(),
        isCurrent = false,
    )

    @Test
    fun `学号前四位就是入学年`() {
        assertEquals(2024, Enrollment.parseFromStudentId("202400000001"))
        assertEquals(1999, Enrollment.parseFromStudentId("199900000042"))
        assertNull(Enrollment.parseFromStudentId("abc"))
        assertNull(Enrollment.parseFromStudentId(null))
        // 明显不合理的年份不接受
        assertNull(Enrollment.parseFromStudentId("000000000042"))
    }

    @Test
    fun `学期名的起始年支持 19xx`() {
        assertEquals(2026, Enrollment.semesterStartYear("2026-2027"))
        assertEquals(1996, Enrollment.semesterStartYear("1996-1997"))
        assertEquals(1999, Enrollment.semesterStartYear(null, "1999-2000-2"))
    }

    @Test
    fun `学校下发的 19xx 学期不再漏网`() {
        // 这是用户实测报的问题：设了 2024，列表里还有 1997 年的学期
        val all = listOf(
            semester("2026-2027"),
            semester("2025-2026"),
            semester("2024-2025"),
            semester("2023-2024"),
            semester("1999-2000"),
            semester("1997-1998"),
            semester("1996-1997"),
        )
        val visible = Enrollment.filterSince(all, 2024, hideUnknown = true)
        assertEquals(
            listOf("2026-2027", "2025-2026", "2024-2025"),
            visible.map { it.schoolYear },
        )
    }

    @Test
    fun `不填入学年就不过滤`() {
        val all = listOf(semester("1997-1998"), semester("2026-2027"))
        assertEquals(2, Enrollment.filterSince(all, null).size)
    }

    @Test
    fun `没开 hideUnknown 时保留解析不出的学期`() {
        val all = listOf(semester(""), semester("2026-2027"))
        assertEquals(2, Enrollment.filterSince(all, 2024, hideUnknown = false).size)
        assertEquals(1, Enrollment.filterSince(all, 2024, hideUnknown = true).size)
    }
}
