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

    fun handlingFailed(id: Long, error: Throwable) {
        Log.e(TAG, "DoAlarm handling failed id=$id", error)
    }
}
