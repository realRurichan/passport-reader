package io.github.realrurichan.twowaypermitreader

import android.Manifest
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.realrurichan.twowaypermitreader.model.DocumentType
import io.github.realrurichan.twowaypermitreader.model.NfcMode
import io.github.realrurichan.twowaypermitreader.mrz.MrzAccessKey
import io.github.realrurichan.twowaypermitreader.mrz.MrzParser
import io.github.realrurichan.twowaypermitreader.nfc.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val chipReader: TravelDocumentChipReader = IcaoChipReader()
    private var pendingNfcRequest: NfcReadRequest? = null
    private var nfcState by mutableStateOf(NfcReadState())
    private var chipResult by mutableStateOf<ChipReadResult?>(null)
    private var relayArmed by mutableStateOf(false)
    private var relayLog by mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TwoWayPermitReaderApp(nfcState, chipResult, relayArmed, relayLog, ::armNfc, ::armRelay, ::disarmRelay) }
    }

    override fun onResume() { super.onResume(); updateReaderMode() }
    override fun onPause() { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this); super.onPause() }

    private fun armNfc(request: NfcReadRequest) {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) { nfcState = NfcReadState(NfcStage.FAILED, "此设备不支持 NFC", error = "NFC_UNAVAILABLE"); return }
        if (!adapter.isEnabled) { nfcState = NfcReadState(NfcStage.FAILED, "请先在系统设置中开启 NFC", error = "NFC_DISABLED"); return }
        pendingNfcRequest = request
        chipResult = null
        nfcState = NfcReadState(message = "已就绪，请将证件贴近手机 NFC 区域")
        updateReaderMode()
    }

    private fun disarmNfc() {
        pendingNfcRequest = null
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        nfcState = NfcReadState()
        chipResult = null
    }

    private fun updateReaderMode() {
        if (!relayArmed && pendingNfcRequest == null) return
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this, { tag ->
            lifecycleScope.launch {
                if (relayArmed) {
                    runCatching { ApduRelay { entry -> runOnUiThread { relayLog = (relayLog + entry).takeLast(40) } }.serve(tag) }
                        .onFailure { runOnUiThread { relayLog = (relayLog + "中继结束：${it.message}").takeLast(40) } }
                } else {
                    val request = pendingNfcRequest ?: return@launch
                    runCatching { chipReader.read(tag, request) { runOnUiThread { nfcState = it } } }
                    .onSuccess {
                        chipResult = it
                        pendingNfcRequest = null
                    }
                }
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }

    private fun armRelay() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) { relayLog = listOf("此设备不支持 NFC"); return }
        if (!adapter.isEnabled) { relayLog = listOf("请先在系统设置中开启 NFC"); return }
        relayArmed = true
        relayLog = listOf("中继已布防，请将证件贴近手机 NFC 区域")
        updateReaderMode()
    }

    private fun disarmRelay() {
        relayArmed = false
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        relayLog = emptyList()
    }
}

@Composable
private fun TwoWayPermitReaderApp(nfcState: NfcReadState, chipResult: ChipReadResult?, relayArmed: Boolean, relayLog: List<String>, onArmNfc: (NfcReadRequest) -> Unit, onArmRelay: () -> Unit, onDisarmRelay: () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF006C4C))) {
        Surface(Modifier.fillMaxSize()) {
            DocumentScreen(DocumentType.HK_MACAO_PERMIT, nfcState, chipResult, relayArmed, relayLog, onArmNfc, onArmRelay, onDisarmRelay)
        }
    }
}

@Composable
private fun DocumentScreen(type: DocumentType, nfcState: NfcReadState, chipResult: ChipReadResult?, relayArmed: Boolean, relayLog: List<String>, onArmNfc: (NfcReadRequest) -> Unit, onArmRelay: () -> Unit, onDisarmRelay: () -> Unit) {
    var showCamera by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf("") }
    var accessKey by remember { mutableStateOf<MrzAccessKey?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    BackHandler(enabled = showCamera) { showCamera = false }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        showCamera = it
        if (!it) message = "相机权限被拒绝"
    }
    if (showCamera) {
        CameraCaptureScreen(singleLineMrz = type == DocumentType.HK_MACAO_PERMIT, onRecognized = { text ->
            ocrText = text
            val parsedKey = if (type == DocumentType.HK_MACAO_PERMIT) MrzParser.parseSingleLinePermit(text) else MrzParser.parseAccessKey(text)
            parsedKey.onSuccess { key ->
                accessKey = key
            }
            if (parsedKey.isFailure) accessKey = null
            message = if (accessKey != null) "机读码校验通过，可以直接读取芯片"
            else "机读码校验失败：${if (type == DocumentType.HK_MACAO_PERMIT) MrzParser.singleLineDiagnosis(text) else MrzParser.diagnosis(text)}"
            showCamera = false
        }, onCancel = { showCamera = false }, onError = { message = it; showCamera = false })
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 18.dp),
    ) {
        item { Text(type.title, style = MaterialTheme.typography.headlineMedium); Text(type.subtitle) }
        item {
            Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. 拍摄与离线 OCR", style = MaterialTheme.typography.titleMedium)
                Text("请将证件文字完整置于取景框内。照片识别后立即删除临时文件。")
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera = true
                    else permissionLauncher.launch(Manifest.permission.CAMERA)
                }) { Text("开始拍摄") }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (ocrText.isNotBlank()) OutlinedTextField(ocrText, { ocrText = it }, label = { Text("OCR 原文（可校对）") }, modifier = Modifier.fillMaxWidth(), minLines = 4)
            } }
        }
        if (type.nfcMode != NfcMode.NONE) item {
            Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Lock, null); Spacer(Modifier.width(8.dp)); Text("2. 芯片读取", style = MaterialTheme.typography.titleMedium) }
                if (type.nfcMode == NfcMode.EXPERIMENTAL_ICAO) Text("实验性 ICAO LDS 模式：失败不代表证件或芯片无效。", color = MaterialTheme.colorScheme.error)
                accessKey?.let { key ->
                    Text("已从机读码读取：${key.documentNumber} · ${key.birthDate} · ${key.expiryDate}")
                } ?: Text("请先拍摄并成功校验证件机读码。")
                Button(enabled = accessKey != null,
                    onClick = { accessKey?.let { onArmNfc(NfcReadRequest(type, it)) } }) { Text("开始 NFC 读取") }
                Text(nfcState.message); nfcState.error?.let { Text("错误：$it", color = MaterialTheme.colorScheme.error) }
                chipResult?.let { ChipResultSection(it, type) }
            } }
        }
        if (type.nfcMode != NfcMode.NONE) item {
            Card { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3. 调试：电脑经 USB 驱动芯片", style = MaterialTheme.typography.titleMedium)
                Text("布防后贴卡，电脑执行 adb forward tcp:8983 tcp:8983，再连 localhost:8983 逐条转发 APDU（十六进制）。")
                Button(onClick = { if (relayArmed) onDisarmRelay() else onArmRelay() }) {
                    Text(if (relayArmed) "停止中继" else "布防中继")
                }
                if (relayLog.isNotEmpty()) SelectionContainer {
                    Column { relayLog.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
                }
            } }
        }
    }
}

