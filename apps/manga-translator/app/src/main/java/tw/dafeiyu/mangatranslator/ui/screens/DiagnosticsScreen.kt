package tw.dafeiyu.mangatranslator.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tw.dafeiyu.mangatranslator.AppViewModel
import tw.dafeiyu.mangatranslator.core.Project
import tw.dafeiyu.mangatranslator.ui.ScreenHeader
import tw.dafeiyu.mangatranslator.ui.SectionCard
import tw.dafeiyu.mangatranslator.ui.fmtCost
import tw.dafeiyu.mangatranslator.ui.fmtDuration
import tw.dafeiyu.mangatranslator.ui.rememberThumb
import java.io.File

private val STAGES = listOf(
    "original" to "原圖",
    "detect" to "偵測框",
    "ocr" to "OCR 原文",
    "translation" to "API 譯文",
    "mask" to "擦字遮罩",
    "cleaned" to "擦字後",
    "final" to "最終嵌字",
)

@Composable
fun DiagnosticsScreen(vm: AppViewModel, project: Project) {
    val d = vm.diagnostics
    val rev = vm.revision
    val idx = d?.pageIndex ?: vm.selectedPage
    val page = project.pages.getOrNull(idx) ?: return
    var stage by remember(idx) { mutableStateOf("original") }
    val path = d?.images?.get(stage)
    val image = rememberThumb(path?.let { File(it) }, 2000)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportDiagnosticsReport(uri, vm.diagIncludeText, vm.diagIncludeImages)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "診斷", "第 ${idx + 1} 頁・${page.displayName}",
            onBack = { vm.back() },
            actions = { Icon(Icons.Rounded.BugReport, null, tint = MaterialTheme.colorScheme.primary) },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            STAGES.forEach { (key, label) ->
                FilterChip(
                    selected = stage == key,
                    onClick = { stage = key },
                    label = { Text(label) },
                    enabled = d?.images?.containsKey(key) == true,
                )
            }
        }
        key(rev) {
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(10.dp)
                .background(Color(0xFF141014), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                d == null -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text("準備診斷資料…", color = Color.White.copy(alpha = 0.8f))
                    }
                }
                path == null -> Text("此階段尚未產生預覽", color = Color.White.copy(alpha = 0.75f))
                image == null -> CircularProgressIndicator(color = Color.White)
                else -> Image(image, stage, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 330.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionCard("階段與用量", Icons.Rounded.BugReport) {
                val diag = d?.diag
                if (diag == null) {
                    Text("目前沒有診斷資料；此頁可能還沒分析。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        "狀態 ${diag.status}・${diag.qualityMode}・偵測 ${diag.detectionPasses} 輪（補抓 ${diag.addedLines} 行）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "文字行 ${diag.lines}・文字區 ${diag.regions}・有譯文 ${diag.kept}・OCR 補救 ${diag.ocrRemedyCount} 行",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "偵測 ${fmtDuration(diag.detectMs)}・OCR ${fmtDuration(diag.ocrMs)}・翻譯 ${fmtDuration(diag.translateMs)}・" +
                            "擦字 ${fmtDuration(diag.inpaintMs)}・排版 ${fmtDuration(diag.renderMs)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "tokens ${diag.promptTokens + diag.completionTokens}・${fmtCost(diag.costUsd)} USD・擦字 ${diag.inpaintMethod}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (diag.stageStatus.isNotEmpty()) {
                        Text(
                            "階段：" + diag.stageStatus.entries.joinToString("・") { "${it.key}=${it.value}" },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (diag.errors.isNotEmpty() || diag.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        (diag.errors + diag.warnings).take(6).forEach {
                            Text("• $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (diag.apiError != null) {
                        Text("API：${diag.apiError}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page.translatedFile == null) {
                    OutlinedButton(onClick = { vm.openDiagnostics(idx) }, enabled = vm.busy == null) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重新分析")
                    }
                }
                OutlinedButton(onClick = vm::generateCleanedPreview, enabled = vm.busy == null) {
                    Icon(Icons.Rounded.CleaningServices, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("擦字預覽")
                }
                OutlinedButton(onClick = vm::checkLeftovers, enabled = vm.busy == null) {
                    Text("漏字檢查")
                }
            }
            OutlinedButton(
                onClick = { vm.rerenderPageNoApi(idx) },
                enabled = vm.busy == null && page.translatedFile != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("套用目前擦字/排版設定重新輸出（不呼叫 API）")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("報告包含原文/譯文", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "預設不包含任何原文、譯文或漫畫圖片；API Key 永遠不會出現在報告中。",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.diagIncludeText, onCheckedChange = vm::updateDiagIncludeText)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("報告包含預覽圖", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "把目前各階段預覽（JPEG）以 base64 放進 JSON；檔案會明顯變大。預設不包含。",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vm.diagIncludeImages, onCheckedChange = vm::updateDiagIncludeImages)
            }
            Button(
                onClick = { launcher.launch("yaoxin-diag-${page.index + 1}.json") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = d != null,
            ) {
                Icon(Icons.Rounded.Download, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("匯出診斷報告（JSON）", fontWeight = FontWeight.SemiBold)
            }
            Text(
                "所有預覽都直接以原圖座標輸出，顯示時只做等比例縮放；框與文字位置不會因預覽縮放而錯位。",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
