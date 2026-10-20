package com.zzupocket.app.data.repo

import com.zzupocket.app.core.WeekMath
import com.zzupocket.app.data.gms.GmsScheduleMapper
import com.zzupocket.app.data.prefs.UiPrefsStore
import com.zzupocket.app.domain.Session
import java.time.LocalDate

/**
 * 「某一天有哪些课」的**唯一口径**：本科 + 研究生合并。
 *
 * 为什么必须只有一个入口：今日页和桌面小组件都在问同一个问题。以前各自写一套
 * （都只看本科学期），研究生账号就会在今日页看到"今天没有课"、组件也空着 ——
 * 而课表页明明有课。两处口径分开写，迟早再次不一致。
 */
object TodayTimetable {

    /**
     * 某天的全部课程，按节次排序。
     *
     * - 本科：当前学期 + `resolveCurrentWeek`
     * - 研究生：用周次设置算出的教学周（研究生没有学期行，也能算）
     *
     * 两边都没有数据就返回空表。
     */
    suspend fun sessionsOn(
        repo: ScheduleRepository,
        prefs: UiPrefsStore,
        date: LocalDate,
    ): List<Session> {
        val out = ArrayList<Session>()

        runCatching {
            val semester = repo.currentSemester() ?: return@runCatching
            val week = repo.resolveCurrentWeek(semester, date)
            out += repo.sessionsOn(semester.id, date, week)
        }

        runCatching {
            val week = gradWeekAt(prefs, date) ?: return@runCatching
            out += repo.sessionsOn(GmsScheduleMapper.GRAD_SEMESTER_ID, date, week)
        }

        return out.distinctBy { it.id }.sortedBy { it.startUnit }
    }

    /** 研究生在 [date] 那天属于第几周；没有周次基准（没设置过）返回 null */
    suspend fun gradWeekAt(prefs: UiPrefsStore, date: LocalDate): Int? {
        val settings = runCatching { prefs.weekSettings(GmsScheduleMapper.GRAD_SEMESTER_ID) }
            .getOrNull() ?: return null
        val start = settings.weekOneStart ?: return null
        val week = WeekMath.weekIndexAt(start, date)
        return week.takeIf { it >= 1 }
    }

    /**
     * 有没有"任何"课表数据（本科缓存或研究生缓存）。
     *
     * 组件用它区分「还没同步过」和「今天没课」——这两种情况显示不同文案，不能混。
     */
    suspend fun hasAnySchedule(repo: ScheduleRepository, prefs: UiPrefsStore): Boolean {
        val undergrad = runCatching { repo.currentSemester()?.let { repo.hasCourseData(it.id) } }
            .getOrNull() == true
        if (undergrad) return true
        return gradWeekAt(prefs, LocalDate.now()) != null
    }
}
