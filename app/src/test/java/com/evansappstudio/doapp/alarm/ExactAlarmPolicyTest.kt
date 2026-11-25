package com.evansappstudio.doapp.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmPolicyTest {
    @Test
    fun api31And32ShowTheBannerWhenExactAlarmsAreNotGranted() {
        assertTrue(ExactAlarmPolicy.shouldShowExactAlarmBanner(31, canScheduleExactAlarms = false))
        assertTrue(ExactAlarmPolicy.shouldShowExactAlarmBanner(32, canScheduleExactAlarms = false))
        assertFalse(ExactAlarmPolicy.shouldShowExactAlarmBanner(31, canScheduleExactAlarms = true))
        assertFalse(ExactAlarmPolicy.shouldShowExactAlarmBanner(32, canScheduleExactAlarms = true))
    }

    @Test
    fun bannerFollowsTheSameCheckOnApi33AndAbove() {
        assertTrue(ExactAlarmPolicy.shouldShowExactAlarmBanner(33, canScheduleExactAlarms = false))
        assertFalse(ExactAlarmPolicy.shouldShowExactAlarmBanner(36, canScheduleExactAlarms = true))
    }

    @Test
    fun preApi31DoesNotShowTheBanner() {
        assertFalse(ExactAlarmPolicy.shouldShowExactAlarmBanner(30, canScheduleExactAlarms = false))
        assertFalse(ExactAlarmPolicy.shouldShowExactAlarmBanner(26, canScheduleExactAlarms = false))
    }
}
