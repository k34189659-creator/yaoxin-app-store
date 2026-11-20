package tw.dafeiyu.mangatranslator.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import tw.dafeiyu.mangatranslator.AppViewModel
import tw.dafeiyu.mangatranslator.ui.ScreenHeader
import tw.dafeiyu.mangatranslator.ui.SectionCard

/** 僅提供 API Key／連線、模型就緒與下載、匯出偏好。不暴露不必要的進階引擎參數。 */
@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.settings
    val readiness = vm.modelReadiness
    val context = LocalContext.current
    var keyDialog by remember { mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                vm.selectExportTree(uri)
            } catch (e: Exception) {
                vm.dialogMessage = "資料夾授權失敗：${e.message}"
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("設定", "翻譯連線・模型・匯出", onBack = { vm.back() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard("翻譯 API", Icons.Rounded.Key) {
                Text(
                    if (vm.hasApiKey()) "API Key 已設定" else "尚未設定 API Key",
                    color = if (vm.hasApiKey()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { keyDialog = true }) {
                        Text(if (vm.hasApiKey()) "更換 API Key" else "設定 API Key")
                    }
                    Button(onClick = vm::testApi, enabled = vm.hasApiKey() && !vm.apiTesting) {
                        if (vm.apiTesting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("測試連線")
                    }
                }
                vm.apiTestResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (result.ok) "連線成功" else "連線失敗：${result.message}",
                        color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            SectionCard("本機翻譯模型", Icons.Rounded.CloudDownload) {
                Text(
                    if (readiness.ready) "已下載・可使用" else "尚未完成下載",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (readiness.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (!readiness.ready) {
                    Spacer(Modifier.height(4.dp))
                    Text("需要下載 ${readiness.files.size - readiness.presentCount} 個模型檔案。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (vm.downloadRunning) {
                    Spacer(Modifier.height(10.dp))
                    vm.downloadUi?.let { state ->
                        Text(
                            state.error?.let { "下載失敗：$it" } ?: "正在下載模型…",
                            color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { if (state.overallTotal > 0L) (state.overallDone.toFloat() / state.overallTotal).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (!readiness.ready || vm.downloadRunning) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::downloadModels, enabled = !vm.downloadRunning) {
                            Text(if (vm.downloadRunning) "下載中" else "下載／補齊模型")
                        }
                        if (vm.downloadRunning) {
                            OutlinedButton(onClick = vm::cancelModelDownload) { Text("取消") }
                        }
                    }
                }
            }

            SectionCard("匯出設定", Icons.Rounded.Save) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("單本翻譯完成後自動匯出", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = s.singleAutoExport, onCheckedChange = { vm.saveSettings(s.copy(singleAutoExport = it)) })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("多本翻譯完成後自動匯出", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    Switch(checked = s.batchAutoExport, onCheckedChange = { vm.saveSettings(s.copy(batchAutoExport = it)) })
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    if (s.exportTreeUri == null) "儲存位置：Download/瑤心翻譯" else "儲存位置：自訂資料夾",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("更改匯出位置") }
                    if (s.exportTreeUri != null) {
                        TextButton(onClick = { vm.selectExportTree(null) }) { Text("恢復預設") }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    if (keyDialog) {
        var apiKey by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { keyDialog = false },
            title = { Text("設定 API Key") },
            text = {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (apiKey.isNotBlank()) vm.setApiKey(apiKey.trim())
                    keyDialog = false
                }, enabled = apiKey.isNotBlank()) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { keyDialog = false }) { Text("取消") } },
        )
    }
}
