# 双App轮换切换（AppSwitcher）

一个**免 root、不需要电脑、不联网**的 Android 小工具：让任意两个已安装的 App 自动前后台轮换。
A 前台停留 N 毫秒 → B 前台停留 N 毫秒 → 再切回 A……**只切换前后台，不重启进程**。

典型用途：使用 FakeLocation 等改位类 App 后，某些应用不会主动刷新定位，需要反复「切后台再切回来」才会重新读取位置。
本工具把这个手动动作自动化。

---

## 1. 交付物

| 文件 | 说明 |
| --- | --- |
| `dist/AppSwitcher-1.0-debug.apk` | 可直接安装的调试版 APK |
| `app/` | 完整 Android 工程源码（Kotlin） |
| `README.md` | 本文件 |
| `VERIFY.md` | 真机实测记录（含原始命令输出） |

---

## 2. 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | 17（必需，AGP 8.x 不支持 JDK 8） |
| Gradle | 8.7（工程自带 wrapper，可用 `gradlew`） |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0） |

依赖刻意保持最小：仅 `androidx.core:core-ktx`、`androidx.appcompat:appcompat`、`com.google.android.material:material`。
**没有** Compose、Hilt/Dagger、Retrofit/OkHttp、Room，**没有任何联网代码或统计 SDK**。

---

## 3. 构建

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

> 如果本机没有 Gradle，可先用命令行 Gradle 生成 wrapper：
> `gradle wrapper --gradle-version 8.7`
>
> 若 `local.properties` 不存在，请自行创建并写入 SDK 路径：
> `sdk.dir=C:\\Android\\Sdk`（macOS/Linux 用 `/Users/xxx/Library/Android/sdk`）

---

## 4. 安装

```bash
adb install -r dist/AppSwitcher-1.0-debug.apk
```

也可以把 APK 拷进手机直接点击安装（需允许「安装未知来源应用」）。

---

## 5. 使用步骤

### 5.1 第一次使用：开 3 个权限

打开 App「双App轮换切换」，页面下方有**权限自检**区，逐项点「去设置」：

1. **悬浮窗权限**（必需）
   - 系统设置 → 应用 → 特殊应用权限 → 显示在其他应用上层 → 允许「双App轮换切换」。
   - 这个权限是 Android 对「后台启动 Activity」的豁免条件之一，**不开就无法从后台把目标 App 拉到前台**。
2. **无障碍服务**（必需）
   - 系统设置 → 无障碍 → 已下载的应用 → 双App轮换切换 → 开启。
   - 用途有两个：① 记录当前前台是哪个 App，用来判断切换是否成功；② 切换失败时用「最近任务」手势兜底。
3. **通知权限**（Android 13+，建议开启）
   - 用于显示常驻通知（通知里有「暂停」和「停止」按钮）。

**电池优化**：建议把本 App 设为「不受电池优化限制」，否则长时间运行时可能被系统冻结。

### 5.2 配置

- **目标应用**：点「选择」从已安装应用列表里挑 App A 和 App B（支持按名称或包名搜索）。选择会**自动保存**，下次打开无需重选。
- **每次停留时长**：默认 `2000` 毫秒，可填 200 ~ 10000；也可以直接点「0.5 秒 / 1 秒 / 2 秒 / 3 秒 / 5 秒」预设。
- **自动停止**：
  - `不限`：只能手动停（默认）。
  - `按轮次`：跑满 N 轮自动停，默认 100 轮。**1 轮 = A、B 各切一次，即 2 次切换**。
  - `按时长`：跑满 T 分钟自动停，默认 10 分钟。

### 5.3 运行

1. 点「显示悬浮面板」（面板会出现在屏幕右上角，可随意拖动）。
2. 点「开始」。
3. 悬浮面板上会实时显示：当前状态、已切换次数。
   - `▶ 开始`：开始轮换
   - `⏸ 暂停 / 继续`：暂停或继续
   - `■ 停止`：立即停止
4. 也可以在**常驻通知**里点「暂停」或「停止」，不需要回到 App 界面。

