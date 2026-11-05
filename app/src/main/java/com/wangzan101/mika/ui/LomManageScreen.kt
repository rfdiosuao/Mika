package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.lom.LomDeviceMethods
import com.wangzan101.mika.core.lom.LomRelayState
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** 设置 › 高级 › 麓鸣电脑:看到已对接的电脑和连接状态,可以解除配对。 */
@Composable
fun LomManageScreen(onBack: () -> Unit, onScan: () -> Unit) {
    val runtime = (LocalContext.current.applicationContext as MikaApp).lom
    val paired by runtime.paired.collectAsState()
    val relay by runtime.relayState.collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    SubPage(LomCopy.MANAGE_TITLE, onBack) {
        val p = paired
        if (p == null) {
            GroupCard {
                ListRow(MikaIcons.Desktop, LomCopy.NOT_PAIRED, LomCopy.MANAGE_EMPTY, first = true)
                RowActions { MikaButton(LomCopy.SCAN_BUTTON, onScan, size = ButtonSize.Small, modifier = Modifier.testTag("lom-manage-scan")) }
            }
        } else {
            GroupCard {
                p.desktops.forEachIndexed { i, d ->
                    ListRow(MikaIcons.Desktop, d.name.ifBlank { LomCopy.NAME }, LomCopy.approvedAt(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(d.approvedAtMs))), first = i == 0)
                }
                ListRow(MikaIcons.Link, stateText(relay), first = false)
                // 电脑上改过这台手机的名字(device.rename)就显示出来,原样展示
                p.trustedKeys.firstOrNull()?.let { LomDeviceMethods.savedName(runtime.kv, it) }?.let {
                    ListRow(MikaIcons.Desktop, LomCopy.deviceNameOnDesktop(it), first = false)
                }
                RowActions {
                    MikaButton(LomCopy.UNPAIR, { confirm = true }, kind = ButtonKind.Text, size = ButtonSize.Small, enabled = !busy, modifier = Modifier.testTag("lom-unpair"))
                }
            }
            FooterNote(LomCopy.PAIRED_NOTE)
        }
        note?.let { FooterNote(it, Modifier.padding(top = 4.dp)) }
    }
    if (confirm) MikaDialog(
        onDismissRequest = { confirm = false },
        title = { Text(LomCopy.UNPAIR_TITLE) }, text = { Text(LomCopy.UNPAIR_BODY) },
        confirmButton = {
            TextButton({
                confirm = false; busy = true
                scope.launch {
                    val notified = runtime.unpair()
                    note = if (notified) null else LomCopy.UNPAIR_NOT_NOTIFIED
                    busy = false
                }
            }) { Text(LomCopy.UNPAIR) }
        },
        dismissButton = { TextButton({ confirm = false }) { Text(LomCopy.CANCEL) } },
    )
}

internal fun stateText(s: LomRelayState): String = when (s) {
    LomRelayState.Online -> LomCopy.STATE_ONLINE
    LomRelayState.Connecting -> LomCopy.STATE_CONNECTING
    LomRelayState.Offline -> LomCopy.STATE_OFFLINE
    else -> LomCopy.STATE_STOPPED
}

/** 「配置模型」页服务商列表下面的入口:扫码对接麓鸣,配对后使用麓鸣推送的模型(服务商名「麓鸣」)。 */
@Composable
internal fun LomModelRow() {
    val runtime = (LocalContext.current.applicationContext as? MikaApp)?.lom ?: return
    val paired by runtime.paired.collectAsState()
    val model by runtime.modelName.collectAsState()
    val name = paired?.desktops?.firstOrNull()?.name
    GroupCard(Modifier.padding(top = 4.dp)) {
        ListRow(
            MikaIcons.Desktop, LomCopy.SCAN_BUTTON,
            LomCopy.modelRowSub(paired != null, name, model),
            first = true, onClick = if (paired == null) ({ runtime.requestPairScreen() }) else null,
        )
    }
}
