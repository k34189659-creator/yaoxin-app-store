package tw.dafeiyu.mangatranslator.ui.screens

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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tw.dafeiyu.mangatranslator.AppViewModel
import tw.dafeiyu.mangatranslator.core.Project
import tw.dafeiyu.mangatranslator.ui.ScreenHeader
import tw.dafeiyu.mangatranslator.ui.SectionCard
import tw.dafeiyu.mangatranslator.ui.StatTile

@Composable
fun ExportScreen(vm: AppViewModel, project: Project) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportTo(uri, vm.exportFileName())
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("匯出", project.name, onBack = { vm.back() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("完成", "${project.doneCount}", Color(0xFF2E7D32), Modifier.weight(1f))
                StatTile("部分", "${project.partialCount}", Color(0xFFB26A00), Modifier.weight(1f))
                StatTile("失敗", "${project.failedCount}", MaterialTheme.colorScheme.error, Modifier.weight(1f))
                StatTile("未處理", "${project.pendingCount}", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }
            Text(
                "略過 ${project.skippedCount} 頁；部分完成頁會輸出已翻區域，失敗/漏譯區保留原始日文。",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard("輸出內容", Icons.Rounded.FolderZip) {
                Text(
                    "• 頁序、資料夾結構、檔名完全沿用原始 ZIP。\n" +
                        "• 已完成的頁面替換成繁中版；失敗、略過、未處理的頁面直接複製原始壓縮條目（不重新編碼）。\n" +
                        "• 非圖片檔（txt、json 等）原樣保留。\n" +
                        "• 原始 ZIP 不會被覆蓋；預設輸出到 Download/瑤心翻譯（可在設定修改）。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "檔名：${vm.exportFileName()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = vm::exportToConfiguredFolder,
                enabled = project.doneCount + project.partialCount > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Download, null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("匯出繁中 ZIP", fontWeight = FontWeight.SemiBold)
            }
            if (project.doneCount + project.partialCount == 0) {
                Text(
                    "目前沒有可匯出的繁中頁面；先完成至少一頁再匯出。失敗頁仍可單獨匯出原圖，但沒有繁中內容。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            vm.lastExport?.let { info ->
                SectionCard("上次匯出", Icons.Rounded.Info) {
                    Text(info.fileName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "繁中 ${info.result.writtenPages} 頁・保留原圖 ${info.result.originalPages} 頁・其他檔 ${info.result.copiedOtherFiles} 個",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (info.result.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("警告：", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                        info.result.warnings.take(8).forEach {
                            Text("• $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "輸出位置：${info.uri}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "重新翻譯失敗頁後再匯出，會得到更完整的版本。若只想修改譯文，請到單頁「編輯譯文」重新嵌字；那不會再花 API 費用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
