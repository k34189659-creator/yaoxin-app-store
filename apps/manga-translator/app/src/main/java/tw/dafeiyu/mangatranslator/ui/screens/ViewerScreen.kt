package tw.dafeiyu.mangatranslator.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tw.dafeiyu.mangatranslator.AppViewModel
import tw.dafeiyu.mangatranslator.PatchSelection
import tw.dafeiyu.mangatranslator.core.PageEntry
import tw.dafeiyu.mangatranslator.core.PageStatus
import tw.dafeiyu.mangatranslator.core.Project
import tw.dafeiyu.mangatranslator.ui.PageStatusBadge
import tw.dafeiyu.mangatranslator.ui.ScreenHeader
import tw.dafeiyu.mangatranslator.ui.fmtCost
import tw.dafeiyu.mangatranslator.ui.fmtDuration
import tw.dafeiyu.mangatranslator.ui.rememberThumb
import java.io.File
import kotlin.math.max
import kotlin.math.min

@Composable
fun ViewerScreen(vm: AppViewModel, project: Project) {
    val index = vm.selectedPage.coerceIn(0, (project.pages.size - 1).coerceAtLeast(0))
    val page = project.pages.getOrNull(index) ?: return
    val dir = File(vm.modelsRoot, project.id)
    val translatedAvailable = (page.status == PageStatus.DONE || page.status == PageStatus.PARTIAL) && page.translatedFile != null
    val showTranslated = vm.viewerTranslated && translatedAvailable
    val file = if (showTranslated && page.translatedFile != null) File(dir, page.translatedFile!!)
    else File(dir, page.originalFile)
    val image = rememberThumb(file, 1600)
    var confirmRetranslate by remember { mutableStateOf(false) }
    var selectionMode by remember(index) { mutableStateOf(false) }
    var selection by remember(index) { mutableStateOf<android.graphics.RectF?>(null) }
    var showPatches by remember(index) { mutableStateOf(false) }
    LaunchedEffect(index) { vm.loadPatches(index) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "第 ${index + 1} / ${project.pages.size} 頁",
            page.displayName,
            onBack = { vm.back() },
            actions = {
                PageStatusBadge(page.status)
                IconButton(onClick = { vm.openDiagnostics(index) }) {
                    Icon(Icons.Rounded.BugReport, contentDescription = "診斷")
                }
            },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = !showTranslated,
                onClick = { vm.setViewerMode(false) },
                label = { Text("原文") },
            )
            FilterChip(
                selected = showTranslated,
                onClick = { if (translatedAvailable) vm.setViewerMode(true) },
                enabled = translatedAvailable,
                label = { Text("繁體中文") },
            )
            FilterChip(
                selected = selectionMode,
                onClick = {
                    selectionMode = !selectionMode
                    selection = null
                },
                label = { Text("框選補救") },
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (index > 0) vm.openViewer(index - 1) }, enabled = index > 0) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = "上一頁")
            }
            IconButton(
                onClick = { if (index < project.pages.size - 1) vm.openViewer(index + 1) },
                enabled = index < project.pages.size - 1,
            ) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = "下一頁")
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
                .background(Color(0xFF141014), RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                SelectableImage(
                    image = image,
                    pageW = page.width,
                    pageH = page.height,
                    enabled = selectionMode,
                    selection = selection,
                    onSelection = { rect ->
                        selection = rect
                        selectionMode = false
                        vm.selectPatchRect(index, rect)
                    },
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
            Text(
                if (selectionMode) "框選中" else if (showTranslated) "繁中版" else "原圖",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            )
            if (selectionMode) {
                Text(
                    "框選模式已開啟：在漫畫上按住並拖曳粉色選取框",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.TopCenter).padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        Surface(tonalElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${page.width} × ${page.height}・${page.ext.uppercase()}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        page.stats?.let { st ->
                            Text(
                                "引擎 ${fmtDuration(st.wallMs)}・" +
                                    "${st.kept}/${st.regions} 區・" +
                                    "${st.promptTokens + st.completionTokens} tokens・${fmtCost(st.costUsd)} USD",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (page.metaFile != null) {
                        OutlinedButton(onClick = { vm.openEditor(index) }) {
                            Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("編輯譯文")
                        }
                    }
                    if (vm.patches.isNotEmpty()) {
                        TextButton(onClick = { showPatches = true }) {
                            Text("補救紀錄 ${vm.patches.size}")
                        }
                    }
                }
                page.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (page.status == PageStatus.SKIPPED) "略過原因：$it" else "錯誤：$it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (page.status == PageStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { if (index > 0) vm.openViewer(index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("上一頁") }
                    Button(
                        onClick = { confirmRetranslate = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (page.status == PageStatus.DONE || page.status == PageStatus.PARTIAL) "重新翻譯" else "翻譯此頁")
                    }
                    OutlinedButton(
                        onClick = { if (index < project.pages.size - 1) vm.openViewer(index + 1) },
                        enabled = index < project.pages.size - 1,
                        modifier = Modifier.weight(1f),
                    ) { Text("下一頁") }
                }
            }
        }
    }

    vm.patchManualRequest?.let { req ->
        var manual by remember(req) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { vm.dismissPatchManual() },
            title = { Text("OCR 沒有讀到文字") },
            text = {
                Column {
                    Text("請輸入框選區域的${if (project.sourceLang == "ENGLISH") "英文" else "日文"}原文，App 會用你輸入的原文翻譯。", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        label = { Text(if (project.sourceLang == "ENGLISH") "英文原文" else "日文原文") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.continuePatchWithManualSource(manual) }, enabled = manual.isNotBlank()) {
                    Text("翻譯並嵌字")
                }
            },
            dismissButton = { TextButton(onClick = { vm.dismissPatchManual() }) { Text("取消") } },
        )
    }

    if (showPatches) {
        AlertDialog(
            onDismissRequest = { showPatches = false },
            title = { Text("局部補救紀錄") },
            text = {
                Column(Modifier.heightIn(max = 460.dp)) {
                    if (vm.patches.isEmpty()) {
                        Text("還沒有局部補救。")
                    } else {
                        vm.patches.forEach { p ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text("原文：${p.source.take(80)}", style = MaterialTheme.typography.bodyMedium)
                                Text("譯文：${p.translated.take(80)}", style = MaterialTheme.typography.bodyMedium)
                                Text("狀態：${p.status}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { vm.reOcrPatch(index, p.id) }) { Text("重新辨識") }
                                    TextButton(onClick = { vm.retranslatePatch(index, p.id) }) { Text("重新翻譯") }
                                    TextButton(onClick = { vm.rerenderPatch(index, p.id) }) { Text("重新嵌字") }
                                }
                            }
                            androidx.compose.material3.HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPatches = false }) { Text("關閉") } },
        )
    }

    if (confirmRetranslate) {
        AlertDialog(
            onDismissRequest = { confirmRetranslate = false },
            title = { Text("重新翻譯此頁？") },
            text = { Text("會再呼叫一次翻譯 API（可能產生費用），並覆蓋這一頁目前的譯文。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRetranslate = false
                    vm.translateSinglePage(index)
                }) { Text("開始翻譯") }
            },
            dismissButton = { TextButton(onClick = { confirmRetranslate = false }) { Text("取消") } },
        )
    }
}


