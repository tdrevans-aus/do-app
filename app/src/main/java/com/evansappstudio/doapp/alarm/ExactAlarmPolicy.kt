package com.evansappstudio.doapp.alarm

/**
 * USE builds declare USE_EXACT_ALARM, plus SCHEDULE_EXACT_ALARM with
 * maxSdkVersion 32 so API 31 and 32 can still be granted exact alarms.
 * SCHEDULE builds declare only SCHEDULE_EXACT_ALARM, with no maxSdk.
 *
 * From API 31 up, scheduling always asks canScheduleExactAlarms() first.
 * When that is false the list shows the banner and the button that opens
 * the system Alarms & reminders screen. API 31-32 is the case that depends
 * on the maxSdk 32 permission: USE_EXACT_ALARM does not exist yet, so the
 * grant is the SCHEDULE_EXACT_ALARM toggle.
 */
object ExactAlarmPolicy {
    const val EXACT_ALARM_CHECK_MIN_SDK = 31
    const val SCHEDULE_EXACT_ON_USE_BUILD_MAX_SDK = 32

    fun shouldShowExactAlarmBanner(sdkInt: Int, canScheduleExactAlarms: Boolean): Boolean {
        return sdkInt >= EXACT_ALARM_CHECK_MIN_SDK && !canScheduleExactAlarms
    }
}
