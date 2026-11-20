package tw.dafeiyu.mangatranslator.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Biotech
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tw.dafeiyu.mangatranslator.AppViewModel
import tw.dafeiyu.mangatranslator.SelfTestResult
import tw.dafeiyu.mangatranslator.ui.ScreenHeader
import tw.dafeiyu.mangatranslator.ui.SectionCard
import tw.dafeiyu.mangatranslator.ui.rememberThumb

@Composable
fun SelfTestScreen(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.runSelfTest(uri)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("本機引擎自測", "不呼叫 API，只驗證 OCR／擦字／嵌字", onBack = { vm.back() })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard("測試方式", Icons.Rounded.Biotech) {
                Text(
                    "選一張日文漫畫頁，App 會用內建的模擬翻譯（固定繁中句子）跑一次完整本機流程：" +
                        "DBNet 偵測 → 48px CTC OCR → AOT-GAN 擦字 → Canvas 嵌字。\n\n" +
                        "這個流程完全在手機上執行，不呼叫 Venice 或其他 API，也不消耗額度。輸出只用於診斷，不會加入專案。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { picker.launch("image/*") },
                    enabled = !vm.selfTestRunning,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Icon(Icons.Rounded.ImageSearch, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (vm.selfTestRunning) "執行中…" else "選擇圖片並開始自測")
                }
                Text(
                    "模型狀態：${if (vm.modelReadiness.ready) "已就緒" else "尚未下載（請先到設定下載）"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (vm.modelReadiness.ready) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                )
            }

            if (vm.selfTestRunning) {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("載入模型並處理中；第一次載入約需數秒…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            vm.selfTestResult?.let { SelfTestResultCard(it) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SelfTestResultCard(r: SelfTestResult) {
    val before = rememberThumb(r.before, 900)
    val after = rememberThumb(r.after, 900)
    SectionCard("自測結果", Icons.Rounded.Biotech) {
        Text(r.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            color = if (r.error != null) MaterialTheme.colorScheme.error else Color(0xFF2E7D32))
        if (r.before != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("原圖", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.72f)
                            .background(Color(0xFF141014), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (before != null) Image(before, "原圖", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("自測輸出", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.72f)
                            .background(Color(0xFF141014), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (r.after != null) {
                            if (after != null) Image(after, "自測輸出", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
                        } else {
                            Text("無輸出", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        r.error?.let {
            Spacer(Modifier.height(10.dp))
            Text("錯誤：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        r.stats?.let { st ->
            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "文字行 ${st.lines}・文字區 ${st.regions}・有效譯文 ${st.kept}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "偵測 ${st.detectMs} ms・OCR ${st.ocrMs} ms・擦字 ${st.inpaintMs} ms・排版 ${st.renderMs} ms",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "實際牆鐘 ${st.wallMs} ms（擦字與翻譯重疊；自測的「翻譯」是立即回傳的模擬器）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "注意：自測輸出使用固定模擬句子，不是真實翻譯結果；它只證明本機 OCR／擦字／嵌字流程可執行。",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
