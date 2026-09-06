package io.github.realrurichan.passportreader

import android.Manifest
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.realrurichan.passportreader.model.DocumentType
import io.github.realrurichan.passportreader.model.NfcMode
import io.github.realrurichan.passportreader.mrz.MrzAccessKey
import io.github.realrurichan.passportreader.mrz.MrzParser
import io.github.realrurichan.passportreader.nfc.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val chipReader: TravelDocumentChipReader = IcaoChipReader()
    private var pendingNfcRequest: NfcReadRequest? = null
    private var nfcState by mutableStateOf(NfcReadState())
    private var chipSummary by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { PassportReaderApp(nfcState, chipSummary, ::armNfc, ::disarmNfc) }
    }

    override fun onResume() { super.onResume(); updateReaderMode() }
    override fun onPause() { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this); super.onPause() }

    private fun armNfc(request: NfcReadRequest) {
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null) { nfcState = NfcReadState(NfcStage.FAILED, "此设备不支持 NFC", error = "NFC_UNAVAILABLE"); return }
        if (!adapter.isEnabled) { nfcState = NfcReadState(NfcStage.FAILED, "请先在系统设置中开启 NFC", error = "NFC_DISABLED"); return }
        pendingNfcRequest = request
        chipSummary = null
        nfcState = NfcReadState(message = "已就绪，请将证件贴近手机 NFC 区域")
        updateReaderMode()
    }

    private fun disarmNfc() {
        pendingNfcRequest = null
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
        nfcState = NfcReadState()
        chipSummary = null
    }

    private fun updateReaderMode() {
        val request = pendingNfcRequest ?: return
        NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(this, { tag ->
            lifecycleScope.launch {
                runCatching { chipReader.read(tag, request) { runOnUiThread { nfcState = it } } }
                    .onSuccess {
                        chipSummary = buildString {
                            appendLine("协议：${it.protocol}")
                            it.fields.forEach { (label, value) -> appendLine("$label：$value") }
                            append("数据组：${it.dataGroups.entries.joinToString { group -> "${group.key} ${group.value}B" }}")
                            if ("DG12" !in it.dataGroups) append("\n未发现可访问的 DG12，芯片签注可能不使用标准 LDS DG12。")
                        }
                        pendingNfcRequest = null
                    }
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
    }
}

@Composable
private fun PassportReaderApp(nfcState: NfcReadState, chipSummary: String?, onArmNfc: (NfcReadRequest) -> Unit, onDisarmNfc: () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF006C4C))) {
        var selected by remember { mutableStateOf<DocumentType?>(null) }
        Surface(Modifier.fillMaxSize()) {
            if (selected == null) HomeScreen { selected = it }
            else DocumentScreen(selected!!, nfcState, chipSummary, onArmNfc) { onDisarmNfc(); selected = null }
        }
    }
}

@Composable
private fun HomeScreen(onSelect: (DocumentType) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(28.dp)); Text("证件读取器", style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.privacy_note), color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(DocumentType.entries) { type ->
                Card(onClick = { onSelect(type) }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CreditCard, null); Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(type.title, style = MaterialTheme.typography.titleMedium); Text(type.subtitle) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentScreen(type: DocumentType, nfcState: NfcReadState, chipSummary: String?, onArmNfc: (NfcReadRequest) -> Unit, onBack: () -> Unit) {
    var showCamera by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf("") }
    var accessKey by remember { mutableStateOf<MrzAccessKey?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        showCamera = it
        if (!it) message = "相机权限被拒绝"
    }
    if (showCamera) {
        CameraCaptureScreen(onRecognized = { text ->
            ocrText = text
            MrzParser.parseAccessKey(text).onSuccess { key ->
                accessKey = key
            }
            if (MrzParser.parseAccessKey(text).isFailure) accessKey = null
            message = if (accessKey != null) "机读码校验通过，可以直接读取芯片"
            else "机读码校验失败：${MrzParser.diagnosis(text)}"
            showCamera = false
        }, onCancel = { showCamera = false }, onError = { message = it; showCamera = false })
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
        item { TextButton(onClick = onBack) { Text("← 返回") } }
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
                chipSummary?.let {
                    HorizontalDivider()
                    Text("芯片读取结果", style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text(it, color = MaterialTheme.colorScheme.primary) }
                }
            } }
        }
    }
}
