package com.wangzan101.mika.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.lom.LomPairLink
import com.wangzan101.mika.core.lom.LomPairPath
import com.wangzan101.mika.lom.LomPairUi
import com.wangzan101.mika.lom.LomRuntime
import com.wangzan101.mika.lom.LomSession
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.launch

private fun Context.lomRuntime(): LomRuntime = (applicationContext as MikaApp).lom

/**
 * 扫码对接麓鸣。「连接电脑 › 远程」里的按钮、「配置模型」里的入口、`lom://pair` 深链都进这一页。
 * 流程:扫码/相册选图/配对码 → 核对 → 显示桌面名称与脱敏账号 → 用户明确点「确认连接」→ 完成。
 */
@Composable
fun LomPairScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { context.lomRuntime() }
    val ui by runtime.session.ui.collectAsState()
    val paired by runtime.paired.collectAsState()
    val leave = {
        // 离开时撤掉还没确认的认领;已完成或失败的直接复位
        if (ui is LomPairUi.Confirm) runtime.session.cancel() else runtime.session.reset()
        onBack()
    }
    BackHandler { leave() }
    SubPage(LomCopy.PAGE_TITLE, onBack = leave) {
        when (val s = ui) {
            LomPairUi.Idle -> if (paired != null) AlreadyPaired(onBack = leave) else ScanPanel(runtime.session)
            LomPairUi.Working -> Working()
            is LomPairUi.Confirm -> ConfirmPanel(s, onConfirm = runtime.session::confirm, onCancel = { runtime.session.cancel() })
            is LomPairUi.Done -> Done(s.desktopName, onDone = { runtime.session.reset(); onBack() })
            is LomPairUi.Failed -> Failed(s.code, onRetry = runtime.session::reset)
        }
    }
}

@Composable
private fun AlreadyPaired(onBack: () -> Unit) {
    FooterNote(LomCopy.ALREADY_PAIRED_SUB)
    RowActions { MikaButton(LomCopy.DONE, onBack, size = ButtonSize.Small) }
}

@Composable
private fun Working() {
    GroupCard { ListRow(MikaIcons.Link, LomCopy.WORKING, first = true) }
}

@Composable
private fun ScanPanel(session: LomSession) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var showCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; asked = true }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = decodeQrFromImage(context, uri)
            when {
                text == null -> message = LomCopy.IMAGE_UNREADABLE
                text.isEmpty() -> message = LomCopy.NO_QR_IN_IMAGE
                else -> submit(session, text) { message = it }
            }
        }
    }
    LaunchedEffect(Unit) { if (!granted && !asked) permission.launch(Manifest.permission.CAMERA) }

    Text(LomCopy.SCAN_HINT, Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.Body, color = MikaTheme.colors.ink2)
    if (granted) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)).background(MikaTheme.colors.fill).testTag("lom-camera")) {
            LomCameraScanner(onText = { submit(session, it) { m -> message = m } })
        }
    } else {
        GroupCard {
            ListRow(MikaIcons.Camera, LomCopy.CAMERA_NEEDED, first = true)
            RowActions {
                // 拒绝过一次后系统可能不再弹窗,给去系统设置的路
                if (!asked) MikaButton(LomCopy.CAMERA_ALLOW, { permission.launch(Manifest.permission.CAMERA) }, size = ButtonSize.Small)
                else MikaButton(LomCopy.CAMERA_SETTINGS, { openAppSettings(context) }, size = ButtonSize.Small)
            }
        }
    }
    message?.let { Text(it, Modifier.padding(top = 10.dp, start = 4.dp), style = MikaType.Caption, color = MikaTheme.colors.bad) }
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaButton(LomCopy.FROM_GALLERY, {
            message = null
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }, kind = ButtonKind.Tonal, size = ButtonSize.Small, modifier = Modifier.testTag("lom-gallery"))
        MikaButton(LomCopy.ENTER_CODE, { showCode = !showCode }, kind = ButtonKind.Text, size = ButtonSize.Small)
    }
    if (showCode) {
        Text(LomCopy.CODE_TITLE, Modifier.padding(top = 16.dp, bottom = 8.dp), style = MikaType.Label, color = MikaTheme.colors.ink3)
        GroupCard {
            InputRow(LomCopy.CODE_LABEL, code, { code = it.take(16) }, LomCopy.CODE_HINT, first = true, modifier = Modifier.testTag("lom-code"))
            RowActions { MikaButton(LomCopy.CODE_SUBMIT, { session.submitCode(code.trim()) }, size = ButtonSize.Small, enabled = code.isNotBlank()) }
        }
    }
}

