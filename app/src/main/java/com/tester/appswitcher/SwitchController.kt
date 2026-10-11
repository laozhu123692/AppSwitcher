package com.tester.appswitcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 轮换引擎（单例）。持有状态机与计数，用 Handler 在主线程上排程，
 * 绝不使用 Thread.sleep 阻塞主线程。
 *
 * 状态机：IDLE → RUNNING → PAUSED → RUNNING … → STOPPED(手动) / FINISHED(定时或定量)
 *
 * 一次「切换」= 成功把某个目标 App 拉到前台（计数 +1）。
 * 一轮 = A→B 一个完整来回 = 2 次切换。
 */
object SwitchController {

    private const val TAG = "SwitchController"

    /** 校验轮询步长：每 150ms 回读一次前台包名 */
    private const val VERIFY_STEP_MS = 150L

    /** 最多轮询次数（约 0.9 秒），仍未到前台才判定失败 */
    private const val MAX_VERIFY_ATTEMPTS = 6

    /** 降级连按两次「最近任务」之后的等待时间 */
    private const val FALLBACK_DELAY_MS = 600L

    /** 系统确认弹窗的轮询间隔 */
    private const val DIALOG_WAIT_MS = 1200L

    /** 系统确认弹窗最多等待次数（约 30 秒） */
    private const val MAX_DIALOG_WAITS = 25

    enum class State { IDLE, RUNNING, PAUSED, FINISHED, STOPPED }

    /** 界面（主界面、悬浮面板）通过它刷新显示 */
    interface Listener {
        fun onSwitchStateChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val handler = Handler(Looper.getMainLooper())

    /** 所有排程消息共用一个 token，方便一次性取消 */
    private val TOKEN = Any()

    var state: State = State.IDLE
        private set

    /** 已成功切换次数 */
    var switchCount: Int = 0
        private set

    /** 已完成的轮数（每完成一次 A→B 记 1 轮） */
    var roundCount: Int = 0
        private set

    /** 当前状态的中文描述 */
    var statusText: String = ""
        private set

    /** 需要提醒用户的一句提示，没有时为空白 */
    var hintText: String = ""
        private set

    private var appContext: Context? = null
    private var pkgA: String = ""
    private var pkgB: String = ""
    private var intervalMs: Int = Prefs.DEFAULT_INTERVAL_MS
    private var stopMode: Int = Prefs.MODE_NONE
    private var stopRounds: Int = Prefs.DEFAULT_STOP_ROUNDS
    private var stopDurationMs: Long = Prefs.DEFAULT_STOP_MINUTES * 60_000L

    /** 下一次该切到 A（true）还是 B（false），保证严格 A→B→A→B */
    private var nextIsA: Boolean = true
    private var consecutiveFailures: Int = 0
    private var lastSwitchAtUptime: Long = 0L

    /** 计时：暂停时把已跑时间累加进 accumulatedMs，避免暂停被算进时长 */
    private var accumulatedMs: Long = 0L
    private var segmentStartUptime: Long = 0L

    val isActive: Boolean
        get() = state == State.RUNNING || state == State.PAUSED

    val currentIntervalMs: Int
        get() = intervalMs

    /** 校验延时：每 150ms 回读一次前台包名，最多 6 次（共约 0.9s） */
    private fun verifyDelay(): Long = VERIFY_STEP_MS

    private fun elapsedMs(): Long =
        accumulatedMs + if (state == State.RUNNING) {
            SystemClock.uptimeMillis() - segmentStartUptime
        } else {
            0L
        }

    private fun s(id: Int, vararg args: Any): String {
        val ctx = appContext ?: return ""
        return try {
            ctx.getString(id, *args)
        } catch (t: Throwable) {
            ""
        }
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        for (l in listeners) {
            try {
                l.onSwitchStateChanged()
            } catch (t: Throwable) {
                Log.w(TAG, "监听器回调异常: ${t.message}")
            }
        }
    }