@Composable
private fun SelectableImage(
    image: androidx.compose.ui.graphics.ImageBitmap,
    pageW: Int,
    pageH: Int,
    enabled: Boolean,
    selection: android.graphics.RectF?,
    onSelection: (android.graphics.RectF) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val sw = size.width.toFloat(); val sh = size.height.toFloat()
    val scale = if (sw > 0 && sh > 0) min(sw / pageW, sh / pageH) else 1f
    val ox = (sw - pageW * scale) / 2f
    val oy = (sh - pageH * scale) / 2f
    fun toOriginal(o: Offset): Offset = Offset(
        ((o.x - ox) / scale).coerceIn(0f, pageW.toFloat()),
        ((o.y - oy) / scale).coerceIn(0f, pageH.toFloat()),
    )
    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(enabled, pageW, pageH, size) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { dragStart = it; dragEnd = it },
                    onDrag = { change, _ -> change.consume(); dragEnd = change.position },
                    onDragEnd = {
                        val a = dragStart; val b = dragEnd
                        dragStart = null; dragEnd = null
                        if (a != null && b != null) {
                            val oa = toOriginal(a); val ob = toOriginal(b)
                            val l = min(oa.x, ob.x); val t = min(oa.y, ob.y)
                            val r = max(oa.x, ob.x); val bb = max(oa.y, ob.y)
                            if (r - l >= 12f && bb - t >= 12f) {
                                onSelection(android.graphics.RectF(l, t, r, bb))
                            }
                        }
                    },
                    onDragCancel = { dragStart = null; dragEnd = null },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(image, "page", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Canvas(Modifier.fillMaxSize()) {
            val activePink = Color(0xFFFF5FA8)
            if (enabled && pageW > 0 && pageH > 0) {
                // 不只彈提示：開啟框選後，整張漫畫立刻出現粉色操作範圍邊框。
                drawRect(
                    activePink.copy(alpha = .85f),
                    topLeft = Offset(ox.coerceAtLeast(0f), oy.coerceAtLeast(0f)),
                    size = Size(pageW * scale, pageH * scale),
                    style = Stroke(width = 5f),
                )
            }
            val a = dragStart; val b = dragEnd
            if (a != null && b != null) {
                val tl = Offset(min(a.x, b.x), min(a.y, b.y))
                val size = Size(kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
                drawRect(activePink.copy(alpha = .24f), topLeft = tl, size = size)
                drawRect(Color.White, topLeft = tl, size = size, style = Stroke(width = 7f))
                drawRect(activePink, topLeft = tl, size = size, style = Stroke(width = 4f))
                drawCircle(activePink, radius = 9f, center = a)
                drawCircle(activePink, radius = 9f, center = b)
            }
            selection?.let { r ->
                val tl = Offset(ox + r.left * scale, oy + r.top * scale)
                val size = Size(r.width() * scale, r.height() * scale)
                drawRect(Color(0xFF2ECC71).copy(alpha = .17f), topLeft = tl, size = size)
                drawRect(Color(0xFF2ECC71), topLeft = tl, size = size, style = Stroke(width = 5f))
            }
        }
    }
}
