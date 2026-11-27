package com.evansappstudio.doapp.alarm

import com.evansappstudio.doapp.notify.ReminderNotifier

/**
 * The alarm engine and the notifier stay concrete. Scheduler talks to these
 * ports so a test can arm and post without a device. The production adapters
 * forward every call unchanged.
 */
interface AlarmArm {
    fun schedule(id: Long, triggerAtMillis: Long)
    fun cancel(id: Long)
}

interface ReminderAlerts {
    fun show(id: Long, title: String, dueAt: Long, requested: AlertLevel): AlertDecision
    fun dismiss(id: Long)
    fun showingIds(): List<Long>
    fun rebuildShowing()
    fun refreshIfShowing(id: Long, title: String, dueAt: Long, requested: AlertLevel)
    fun decide(requested: AlertLevel): AlertDecision
}

fun AlarmScheduler.asArm(): AlarmArm = object : AlarmArm {
    override fun schedule(id: Long, triggerAtMillis: Long) {
        this@asArm.schedule(id, triggerAtMillis)
    }

    override fun cancel(id: Long) {
        this@asArm.cancel(id)
    }
}

fun ReminderNotifier.asAlerts(): ReminderAlerts = object : ReminderAlerts {
    override fun show(id: Long, title: String, dueAt: Long, requested: AlertLevel): AlertDecision {
        return this@asAlerts.show(id, title, dueAt, requested)
    }

    override fun dismiss(id: Long) {
        this@asAlerts.dismiss(id)
    }

    override fun showingIds(): List<Long> = this@asAlerts.showingIds()

    override fun rebuildShowing() {
        this@asAlerts.rebuildShowing()
    }

    override fun refreshIfShowing(id: Long, title: String, dueAt: Long, requested: AlertLevel) {
        this@asAlerts.refreshIfShowing(id, title, dueAt, requested)
    }

    override fun decide(requested: AlertLevel): AlertDecision = this@asAlerts.decide(requested)
}
