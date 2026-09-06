package io.github.realrurichan.passportreader

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.realrurichan.passportreader.model.DocumentType
import io.github.realrurichan.passportreader.model.NfcMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { PassportReaderApp() }
    }
}

@Composable
private fun PassportReaderApp() {
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF006C4C))) {
        var selected by remember { mutableStateOf<DocumentType?>(null) }
        Surface(Modifier.fillMaxSize()) {
            if (selected == null) HomeScreen { selected = it } else DocumentScreen(selected!!) { selected = null }
        }
    }
}

@Composable
private fun HomeScreen(onSelect: (DocumentType) -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("证件读取器", style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.privacy_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(DocumentType.entries) { type ->
                Card(onClick = { onSelect(type) }, shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CreditCard, null)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(type.title, style = MaterialTheme.typography.titleMedium)
                            Text(type.subtitle, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentScreen(type: DocumentType, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回") }
        Text(type.title, style = MaterialTheme.typography.headlineMedium)
        Text(type.subtitle)
        Card {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. 拍摄证件正反面", style = MaterialTheme.typography.titleMedium)
                Text("相机与自动裁切将在下一迭代接入；OCR 引擎已配置为离线中英文模型。")
                Button(onClick = {}, enabled = false) { Text("开始拍摄") }
            }
        }
        if (type.nfcMode != NfcMode.NONE) Card {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, null); Spacer(Modifier.width(8.dp))
                    Text("2. 芯片读取", style = MaterialTheme.typography.titleMedium)
                }
                Text(if (type.nfcMode == NfcMode.ICAO) "通过 MRZ 执行 PACE/BAC 并读取 ICAO LDS。" else "实验性 ICAO LDS 兼容模式；是否可读取取决于证件协议与授权。")
                Button(onClick = {}, enabled = false) { Text("等待 OCR 密钥") }
            }
        }
    }
}
