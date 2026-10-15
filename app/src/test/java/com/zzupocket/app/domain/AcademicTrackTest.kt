package com.zzupocket.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 学籍判定的纯逻辑测试。
 *
 * 这条判断是"按学籍路由数据源"的地基：本科教务认令牌 ⇒ 本科生；被拒 ⇒ 研究生。
 * 判错了就会把本科生的课表弄没（或者反过来，让研究生一直看到"重新登录"）。
 */
class AcademicTrackTest {

    @Test
    fun `本科教务 200 判为本科生`() {
        assertEquals(
            AcademicTrack.UNDERGRAD,
            AcademicTrack.fromUndergradProbe(httpStatus = 200, identity = "STUDENT", account = "202415000443"),
        )
    }

    @Test
    fun `被网关拒绝判为非本科`() {
        assertEquals(AcademicTrack.NOT_UNDERGRAD, AcademicTrack.fromUndergradProbe(httpStatus = 401))
        assertEquals(AcademicTrack.NOT_UNDERGRAD, AcademicTrack.fromUndergradProbe(httpStatus = 403))
    }

    @Test
    fun `网络失败与其他状态码一律不猜`() {
        // 0 = 网络失败；5xx = 服务端故障。都不能推出"他不是本科生"
        assertEquals(AcademicTrack.UNKNOWN, AcademicTrack.fromUndergradProbe(httpStatus = 0))
        assertEquals(AcademicTrack.UNKNOWN, AcademicTrack.fromUndergradProbe(httpStatus = 500))
        assertEquals(AcademicTrack.UNKNOWN, AcademicTrack.fromUndergradProbe(httpStatus = 404))
        assertEquals(AcademicTrack.UNKNOWN, AcademicTrack.fromUndergradProbe(httpStatus = 302))
    }

    @Test
    fun `身份字段不影响判定（只看状态码）`() {
        // 即使服务端把身份字段写成别的值，也以"本科教务认不认"为准
        assertEquals(AcademicTrack.UNDERGRAD, AcademicTrack.fromUndergradProbe(200, identity = "GRADUATE"))
        assertEquals(AcademicTrack.NOT_UNDERGRAD, AcademicTrack.fromUndergradProbe(403, identity = "STUDENT"))
    }
}