@Composable
private fun ChipResultSection(result: ChipReadResult, type: DocumentType) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HorizontalDivider()
        Text("芯片读取结果（协议 ${result.protocol}）", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        ChipInfoCard(result)
        ChipEndorsementCard(result, type)
        ChipCrossingCard(result)
        ChipTechnicalCard(result)
    }
}

@Composable
private fun ChipInfoCard(result: ChipReadResult) {
    val info = result.fields.filterKeys { key -> !key.startsWith("签注 ") && !key.startsWith("出入境记录（") }
    if (info.isEmpty()) return
    Card { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("基本信息", style = MaterialTheme.typography.titleSmall)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                info.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                        Text(value, Modifier.weight(1f))
                    }
                }
            }
        }
    } }
}

@Composable
private fun ChipEndorsementCard(result: ChipReadResult, type: DocumentType) {
    val endorsements = result.permitRecords?.endorsements.orEmpty()
    if (endorsements.isEmpty()) {
        if (type == DocumentType.HK_MACAO_PERMIT && "DG12" !in result.dataGroups) {
            Text(
                "标准 LDS 内未读到签注：芯片拒绝了 DG12 访问（${result.probe["DG12（签注与备注）"]}），且未发现其他可访问的签注数据。",
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }
    Card { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("签注记录", style = MaterialTheme.typography.titleSmall)
        endorsements.forEach { endorsement ->
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val title = listOf(endorsement.target, endorsement.typeLabel)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    Text("${endorsement.code} · $title", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (endorsement.detailText.isNotBlank()) Text(endorsement.detailText)
                    if (endorsement.detailRaw.isNotBlank() && endorsement.detailRaw != endorsement.detailText) {
                        Text("明细原文：${endorsement.detailRaw}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (endorsement.mac.isNotBlank()) {
                        Text("校验 MAC：${endorsement.mac}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } }
}

@Composable
private fun ChipCrossingCard(result: ChipReadResult) {
    val crossings = result.permitRecords?.crossings.orEmpty()
    if (crossings.isEmpty()) return
    val mainland = crossings.filter { it.approvedStayUntil == null && it.code?.contains("HZM") != true }
    val hongkong = crossings.filter { it.approvedStayUntil != null || it.code?.contains("HZM") == true }
    val endorsements = result.permitRecords?.endorsements.orEmpty()
    fun endorsementCode(terminal: String?): String? = terminal?.let { t ->
        endorsements.firstOrNull { it.code.startsWith(t) }?.code ?: t
    }
    Card { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("出入境记录", style = MaterialTheme.typography.titleSmall)
        if (mainland.isNotEmpty()) {
            Text("内地侧签注激活记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            mainland.forEach { record ->
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            listOfNotNull(
                                record.timestamp,
                                record.terminal?.let { "签注编码 ${endorsementCode(it)}" },
                                record.code,
                            ).joinToString(" · "),
                        )
                        Text("记录原文：${record.raw}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (hongkong.isNotEmpty()) {
            Text("香港/澳门侧签注激活记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            hongkong.forEach { record ->
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            listOfNotNull(
                                record.terminal?.let { "签注编码 ${endorsementCode(it)}" },
                                record.timestamp?.let { "入境 $it" },
                                record.approvedStayUntil?.let { "批准逗留至 $it" },
                                record.port?.let { "口岸 $it" },
                                record.code,
                            ).joinToString(" · "),
                        )
                        Text("记录原文：${record.raw}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        result.permitRecords?.counterNotes?.takeIf { it.isNotEmpty() }?.let { notes ->
            Text("隐藏文件计数：${notes.joinToString("，")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } }
}

@Composable
private fun ChipTechnicalCard(result: ChipReadResult) {
    Card { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("数据组", style = MaterialTheme.typography.titleSmall)
        Text("数据组：${result.dataGroups.entries.joinToString { "${it.key} ${it.value}B" }}")
        if (result.declaredDataGroups.isNotEmpty()) {
            Text("芯片声明的数据组：${result.declaredDataGroups.joinToString { "DG$it" }}")
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                result.probe.forEach { (label, note) -> Text("$label：$note", style = MaterialTheme.typography.bodySmall) }
            }
        }
    } }
}
