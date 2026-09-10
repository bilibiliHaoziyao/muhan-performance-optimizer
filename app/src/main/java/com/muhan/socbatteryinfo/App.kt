package com.muhan.socbatteryinfo

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import com.google.android.material.color.DynamicColors
import com.muhan.socbatteryinfo.util.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.applyTheme(this)
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            // 在每个 Activity 创建前根据开关状态应用莫奈取色（动态取色）
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Prefs.isMonetEnabled(this@App)) {
                    DynamicColors.applyToActivityIfAvailable(activity)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
