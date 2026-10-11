package com.tester.appswitcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 无障碍服务。两个用途：
 *   1. 通过 typeWindowStateChanged 事件记录「当前前台包名」，用于校验切换是否真的成功；
 *   2. 切换失败时降级：连按两次「最近任务」等价于切回上一个应用。
 *
 * 无障碍没开时 App 不会崩溃，只会在主界面与悬浮面板上给出中文提示。
 */
class SwitchAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (pkg.isNullOrEmpty()) return
        currentForegroundPackage = pkg
        Log.i(TAG, "前台窗口变化: $pkg (eventTime=${event.eventTime})")
    }

    /**
     * 实时查询当前处于活动状态的应用窗口包名。
     * 比只依赖事件记录更可靠：事件可能被系统窗口、输入法或过渡动画覆盖。
     * 需要在配置里声明 flagRetrieveInteractiveWindows。
     */
    fun activeWindowPackage(): String? {
        return try {
            val list = windows ?: return null
            val app = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val picked = app.firstOrNull { it.isActive } ?: app.firstOrNull { it.isFocused }
            picked?.root?.packageName?.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "读取活动窗口失败: ${t.message}")
            null
        }
    }

    override fun onInterrupt() {
        // 本服务不处理中断，留空即可
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        isRunning = false
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        isRunning = false
        super.onDestroy()
    }

    /** 降级用：按一次「最近任务」；短时间内连按两次即切回上一个应用 */
    fun pressRecents() {
        try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (t: Throwable) {
            Log.w(TAG, "pressRecents 失败: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "SwitchAccessibility"

        /** 已连接的服务实例；未开启时为 null */
        @Volatile
        var instance: SwitchAccessibilityService? = null

        /** 无障碍服务当前是否已连接 */
        @Volatile
        var isRunning: Boolean = false

        /** 由无障碍事件记录下来的当前前台包名 */
        @Volatile
        var currentForegroundPackage: String? = null

        /** 从系统设置里读无障碍服务是否已开启（主界面自检用） */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, SwitchAccessibilityService::class.java)
            val enabled = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (t: Throwable) {
                null
            } ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            for (item in splitter) {
                val cn = ComponentName.unflattenFromString(item) ?: continue
                if (cn == expected) return true
            }
            return false
        }
    }
}