    /** 启动轮换 */
    fun start(
        context: Context,
        a: String,
        b: String,
        interval: Int,
        mode: Int,
        rounds: Int,
        minutes: Int
    ) {
        appContext = context.applicationContext
        pkgA = a
        pkgB = b
        intervalMs = interval.coerceIn(Prefs.MIN_INTERVAL_MS, Prefs.MAX_INTERVAL_MS)
        stopMode = mode
        stopRounds = rounds.coerceAtLeast(1)
        stopDurationMs = minutes.coerceAtLeast(1) * 60_000L

        switchCount = 0
        roundCount = 0
        consecutiveFailures = 0
        nextIsA = true
        hintText = ""
        state = State.RUNNING
        statusText = s(R.string.status_running)
        accumulatedMs = 0L
        segmentStartUptime = SystemClock.uptimeMillis()

        handler.removeCallbacksAndMessages(TOKEN)
        Log.i(TAG, "开始轮换：A=$pkgA B=$pkgB interval=${intervalMs}ms mode=$stopMode")
        notifyChanged()
        handler.postAtTime(stepRunnable, TOKEN, SystemClock.uptimeMillis())
    }

    /** 暂停（保留计数，可继续） */
    fun pause() {
        if (state != State.RUNNING) return
        accumulatedMs += SystemClock.uptimeMillis() - segmentStartUptime
        handler.removeCallbacksAndMessages(TOKEN)
        state = State.PAUSED
        statusText = s(R.string.status_paused)
        notifyChanged()
    }

    /** 继续 */
    fun resume() {
        if (state != State.PAUSED) return
        consecutiveFailures = 0
        hintText = ""
        segmentStartUptime = SystemClock.uptimeMillis()
        state = State.RUNNING
        statusText = s(R.string.status_running)
        notifyChanged()
        handler.postAtTime(stepRunnable, TOKEN, SystemClock.uptimeMillis())
    }

    /** 暂停与继续的合并操作，供悬浮面板的「暂停 / 继续」按钮使用 */
    fun togglePause() {
        when (state) {
            State.RUNNING -> pause()
            State.PAUSED -> resume()
            else -> Unit
        }
    }

    /** 停止轮换。finalState 为 STOPPED（手动）或 FINISHED（定时 / 定量） */
    fun stop(finalState: State, message: String) {
        if (state == State.IDLE && finalState == State.STOPPED) return
        handler.removeCallbacksAndMessages(TOKEN)
        state = finalState
        statusText = message
        Log.i(TAG, "停止轮换：$message（共切换 $switchCount 次 / $roundCount 轮）")
        notifyChanged()
    }

    /** 由服务在销毁时调用，做最后的清理 */
    fun reset() {
        handler.removeCallbacksAndMessages(TOKEN)
        state = State.IDLE
        statusText = s(R.string.status_idle)
        notifyChanged()
    }

    // ---------------------------------------------------------------- 循环

    private val stepRunnable = object : Runnable {
        override fun run() {
            if (state != State.RUNNING) return
            val ctx = appContext ?: return
            val target = if (nextIsA) pkgA else pkgB
            lastSwitchAtUptime = SystemClock.uptimeMillis()
            Log.i(TAG, "第 ${switchCount + 1} 次切换 → $target（本轮取 A=$nextIsA）")

            if (!bringToFront(ctx, target)) {
                onSwitchFailed(ctx, s(R.string.fail_launch, target))
                return
            }
            if (!SwitchAccessibilityService.isRunning) {
                // 无障碍没开就没法校验，按成功计并给出提示，不崩溃
                hintText = s(R.string.hint_no_accessibility)
                onSwitchSucceeded(ctx)
                return
            }
            handler.postAtTime(
                { verify(ctx, target, 0) },
                TOKEN,
                SystemClock.uptimeMillis() + VERIFY_STEP_MS
            )
        }
    }

