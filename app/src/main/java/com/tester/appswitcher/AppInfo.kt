package com.tester.appswitcher

import android.content.Context
import android.content.Intent

/** 一个已安装且带启动图标的 App */
data class AppInfo(val packageName: String, val label: String)

/**
 * 已安装应用枚举。
 *
 * 只通过 MAIN/LAUNCHER intent 查询，配合 AndroidManifest.xml 里的 <queries> 声明，
 * 因此不需要 QUERY_ALL_PACKAGES 这种敏感权限。
 */
object AppLoader {

    /** 枚举所有可以从桌面启动的 App（排除自己），按名称排序 */
    fun load(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = try {
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            return emptyList()
        }
        val self = context.packageName
        return resolved.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName
            if (pkg.isNullOrEmpty() || pkg == self) {
                null
            } else {
                val label = try {
                    ri.loadLabel(pm).toString()
                } catch (t: Throwable) {
                    pkg
                }
                AppInfo(pkg, label)
            }
        }.distinctBy { it.packageName }.sortedBy { it.label }
    }

    /** 取一个包名对应的应用名；取不到时退回包名本身 */
    fun labelOf(context: Context, pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (t: Throwable) {
            pkg
        }
    }

    /** 该包名当前是否还装着（用户可能卸载了） */
    fun isInstalled(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
