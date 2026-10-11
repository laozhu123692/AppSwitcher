package com.tester.appswitcher

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置持久化：App A / App B 包名、每个 App 的停留时长、自动停止方式。
 * 选中的 App 会一直保存，下次打开 App 不用重新选。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** App A 的包名，未选择时为 null */
    var packageA: String?
        get() = sp.getString(KEY_A, null)
        set(value) {
            sp.edit().putString(KEY_A, value).apply()
        }

    /** App B 的包名，未选择时为 null */
    var packageB: String?
        get() = sp.getString(KEY_B, null)
        set(value) {
            sp.edit().putString(KEY_B, value).apply()
        }

    /** 每个 App 的停留时长（毫秒），范围 200 ~ 10000，默认 2000 */
    var intervalMs: Int
        get() = sp.getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MS)
        set(value) {
            sp.edit()
                .putInt(KEY_INTERVAL, value.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS))
                .apply()
        }

    /** 自动停止方式：MODE_NONE / MODE_ROUNDS / MODE_DURATION */
    var stopMode: Int
        get() = sp.getInt(KEY_STOP_MODE, MODE_NONE)
        set(value) {
            sp.edit().putInt(KEY_STOP_MODE, value).apply()
        }

    /** 按轮次自动停止：轮数，默认 100（1 轮 = A→B 一个完整来回 = 2 次切换） */
    var stopRounds: Int
        get() = sp.getInt(KEY_STOP_ROUNDS, DEFAULT_STOP_ROUNDS)
        set(value) {
            sp.edit().putInt(KEY_STOP_ROUNDS, value.coerceIn(1, 100000)).apply()
        }

    /** 按时长自动停止：分钟数，默认 10 */
    var stopMinutes: Int
        get() = sp.getInt(KEY_STOP_MINUTES, DEFAULT_STOP_MINUTES)
        set(value) {
            sp.edit().putInt(KEY_STOP_MINUTES, value.coerceIn(1, 24 * 60)).apply()
        }

    /** App A 与 App B 是否都已选择 */
    val hasBothApps: Boolean
        get() = !packageA.isNullOrEmpty() && !packageB.isNullOrEmpty()

    companion object {
        private const val NAME = "app_switcher_prefs"
        private const val KEY_A = "package_a"
        private const val KEY_B = "package_b"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_STOP_MODE = "stop_mode"
        private const val KEY_STOP_ROUNDS = "stop_rounds"
        private const val KEY_STOP_MINUTES = "stop_minutes"

        /** 不自动停止，只能手动停止 */
        const val MODE_NONE = 0

        /** 按轮次自动停止 */
        const val MODE_ROUNDS = 1

        /** 按时长自动停止 */
        const val MODE_DURATION = 2

        const val DEFAULT_INTERVAL_MS = 2000
        const val MIN_INTERVAL_MS = 200
        const val MAX_INTERVAL_MS = 10000
        const val DEFAULT_STOP_ROUNDS = 100
        const val DEFAULT_STOP_MINUTES = 10
    }
}
