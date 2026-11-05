package com.wangzan101.mika.lom

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.lom.LomRelayState
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.LomCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 已对接麓鸣期间的前台服务:只为让到云端中转的 WebSocket 在后台活着(云端不为离线手机排队请求)。
 * 常驻通知说明在连接哪台电脑;未配对或已被撤销时当场退出。系统不允许进前台时不崩,界面回到前台会补起。
 */
class LomService : Service() {
    private val runtime get() = (application as MikaApp).lom
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure { Log.w(TAG, "Lom service could not enter the foreground", it) }.isSuccess
        if (!foreground || runtime.store.pairing() == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        runtime.watchNetwork()
        runtime.relay.start()
        runtime.relay.reconnectNow()
        if (watching == null) watching = scope.launch {
            runtime.relayState.collect { s ->
                if (s == LomRelayState.Revoked || s == LomRelayState.Stopped && runtime.store.pairing() == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.unwatchNetwork()
        runtime.relay.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val name = runtime.store.pairing()?.desktops?.firstOrNull()?.name.orEmpty()
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(LomCopy.NOTIF_TITLE)
            .setContentText(LomCopy.notifBody(name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(TaskService.openApp(this))
            .build()
    }

    private companion object {
        const val TAG = "MikaLom"
        /** 和任务通知(槽位、结果编号段)、电脑连接通知(30001)错开。 */
        const val NOTIFICATION_ID = 30_011
    }
}
