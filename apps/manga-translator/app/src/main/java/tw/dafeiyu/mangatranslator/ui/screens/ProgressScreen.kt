package tw.dafeiyu.mangatranslator.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.dafeiyu.mangatranslator.AppViewModel
import tw.dafeiyu.mangatranslator.core.PageStatus
import tw.dafeiyu.mangatranslator.core.Project
import tw.dafeiyu.mangatranslator.core.ProjectStatus
import tw.dafeiyu.mangatranslator.ui.ScreenHeader
import tw.dafeiyu.mangatranslator.ui.SectionCard
import tw.dafeiyu.mangatranslator.ui.StatTile
import tw.dafeiyu.mangatranslator.ui.fmtCost
import tw.dafeiyu.mangatranslator.ui.fmtDuration

@Composable
fun ProgressScreen(vm: AppViewModel, project: Project) {
    val processed = project.processedCount
    val total = project.pages.size
    val fraction = if (total > 0) processed.toFloat() / total else 0f
    val running = vm.runRunning || project.status == ProjectStatus.RUNNING
    val doneStats = project.pages.filter { it.status == PageStatus.DONE || it.status == PageStatus.PARTIAL }.mapNotNull { it.stats }
    val avgWall = if (doneStats.isNotEmpty()) doneStats.sumOf { it.wallMs } / doneStats.size else 0
    val hasPending = project.pages.any { it.status == PageStatus.PENDING }
    val hasFailed = project.failedCount > 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(
            "翻譯進度",
            project.name,
            onBack = { vm.back() },
            actions = {
                if (project.doneCount + project.partialCount > 0) {
                    OutlinedButton(onClick = vm::openExport) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("匯出")
                    }
                }
            },
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = fraction,
                            modifier = Modifier.size(118.dp),
                            strokeWidth = 9.dp,
                            trackColor = MaterialTheme.colorScheme.primaryContainer,
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.headlineLarge)
                            Text("已處理 $processed / $total", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        vm.runMessage.ifBlank {
                            when (project.status) {
                                ProjectStatus.DONE -> "全部完成"
                                ProjectStatus.DONE_WITH_ERRORS -> "完成，但有部分/失敗頁面待檢查"
                                ProjectStatus.PAUSED -> "已暫停，可繼續"
                                ProjectStatus.READY -> "等待開始"
                                ProjectStatus.ERROR -> "處理中止"
                                else -> "準備中…"
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (project.status == ProjectStatus.ERROR) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (avgWall > 0) {
                        Text(
                            "平均每頁 ${fmtDuration(avgWall)}（引擎牆鐘）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp)),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("完成", "${project.doneCount}", Color(0xFF2E7D32), Modifier.weight(1f))
                StatTile("部分", "${project.partialCount}", Color(0xFFB26A00), Modifier.weight(1f))
                StatTile("失敗", "${project.failedCount}", MaterialTheme.colorScheme.error, Modifier.weight(1f))
                StatTile("待處理", "${project.pendingCount}", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }
            Text(
                "略過 ${project.skippedCount} 頁（沒有可翻譯文字）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionCard("用量", Icons.Rounded.CheckCircle) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Prompt tokens", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${project.totalPromptTokens}", style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Completion tokens", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${project.totalCompletionTokens}", style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("費用", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${fmtCost(project.totalCostUsd)} USD", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "以 provider 回傳的 usage / cost 為準；Venice 會回傳 cost.usd，其他相容端點未提供時顯示「—」。",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (vm.runLogs.isNotEmpty()) {
                SectionCard("錯誤與重試紀錄", Icons.Rounded.ErrorOutline) {
                    Column(
                        Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    ) {
                        vm.runLogs.reversed().forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            if (project.failedCount > 0 || project.partialCount > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "失敗 ${project.failedCount} 頁、部分完成 ${project.partialCount} 頁。",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "部分完成頁已輸出成功區域，失敗/漏譯區保留原始日文並標記待檢查；失敗頁匯出時保留原圖。可到頁面清單點該頁查看診斷與原因。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running) {
                    OutlinedButton(onClick = vm::pauseTranslation, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Rounded.Pause, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("暫停")
                    }
                } else if (hasPending) {
                    Button(onClick = { vm.startTranslation() }, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("繼續翻譯")
                    }
                } else if (hasFailed) {
                    Button(onClick = vm::retryFailedPages, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重試失敗頁")
                    }
                } else {
                    Button(onClick = vm::openExport, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("匯出繁中 ZIP")
                    }
                }
                OutlinedButton(onClick = vm::openPages, modifier = Modifier.height(48.dp)) {
                    Text("頁面清單")
                }
            }
            if (!running && hasPending && hasFailed) {
                TextButton(onClick = vm::retryFailedPages, modifier = Modifier.fillMaxWidth()) {
                    Text("只重試 ${project.failedCount} 個失敗頁")
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