    /**
     * 拉起目标 App：必须 bring-to-front，绝不冷启动。
     * 用 getLaunchIntentForPackage 拿到的就是已有任务的入口，
     * 加 NEW_TASK + RESET_TASK_IF_NEEDED，绝不加 CLEAR_TASK / CLEAR_TOP。
     */
    private fun bringToFront(context: Context, targetPkg: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(targetPkg)
            if (intent == null) {
                Log.w(TAG, "getLaunchIntentForPackage 返回 null: $targetPkg")
                return false
            }
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            Log.i(TAG, "startActivity: ${intent.component?.flattenToShortString()} flags=${intent.flags}")
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity 失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** 当前前台包名：优先实时查询活动窗口，查不到再用无障碍事件记录兜底 */
    private fun currentForeground(): String? {
        val live = SwitchAccessibilityService.instance?.activeWindowPackage()
        return live ?: SwitchAccessibilityService.currentForegroundPackage
    }

    /** 切换后轮询回读前台包名；多次仍不一致才降级连按两次「最近任务」 */
    private fun verify(context: Context, target: String, attempt: Int) {
        if (state != State.RUNNING) return

        val fg = currentForeground()
        if (fg == target) {
            Log.i(TAG, "切换成功：$target（第 ${attempt + 1} 次校验，前台=$fg）")
            onSwitchSucceeded(context)
            return
        }

        if (attempt + 1 < MAX_VERIFY_ATTEMPTS) {
            handler.postAtTime(
                { verify(context, target, attempt + 1) },
                TOKEN,
                SystemClock.uptimeMillis() + VERIFY_STEP_MS
            )
            return
        }

        Log.w(TAG, "校验 $MAX_VERIFY_ATTEMPTS 次仍未到前台：target=$target 实际前台=$fg")

        // ColorOS/原生系统的「打开应用」确认弹窗会抢占前台，这不是切换失败，
        // 而是等用户在手机上点「允许」。此时不计失败、也不降级连按最近任务。
        if (isSystemConfirmDialog(fg)) {
            Log.w(TAG, "被系统授权弹窗拦截（$fg），等待用户点击「允许」，本次不计入失败")
            hintText = s(R.string.hint_system_dialog)
            notifyChanged()
            handler.postAtTime(
                { afterSystemDialog(context, target, 0) },
                TOKEN,
                SystemClock.uptimeMillis() + DIALOG_WAIT_MS
            )
            return
        }

        if (SwitchAccessibilityService.isRunning) {
            val svc = SwitchAccessibilityService.instance
            if (svc != null) {
                Log.w(TAG, "降级：连按两次「最近任务」")
                svc.pressRecents()
                handler.postAtTime({ svc.pressRecents() }, TOKEN, SystemClock.uptimeMillis() + 150L)
                handler.postAtTime(
                    { verifyAfterFallback(context, target) },
                    TOKEN,
                    SystemClock.uptimeMillis() + FALLBACK_DELAY_MS
                )
                return
            }
        }
        onSwitchFailed(context, s(R.string.fail_switch, target))
    }

    /**
     * 系统「打开应用」确认弹窗还在前台时，只等待、不计失败。
     * 用户点了「允许」后目标 App 会自己到前台，这次切换照样算成功。
     * 等待有上限（MAX_DIALOG_WAITS 次），避免弹窗永远挂着导致死等。
     */
    private fun afterSystemDialog(context: Context, target: String, waits: Int) {
        if (state != State.RUNNING) return
        // 等弹窗期间也要检查定时 / 定量停止条件，否则时长到了会一直等下去
        if (tryAutoStop()) return
        val fg = currentForeground()
        when {
            fg == target -> {
                Log.i(TAG, "用户授权系统弹窗后切换成功：$target")
                onSwitchSucceeded(context)
            }
            isSystemConfirmDialog(fg) && waits < MAX_DIALOG_WAITS -> {
                handler.postAtTime(
                    { afterSystemDialog(context, target, waits + 1) },
                    TOKEN,
                    SystemClock.uptimeMillis() + DIALOG_WAIT_MS
                )
            }
            else -> {
                Log.w(TAG, "系统弹窗结束但目标仍未到前台：target=$target 实际前台=$fg")
                onSwitchFailed(context, s(R.string.fail_switch, target))
            }
        }
    }

    /** 是否处于「系统确认弹窗/系统界面」而不是用户 App */
    private fun isSystemConfirmDialog(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return pkg == "com.oplus.securitypermission" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "com.android.systemui"
    }

    /** 降级连按两次「最近任务」之后再做一次判定 */
    private fun verifyAfterFallback(context: Context, target: String) {
        if (state != State.RUNNING) return
        val fg = currentForeground()
        if (fg == target) {
            Log.i(TAG, "降级后切换成功：$target")
            onSwitchSucceeded(context)
        } else {
            Log.w(TAG, "降级后仍未到前台：target=$target 实际前台=$fg")
            onSwitchFailed(context, s(R.string.fail_switch, target))
        }
    }

    private fun onSwitchSucceeded(context: Context) {
        consecutiveFailures = 0
        switchCount += 1
        roundCount = switchCount / 2
        if (hintText == s(R.string.hint_no_accessibility)) {
            // 保留无障碍提示
        } else {
            hintText = ""
        }
        notifyChanged()

        // 先判断停止条件，再排下一次
        if (tryAutoStop()) return

        nextIsA = !nextIsA
        scheduleNext()
    }

    /**
     * 判断是否已到自动停止条件（定时 / 定量）。
     * 到点就停并返回 true。
     *
     * 注意：这个判断必须对「成功」和「失败」两种收尾都生效。
     * 之前的版本只在 onSwitchSucceeded 里判断，结果遇到系统弹窗拦截、
     * 连续切换失败时，时长到了也不会停（真机实测踩过这个坑）。
     */
    private fun tryAutoStop(): Boolean {
        if (stopMode == Prefs.MODE_ROUNDS && roundCount >= stopRounds) {
            stop(State.FINISHED, s(R.string.status_finished_rounds, roundCount))
            return true
        }
        if (stopMode == Prefs.MODE_DURATION && elapsedMs() >= stopDurationMs) {
            val minutes = (elapsedMs() / 60_000L).toInt()
            stop(State.FINISHED, s(R.string.status_finished_duration, maxOf(minutes, 1)))
            return true
        }
        return false
    }

    private fun onSwitchFailed(context: Context, why: String) {
        consecutiveFailures += 1
        hintText = why
        Log.w(TAG, "$why（连续失败 $consecutiveFailures 次）")

        // 切换失败也不能漏掉定时 / 定量停止条件
        if (tryAutoStop()) return

        if (consecutiveFailures >= 3) {
            pause()
            statusText = s(R.string.status_paused_failures)
            hintText = s(R.string.fail_three_times)
            Toast.makeText(context, R.string.fail_three_times, Toast.LENGTH_LONG).show()
            notifyChanged()
            return
        }

        notifyChanged()
        // 重试同一个目标，不推进 nextIsA，保证 A/B 交替不被破坏
        scheduleNext()
    }

    /** 以「上一次切换时刻 + 间隔」为基准排下一次，保证实测间隔≈设定值 */
    private fun scheduleNext() {
        var nextAt = lastSwitchAtUptime + intervalMs
        val now = SystemClock.uptimeMillis()
        if (nextAt <= now) nextAt = now + 20L
        handler.postAtTime(stepRunnable, TOKEN, nextAt)
    }

    // ------------------------------------------------------------ 展示文本

    fun notificationText(): String = when (state) {
        State.IDLE -> s(R.string.status_idle)
        State.RUNNING -> s(R.string.notif_running, switchCount)
        State.PAUSED -> s(R.string.notif_paused, switchCount)
        State.FINISHED -> s(R.string.notif_finished, switchCount)
        State.STOPPED -> s(R.string.notif_stopped, switchCount)
    }

    /** 悬浮面板第二行：计数 */
    fun counterText(): String = s(R.string.panel_counter, switchCount, roundCount)
}
