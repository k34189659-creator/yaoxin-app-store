package tw.dafeiyu.mangatranslator.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tw.dafeiyu.mangatranslator.AppViewModel
import tw.dafeiyu.mangatranslator.Screen
import tw.dafeiyu.mangatranslator.core.PageEntry
import tw.dafeiyu.mangatranslator.core.PageStatus
import tw.dafeiyu.mangatranslator.core.Project
import tw.dafeiyu.mangatranslator.core.ProjectStatus
import tw.dafeiyu.mangatranslator.ui.PageStatusBadge
import tw.dafeiyu.mangatranslator.ui.ScreenHeader
import tw.dafeiyu.mangatranslator.ui.rememberThumb
import java.io.File

private enum class PageFilter(val label: String) {
    ALL("全部"), PENDING("待翻"), DONE("完成"), PARTIAL("部分"), FAILED("失敗")
}

@Composable
fun PagesScreen(vm: AppViewModel, project: Project) {
    var filter by remember { mutableStateOf(PageFilter.ALL) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dir = File(vm.modelsRoot, project.id)
    val hasPending = project.pages.any { it.status == PageStatus.PENDING }
    val hasFailed = project.failedCount > 0
    val hasPartial = project.partialCount > 0
    val running = vm.runRunning || project.status == ProjectStatus.RUNNING
    // 專案的來源語言是可變欄位；觀察 ViewModel 修訂號才能在切換後立即重繪選取狀態。
    val chosenSourceLanguage = remember(vm.revision, project) { project.sourceLang }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = project.name,
            subtitle = "${project.pages.size} 頁・${if (project.sourceLang == "ENGLISH") "英文" else "日文"} → 繁中・${project.inpaint.uppercase()} 擦字・${orientationLabel(project.orientation)}・${langLabel(project.targetLang)}",
            onBack = { vm.back() },
            actions = {
                IconButton(onClick = vm::openSettings) { Icon(Icons.Rounded.Settings, contentDescription = "設定") }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "更多") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("重新整理清單") },
                            leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                            onClick = { menuOpen = false; vm.refreshSummaries() },
                        )
                        DropdownMenuItem(
                            text = { Text("刪除這個專案", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                }
            },
        )

        // 進度摘要
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "完成 ${project.doneCount}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(10.dp))
                Text("部分 ${project.partialCount}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFB26A00))
                Spacer(Modifier.width(10.dp))
                Text("失敗 ${project.failedCount}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Text("略過 ${project.skippedCount}", style = MaterialTheme.typography.labelLarge, color = Color(0xFF8A6D1D))
                Spacer(Modifier.weight(1f))
                Text("${project.processedCount}/${project.pages.size}", style = MaterialTheme.typography.labelLarge)
            }
            if (!running && project.processedCount == 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "來源語言",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("JAPANESE" to "日文 → 繁中", "ENGLISH" to "英文 → 繁中").forEach { (key, label) ->
                        val isSelected = chosenSourceLanguage == key
                        Button(
                            onClick = { vm.setProjectSourceLanguage(key) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { stateDescription = if (isSelected) "已選取" else "未選取" },
                            shape = RoundedCornerShape(15.dp),
                            border = BorderStroke(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) Color(0xFF8A2556) else Color(0xFFE5BDD0),
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) Color(0xFFAD316A) else Color(0xFFFFEFF6),
                                contentColor = if (isSelected) Color.White else Color(0xFF67445A),
                            ),
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 9.dp),
                        ) {
                            if (isSelected) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "目前選擇：${if (chosenSourceLanguage == "ENGLISH") "英文" else "日文"} → 繁體中文",
                    color = Color(0xFF8A2556),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = if (project.pages.isEmpty()) 0f else project.processedCount.toFloat() / project.pages.size,
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(99.dp)),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = {
                            Text(
                                when (f) {
                                    PageFilter.ALL -> "全部 ${project.pages.size}"
                                    PageFilter.PENDING -> "待翻 ${project.pendingCount}"
                                    PageFilter.DONE -> "完成 ${project.doneCount}"
                                    PageFilter.PARTIAL -> "部分 ${project.partialCount}"
                                    PageFilter.FAILED -> "失敗 ${project.failedCount}"
                                }
                            )
                        },
                    )
                }
            }
        }

        val pages = when (filter) {
            PageFilter.ALL -> project.pages
            PageFilter.PENDING -> project.pages.filter { it.status == PageStatus.PENDING || it.status == PageStatus.RUNNING }
            PageFilter.DONE -> project.pages.filter { it.status == PageStatus.DONE }
            PageFilter.PARTIAL -> project.pages.filter { it.status == PageStatus.PARTIAL }
            PageFilter.FAILED -> project.pages.filter { it.status == PageStatus.FAILED }
        }
        if (pages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("這個分類沒有頁面", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(pages, key = { it.index }) { page ->
                    PageCell(page, dir, onClick = { vm.openViewer(page.index) })
                }
            }
        }

        Surface(tonalElevation = 1.dp, shadowElevation = 2.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            when {
                                running -> vm.openProgress()
                                hasPending -> vm.startTranslation()
                                hasFailed -> vm.retryFailedPages()
                                else -> vm.openExport()
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Icon(
                            when {
                                running -> Icons.Rounded.PlayArrow
                                hasPending -> Icons.Rounded.PlayArrow
                                hasFailed -> Icons.Rounded.Refresh
                                else -> Icons.Rounded.Download
                            }, null, Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                running -> "查看翻譯進度"
                                hasPending -> if (project.processedCount > 0) "繼續翻譯" else "開始翻譯"
                                hasFailed -> "重試失敗頁"
                                else -> "匯出繁中 ZIP"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    OutlinedButton(
                        onClick = vm::openExport,
                        enabled = project.doneCount > 0,
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                        Icon(Icons.Rounded.Download, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("匯出")
                    }
                }
                if (hasPending && hasFailed && !running) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = vm::retryFailedPages, modifier = Modifier.fillMaxWidth()) {
                        Text("重試 ${project.failedCount} 個失敗頁")
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除專案？") },
            text = { Text("「${project.name}」的所有原圖、譯圖與進度都會刪除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteProject(project.id)
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun PageCell(page: PageEntry, dir: File, onClick: () -> Unit) {
    val file = if ((page.status == PageStatus.DONE || page.status == PageStatus.PARTIAL) && page.translatedFile != null) {
        File(dir, page.translatedFile!!)
    } else {
        File(dir, page.originalFile)
    }
    val thumb = rememberThumb(file, 360)
    Column {
        Card(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb, contentDescription = page.displayName,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Box(
                    Modifier.align(Alignment.TopStart).padding(6.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text("${page.index + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) { PageStatusBadge(page.status) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            page.displayName,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if ((page.status == PageStatus.DONE || page.status == PageStatus.PARTIAL) && page.regionCount > 0) {
            Text(
                "${page.translatedRegions}/${page.regionCount} 區",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun langLabel(code: String): String = when (code) {
    "zh-HK" -> "繁中（香港）"
    "zh-CN" -> "簡體中文"
    else -> "繁中（台灣）"
}

private fun orientationLabel(o: String): String = when (o.uppercase()) {
    "VERTICAL" -> "直排"
    "HORIZONTAL" -> "橫排"
    else -> "自動方向"
}