/** 扫到或选到的文本:麓鸣链接进入流程;别的二维码只提示,继续扫。 */
private fun submit(session: LomSession, text: String, onMessage: (String) -> Unit) {
    when {
        LomPairLink.isNewerVersion(text) -> onMessage(LomCopy.NEWER_QR)
        LomSession.isLom(text) -> { onMessage(""); session.submitLink(text) }
        else -> onMessage(LomCopy.NOT_LOM_QR)
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
private fun ConfirmPanel(s: LomPairUi.Confirm, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val c = MikaTheme.colors
    Text(LomCopy.CONFIRM_TITLE, Modifier.padding(top = 4.dp, bottom = 12.dp), style = MikaType.LargeTitle.copy(fontSize = 22.sp, lineHeight = 28.sp), color = c.ink)
    GroupCard {
        ListRow(MikaIcons.Desktop, LomCopy.DESKTOP, first = true) {
            Text(s.pending.desktopName.ifBlank { LomCopy.NAME }, style = MikaType.Value, color = c.ink2, modifier = Modifier.testTag("lom-desktop"))
        }
        ListRow(MikaIcons.Shield, LomCopy.ACCOUNT) {
            Text(s.pending.maskedAccount, style = MikaType.Value, color = c.ink2, modifier = Modifier.testTag("lom-account"))
        }
        // 配对码路径:必须让用户对照电脑屏幕上的确认码;扫码路径不显示(由指纹与 key_proof 保证)
        s.pending.sas?.let { sas ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(LomCopy.SAS_LABEL, style = MikaType.Caption, color = c.ink3)
                Text(sas.substring(0, 3) + " " + sas.substring(3), Modifier.padding(top = 4.dp).testTag("lom-sas"),
                    fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c.ink, textAlign = TextAlign.Center)
            }
        }
    }
    if (s.pending.path == LomPairPath.Code) FooterNote(LomCopy.SAS_NOTE)
    if (s.waiting) FooterNote(LomCopy.WAITING_DESKTOP, Modifier.testTag("lom-waiting"))
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MikaButton(LomCopy.CANCEL, onCancel, kind = ButtonKind.Tonal, modifier = Modifier.weight(1f), enabled = !s.waiting)
        MikaButton(LomCopy.CONFIRM, onConfirm, modifier = Modifier.weight(1f).testTag("lom-confirm"), enabled = !s.waiting)
    }
}

@Composable
private fun Done(name: String, onDone: () -> Unit) {
    Text(LomCopy.DONE_TITLE, Modifier.padding(top = 4.dp, bottom = 8.dp), style = MikaType.LargeTitle.copy(fontSize = 22.sp, lineHeight = 28.sp), color = MikaTheme.colors.ink)
    Text(LomCopy.doneBody(name), style = MikaType.Body, color = MikaTheme.colors.ink2)
    FooterNote(LomCopy.PAIRED_NOTE, Modifier.padding(top = 8.dp))
    MikaButton(LomCopy.DONE, onDone, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("lom-done"))
}

@Composable
private fun Failed(code: String, onRetry: () -> Unit) {
    Text(LomCopy.error(code), Modifier.padding(vertical = 12.dp).testTag("lom-error"), style = MikaType.Body, color = MikaTheme.colors.bad)
    MikaButton(if (code == "pairing.already_paired") LomCopy.DONE else LomCopy.RETRY, onRetry, Modifier.fillMaxWidth())
}
