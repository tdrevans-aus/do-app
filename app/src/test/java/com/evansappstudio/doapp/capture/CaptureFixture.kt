package com.evansappstudio.doapp.capture

import com.evansappstudio.doapp.alarm.Fixture
import com.evansappstudio.doapp.alarm.schedulerFixture
import java.time.ZoneId
import java.time.ZonedDateTime

/** Wednesday 15 November 2023, 6:13 am in Perth: the Scheduler fixture's clock. */
internal val PERTH: ZoneId = ZoneId.of("Australia/Perth")

internal fun perth(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, PERTH).toInstant().toEpochMilli()

internal class CaptureFixture(val fx: Fixture = schedulerFixture(), enabled: Boolean = true) {
    val capture = CaptureCommands(fx.commands)
    val agent = AgentFunctions(
        enabled = enabled,
        commands = fx.commands,
        active = { fx.dao.active() },
        byId = { id -> fx.dao.getById(id) },
        defaultNagMinutes = { 5 },
        clock = { fx.clockMillis },
        zone = { fx.zone },
    )

    init {
        fx.zone = PERTH
    }

    val later: Long get() = fx.clockMillis + 60 * 60_000L
}
