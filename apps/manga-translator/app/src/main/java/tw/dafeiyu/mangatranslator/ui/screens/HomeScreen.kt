package tw.dafeiyu.mangatranslator.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.dafeiyu.mangatranslator.AppViewModel
import tw.dafeiyu.mangatranslator.R
import tw.dafeiyu.mangatranslator.core.ProjectStatus
import tw.dafeiyu.mangatranslator.core.ProjectSummary
import tw.dafeiyu.mangatranslator.data.QueueItem
import tw.dafeiyu.mangatranslator.ui.EmptyState
import tw.dafeiyu.mangatranslator.ui.StatusBadge
import tw.dafeiyu.mangatranslator.ui.fmtTime

@Composable
fun HomeScreen(vm: AppViewModel) {
    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importZip(uri)
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importZips(uris)
    }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    val summaries = vm.summaries

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        SakuraImportBanner(
            onSingle = { singlePicker.launch(arrayOf("application/zip", "application/x-cbz", "*/*")) },
            onBatch = { multiPicker.launch(arrayOf("application/zip", "application/x-cbz", "*/*")) },
            onSettings = vm::openSettings,
        )
        if (vm.translationQueue.isNotEmpty()) {
            SectionQueue(vm)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近的專案", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (summaries.isNotEmpty()) {
                if (selecting) {
                    TextButton(onClick = {
                        if (selected.isNotEmpty()) pendingDelete = selected
                    }, enabled = selected.isNotEmpty()) { Text("刪除已選 (${selected.size})") }
                    TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("取消選取") }
                } else {
                    TextButton(onClick = { selecting = true }) { Text("選取專案") }
                    TextButton(onClick = { pendingDelete = summaries.map { it.id }.toSet() }) { Text("全部刪除", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (selecting) {
            Text(
                "已選取 ${selected.size} 個專案・點選漫畫卡片可勾選／取消",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (summaries.isEmpty()) {
            EmptyState("還沒有漫畫專案", "選擇單本或多本 ZIP／CBZ 開始使用。")
        } else {
            summaries.forEach { entry ->
                ProjectItem(
                    summary = entry,
                    selecting = selecting,
                    checked = entry.id in selected,
                    onClick = {
                        if (selecting) selected = if (entry.id in selected) selected - entry.id else selected + entry.id
                        else vm.openProject(entry.id)
                    },
                    onDelete = { pendingDelete = setOf(entry.id) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    pendingDelete?.let { ids ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (ids.size == 1) "刪除漫畫專案？" else "刪除 ${ids.size} 個專案？") },
            text = { Text("所選專案的原圖、翻譯結果與進度都會永久刪除，無法復原。正在執行的佇列任務不能刪除。") },
            confirmButton = { TextButton(onClick = {
                vm.deleteProjects(ids)
                selected = emptySet()
                selecting = false
                pendingDelete = null
            }) { Text("確認刪除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SakuraImportBanner(onSingle: () -> Unit, onBatch: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFFFFE4F0), Color(0xFFF1DDF9)))),
    ) {
        Box(Modifier.fillMaxWidth().height(145.dp)) {
            Image(painterResource(R.drawable.yx_manga_character), contentDescription = null,
                modifier = Modifier.align(Alignment.CenterEnd).width(136.dp).fillMaxHeight(),
                contentScale = ContentScale.Crop, alignment = Alignment.CenterEnd)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                listOf(Color(0xFFFFE7F2), Color(0xFFFFE7F2).copy(alpha = .88f), Color.Transparent))))
            IconButton(
                onClick = onSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = .83f)),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "設定", tint = Color(0xFF87335F))
            }
            Row(Modifier.align(Alignment.CenterStart).padding(start = 14.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.yx_manga_logo), contentDescription = "瑤芯 Logo", modifier = Modifier.size(52.dp))
                Spacer(Modifier.width(7.dp))
                Column {
                    Text("瑤芯漫畫翻譯", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF4A253E))
                    Text("我的漫畫書庫", style = MaterialTheme.typography.labelLarge, color = Color(0xFF825B76))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSingle, modifier = Modifier.weight(1f).height(47.dp), shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6397F), contentColor = Color.White)) {
                Icon(Icons.Rounded.FileOpen, null, Modifier.size(19.dp))
                Spacer(Modifier.width(4.dp))
                Text("單本匯入", maxLines = 1)
            }
            Button(onClick = onBatch, modifier = Modifier.weight(1f).height(47.dp), shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF90365F))) {
                Icon(Icons.Rounded.LibraryAdd, null, Modifier.size(19.dp))
                Spacer(Modifier.width(4.dp))
                Text("多本匯入", maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionQueue(vm: AppViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("翻譯佇列", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (vm.queueRunning) {
                    TextButton(onClick = vm::pauseQueue) { Icon(Icons.Rounded.Pause, null); Text("暫停") }
                } else {
                    TextButton(onClick = vm::startQueue) { Icon(Icons.Rounded.PlayArrow, null); Text("開始／繼續") }
                }
            }
            vm.translationQueue.forEach { item ->
                val summary = vm.summaries.firstOrNull { it.id == item.projectId }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (item.state == QueueItem.DONE) Icons.Rounded.CheckCircle else Icons.Rounded.Book,
                        contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(summary?.name ?: "已遺失的專案", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        val label = when(item.state) {
                            QueueItem.RUNNING -> "翻譯中"
                            QueueItem.PAUSED -> "已暫停"
                            QueueItem.DONE -> "已完成"
                            QueueItem.ERROR -> "需要檢查"
                            else -> "等待中"
                        }
                        Text("$label${if (item.autoExport) "・自動匯出" else "・手動匯出"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        if (item.state == QueueItem.RUNNING && vm.project?.id == item.projectId) {
                            // 讀取 revision 以便每完成一頁就重新顯示進度，不另行載入 RUNNING 專案。
                            vm.revision // 訂閱本頁更新事件
                            val active = vm.project
                            if (active != null && active.pages.isNotEmpty()) {
                                val finished = active.processedCount
                                LinearProgressIndicator(
                                    progress = { finished.toFloat() / active.pages.size },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(99.dp)),
                                )
                                Text("$finished / ${active.pages.size} 頁 · 點入可看詳細進度", 
                                    modifier = Modifier.clickable { vm.openProgress() },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        item.message?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                            color = if (item.state == QueueItem.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (item.state == QueueItem.WAITING) {
                        IconButton(onClick = { vm.moveQueue(item.projectId, -1) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.KeyboardArrowUp, "往前", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { vm.moveQueue(item.projectId, 1) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.KeyboardArrowDown, "往後", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (item.state != QueueItem.RUNNING) {
                        IconButton(onClick = { vm.removeFromQueue(item.projectId) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, "移出佇列", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (item != vm.translationQueue.last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun ProjectItem(summary: ProjectSummary, selecting: Boolean, checked: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    Checkbox(checked = checked, onCheckedChange = { onClick() })
                    Spacer(Modifier.width(6.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(summary.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${summary.pageCount} 頁 · 已完成 ${summary.done} · 部分 ${summary.partial} · 失敗 ${summary.failed}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (!selecting) {
                    val label = when (summary.status) {
                        ProjectStatus.RUNNING -> "進行中"
                        ProjectStatus.PAUSED -> "已暫停"
                        ProjectStatus.DONE -> "完成"
                        ProjectStatus.DONE_WITH_ERRORS -> "部分完成"
                        ProjectStatus.ERROR -> "失敗"
                        else -> "待翻譯"
                    }
                    StatusBadge(label, MaterialTheme.colorScheme.primary)
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.DeleteOutline, "刪除專案", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val progress = if (summary.pageCount > 0) (summary.done + summary.partial + summary.failed + summary.skipped).toFloat() / summary.pageCount else 0f
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(99.dp)))
            Spacer(Modifier.height(4.dp))
            Text("更新於 ${fmtTime(summary.updatedAt)}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
