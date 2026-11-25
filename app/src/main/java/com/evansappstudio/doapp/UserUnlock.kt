package com.evansappstudio.doapp

import android.content.Context
import android.os.UserManager

object UserUnlock {
    fun isUnlocked(context: Context): Boolean {
        val userManager = context.getSystemService(UserManager::class.java) ?: return true
        return userManager.isUserUnlocked
    }
}