建议先点「显示悬浮面板」再点开始 —— 面板可见是本工具能把目标 App 拉到前台的前提条件之一。

---

## 6. ColorOS（OPPO / 一加 / realme）专项设置 ⚠️

**实测中最常见的失败原因就是下面第 1 条没开。** ColorOS 对「后台拉起界面」管得很严，请务必逐条检查：

| # | 设置路径 | 说明 |
| --- | --- | --- |
| 1 | 设置 → 应用 → 应用管理 → 双App轮换切换 → **允许后台弹出界面** | ⭐ 最关键。不开的话 `startActivity` 会被系统静默拦截，表现为「计数在涨但界面不切换」 |
| 2 | 设置 → 电池 → 应用耗电管理 → 双App轮换切换 → 允许**后台高耗电** + 允许**关联启动** | 防止后台服务被冻结 |
| 3 | 设置 → 应用 → 自启动管理（部分机型在「手机管家 → 权限隐私」里）→ 允许双App轮换切换自启动 | 提升长时间运行稳定性 |
| 4 | 在多任务界面下拉本 App 卡片 → 加锁（锁定后台） | 防止清理后台时被一并清掉 |
| 5 | 开发者选项 → **不锁定屏幕 / 充电时保持唤醒** | 长时间测试时避免息屏暂停 |

> 目标 App 也可能需要类似的白名单设置（例如 FakeLocation 需要定位权限 + 后台运行权限）。

### 6.1 首次运行必看：ColorOS 的「想要打开 XX」确认弹窗

真机实测发现，**光开「允许后台弹出界面」还不够**。ColorOS 还会额外弹一个系统确认框：

> **「双App轮换切换」想要打开「Fake Location」**
> 　30天内允许　/　仅本次允许　/　取消

* **第一次启动轮换时必须点一次「30天内允许」**，否则目标 App 永远到不了前台，
  表现为面板上一直提示切换失败。
* 这个弹窗**不能用脚本自动点掉**（`uiautomator` 看不到它），只能人工点。
* 本 App 的处理方式是「**等待你点，不把这次算作失败**」，面板会提示
  「系统弹窗询问「想要打开 XX」，请在手机上点「30天内允许」」，最长等你约 30 秒。
* ⚠️ **重新安装 APK 会重置这个授权**，需要重新点一次。

（这条以及「用 adb 重新开启无障碍后 ColorOS 会弹『保持开启』」都已记入 `VERIFY.md` §5。）

---

## 7. 工作原理

### 7.1 拉起目标 App：bring-to-front，绝不冷启动

```kotlin
val intent = packageManager.getLaunchIntentForPackage(targetPkg) ?: return false
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
startActivity(intent)
```

**绝不使用** `FLAG_ACTIVITY_CLEAR_TASK` / `FLAG_ACTIVITY_CLEAR_TOP` —— 那会导致目标 App 冷启动重建。
判断标准：如果切换过程中 logcat 里出现 `am_create_activity` 或 `am_proc_start`，说明发生了冷启动，属于不合格。

### 7.2 为什么需要「悬浮窗 + 无障碍」两重权限

Android 10+ 默认禁止后台应用启动 Activity（BAL 限制）。系统给出的豁免条件中，本工具依赖两条：

1. 应用持有 `SYSTEM_ALERT_WINDOW`（悬浮窗）权限**并且悬浮窗当前可见**；
2. 应用已经绑定 `AccessibilityService`。

因此 `SwitchService` 在真正开始循环之前会逐项校验，任何一项不满足都拒绝启动并给出明确提示。

### 7.3 前台服务 + Handler 循环

- `SwitchService` 是**前台服务**（`startForeground`），常驻通知带「暂停」「停止」按钮。
- 轮换循环用主线程 `Handler.postAtTime` 调度，**不使用 `Thread.sleep`**，不会阻塞主线程。
- Android 14+ 声明 `foregroundServiceType="specialUse"` 并附带
  `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="app_switching_automation"/>`。
- 停止时 `removeCallbacks` + `stopForeground`，不留残留回调。

