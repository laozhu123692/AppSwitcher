package com.tester.appswitcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tester.appswitcher.databinding.ActivityMainBinding

/**
 * 主界面：选择 App A / App B、设置停留时长与自动停止方式、检查各项权限，
 * 并控制悬浮面板与轮换的开始、停止。
 */
class MainActivity : AppCompatActivity(), SwitchController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        setupUi()
        requestNotificationPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        SwitchController.addListener(this)
        refreshAppLabels()
        refreshPermissions()
        refreshPanelState()
        refreshStatus()
    }

    override fun onPause() {
        SwitchController.removeListener(this)
        super.onPause()
    }

    override fun onSwitchStateChanged() {
        refreshStatus()
        refreshPanelState()
    }

    // ------------------------------------------------------------ UI 初始化

    private fun setupUi() {
        binding.etInterval.setText(prefs.intervalMs.toString())
        binding.etStopRounds.setText(prefs.stopRounds.toString())
        binding.etStopMinutes.setText(prefs.stopMinutes.toString())
        when (prefs.stopMode) {
            Prefs.MODE_ROUNDS -> binding.rbStopRounds.isChecked = true
            Prefs.MODE_DURATION -> binding.rbStopDuration.isChecked = true
            else -> binding.rbStopNone.isChecked = true
        }

        binding.btnPickA.setOnClickListener {
            AppPickerDialog.show(this) { picked ->
                prefs.packageA = picked.packageName
                refreshAppLabels()
                if (prefs.packageA == prefs.packageB) {
                    toast(getString(R.string.err_same_app))
                }
            }
        }
        binding.btnPickB.setOnClickListener {
            AppPickerDialog.show(this) { picked ->
                prefs.packageB = picked.packageName
                refreshAppLabels()
                if (prefs.packageA == prefs.packageB) {
                    toast(getString(R.string.err_same_app))
                }
            }
        }

        binding.btnPreset05.setOnClickListener { setIntervalText(500) }
        binding.btnPreset1.setOnClickListener { setIntervalText(1000) }
        binding.btnPreset2.setOnClickListener { setIntervalText(2000) }
        binding.btnPreset3.setOnClickListener { setIntervalText(3000) }
        binding.btnPreset5.setOnClickListener { setIntervalText(5000) }

        binding.btnTogglePanel.setOnClickListener {
            FloatingPanel.toggle(this)
            refreshPanelState()
        }

        binding.btnPermOverlay.setOnClickListener {
            openSafely(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        binding.btnPermAccess.setOnClickListener {
            openSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnPermBattery.setOnClickListener {
            openSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        binding.btnPermNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION
                )
            } else {
                openAppNotificationSettings()
            }
        }
        binding.btnPermColorOs.setOnClickListener {
            openSafely(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener {
            if (SwitchController.isActive) {
                SwitchController.stop(
                    SwitchController.State.STOPPED,
                    getString(R.string.status_stopped)
                )
            } else {
                toast(getString(R.string.toast_not_running))
            }
        }
    }

    private fun setIntervalText(ms: Int) {
        binding.etInterval.setText(ms.toString())
        binding.etInterval.setSelection(binding.etInterval.text?.length ?: 0)
    }

    // ---------------------------------------------------------------- 开始

    private fun onStartClicked() {
        val interval = readInterval() ?: return
        prefs.intervalMs = interval
        saveStopConfig()

        if (!prefs.hasBothApps) {
            toast(getString(R.string.err_pick_apps))
            return
        }
        if (prefs.packageA == prefs.packageB) {
            toast(getString(R.string.err_same_app))
            return
        }
        if (!AppLoader.isInstalled(this, prefs.packageA) ||
            !AppLoader.isInstalled(this, prefs.packageB)
        ) {
            toast(getString(R.string.err_app_missing))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.err_overlay_permission))
            openSafely(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        // 必须先让悬浮面板显示出来，后台启动 Activity 才被系统允许
        if (!FloatingPanel.isVisible && !FloatingPanel.show(this)) {
            return
        }
        refreshPanelState()

        if (!SwitchAccessibilityService.isRunning) {
            toast(getString(R.string.err_accessibility_required))
            openSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        SwitchService.start(this)
        refreshStatus()
    }

    private fun readInterval(): Int? {
        val value = binding.etInterval.text?.toString()?.trim()?.toIntOrNull()
        if (value == null || value < Prefs.MIN_INTERVAL_MS || value > Prefs.MAX_INTERVAL_MS) {
            toast(getString(R.string.err_interval_invalid))
            return null
        }
        return value
    }

    private fun saveStopConfig() {
        val mode = when {
            binding.rbStopRounds.isChecked -> Prefs.MODE_ROUNDS
            binding.rbStopDuration.isChecked -> Prefs.MODE_DURATION
            else -> Prefs.MODE_NONE
        }
        prefs.stopMode = mode
        prefs.stopRounds =
            binding.etStopRounds.text?.toString()?.trim()?.toIntOrNull() ?: Prefs.DEFAULT_STOP_ROUNDS
        prefs.stopMinutes =
            binding.etStopMinutes.text?.toString()?.trim()?.toIntOrNull()
                ?: Prefs.DEFAULT_STOP_MINUTES
    }

    // ------------------------------------------------------------ 界面刷新

    private fun refreshAppLabels() {
        binding.tvValueA.text = describe(prefs.packageA)
        binding.tvValueB.text = describe(prefs.packageB)
    }

    private fun describe(pkg: String?): String {
        if (pkg.isNullOrEmpty()) return getString(R.string.app_not_selected)
        return AppLoader.labelOf(this, pkg) + "（" + pkg + "）"
    }

    private fun refreshPanelState() {
        val visible = FloatingPanel.isVisible
        binding.tvPanelState.text =
            getString(if (visible) R.string.panel_state_shown else R.string.panel_state_hidden)
        binding.btnTogglePanel.text =
            getString(if (visible) R.string.btn_hide_panel else R.string.btn_show_panel)
    }

    private fun refreshStatus() {
        val status = SwitchController.statusText.ifEmpty { getString(R.string.status_idle) }
        val counter = getString(
            R.string.status_counter,
            SwitchController.switchCount,
            SwitchController.roundCount
        )
        val builder = StringBuilder()
        builder.append(getString(R.string.status_label, status))
        builder.append(" · ").append(counter)
        if (SwitchController.hintText.isNotEmpty()) {
            builder.append('\n').append(SwitchController.hintText)
        }
        binding.tvStatus.text = builder.toString()
    }

    private fun refreshPermissions() {
        val okText = getString(R.string.state_ok)
        val noText = getString(R.string.state_no)
        val okColor = ContextCompat.getColor(this, R.color.ok)
        val noColor = ContextCompat.getColor(this, R.color.warn)

        fun apply(textView: TextView, granted: Boolean) {
            textView.text = if (granted) okText else noText
            textView.setTextColor(if (granted) okColor else noColor)
        }

        apply(binding.tvPermOverlay, Settings.canDrawOverlays(this))
        apply(
            binding.tvPermAccess,
            SwitchAccessibilityService.isRunning ||
                SwitchAccessibilityService.isEnabledInSettings(this)
        )

        val ignoring = try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (t: Throwable) {
            false
        }
        apply(binding.tvPermBattery, ignoring)

        apply(binding.tvPermNotif, NotificationManagerCompat.from(this).areNotificationsEnabled())

        // ColorOS 的「后台弹出界面」没有公开接口可查，只能人工确认
        binding.tvPermColorOs.text = getString(R.string.perm_coloros_value)
        binding.tvPermColorOs.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    // ------------------------------------------------------------ 权限相关

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIFICATION
        )
    }

    private fun openAppNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        }
        openSafely(intent)
    }

    private fun openSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            toast(getString(R.string.toast_settings_unavailable))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_NOTIFICATION = 1001
    }
}
