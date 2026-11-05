package com.wangzan101.mika.lom

import android.content.Context
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.lom.LomAppsPort

/** `apps.list` 的数据来源:包管理器里可启动的应用,去掉用户在「应用权限」里禁用的;无障碍是否已开启。 */
class AndroidApps(private val context: Context) : LomAppsPort {
    override fun launchable(): List<Pair<String, String>> {
        val blocked = (context.applicationContext as MikaApp).appAccess.current().blocked
        return launchableApps(context.packageManager, context.packageName).filter { it.first !in blocked }
    }

    override fun accessibilityEnabled(): Boolean = ScreenAccessibility.instance.value != null
}