### 7.4 切换成功判定与兜底

1. `startActivity` 之后约 300ms，读取无障碍服务记录的当前前台包名；
2. 如果**不是**目标包名，判定本次失败 → 连续按两次「最近任务」（`performGlobalAction(GLOBAL_ACTION_RECENTS)`）回到上一个应用；
3. 连续失败 3 次 → 自动暂停循环，并提示：
   「请检查：悬浮窗权限 / 后台弹出界面权限 / 无障碍服务」。

### 7.5 状态机

```
IDLE ──开始──▶ RUNNING ──暂停──▶ PAUSED ──继续──▶ RUNNING
                  │                 │
                  ├── 达到轮次/时长 ──▶ FINISHED
                  └── 手动/通知停止 ──▶ STOPPED
```

计数定义：**每成功把目标 App 拉到前台一次，计数 +1**；1 轮 = 2 次切换。

---

## 8. 常见问题

**Q1：计数在涨，但屏幕上的 App 没有切换？**
九成是 ColorOS 的「允许后台弹出界面」没开（见 §6 第 1 条）。其次检查悬浮面板是否处于显示状态。

**Q2：点开始提示「请先显示悬浮面板」？**
本工具要求悬浮窗可见才允许启动，这是 BAL 豁免的硬性条件。点主页面的「显示悬浮面板」即可。

**Q3：无障碍服务老是自动关闭？**
部分系统在 App 被强行停止或重装后会关闭无障碍。重新到「设置 → 无障碍」里打开即可。请勿使用「强行停止」按钮。

**Q4：通知栏看不到常驻通知？**
Android 13+ 需要通知权限；到「设置 → 应用 → 双App轮换切换 → 通知」里允许。

**Q5：切换间隔和设定值有偏差？**
实际间隔 = 系统拉起目标 App 的耗时 + 设定时长。实测在 2000ms 设定下相邻两次 `am_on_resume_called` 的间隔约为 2.0s ± 0.4s。

**Q6：会不会把目标 App 杀掉/重启？**
不会。本工具只做 bring-to-front，不发送任何停止、清理或杀进程的指令。验证方法见 `VERIFY.md`：整个过程中不应出现 `am_proc_start`。

---

## 9. 已知限制

- 不支持 root 相关功能，也不依赖 root。
- 不支持 Android 7.0 以下（minSdk 24）。
- 只支持「一组」App A / App B，不做多组预设切换。
- 不提供息屏自动亮屏功能（息屏时系统会限制后台启动界面）。
- 不做 CSV 导出、不做广告、不做任何联网上报。
- 无障碍服务被系统关闭后需要**手动**重新打开；本工具不尝试自动重开（避免与系统安全策略对抗）。

---

## 10. 目录结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/tester/appswitcher/
│   ├── MainActivity.kt                # 主界面：配置 / 权限自检 / 启停
│   ├── AppPickerDialog.kt             # 应用选择对话框（本地枚举，不用 QUERY_ALL_PACKAGES）
│   ├── AppInfo.kt                     # 应用信息 + AppLoader 枚举
│   ├── Prefs.kt                       # 配置持久化
│   ├── SwitchController.kt            # 轮换状态机（Handler 调度 / 成功判定 / 降级）
│   ├── SwitchService.kt               # 前台服务 + 常驻通知
│   ├── SwitchAccessibilityService.kt  # 记录前台包名 / RECENTS 兜底
│   └── FloatingPanel.kt               # 可拖动悬浮控制面板
└── res/
    ├── layout/{activity_main,dialog_app_picker,panel_floating}.xml
    ├── values/{strings,colors,themes}.xml
    ├── drawable/{bg_panel,bg_panel_button,ic_launcher,ic_stat_switch}.xml
    └── xml/accessibility_service_config.xml
```

---

## 11. 合规提醒

本工具仅用于**自有设备上的功能测试与性能测试**（例如验证定位类 App 的刷新行为）。
请勿用于任何违反第三方应用服务条款、规避风控或侵害他人权益的用途。使用者需自行承担相应责任。
