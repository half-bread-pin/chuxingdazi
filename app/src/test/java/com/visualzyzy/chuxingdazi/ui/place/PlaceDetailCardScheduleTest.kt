package com.visualzyzy.chuxingdazi.ui.place

import com.visualzyzy.chuxingdazi.data.db.dao.ScheduleWithTripRow
import com.visualzyzy.chuxingdazi.data.db.entity.ScheduleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** #1（2026-11-24）：S07「已安排」区块的未出行过滤与时间文案单测（now 参数注入，不用真时钟）。 */
class PlaceDetailCardScheduleTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 27)

    private fun row(
        planDate: String,
        startMin: Int,
        endMin: Int,
        tripId: String = "trip1",
        tripName: String = "津皖赣7日",
    ) = ScheduleWithTripRow(
        schedule = ScheduleEntity(
            id = "$planDate-$startMin",
            tripId = tripId,
            placeId = "place1",
            planDate = planDate,
            startMin = startMin,
            endMin = endMin,
            createdAt = 0L,
            updatedAt = 0L,
        ),
        tripName = tripName,
    )

    // ---- filterUpcomingSchedules ----

    @Test
    fun todayEndedSegmentDropped() {
        // 今天 08:00–09:00 已结束（now=12:00）→ 剔除
        val rows = listOf(row("2026-11-27", 480, 540))
        assertTrue(filterUpcomingSchedules(rows, today, 720).isEmpty())
    }

    @Test
    fun ongoingSegmentKept() {
        // 今天 10:00–14:00 正在进行（now=12:00，endMin-1=839 ≥ 720）→ 保留
        val rows = listOf(row("2026-11-27", 600, 840))
        assertEquals(1, filterUpcomingSchedules(rows, today, 720).size)
    }

    @Test
    fun todayUpcomingSegmentKept() {
        // 今天 20:00–次日 08:43（跨天，now=12:00）→ 保留
        val rows = listOf(row("2026-11-27", 1200, 1200 + (8 * 60 + 43)))
        assertEquals(1, filterUpcomingSchedules(rows, today, 720).size)
    }

    @Test
    fun pastTripSegmentsAllDropped() {
        // 纯历史行程（11/20 一段）→ 整批剔除
        val rows = listOf(
            row("2026-11-20", 480, 540),
            row("2026-11-20", 900, 1500),
        )
        assertTrue(filterUpcomingSchedules(rows, today, 720).isEmpty())
    }

    @Test
    fun dirtyPlanDateKeptFailOpen() {
        // planDate 解析失败 → 该行保留（fail-open）
        val rows = listOf(row("not-a-date", 480, 540))
        assertEquals(1, filterUpcomingSchedules(rows, today, 720).size)
    }

    // ---- scheduleSpanText ----

    @Test
    fun sameDaySpanText() {
        assertEquals("11/27 20:00–21:30", scheduleSpanText(row("2026-11-27", 1200, 1290)))
    }

    @Test
    fun overnightSpanText() {
        // 20:00 起跨天到次日 08:43 → endMin = 1200 + (1440 + 8*60+43) = 1963 →「次日 08:43」
        assertEquals("11/27 20:00–次日 08:43", scheduleSpanText(row("2026-11-27", 1200, 1963)))
    }

    @Test
    fun twoDaySpanText() {
        // 20:00 起跨两天多（endMin=2623 → spannedDays=1？验证：2622/1440=1 → 次日）。
        // 取跨两天整：endMin=2881（次日 24:01，即第三天 00:01）→ spannedDays=2 → 结束日 = 11/29
        val r = row("2026-11-27", 1200, 2881)
        assertEquals(2, r.schedule.spannedDays)
        assertEquals("11/27 20:00–11/29 00:01", scheduleSpanText(r))
    }

    @Test
    fun dirtyPlanDateSpanTextNull() {
        assertEquals(null, scheduleSpanText(row("not-a-date", 480, 540)))
    }
}
