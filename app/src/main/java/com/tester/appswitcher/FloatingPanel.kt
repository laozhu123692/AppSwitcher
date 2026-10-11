package com.tester.appswitcher

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.tester.appswitcher.databinding.PanelFloatingBinding

/**
 * 悬浮控制面板。
 *
 * 面板必须在开始轮换之前显示出来，因为「后台启动 Activity」的豁免条件之一
 * 就是：App 持有 SYSTEM_ALERT_WINDOW 权限且当前有悬浮窗正在显示。
 *
 * 面板本身可以在屏幕上拖动，显示当前状态与已切换次数，并提供
 * 开始 / 暂停·继续 / 停止 三个按钮。
 */
object FloatingPanel : SwitchController.Listener {

    private var binding: PanelFloatingBinding? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    val isVisible: Boolean
        get() = binding != null

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    /** 显示悬浮面板，成功返回 true */
    fun show(context: Context): Boolean {
        if (isVisible) return true
        val ctx = context.applicationContext
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            toast(ctx, R.string.err_overlay_add_failed)
            return false
        }
        val b = try {
            PanelFloatingBinding.inflate(LayoutInflater.from(ctx))
        } catch (t: Throwable) {
            toast(ctx, R.string.err_overlay_add_failed)
            return false
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        // 默认停靠右上角
        p.gravity = Gravity.TOP or Gravity.END
        p.x = 24
        p.y = 240

        try {
            wm.addView(b.root, p)
        } catch (t: Throwable) {
            toast(ctx, R.string.err_overlay_add_failed)
            return false
        }

        binding = b
        windowManager = wm
        params = p

        attachDrag(b.root, wm, p)
        b.panelBtnStart.setOnClickListener { onStartClicked(ctx) }
        b.panelBtnPause.setOnClickListener { SwitchController.togglePause() }
        b.panelBtnStop.setOnClickListener {
            SwitchController.stop(
                SwitchController.State.STOPPED,
                ctx.getString(R.string.status_stopped)
            )
        }

        SwitchController.addListener(this)
        refresh()
        return true
    }

    fun hide() {
        SwitchController.removeListener(this)
        val b = binding ?: return
        try {
            windowManager?.removeView(b.root)
        } catch (t: Throwable) {
            // 已经移除过了，忽略
        }
        binding = null
        windowManager = null
        params = null
    }

    /** 显示 / 隐藏切换，返回切换后是否可见 */
    fun toggle(context: Context): Boolean {
        return if (isVisible) {
            hide()
            false
        } else {
            show(context)
        }
    }

    override fun onSwitchStateChanged() {
        refresh()
    }

    private fun refresh() {
        val b = binding ?: return
        val ctx = b.root.context

        b.panelStatus.text = SwitchController.statusText.ifEmpty { ctx.getString(R.string.status_idle) }
        b.panelCounter.text = SwitchController.counterText()

        val hint = SwitchController.hintText
        if (hint.isEmpty()) {
            b.panelHint.visibility = View.GONE
        } else {
            b.panelHint.text = hint
            b.panelHint.visibility = View.VISIBLE
        }

        b.panelBtnPause.text = if (SwitchController.state == SwitchController.State.PAUSED) {
            ctx.getString(R.string.panel_btn_resume)
        } else {
            ctx.getString(R.string.panel_btn_pause)
        }
        b.panelBtnStart.isEnabled = !SwitchController.isActive
    }

    private fun onStartClicked(ctx: Context) {
        if (SwitchController.isActive) {
            toast(ctx, R.string.panel_already_running)
            return
        }
        if (!Prefs(ctx).hasBothApps) {
            toast(ctx, R.string.err_pick_apps)
            return
        }
        SwitchService.start(ctx)
    }

    /** 拖动面板：手指移动时更新窗口位置 */
    private fun attachDrag(root: View, wm: WindowManager, p: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = p.x
                    startY = p.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // gravity 带 END，x 越大越靠左，所以横向取反
                    p.x = startX - (ev.rawX - downX).toInt()
                    val newY = startY + (ev.rawY - downY).toInt()
                    p.y = if (newY < 0) 0 else newY
                    try {
                        wm.updateViewLayout(root, p)
                    } catch (t: Throwable) {
                        // 拖动过程中窗口被移除，忽略
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun toast(context: Context, resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }
}
