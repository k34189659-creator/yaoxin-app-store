package tw.dafeiyu.mangatranslator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.dafeiyu.mangatranslator.AppViewModel
import li.joye.yakuyomi.engine.RegionOverride
import li.joye.yakuyomi.engine.TextOrientation
import tw.dafeiyu.mangatranslator.core.AnalysisCodec
import tw.dafeiyu.mangatranslator.core.DiagCodec
import tw.dafeiyu.mangatranslator.core.LoadedAnalysis
import tw.dafeiyu.mangatranslator.core.PageStatus
import tw.dafeiyu.mangatranslator.core.Project
import tw.dafeiyu.mangatranslator.ui.ScreenHeader
import java.io.File

@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun EditorScreen(vm: AppViewModel, project: Project) {
    val index = vm.selectedPage.coerceIn(0, (project.pages.size - 1).coerceAtLeast(0))
    val page = project.pages.getOrNull(index) ?: return
    val dir = File(vm.modelsRoot, project.id)
    val rev = vm.revision
    val loaded by produceState<LoadedAnalysis?>(initialValue = null, index, rev, page.metaFile) {
        value = withContext(Dispatchers.IO) { AnalysisCodec.load(dir, page) }
    }
    var texts by remember(index, rev) {
        mutableStateOf(loaded?.regions?.map { it.translatedText }.orEmpty())
    }
    val diag by produceState<tw.dafeiyu.mangatranslator.core.PageDiag?>(initialValue = null, index, rev) {
        value = withContext(Dispatchers.IO) { DiagCodec.load(dir, page) }
    }
    var overrides by remember(index, rev) { mutableStateOf<Map<Int, RegionOverride>>(emptyMap()) }
    LaunchedEffect(loaded) {
        loaded?.let { a -> texts = a.regions.map { it.translatedText } }
    }
    LaunchedEffect(diag) {
        overrides = diag?.overrides ?: emptyMap()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(
            "編輯譯文",
            "第 ${index + 1} 頁・${page.displayName}",
            onBack = { vm.back() },
            actions = {
                if (page.metaFile != null) {
                    OutlinedButton(onClick = { vm.reTranslateFromEditor(index) }) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重新翻譯")
                    }
                }
            },
        )

        val a = loaded
        if (a == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (page.metaFile == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("這一頁還沒有文字區資料", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "需要先成功翻譯一次，才會有原文、譯文與可重新排版的遮罩。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { vm.translateSinglePage(index) }) { Text("翻譯此頁") }
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "修改下方譯文後按「儲存並重新嵌字」：只重跑擦字與排版，不會呼叫付費 API。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "共 ${a.regions.size} 個文字區；來源語言：日文。快取遮罩：${if (a.maskFile.isFile) "有" else "缺少"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(a.regions) { i, region ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(24.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center,
                                ) { Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (region.direction == "v") "直排" else "橫排",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { vm.reOcrRegion(index, i) }) {
                                    Icon(Icons.Rounded.TextFields, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("重新辨識此區")
                                }
                            }
                            Text(
                                "原文：${region.sourceText.ifBlank { "（空白）" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = texts.getOrElse(i) { "" },
                                onValueChange = { v ->
                                    texts = texts.toMutableList().also { list ->
                                        while (list.size <= i) list.add("")
                                        list[i] = v
                                    }
                                },
                                label = { Text("繁體中文譯文") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 1,
                                maxLines = 6,
                            )
                            val ov = overrides[i] ?: RegionOverride()
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("位置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { overrides = overrides + (i to ov.copy(dx = ov.dx - 4f)) }) { Text("←") }
                                TextButton(onClick = { overrides = overrides + (i to ov.copy(dx = ov.dx + 4f)) }) { Text("→") }
                                TextButton(onClick = { overrides = overrides + (i to ov.copy(dy = ov.dy - 4f)) }) { Text("↑") }
                                TextButton(onClick = { overrides = overrides + (i to ov.copy(dy = ov.dy + 4f)) }) { Text("↓") }
                                TextButton(onClick = { overrides = overrides - i }) { Text("重設") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("字級", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { overrides = overrides + (i to ov.copy(fontScale = (ov.fontScale - 0.05f).coerceAtLeast(0.5f))) }) { Text("A−") }
                                TextButton(onClick = { overrides = overrides + (i to ov.copy(fontScale = (ov.fontScale + 0.05f).coerceAtMost(2.0f))) }) { Text("A+") }
                                TextButton(onClick = {
                                    val next = when (ov.orientation) {
                                        null -> TextOrientation.VERTICAL
                                        TextOrientation.VERTICAL -> TextOrientation.HORIZONTAL
                                        else -> null
                                    }
                                    overrides = overrides + (i to ov.copy(orientation = next))
                                }) {
                                    Text("方向：" + when (ov.orientation) { TextOrientation.VERTICAL -> "直"; TextOrientation.HORIZONTAL -> "橫"; else -> "自動" })
                                }
                                Text(
                                    "x${ov.dx.toInt()} y${ov.dy.toInt()} ${(ov.fontScale * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Surface(tonalElevation = 2.dp, shadowElevation = 8.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.translateSinglePage(index) },
                        modifier = Modifier.weight(0.42f).height(48.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重新翻譯")
                    }
                    Button(
                        onClick = { vm.saveEditsAndRender(index, texts, overrides) },
                        modifier = Modifier.weight(0.58f).height(48.dp),
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("儲存並重新嵌字", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
