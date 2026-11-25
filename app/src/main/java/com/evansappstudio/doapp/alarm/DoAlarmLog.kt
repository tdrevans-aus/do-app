package com.evansappstudio.doapp.alarm

import android.util.Log

object DoAlarmLog {
    const val TAG = "DoAlarm"

    fun fire(id: Long, scheduled: Long, actual: Long) {
        Log.i(TAG, "DoAlarm fire id=$id scheduled=$scheduled actual=$actual")
    }

    fun debugAdd(id: Long, dueAt: Long, nagMinutes: Int?) {
        val logged = nagMinutes ?: 0
        Log.i(TAG, "DoAlarm debug-add id=$id dueAt=$dueAt nagMinutes=$logged")
    }

    fun snooze(id: Long, newDue: Long, option: Int) {
        Log.i(TAG, "DoAlarm snooze id=$id newDue=$newDue option=$option")
    }

    fun configSnooze(option: Int, kind: String, value: Int) {
        Log.i(TAG, "DoAlarm config-snooze option=$option kind=$kind value=$value")
    }

    fun qatConfig(message: String) {
        Log.i(TAG, message)
    }

    fun qat(id: Long, newDue: Long, slot: Int) {
        Log.i(TAG, "DoAlarm qat id=$id newDue=$newDue slot=$slot")
    }

    fun snoozeSkipped(id: Long, reason: String) {
        Log.i(TAG, "DoAlarm snooze skipped id=$id reason=$reason")
    }

    fun exactUnavailable(id: Long, error: Exception?) {
        if (error == null) {
            Log.w(TAG, "DoAlarm exact alarm unavailable id=$id; using setAndAllowWhileIdle")
        } else {
            Log.e(TAG, "DoAlarm exact alarm unavailable id=$id; using setAndAllowWhileIdle", error)
        }
    }

    fun inexactFailed(id: Long, error: Exception) {
        Log.e(TAG, "DoAlarm inexact alarm failed id=$id", error)
    }

    fun repeat(id: Long, rule: String, from: String, dueAt: Long, patternDue: Long?, next: String?) {
        val pattern = patternDue?.toString() ?: "null"
        val tail = if (next == null) "" else " next=$next"
        Log.i(TAG, "DoAlarm repeat id=$id rule=$rule from=$from dueAt=$dueAt patternDue=$pattern$tail")
    }

    fun repeatNext(id: Long, index: Int, at: Long) {
        Log.i(TAG, "DoAlarm repeat-next id=$id i=$index at=$at")
    }

    fun repeatAdvance(id: Long, newDue: Long, patternDue: Long?, from: String) {
        val pattern = patternDue?.toString() ?: "null"
        Log.i(TAG, "DoAlarm repeat-advance id=$id newDue=$newDue patternDue=$pattern from=$from")
    }

    fun shiftDue(id: Long, newDue: Long, mode: String, patternDue: Long?) {
        val pattern = patternDue?.toString() ?: "null"
        Log.i(TAG, "DoAlarm shift-due id=$id newDue=$newDue mode=$mode patternDue=$pattern")
    }

    fun configRepeatPrompt(value: String) {
        Log.i(TAG, "DoAlarm config-repeat-prompt value=$value")
    }

    fun handlingFailed(id: Long, error: Throwable) {
        Log.e(TAG, "DoAlarm handling failed id=$id", error)
    }
}
