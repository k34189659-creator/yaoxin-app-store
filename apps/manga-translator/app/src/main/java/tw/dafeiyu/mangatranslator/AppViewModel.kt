package tw.dafeiyu.mangatranslator

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.net.Uri
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.EnglishTranslationGuard
import li.joye.yakuyomi.engine.Grouping
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.Legacy020Inpainter
import li.joye.yakuyomi.engine.Legacy020SmartRenderer
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.OcrRemedyMode
import li.joye.yakuyomi.engine.RegionLayout
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.RegionOverride
import li.joye.yakuyomi.engine.RenderConfig
import tw.dafeiyu.mangatranslator.core.OcrMode
import tw.dafeiyu.mangatranslator.core.SourceLanguage
import li.joye.yakuyomi.engine.SmartInpaintResult
import li.joye.yakuyomi.engine.SmartRenderer
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslatorConfig
import tw.dafeiyu.mangatranslator.core.ADULT_FAITHFUL_PROMPT
import tw.dafeiyu.mangatranslator.core.LEGACY020_ADULT_FAITHFUL_PROMPT
import tw.dafeiyu.mangatranslator.core.LEGACY020_SIMPLIFIED_SAMPLE_TARGET
import tw.dafeiyu.mangatranslator.core.ENGLISH_SAMPLE_SOURCE
import tw.dafeiyu.mangatranslator.core.ENGLISH_SAMPLE_TARGET
import tw.dafeiyu.mangatranslator.core.JAPANESE_SAMPLE_SOURCE
import tw.dafeiyu.mangatranslator.core.JAPANESE_SAMPLE_TARGET
import tw.dafeiyu.mangatranslator.core.DiagCodec
import tw.dafeiyu.mangatranslator.core.AnalysisCodec
import tw.dafeiyu.mangatranslator.core.ExportResult
import tw.dafeiyu.mangatranslator.core.ImportProgress
import tw.dafeiyu.mangatranslator.core.ImageIo
import tw.dafeiyu.mangatranslator.core.MangaExporter
import tw.dafeiyu.mangatranslator.core.ManualPatch
import tw.dafeiyu.mangatranslator.core.MockTranslator
import tw.dafeiyu.mangatranslator.core.PatchCodec
import tw.dafeiyu.mangatranslator.core.PageEntry
import tw.dafeiyu.mangatranslator.core.PageDiag
import tw.dafeiyu.mangatranslator.core.PageStatsInfo
import tw.dafeiyu.mangatranslator.core.PageStatus
import tw.dafeiyu.mangatranslator.core.Project
import tw.dafeiyu.mangatranslator.core.ProjectStatus
import tw.dafeiyu.mangatranslator.core.ProjectStore
import tw.dafeiyu.mangatranslator.core.ProjectSummary
import tw.dafeiyu.mangatranslator.core.RegionDiag
import tw.dafeiyu.mangatranslator.core.RunnerEvent
import tw.dafeiyu.mangatranslator.core.SmartTranslator
import tw.dafeiyu.mangatranslator.core.EnglishImagePipeline
import tw.dafeiyu.mangatranslator.core.MlKitLatinOcr
import tw.dafeiyu.mangatranslator.core.Legacy020SmartTranslator
import tw.dafeiyu.mangatranslator.core.StageImageFactory
import tw.dafeiyu.mangatranslator.core.TranslationRunner
import tw.dafeiyu.mangatranslator.core.ZipImporter
import tw.dafeiyu.mangatranslator.data.QueueItem
import tw.dafeiyu.mangatranslator.data.TranslationQueueStore
import tw.dafeiyu.mangatranslator.data.AppSettings
import tw.dafeiyu.mangatranslator.data.DownloadUi
import tw.dafeiyu.mangatranslator.data.ModelReadiness
import tw.dafeiyu.mangatranslator.data.ModelRepository
import tw.dafeiyu.mangatranslator.data.SettingsStore
import tw.dafeiyu.mangatranslator.net.ApiClient
import tw.dafeiyu.mangatranslator.net.ApiConfig
import tw.dafeiyu.mangatranslator.net.ApiTestResult
import tw.dafeiyu.mangatranslator.net.ModelOption
import java.io.File
import java.io.FileOutputStream

enum class Screen { HOME, PAGES, SETTINGS, PROGRESS, VIEWER, EXPORT, EDITOR, SELFTEST, DIAG }

data class BusyState(val label: String, val detail: String = "", val progress: Float? = null)

data class SelfTestResult(
    val before: File?,
    val after: File?,
    val stats: PageStatsInfo?,
    val error: String?,
    val summary: String,
)

data class DiagnosticsUi(
    val pageIndex: Int,
    val images: Map<String, String>,
    val diag: PageDiag?,
    val message: String? = null,
)

data class PatchSelection(val pageIndex: Int, val rect: RectF)

data class ExportInfo(
    val uri: String,
    val fileName: String,
    val result: ExportResult,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val settingsStore = SettingsStore(app)
    val modelsRoot = File(app.filesDir, "projects").apply { mkdirs() }
    val projectStore = ProjectStore(modelsRoot)
    private val queueStore = TranslationQueueStore(File(app.filesDir, "translation_queue.json"))
    var translationQueue by mutableStateOf(queueStore.load())
        private set
    private var queueJob: Job? = null
    val queueRunning: Boolean get() = queueJob?.isActive == true
    val modelRepo = ModelRepository(app)

    var settings by mutableStateOf(settingsStore.load())
        private set
    var modelReadiness by mutableStateOf(modelRepo.readiness())
        private set
    var downloadUi by mutableStateOf<DownloadUi?>(null)
    var ppReadiness by mutableStateOf(modelRepo.ppReadiness())
    var ppDownloadRunning by mutableStateOf(false)
        private set
    var downloadRunning by mutableStateOf(false)
        private set
    var summaries by mutableStateOf(projectStore.list())
        private set

    var screen by mutableStateOf(Screen.HOME)
        private set
    var project by mutableStateOf<Project?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var selectedPage by mutableIntStateOf(0)
        private set
    var viewerTranslated by mutableStateOf(true)
        private set

    var busy by mutableStateOf<BusyState?>(null)
        private set
    var dialogMessage by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)

    var importProgress by mutableStateOf<ImportProgress?>(null)
        private set
    var runRunning by mutableStateOf(false)
        private set
    var runMessage by mutableStateOf("")
        private set
    var runLogs by mutableStateOf<List<String>>(emptyList())
        private set
    var lastExport by mutableStateOf<ExportInfo?>(null)
        private set
    var selfTestRunning by mutableStateOf(false)
        private set
    var selfTestResult by mutableStateOf<SelfTestResult?>(null)
        private set
    var diagnostics by mutableStateOf<DiagnosticsUi?>(null)
        private set
    var diagIncludeText by mutableStateOf(false)
        private set
    var diagIncludeImages by mutableStateOf(false)
        private set
    var patches by mutableStateOf<List<ManualPatch>>(emptyList())
        private set
    var patchManualRequest by mutableStateOf<PatchSelection?>(null)
        private set

    var apiModels by mutableStateOf<List<ModelOption>>(emptyList())
        private set
    var apiModelsLoading by mutableStateOf(false)
        private set
    var apiModelsError by mutableStateOf<String?>(null)
        private set
    var apiTesting by mutableStateOf(false)
        private set
    var apiTestResult by mutableStateOf<ApiTestResult?>(null)
        private set

    private var runJob: Job? = null
    private var downloadJob: Job? = null
    private var afterRunScreen: Screen? = null
    private var afterRunPage: Int? = null

    private val alphabetCache by lazy {
        app.assets.open("models/alphabet-all-v5.txt").bufferedReader(Charsets.UTF_8).readLines()
    }

    fun hasApiKey(): Boolean = settingsStore.hasApiKey()
    fun apiKeyPreview(): String = if (settingsStore.hasApiKey()) "已安全儲存" else "未設定"

    fun setApiKey(key: String?) {
        settingsStore.setApiKey(key)
        val s = settings.copy()
        settingsStore.save(s)
        toast = if (key.isNullOrBlank()) "已清除 API Key" else "API Key 已加密儲存"
    }

    fun saveSettings(s: AppSettings) {
        val normalized = if (s.providerId == "venice") {
            s.copy(baseUrl = ApiConfig.VENICE_BASE, modelId = s.modelId.ifBlank { ApiConfig.DEFAULT_MODEL })
        } else s
        settings = normalized
        settingsStore.save(normalized)
        apiTestResult = null
    }

    fun setMockTranslation(enabled: Boolean) {
        saveSettings(settings.copy(mockTranslation = enabled))
    }

    // ---------------- 匯入 ----------------

    /** 單本匯入：保持原 0.4.1 的匯入流程；預設由使用者手動開始翻譯及匯出。 */
    fun importZip(uri: Uri) {
        if (!canImport()) return
        viewModelScope.launch {
            try {
                val p = importOneZip(uri)
                project = p
                selectedPage = 0
                screen = Screen.PAGES
                // 單本匯入後直接顯示專案與語言選擇，不用底部 Snackbar 遮住操作鈕。
            } catch (t: Throwable) {
                dialogMessage = "匯入失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = null
                importProgress = null
            }
        }
    }

    /** 多本匯入：序列匯入、寫入持久佇列，全部匯入後才開始串行翻譯。 */
    fun importZips(uris: List<Uri>) {
        if (uris.isEmpty() || !canImport()) return
        val uniqueUris = uris.distinctBy(Uri::toString)
        viewModelScope.launch {
            var imported = 0
            val errors = mutableListOf<String>()
            try {
                uniqueUris.forEachIndexed { index, uri ->
                    try {
                        busy = BusyState("批次匯入 ${index + 1}/${uniqueUris.size}")
                        val p = importOneZip(uri)
                        enqueueProject(p.id, settings.batchAutoExport)
                        imported++
                    } catch (t: Throwable) {
                        errors.add("${queryDisplayName(uri) ?: "第 ${index + 1} 本"}：${t.message ?: "匯入失敗"}")
                    }
                }
            } finally {
                busy = null
                importProgress = null
            }
            screen = Screen.HOME
            summaries = withContext(Dispatchers.IO) { projectStore.list() }
            // 佇列在首頁會持續顯示任務狀態；不再用覆蓋式通知提示匯入成功。
            if (errors.isNotEmpty()) dialogMessage = "部分漫畫無法匯入：\n" + errors.take(5).joinToString("\n")
            if (imported > 0) startQueue()
        }
    }

    private fun canImport(): Boolean {
        if (busy != null || runRunning || downloadRunning || selfTestRunning || queueRunning) {
            toast = "翻譯、匯入或模型下載正在進行，請稍後再匯入。"
            return false
        }
        return true
    }

    private suspend fun importOneZip(uri: Uri): Project {
        val app = getApplication<Application>()
        busy = BusyState("匯入漫畫 ZIP", "複製檔案…")
        importProgress = null
        var dir: File? = null
        try {
            val displayName = queryDisplayName(uri)
            val declaredSize = querySize(uri)
            val targetDir = withContext(Dispatchers.IO) { ZipImporter.newProjectDir(modelsRoot) }
            dir = targetDir
            if (declaredSize > 0 && targetDir.usableSpace < declaredSize * 3) {
                throw IllegalStateException("儲存空間不足：ZIP ${fmt(declaredSize)}，可用 ${fmt(targetDir.usableSpace)}")
            }
            val zip = File(targetDir, "source.zip")
            withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(zip).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        var last = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - last > 4L * 1024 * 1024) {
                                last = done
                                val pct = if (declaredSize > 0) done.toFloat() / declaredSize else null
                                withContext(Dispatchers.Main) {
                                    busy = BusyState("匯入漫畫 ZIP", "複製 ${fmt(done)} / ${if (declaredSize > 0) fmt(declaredSize) else "?"}", pct)
                                }
                            }
                        }
                        out.fd.sync()
                    }
                } ?: throw IllegalStateException("無法讀取選擇的 ZIP")
            }
            busy = BusyState("匯入漫畫 ZIP", "建立漫畫頁序…")
            val name = ZipImporter.projectNameFromZip(displayName ?: "漫畫")
            val p = withContext(Dispatchers.IO) {
                ZipImporter.import(zip, targetDir, name, onProgress = { prog ->
                    importProgress = prog
                    val pct = if (prog.totalBytes > 0) prog.doneBytes.toFloat() / prog.totalBytes else null
                    busy = BusyState("匯入漫畫 ZIP", "${prog.stage}：${prog.currentName.takeLast(40)}", pct)
                })
            }
            p.sourceLang = settings.ocrLang
            settings = settings.copy(lastProjectId = p.id)
            withContext(Dispatchers.IO) { projectStore.save(p) }
            settingsStore.save(settings)
            summaries = withContext(Dispatchers.IO) { projectStore.list() }
            revision++
            return p
        } catch (t: Throwable) {
            withContext(Dispatchers.IO) { runCatching { dir?.deleteRecursively() } }
            throw t
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            getApplication<Application>().contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    private fun querySize(uri: Uri): Long =
        runCatching {
            getApplication<Application>().contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
        }.getOrDefault(-1L)

    // ---------------- 專案 ----------------

    fun openProject(id: String) {
        if (runRunning) {
            // 避免重新載入同一本專案覆蓋正在更新的記憶體物件（舊版工作中斷的原因之一）。
            if (project?.id == id) screen = Screen.PAGES
            else toast = "佇列正在翻譯其他漫畫，請稍後再切換專案"
            return
        }
        viewModelScope.launch {
            busy = BusyState("開啟專案…")
            try {
                val p = withContext(Dispatchers.IO) { projectStore.load(id) }
                if (p == null) {
                    dialogMessage = "找不到專案資料"
                    summaries = withContext(Dispatchers.IO) { projectStore.list() }
                } else {
                    // 上次被系統殺掉時殘留的 RUNNING → 回到 PENDING，續傳會重新處理該頁
                    p.pages.filter { it.status == PageStatus.RUNNING }.forEach { it.status = PageStatus.PENDING }
                    if (p.status == ProjectStatus.RUNNING) p.status = ProjectStatus.PAUSED
                    p.lastOpenedAt = System.currentTimeMillis()
                    withContext(Dispatchers.IO) { projectStore.save(p) }
                    project = p
                    selectedPage = 0
                    viewerTranslated = true
                    screen = Screen.PAGES
                    settings = settings.copy(lastProjectId = p.id)
                    settingsStore.save(settings)
                    revision++
                }
            } finally {
                busy = null
            }
        }
    }

    fun deleteProject(id: String) {
        if ((project?.id == id && runJob?.isActive == true) || translationQueue.any { it.projectId == id && it.state == QueueItem.RUNNING }) {
            dialogMessage = "翻譯正在進行，請先暫停或等待完成後再刪除專案。"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { projectStore.delete(id) }
            setQueue(translationQueue.filterNot { it.projectId == id })
            if (project?.id == id) project = null
            summaries = withContext(Dispatchers.IO) { projectStore.list() }
            if (project == null) screen = Screen.HOME
            toast = "專案已刪除"
        }
    }

    fun deleteProjects(ids: Set<String>) {
        if (ids.isEmpty()) return
        val active = translationQueue.firstOrNull { it.state == QueueItem.RUNNING }?.projectId
        if (ids.contains(active) || (runRunning && ids.contains(project?.id))) {
            dialogMessage = "選取項目包含正在翻譯的漫畫，請先暫停佇列。"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ids.forEach(projectStore::delete) }
            setQueue(translationQueue.filterNot { it.projectId in ids })
            if (project?.id in ids) project = null
            summaries = withContext(Dispatchers.IO) { projectStore.list() }
            if (project == null) screen = Screen.HOME
            toast = "已刪除 ${ids.size} 個專案"
        }
    }

    private fun setQueue(value: List<QueueItem>) {
        translationQueue = value
        queueStore.save(value)
    }

    private fun enqueueProject(id: String, autoExport: Boolean) {
        if (translationQueue.any { it.projectId == id }) return
        setQueue(translationQueue + QueueItem(id, autoExport = autoExport))
    }

    fun moveQueue(id: String, delta: Int) {
        val list = translationQueue.toMutableList()
        val from = list.indexOfFirst { it.projectId == id && it.state == QueueItem.WAITING }
        val to = from + delta
        if (from < 0 || to !in list.indices || list[to].state != QueueItem.WAITING) return
        java.util.Collections.swap(list, from, to)
        setQueue(list)
    }

    fun removeFromQueue(id: String) {
        val item = translationQueue.firstOrNull { it.projectId == id } ?: return
        if (item.state == QueueItem.RUNNING) {
            toast = "先暫停翻譯，再移除目前任務"
            return
        }
        setQueue(translationQueue.filterNot { it.projectId == id })
    }

    /** 佇列只由一個協程逐本啟動，不會同時載入多份 OCR 模型。 */
    fun startQueue() {
        if (queueRunning || runRunning || busy != null) return
        val waiting = translationQueue.any { it.state == QueueItem.WAITING || it.state == QueueItem.PAUSED }
        if (!waiting) return
        if (modelRepo.modelSet() == null || (!settingsStore.hasApiKey() && !(BuildConfig.DEBUG && settings.mockTranslation))) {
            toast = "佇列已保存；請先在設定中準備模型和 API Key，再按繼續"
            return
        }
        queueJob = viewModelScope.launch {
            while (true) {
                val task = translationQueue.firstOrNull { it.state == QueueItem.WAITING || it.state == QueueItem.PAUSED } ?: break
                val p = withContext(Dispatchers.IO) { projectStore.load(task.projectId) }
                if (p == null) {
                    setQueue(translationQueue.map { if (it.projectId == task.projectId) it.copy(state = QueueItem.ERROR, message = "找不到專案") else it })
                    continue
                }
                project = p
                setQueue(translationQueue.map { if (it.projectId == task.projectId) it.copy(state = QueueItem.RUNNING, message = null) else it })
                try {
                    startTranslation(navigateToProgress = false)
                    runJob?.join()
                    if (p.status == ProjectStatus.PAUSED || p.status == ProjectStatus.RUNNING) {
                        setQueue(translationQueue.map { if (it.projectId == task.projectId) it.copy(state = QueueItem.PAUSED) else it })
                        break
                    }
                    if (p.status == ProjectStatus.ERROR || p.status == ProjectStatus.READY) {
                        setQueue(translationQueue.map { if (it.projectId == task.projectId) it.copy(state = QueueItem.ERROR, message = p.lastError ?: "翻譯未完成") else it })
                        continue
                    }
                    var output: String? = null
                    var message: String? = null
                    if (task.autoExport) {
                        try {
                            val info = exportProjectToConfiguredFolder(p)
                            output = info.uri
                            message = "已匯出：${info.fileName}"
                        } catch (e: Exception) {
                            message = "翻譯完成，但自動匯出失敗：${e.message ?: "請手動匯出"}"
                        }
                    }
                    setQueue(translationQueue.map {
                        if (it.projectId == task.projectId) it.copy(state = if (task.autoExport && output == null) QueueItem.ERROR else QueueItem.DONE, outputUri = output, message = message)
                        else it
                    })
                } catch (c: CancellationException) {
                    setQueue(translationQueue.map { if (it.projectId == task.projectId) it.copy(state = QueueItem.PAUSED) else it })
                    throw c
                }
                summaries = withContext(Dispatchers.IO) { projectStore.list() }
            }
            toast = "翻譯佇列已處理完成"
        }
    }

    fun pauseQueue() {
        queueJob?.cancel()
        pauseTranslation()
        val active = translationQueue.firstOrNull { it.state == QueueItem.RUNNING }?.projectId
        if (active != null) setQueue(translationQueue.map { if (it.projectId == active) it.copy(state = QueueItem.PAUSED) else it })
    }

    fun refreshSummaries() {
        viewModelScope.launch {
            summaries = withContext(Dispatchers.IO) { projectStore.list() }
        }
    }

    // ---------------- 翻譯 ----------------

    /** 選擇當前漫畫來源語言。已翻譯的專案不可直接切換，以免 OCR 快取混用。 */
    fun setProjectSourceLanguage(value: String) {
        val p = project ?: return
        if (runRunning || busy != null || p.processedCount > 0) {
            toast = "已有翻譯紀錄；如需更換語言，請重新匯入漫畫。"
            return
        }
        val lang = if (value == "ENGLISH") "ENGLISH" else "JAPANESE"
        if (p.sourceLang == lang) return
        p.sourceLang = lang
        revision++
        viewModelScope.launch(Dispatchers.IO) { projectStore.save(p) }
        // 語言按鈕和目前選擇文字即時顯示狀態，不需再彈遮住畫面的通知。
    }

    fun startTranslation(retryFailed: Boolean = false, onlyPage: Int? = null, returnTo: Screen? = null, navigateToProgress: Boolean = true) {
        val p = project ?: return
        if (runJob?.isActive == true) return
        if (selfTestRunning || downloadRunning) {
            dialogMessage = "本機自測或模型下載正在進行，請稍後再開始翻譯。"
            return
        }
        val mock = BuildConfig.DEBUG && settings.mockTranslation
        val apiKey = settingsStore.apiKey()
        val models = modelRepo.modelSet()
        if (models == null) {
            dialogMessage = "翻譯模型尚未就緒。請先到「設定」下載或驗證模型（約 248 MB）。"
            screen = Screen.SETTINGS
            return
        }
        if (!mock && apiKey.isNullOrBlank()) {
            dialogMessage = "請先到「設定」輸入 API Key，或開啟測試模式（僅 Debug 版）。"
            screen = Screen.SETTINGS
            return
        }
        p.providerId = settings.providerId
        p.modelId = settings.modelId
        p.inpaint = settings.inpaint
        p.orientation = settings.orientation
        p.targetLang = settings.targetLang
        p.remedy = settings.remedy
        afterRunScreen = returnTo
        afterRunPage = onlyPage
        runRunning = true
        runMessage = if (mock) "模擬翻譯（不會呼叫 API）" else "準備載入模型…"
        runLogs = emptyList()
        if (navigateToProgress) screen = Screen.PROGRESS
        revision++
        val projectDir = File(modelsRoot, p.id)
        runJob = viewModelScope.launch {
            try {
                val runner = TranslationRunner(
                    projectDir = projectDir,
                    project = p,
                    models = models,
                    alphabet = alphabetCache,
                    apiKey = apiKey,
                    config = buildEngineConfig(p),
                    retries = settings.retries,
                    mock = mock,
                    remedy = runCatching { OcrRemedyMode.valueOf(settings.remedy) }.getOrDefault(OcrRemedyMode.BALANCED),
                    smartInpaint = p.inpaint == "smart",
                    includeFailed = retryFailed,
                    onlyIndex = onlyPage,
                    ppPaths = runCatching { modelRepo.ppPaths() }.getOrNull(),
                    ppDict = runCatching { modelRepo.ppDictionary() }.getOrDefault(emptyList()),
                    ocrMode = OcrMode.parse(settings.ocrMode),
                    ocrLang = SourceLanguage.parse(p.sourceLang),
                    styleMode = settings.styleMode,
                    exportFormat = settings.exportFormat,
                ) { ev: RunnerEvent ->
                    runMessage = ev.message
                    if (ev.kind == "page_failed" || ev.kind == "fatal" || ev.kind == "retry" || ev.kind == "page_partial") {
                        runLogs = (runLogs + ev.message).takeLast(80)
                    }
                    revision++
                }
                val result = runner.run()
                runMessage = "完成：${result.done} 成功、${result.partial} 部分、${result.failed} 失敗、${result.skipped} 略過"
                if (result.fatal != null) runLogs = (runLogs + "中止：${result.fatal}").takeLast(80)
                if (!queueRunning && onlyPage == null && settings.singleAutoExport &&
                    (p.status == ProjectStatus.DONE || p.status == ProjectStatus.DONE_WITH_ERRORS)) {
                    try {
                        val info = exportProjectToConfiguredFolder(p)
                        runLogs = (runLogs + "自動匯出：${info.fileName}").takeLast(80)
                        toast = "已自動匯出：${info.fileName}"
                    } catch (e: Exception) {
                        runLogs = (runLogs + "自動匯出失敗：${e.message}").takeLast(80)
                    }
                }
            } catch (c: CancellationException) {
                runMessage = "已暫停；進度已儲存，可繼續翻譯"
            } catch (t: Throwable) {
                runMessage = "處理失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                runRunning = false
                summaries = withContext(Dispatchers.IO) { projectStore.list() }
                revision++
                val back = afterRunScreen
                afterRunScreen = null
                if (back != null) {
                    screen = back
                    afterRunPage?.let { selectedPage = it }
                    afterRunPage = null
                }
            }
        }
    }

    fun pauseTranslation() {
        if (runJob?.isActive == true) {
            runMessage = "正在暫停…"
            runJob?.cancel()
        }
    }

    fun retryFailedPages() {
        startTranslation(retryFailed = true)
    }

    fun translateSinglePage(index: Int) {
        val p = project ?: return
        val page = p.pages.getOrNull(index) ?: return
        page.status = PageStatus.PENDING
        page.error = null
        viewModelScope.launch(Dispatchers.IO) { projectStore.save(p) }
        startTranslation(onlyPage = index, returnTo = Screen.VIEWER)
    }

    // ---------------- 設定 / 模型 / API ----------------

    fun refreshModelStatus() {
        modelReadiness = modelRepo.readiness()
        ppReadiness = modelRepo.ppReadiness()
    }

    /** 下載 PP-OCRv6（23 MB，選配；標準/高品質模式才需要）。 */
    fun downloadPpOcr() {
        if (ppDownloadRunning) return
        if (runRunning) { dialogMessage = "翻譯執行中，請先暫停再下載 OCR 模型。"; return }
        ppDownloadRunning = true
        downloadUi = DownloadUi()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    modelRepo.downloadPp { ui -> downloadUi = ui }
                }
                ppReadiness = modelRepo.ppReadiness()
                val bad = withContext(Dispatchers.IO) { modelRepo.verifyPp() }
                toast = if (bad.isEmpty()) "PP-OCRv6 下載完成，SHA-256 校驗通過"
                else "PP-OCRv6 校驗失敗：${bad.joinToString()}"
            } catch (c: CancellationException) {
                toast = "已取消 OCR 模型下載"
            } catch (t: Throwable) {
                dialogMessage = "PP-OCRv6 下載失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                ppDownloadRunning = false
                ppReadiness = modelRepo.ppReadiness()
            }
        }
    }

    fun downloadModels() {
        if (downloadRunning) return
        if (busy != null) { dialogMessage = "目前有其他工作在進行，請稍後再下載模型。"; return }
        if (runRunning) { dialogMessage = "翻譯執行中，請先暫停再下載模型。"; return }
        downloadRunning = true
        downloadUi = DownloadUi()
        downloadJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    modelRepo.download { ui -> downloadUi = ui }
                }
                modelReadiness = modelRepo.readiness()
                toast = "模型下載完成，SHA-256 校驗通過"
            } catch (c: CancellationException) {
                toast = "已取消模型下載"
            } catch (t: Throwable) {
                dialogMessage = "模型下載失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                downloadRunning = false
            }
        }
    }

    fun cancelModelDownload() {
        downloadJob?.cancel()
    }

    fun verifyModels() {
        if (busy != null) return
        if (runRunning) { dialogMessage = "翻譯執行中，請先暫停再驗證模型。"; return }
        viewModelScope.launch {
            busy = BusyState("驗證模型", "計算 SHA-256…", 0f)
            try {
                val bad = withContext(Dispatchers.IO) {
                    modelRepo.verify { name, i, n ->
                        busy = BusyState("驗證模型", name, if (n > 0) i.toFloat() / n else null)
                    }
                }
                modelReadiness = modelRepo.readiness()
                dialogMessage = if (bad.isEmpty()) "模型完整，SHA-256 全部正確。"
                else "以下檔案損壞或大小不符，請重新下載：\n${bad.joinToString("\n")}"
            } finally {
                busy = null
            }
        }
    }

    fun deleteModels() {
        if (runRunning) { dialogMessage = "翻譯執行中，請先暫停再刪除模型。"; return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { modelRepo.deleteAll() }
            modelReadiness = modelRepo.readiness()
            toast = "已刪除翻譯模型"
        }
    }

    fun refreshApiModels() {
        if (apiModelsLoading) return
        apiModelsLoading = true
        apiModelsError = null
        apiModels = emptyList()
        val cfg = settings.toApiConfig(settingsStore.apiKey() ?: "")
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { ApiClient.listModels(cfg) }
                apiModels = list
                if (list.isEmpty()) apiModelsError = "模型清單為空，請手動輸入模型 ID"
            } catch (c: CancellationException) {
                // ignore
            } catch (t: Throwable) {
                apiModelsError = t.message
            } finally {
                apiModelsLoading = false
            }
        }
    }

    fun testApi() {
        if (apiTesting) return
        apiTesting = true
        apiTestResult = null
        val cfg = settings.toApiConfig(settingsStore.apiKey() ?: "")
        viewModelScope.launch {
            apiTestResult = withContext(Dispatchers.IO) { ApiClient.testConnection(cfg) }
            apiTesting = false
        }
    }

    // ---------------- 手動修正 / 重新排版 / 局部 OCR ----------------

    fun saveEditsAndRender(index: Int, texts: List<String>, overrides: Map<Int, RegionOverride> = emptyMap()) {
        val p = project ?: return
        val page = p.pages.getOrNull(index) ?: return
        if (busy != null) return
        val models = modelRepo.modelSet()
        if (models == null) {
            dialogMessage = "需要先下載翻譯模型才能重新擦字與嵌字。"
            return
        }
        viewModelScope.launch {
            busy = BusyState("重新嵌字", "套用譯文並重建背景…", null)
            try {
                val dir = File(modelsRoot, p.id)
                withContext(Dispatchers.IO) {
                    AnalysisCodec.updateTranslations(dir, page, texts)
                    rerender(dir, p, page, texts, models, overrides)
                }
                withContext(Dispatchers.IO) { projectStore.save(p) }
                revision++
                toast = "已重新嵌字（未呼叫 API）"
                screen = Screen.VIEWER
                selectedPage = index
                viewerTranslated = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                dialogMessage = "重新嵌字失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    private suspend fun rerender(
        dir: File,
        p: Project,
        page: PageEntry,
        texts: List<String>,
        models: li.joye.yakuyomi.engine.ModelSet,
        overrideInput: Map<Int, RegionOverride> = emptyMap(),
    ) {
        if (p.sourceLang == "ENGLISH") {
            rerenderEnglish(dir, p, page, texts, models, overrideInput)
            return
        }
        val tStart = System.currentTimeMillis()
        val loaded = AnalysisCodec.load(dir, page)
            ?: throw IllegalStateException("找不到此頁的文字區資料（meta/mask），請先重新翻譯此頁")
        loaded.regions.forEachIndexed { i, r -> if (i < texts.size) r.translatedText = texts[i] }
        val diag = DiagCodec.load(dir, page)
        val overrides = if (overrideInput.isNotEmpty()) overrideInput else (diag?.overrides ?: emptyMap())
        val original = ImageIo.decode(File(dir, page.originalFile))
            ?: throw IllegalStateException("原圖讀取失敗")
        val mask = ImageIo.loadMask(loaded.maskFile)
            ?: throw IllegalStateException("文字遮罩讀取失敗")

        // 只擦/只排有譯文的區；使用者清空某區譯文＝保留該區原始日文。
        val pairs = loaded.regions.mapIndexedNotNull { i, r ->
            if (r.sourceText.isNotBlank() && r.translatedText.isNotBlank()) i to r else null
        }
        val renderRegions = pairs.map { it.second }
        var cleaned: Bitmap? = null
        var rendered: Bitmap? = null
        val inpaintMethod = settings.inpaint
        val orientation = settings.orientation
        p.inpaint = inpaintMethod
        p.orientation = orientation
        val legacyInpainter = Legacy020Inpainter(models.aotInpainterNcnn!!, InpainterConfig(method = inpaintMethod))
        try {
            val mode = if (inpaintMethod == "smart") "smart" else inpaintMethod
            val erased = if (renderRegions.isEmpty()) {
                SmartInpaintResult(original.copy(Bitmap.Config.ARGB_8888, true), emptyList())
            } else {
                legacyInpainter.inpaintSmart(original, renderRegions, mask, mode)
            }
            cleaned = erased.bitmap
            val posOverrides = pairs.mapIndexedNotNull { pos, (orig, _) ->
                overrides[orig]?.let { pos to it }
            }.toMap()
            val renderCfg = RenderConfig(
                orientation = runCatching { TextOrientation.valueOf(orientation) }.getOrDefault(TextOrientation.AUTO),
                fontBorder = settings.fontBorder,
            )
            val renderStart = System.currentTimeMillis()
            val rr = Legacy020SmartRenderer.renderDetailed(cleaned, renderRegions, renderCfg, null, posOverrides)
            rendered = rr.bitmap
            val renderMs = System.currentTimeMillis() - renderStart
            val rel = page.translatedFile ?: "translated/%06d.%s".format(page.index, page.ext)
            val patches = PatchCodec.load(dir, page)
            val finalBmp = if (patches.isEmpty()) rendered else PatchCodec.apply(dir, rendered, patches)
            ImageIo.save(finalBmp, File(dir, rel), page.ext)
            if (finalBmp !== rendered) finalBmp.recycle()
            page.translatedFile = rel

            val layoutsByOrig = rr.layouts.associateBy { l -> pairs.getOrNull(l.index)?.first ?: l.index }
            val eraseByOrig = erased.strategies.associate { e -> (pairs.getOrNull(e.index)?.first ?: e.index) to e.strategy }
            val translatable = loaded.regions.count { it.sourceText.isNotBlank() }
            val missing = loaded.regions.count { it.sourceText.isNotBlank() && it.translatedText.isBlank() }
            val overflow = rr.layouts.count { it.overflow }
            page.status = if (missing > 0 || overflow > 0) PageStatus.PARTIAL else PageStatus.DONE
            page.error = when {
                overflow > 0 -> "有 $overflow 個文字區排版待處理（全文已保留，未截斷）"
                missing > 0 -> "有 $missing 個文字區未填譯文，保留原始日文"
                else -> null
            }
            page.translatedRegions = renderRegions.size
            page.updatedAt = System.currentTimeMillis()
            val now = System.currentTimeMillis()
            val stats = page.stats ?: PageStatsInfo()
            page.stats = stats.copy(
                regions = translatable, kept = renderRegions.size,
                renderMs = renderMs, wallMs = now - tStart,
            )

            val cache = LinkedHashMap<String, String>(diag?.cache ?: emptyMap())
            loaded.regions.filter { it.sourceText.isNotBlank() && it.translatedText.isNotBlank() }.forEach {
                cache[it.sourceText.trim()] = it.translatedText.trim()
            }
            val regionDiag = loaded.regions.mapIndexedNotNull { orig, r ->
                if (r.sourceText.isBlank()) return@mapIndexedNotNull null
                val l = layoutsByOrig[orig]
                RegionDiag(
                    index = orig, x0 = r.x0, y0 = r.y0, x1 = r.x1, y1 = r.y1, direction = r.direction,
                    source = r.sourceText, translated = r.translatedText,
                    ocrProb = r.lines.maxOfOrNull { it.ocrProb } ?: 0f,
                    ocrStrategy = r.lines.map { it.ocrStrategy }.filter { it.isNotBlank() }.joinToString("+"),
                    translationStatus = if (r.translatedText.isNotBlank()) "cached" else "failed",
                    translationError = if (r.translatedText.isBlank()) "未填譯文，保留原文" else null,
                    eraseStrategy = eraseByOrig[orig],
                    layoutFontSize = l?.fontSize ?: 0,
                    layoutOverflow = l?.overflow ?: false,
                    layoutNote = l?.note,
                )
            }
            val newDiag = (diag ?: PageDiag(
                pageIndex = page.index, createdAt = now, status = page.status.name,
                qualityMode = settings.remedy, detectionPasses = 0, addedLines = 0, ocrRemedyCount = 0,
                detectMs = 0, ocrMs = 0, translateMs = 0, inpaintMs = 0, renderMs = 0, wallMs = 0,
                lines = stats.lines, regions = loaded.regions.size, kept = renderRegions.size,
                promptTokens = stats.promptTokens, completionTokens = stats.completionTokens, costUsd = stats.costUsd,
                errors = emptyList(), warnings = emptyList(), regionDiag = regionDiag, cache = cache,
                overrides = overrides, inpaintMethod = inpaintMethod,
            )).copy(
                status = page.status.name,
                regions = loaded.regions.size, kept = renderRegions.size,
                regionDiag = regionDiag, cache = cache, overrides = overrides,
                inpaintMethod = inpaintMethod,
                warnings = listOfNotNull(page.error),
            )
            DiagCodec.save(dir, page, newDiag)
        } finally {
            runCatching { legacyInpainter.close() }
            runCatching { cleaned?.recycle() }
            runCatching { rendered?.recycle() }
            runCatching { original.recycle() }
            runCatching { mask.recycle() }
        }
    }

    /** English editor output uses the same verified image path as automatic translation. */
    private suspend fun rerenderEnglish(
        dir: File,
        p: Project,
        page: PageEntry,
        texts: List<String>,
        models: li.joye.yakuyomi.engine.ModelSet,
        overrideInput: Map<Int, RegionOverride> = emptyMap(),
    ) {
        val started = System.currentTimeMillis()
        val loaded = AnalysisCodec.load(dir, page)
            ?: error("找不到此頁的文字區資料（meta/mask），請先重新翻譯此頁")
        loaded.regions.forEachIndexed { index, region ->
            if (index < texts.size) region.translatedText = texts[index]
        }
        val oldDiag = DiagCodec.load(dir, page)
        val overrides = if (overrideInput.isNotEmpty()) overrideInput else oldDiag?.overrides.orEmpty()
        var original: Bitmap? = null
        var mask: Bitmap? = null
        var output: EnglishImagePipeline.Result? = null
        var inpainter: Legacy020Inpainter? = null
        val latin = MlKitLatinOcr()
        try {
            val source = ImageIo.decode(File(dir, page.originalFile)) ?: error("原圖讀取失敗")
            original = source
            val detectorMask = ImageIo.loadMask(loaded.maskFile) ?: error("文字遮罩讀取失敗")
            mask = detectorMask
            p.inpaint = settings.inpaint
            p.orientation = settings.orientation
            val engine = Legacy020Inpainter(models.aotInpainterNcnn ?: error("缺少擦字模型"),
                InpainterConfig(method = p.inpaint, maskDilate = 3f, bboxPad = 3))
            inpainter = engine
            val result = withContext(Dispatchers.Default) {
                EnglishImagePipeline.finish(source, detectorMask, loaded.regions, engine, overrides,
                    maxFontSize = 48, residualOcr = { latin.recognize(it) })
            }
            output = result
            val storedPatches = PatchCodec.load(dir, page)
            val eligiblePatches = storedPatches.filter { eligibleEnglishPatch(it) && File(dir, it.patchFile).isFile }
            val stalePatches = storedPatches.size - eligiblePatches.size
            val warnings = result.warnings.toMutableList()
            if (stalePatches > 0) warnings.add(
                "有 ${stalePatches} 個舊版或無效的局部修正尚未套用，請重新擦字／嵌字該局部區域")
            if (eligiblePatches.isNotEmpty()) warnings.add(
                "已套用 ${eligiblePatches.size} 個局部修正，請檢查整頁")
            val finalImage = if (eligiblePatches.isEmpty()) result.final
                             else PatchCodec.apply(dir, result.final, eligiblePatches)
            val relative = page.translatedFile ?: "translated/%06d.%s".format(page.index, page.ext)
            try {
                ImageIo.save(finalImage, File(dir, relative), page.ext)
            } finally {
                if (finalImage !== result.final && !finalImage.isRecycled) finalImage.recycle()
            }
            val meaningful = loaded.regions.indices.filter { loaded.regions[it].sourceText.isNotBlank() }
            val accepted = result.accepted.filter { it in meaningful }.toSet()
            val failures = result.failures.filterKeys { it in meaningful }
            page.status = when {
                accepted.isEmpty() && eligiblePatches.isEmpty() -> PageStatus.FAILED
                accepted.size < meaningful.size || warnings.isNotEmpty() -> PageStatus.PARTIAL
                else -> PageStatus.DONE
            }
            page.error = (failures.entries.map { "區域${it.key + 1}：${it.value}" } + warnings)
                .joinToString("；").take(600).ifBlank { null }
            if (page.status == PageStatus.FAILED && page.error == null) page.error = "沒有通過檢查的文字區域，已保留原圖"
            page.translatedFile = relative
            page.regionCount = meaningful.size
            page.translatedRegions = accepted.size
            page.updatedAt = System.currentTimeMillis()
            val previous = page.stats ?: PageStatsInfo()
            val stats = previous.copy(
                lines = loaded.regions.sumOf { it.lines.size }, regions = meaningful.size, kept = accepted.size,
                inpaintMs = result.inpaintMs, renderMs = result.renderMs, wallMs = page.updatedAt - started,
            )
            page.stats = stats
            // Keep valid translations for cheap image retries, but purge old runaway values.
            val cache = LinkedHashMap(oldDiag?.cache.orEmpty().filter { (sourceText, translated) ->
                EnglishTranslationGuard.validate(sourceText, translated) == null
            })
            for (region in loaded.regions) {
                if (region.sourceText.isNotBlank() &&
                    EnglishTranslationGuard.validate(region.sourceText, region.translatedText) == null) {
                    cache[region.sourceText.trim()] = region.translatedText.trim()
                }
            }
            val layouts = result.layouts.associateBy { it.index }
            val strategies = result.strategies.associateBy { it.index }
            val regionDiag = meaningful.map { index ->
                val region = loaded.regions[index]
                val fit = layouts[index]
                val failure = failures[index]
                RegionDiag(
                    index = index, x0 = region.x0, y0 = region.y0, x1 = region.x1, y1 = region.y1,
                    direction = region.direction, source = region.sourceText, translated = region.translatedText,
                    ocrProb = region.lines.maxOfOrNull { it.ocrProb } ?: 0f,
                    ocrStrategy = region.lines.map { it.ocrStrategy }.filter { it.isNotBlank() }.joinToString("+"),
                    translationStatus = if (index in accepted) "cached" else "failed",
                    translationError = failure, eraseStrategy = strategies[index]?.strategy,
                    layoutFontSize = fit?.fontSize ?: 0, layoutOverflow = fit?.overflow ?: false,
                    layoutNote = failure ?: fit?.note, kind = result.routes[index]?.type?.label.orEmpty(),
                    eraseSafe = index in accepted,
                )
            }
            val stage = when {
                accepted.isEmpty() -> "failed"
                accepted.size < meaningful.size || warnings.isNotEmpty() -> "partial"
                else -> "ok"
            }
            val validTranslations = meaningful.count { index ->
                val region = loaded.regions[index]
                EnglishTranslationGuard.validate(region.sourceText, region.translatedText) == null
            }
            val translationStage = when {
                validTranslations == 0 -> "failed"
                validTranslations < meaningful.size -> "partial"
                else -> "ok"
            }
            val newDiag = PageDiag(
                pageIndex = page.index, createdAt = page.updatedAt, status = page.status.name,
                qualityMode = oldDiag?.qualityMode ?: settings.remedy,
                detectionPasses = oldDiag?.detectionPasses ?: 0, addedLines = oldDiag?.addedLines ?: 0,
                ocrRemedyCount = oldDiag?.ocrRemedyCount ?: 0,
                detectMs = previous.detectMs, ocrMs = previous.ocrMs, translateMs = previous.translateMs,
                inpaintMs = result.inpaintMs, renderMs = result.renderMs, wallMs = stats.wallMs,
                lines = stats.lines, regions = meaningful.size, kept = accepted.size,
                promptTokens = previous.promptTokens, completionTokens = previous.completionTokens,
                costUsd = previous.costUsd, errors = failures.values.distinct(), warnings = warnings,
                regionDiag = regionDiag, cache = cache, overrides = overrides, inpaintMethod = "english-four-route",
                apiError = null, stageStatus = oldDiag?.stageStatus.orEmpty() +
                    mapOf("translate" to translationStage, "erase" to stage, "render" to stage, "layout" to stage),
            )
            DiagCodec.save(dir, page, newDiag)
        } finally {
            latin.close()
            runCatching { inpainter?.close() }
            listOfNotNull(output?.cleaned, output?.final, output?.mask, original, mask).distinct().forEach {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    private fun eligibleEnglishPatch(patch: ManualPatch): Boolean =
        patch.renderRevision >= 1 && EnglishTranslationGuard.validate(patch.source, patch.translated) == null

    /** 只重做擦字＋排版（套用目前設定與已存在的譯文），不呼叫 API。 */
    fun rerenderPageNoApi(pageIndex: Int) {
        val p = project ?: return
        val page = p.pages.getOrNull(pageIndex) ?: return
        if (busy != null) return
        val models = modelRepo.modelSet() ?: run { dialogMessage = "需要先下載模型"; return }
        viewModelScope.launch {
            busy = BusyState("重新擦字／排版", "不呼叫 API…", null)
            try {
                val dir = File(modelsRoot, p.id)
                val loaded = withContext(Dispatchers.IO) { AnalysisCodec.load(dir, page) }
                    ?: throw IllegalStateException("找不到此頁文字區資料")
                val texts = loaded.regions.map { it.translatedText }
                if (texts.none { it.isNotBlank() }) throw IllegalStateException("此頁尚無譯文，請先翻譯")
                val diag = withContext(Dispatchers.IO) { DiagCodec.load(dir, page) }
                rerender(dir, p, page, texts, models, diag?.overrides ?: emptyMap())
                withContext(Dispatchers.IO) { projectStore.save(p) }
                diagnostics = diagnostics?.copy()
                revision++
                toast = "已重新擦字／排版（未呼叫 API）"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                dialogMessage = "重新擦字／排版失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    fun reOcrRegion(pageIndex: Int, regionIndex: Int) {
        val p = project ?: return
        val page = p.pages.getOrNull(pageIndex) ?: return
        if (busy != null) return
        val models = modelRepo.modelSet()
        if (models == null) {
            dialogMessage = "需要先下載 OCR 模型。"
            return
        }
        viewModelScope.launch {
            busy = BusyState("重新辨識", "載入 OCR 模型…", null)
            try {
                val dir = File(modelsRoot, p.id)
                withContext(Dispatchers.IO) {
                    val loaded = AnalysisCodec.load(dir, page)
                        ?: throw IllegalStateException("找不到此頁文字區資料")
                    val region = loaded.regions.getOrNull(regionIndex)
                        ?: throw IllegalStateException("找不到這個文字區")
                    val bmp = ImageIo.decode(File(dir, page.originalFile))
                        ?: throw IllegalStateException("原圖讀取失敗")
                    try {
                        val texts = ArrayList<String>()
                        if (p.sourceLang == "ENGLISH") {
                            val latin = MlKitLatinOcr()
                            try {
                                for (line in region.lines) {
                                    if (line.quad.isEmpty()) { texts.add(""); continue }
                                    val left = (line.quad.minOf { it.x }.toInt() - 3).coerceIn(0, bmp.width - 1)
                                    val top = (line.quad.minOf { it.y }.toInt() - 3).coerceIn(0, bmp.height - 1)
                                    val right = (kotlin.math.ceil(line.quad.maxOf { it.x }.toDouble()).toInt() + 3).coerceIn(left + 1, bmp.width)
                                    val bottom = (kotlin.math.ceil(line.quad.maxOf { it.y }.toDouble()).toInt() + 3).coerceIn(top + 1, bmp.height)
                                    val crop = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
                                    try {
                                        texts.add(latin.recognize(crop).sortedWith(
                                            compareBy<li.joye.yakuyomi.engine.TextLine> { it.quad.minOf { p -> p.y } }
                                                .thenBy { it.quad.minOf { p -> p.x } })
                                            .joinToString(" ") { it.text })
                                    } finally { if (crop !== bmp) crop.recycle() }
                                }
                            } finally { latin.close() }
                        } else {
                            val ocr = Ocr(models.ocr, alphabetCache, OcrConfig())
                            try {
                                region.lines.forEach { line ->
                                    ocr.recognize(bmp, mutableListOf(line))
                                    texts.add(line.text)
                                }
                            } finally { runCatching { ocr.close() } }
                        }
                        AnalysisCodec.updateSourceTexts(dir, page, regionIndex, texts)
                    } finally {
                        bmp.recycle()
                    }
                    page.status = PageStatus.PENDING
                    page.error = "原文已局部重新辨識，需重新翻譯此頁"
                    page.updatedAt = System.currentTimeMillis()
                    projectStore.save(p)
                }
                revision++
                toast = "已重新辨識此區，原文已更新"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                dialogMessage = "重新辨識失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    // ---------------- 本機引擎自測 ----------------

    /** 選一張圖跑完整本機流程（偵測→OCR→擦字→排版），用固定繁中句子的 MockTranslator，不呼叫 API。 */
    fun runSelfTest(uri: Uri) {
        if (selfTestRunning) return
        if (runRunning || busy != null) { dialogMessage = "翻譯或其他工作正在進行，請稍後再自測。"; return }
        val models = modelRepo.modelSet()
        if (models == null) {
            dialogMessage = "需要先下載翻譯模型才能做本機自測。"
            return
        }
        val ctx = getApplication<Application>()
        selfTestRunning = true
        selfTestResult = null
        viewModelScope.launch {
            var beforeBmp: Bitmap? = null
            var afterBmp: Bitmap? = null
            var pipeline: AutoCloseable? = null
            try {
                val dir = File(ctx.cacheDir, "selftest").apply { mkdirs() }
                val input = File(dir, "input.jpg")
                withContext(Dispatchers.IO) {
                    val src = ctx.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("無法讀取圖片")
                    src.use { inp ->
                        FileOutputStream(input).use { out -> inp.copyTo(out) }
                    }
                }
                val before = withContext(Dispatchers.IO) { ImageIo.decode(input) }
                    ?: throw IllegalStateException("圖片解碼失敗")
                beforeBmp = before
                val config = EngineConfig(
                    inpainter = InpainterConfig(method = settings.inpaint),
                    render = RenderConfig(
                        orientation = runCatching { TextOrientation.valueOf(settings.orientation) }
                            .getOrDefault(TextOrientation.AUTO),
                        fontBorder = settings.fontBorder,
                    ),
                )
                // 模型建構、偵測/擦字/排版都是阻塞 CPU 工作；全部丟到 Default，避免自測時 UI 凍住。
                // 自測與正式翻譯使用相同後段：日文 0.2.0、英文獨立分組＋0.2.0 擦字嵌字。
                val outcome = withContext(Dispatchers.Default) {
                    val det = li.joye.yakuyomi.engine.Detector(models.detectorNcnn!!, config.detector)
                    val ocr = li.joye.yakuyomi.engine.Ocr(models.ocr, alphabetCache, config.ocr)
                    pipeline = det
                    try {
                        val fix = runCatching { OcrRemedyMode.valueOf(settings.remedy) }
                            .getOrDefault(OcrRemedyMode.BALANCED)
                        if (project?.sourceLang == "ENGLISH") {
                            val inp = Legacy020Inpainter(models.aotInpainterNcnn!!,
                                config.inpainter.copy(maskDilate = 3f, bboxPad = 3))
                            try {
                                val mlkit = tw.dafeiyu.mangatranslator.core.MlKitLatinOcr()
                                try {
                                    tw.dafeiyu.mangatranslator.core.English020SmartTranslator(
                                        det, ocr, mlkit, inp, null, MockTranslator(), config, fix, true,
                                    ).run(before)
                                } finally { runCatching { mlkit.close() } }
                            } finally { runCatching { inp.close() } }
                        } else {
                            val inp = Legacy020Inpainter(models.aotInpainterNcnn!!, config.inpainter)
                            try {
                                Legacy020SmartTranslator(det, ocr, inp, null, MockTranslator(), config,
                                    fix, smartInpaint = true).run(before)
                            } finally { runCatching { inp.close() } }
                        }
                    } finally {
                        runCatching { det.close() }; runCatching { ocr.close() }
                    }
                }
                val after = outcome.final
                if (after != null) {
                    afterBmp = after
                    withContext(Dispatchers.IO) { ImageIo.save(after, File(dir, "after.jpg"), "jpg") }
                    selfTestResult = SelfTestResult(
                        before = input, after = File(dir, "after.jpg"), stats = outcome.stats,
                        error = outcome.error,
                        summary = "${outcome.status.name}：${outcome.stats.lines} 行、${outcome.stats.regions} 區、${outcome.stats.kept} 區有譯文",
                    )
                } else {
                    selfTestResult = SelfTestResult(input, null, outcome.stats, outcome.error,
                        "${outcome.status.name}：${outcome.error ?: "沒有輸出"}")
                }
                runCatching { outcome.cleaned?.recycle() }
                runCatching { outcome.mask?.recycle() }
            } catch (t: Throwable) {
                selfTestResult = SelfTestResult(null, null, null, t.message ?: t.javaClass.simpleName,
                    "自測失敗：${t.message ?: t.javaClass.simpleName}")
            } finally {
                runCatching { pipeline?.close() }
                runCatching { beforeBmp?.recycle() }
                runCatching { afterBmp?.recycle() }
                selfTestRunning = false
            }
        }
    }

    // ---------------- 診斷 ----------------

    fun openDiagnostics(index: Int) {
        selectedPage = index
        screen = Screen.DIAG
        prepareDiagnostics(index)
    }

    fun updateDiagIncludeText(value: Boolean) { diagIncludeText = value }
    fun updateDiagIncludeImages(value: Boolean) { diagIncludeImages = value }

    private fun diagDir(p: Project, page: PageEntry): File =
        File(getApplication<Application>().cacheDir, "diag/${p.id}/${page.index}").apply { mkdirs() }

    private fun prepareDiagnostics(index: Int) {
        val p = project ?: return
        val page = p.pages.getOrNull(index) ?: return
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("準備診斷", "載入頁面資料…", null)
            try {
                val dir = File(modelsRoot, p.id)
                if (page.metaFile == null) {
                    analyzeForDiagnostics(p, page, dir)
                }
                val loaded = withContext(Dispatchers.IO) { AnalysisCodec.load(dir, page) }
                    ?: throw IllegalStateException("找不到文字區資料；請先執行一次翻譯或重新分析")
                val diag = withContext(Dispatchers.IO) { DiagCodec.load(dir, page) }
                val stageDir = diagDir(p, page)
                val images = LinkedHashMap<String, String>()
                images["original"] = File(dir, page.originalFile).absolutePath
                val original = withContext(Dispatchers.IO) { ImageIo.decode(File(dir, page.originalFile)) }
                    ?: throw IllegalStateException("原圖讀取失敗")
                try {
                    val detect = withContext(Dispatchers.IO) {
                        StageImageFactory.detection(original, loaded.regions)
                    }
                    StageImageFactory.save(detect, File(stageDir, "detect.jpg")); detect.recycle()
                    images["detect"] = File(stageDir, "detect.jpg").absolutePath
                    val ocr = withContext(Dispatchers.IO) { StageImageFactory.ocr(original, loaded.regions) }
                    StageImageFactory.save(ocr, File(stageDir, "ocr.jpg")); ocr.recycle()
                    images["ocr"] = File(stageDir, "ocr.jpg").absolutePath
                    val tr = withContext(Dispatchers.IO) { StageImageFactory.translation(original, loaded.regions) }
                    StageImageFactory.save(tr, File(stageDir, "translation.jpg")); tr.recycle()
                    images["translation"] = File(stageDir, "translation.jpg").absolutePath
                    val mask = withContext(Dispatchers.IO) { ImageIo.loadMask(loaded.maskFile) }
                    if (mask != null) {
                        val mo = withContext(Dispatchers.IO) { StageImageFactory.mask(original, mask) }
                        StageImageFactory.save(mo, File(stageDir, "mask.jpg")); mo.recycle(); mask.recycle()
                        images["mask"] = File(stageDir, "mask.jpg").absolutePath
                    }
                } finally {
                    original.recycle()
                }
                page.translatedFile?.let { rel ->
                    val f = File(dir, rel)
                    if (f.isFile) images["final"] = f.absolutePath
                }
                File(stageDir, "cleaned.jpg").takeIf { it.isFile }?.let { images["cleaned"] = it.absolutePath }
                diagnostics = DiagnosticsUi(index, images, diag, null)
            } catch (t: Throwable) {
                dialogMessage = "診斷準備失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    /** 只跑偵測+OCR（不呼叫 API），把結果寫成 meta/mask，供診斷與之後的翻譯快取使用。 */
    private suspend fun analyzeForDiagnostics(p: Project, page: PageEntry, dir: File) {
        val models = modelRepo.modelSet() ?: throw IllegalStateException("需要先下載翻譯模型")
        val bitmap = withContext(Dispatchers.IO) { ImageIo.decode(File(dir, page.originalFile)) }
            ?: throw IllegalStateException("原圖讀取失敗")
        val cfg = buildEngineConfig(p)
        val analysis = withContext(Dispatchers.Default) {
            val detector = Detector(models.detectorNcnn!!, cfg.detector)
            val ocr = Ocr(models.ocr, alphabetCache, cfg.ocr)
            try {
                val fix = runCatching { OcrRemedyMode.valueOf(settings.remedy) }
                    .getOrDefault(OcrRemedyMode.BALANCED)
                if (p.sourceLang == "ENGLISH") {
                    val inpainter = Legacy020Inpainter(models.aotInpainterNcnn!!,
                        cfg.inpainter.copy(maskDilate = 3f, bboxPad = 3))
                    try {
                        val mlkit = tw.dafeiyu.mangatranslator.core.MlKitLatinOcr()
                        try {
                            tw.dafeiyu.mangatranslator.core.English020SmartTranslator(
                                detector, ocr, mlkit, inpainter, null, null, cfg, fix, true,
                            ).analyze(bitmap)
                        } finally { runCatching { mlkit.close() } }
                    } finally { runCatching { inpainter.close() } }
                } else {
                    val inpainter = Legacy020Inpainter(models.aotInpainterNcnn!!, cfg.inpainter)
                    try {
                        Legacy020SmartTranslator(detector, ocr, inpainter, null, null, cfg, fix, true).analyze(bitmap)
                    } finally { runCatching { inpainter.close() } }
                }
            } finally {
                runCatching { detector.close() }
                runCatching { ocr.close() }
            }
        }
        try {
            val textRegions = analysis.regions.filter { it.sourceText.isNotBlank() }
            if (textRegions.isEmpty()) throw IllegalStateException(analysis.errors.joinToString("；").ifBlank { "偵測不到文字" })
            val (meta, mask) = withContext(Dispatchers.IO) {
                AnalysisCodec.save(dir, page, PageAnalysis(analysis.textMask, textRegions), p.inpaint)
            }
            page.metaFile = meta; page.maskFile = mask; page.regionCount = textRegions.size
            page.updatedAt = System.currentTimeMillis()
            val regionDiag = textRegions.mapIndexed { i, r ->
                RegionDiag(
                    index = i, x0 = r.x0, y0 = r.y0, x1 = r.x1, y1 = r.y1, direction = r.direction,
                    source = r.sourceText, translated = "", ocrProb = r.lines.maxOfOrNull { it.ocrProb } ?: 0f,
                    ocrStrategy = r.lines.map { it.ocrStrategy }.filter { it.isNotBlank() }.joinToString("+"),
                    translationStatus = "pending", translationError = null, eraseStrategy = null,
                    layoutFontSize = 0, layoutOverflow = false, layoutNote = null,
                )
            }
            val diag = PageDiag(
                pageIndex = page.index, createdAt = System.currentTimeMillis(), status = "ANALYZED",
                qualityMode = settings.remedy, detectionPasses = analysis.detectionPasses,
                addedLines = analysis.addedLines, ocrRemedyCount = analysis.ocrRemedyCount,
                detectMs = analysis.detectMs, ocrMs = analysis.ocrMs, translateMs = 0, inpaintMs = 0,
                renderMs = 0, wallMs = analysis.detectMs + analysis.ocrMs, lines = analysis.lines.size,
                regions = textRegions.size, kept = 0, promptTokens = 0, completionTokens = 0, costUsd = 0.0,
                errors = analysis.errors, warnings = analysis.warnings, regionDiag = regionDiag,
                cache = emptyMap(), overrides = emptyMap(), inpaintMethod = p.inpaint,
            )
            withContext(Dispatchers.IO) {
                DiagCodec.save(dir, page, diag)
                projectStore.save(p)
            }
            analysis.textMask.recycle()
        } finally {
            bitmap.recycle()
        }
    }

    /** 翻譯後的疑似漏字檢查：只標記、不擦除。會跑一次完整偵測+OCR（較慢）。 */
    fun checkLeftovers() {
        val p = project ?: return
        val idx = diagnostics?.pageIndex ?: selectedPage
        val page = p.pages.getOrNull(idx) ?: return
        if (busy != null) return
        val models = modelRepo.modelSet() ?: run { dialogMessage = "需要先下載模型"; return }
        viewModelScope.launch {
            busy = BusyState("漏字檢查", "重新偵測整頁並比對已翻區域…", null)
            var detector: Detector? = null; var ocr: Ocr? = null; var bmp: Bitmap? = null
            try {
                val dir = File(modelsRoot, p.id)
                val cfg = buildEngineConfig(p)
                bmp = withContext(Dispatchers.IO) { ImageIo.decode(File(dir, page.originalFile)) } ?: error("原圖讀取失敗")
                val translated = withContext(Dispatchers.IO) { AnalysisCodec.load(dir, page)?.regions.orEmpty() }
                    .filter { it.translatedText.isNotBlank() }
                val detected = withContext(Dispatchers.Default) {
                    detector = Detector(models.detectorNcnn!!, cfg.detector)
                    ocr = Ocr(models.ocr, alphabetCache, cfg.ocr)
                    val det = detector!!.detect(bmp!!, cfg.detector.copy(dbnetInputSize = 1024))
                    ocr!!.recognizeAllBest(bmp!!, det.lines, OcrRemedyMode.BALANCED)
                    Grouping.group(det.lines).filter { it.sourceText.any { c -> c.code in 0x3040..0x30FF || c.code in 0x4E00..0x9FFF } }
                }
                val suspects = detected.filter { r ->
                    translated.none { t -> overlapRatio(r, t) > 0.22f }
                }
                if (suspects.isEmpty()) {
                    dialogMessage = "漏字檢查：沒有發現未覆蓋的日文文字（仍可能有漏偵測；此檢查不是保證）。"
                } else {
                    val lines = suspects.take(8).mapIndexed { i, r ->
                        "#${i + 1} ${r.sourceText.take(40)}（${r.x0.toInt()},${r.y0.toInt()}）"
                    }
                    dialogMessage = "疑似漏字 ${suspects.size} 區（只標記，未修改圖片）：\n" + lines.joinToString("\n") +
                        "\n\n可用『框選補救』逐區處理，或把診斷報告回報。"
                }
            } catch (t: Throwable) {
                dialogMessage = "漏字檢查失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                runCatching { detector?.close() }; runCatching { ocr?.close() }
                runCatching { bmp?.recycle() }
                busy = null
            }
        }
    }

    private fun overlapRatio(a: TextRegion, b: TextRegion): Float {
        val ix = maxOf(0f, minOf(a.x1, b.x1) - maxOf(a.x0, b.x0))
        val iy = maxOf(0f, minOf(a.y1, b.y1) - maxOf(a.y0, b.y0))
        val inter = ix * iy
        val union = (a.x1 - a.x0) * (a.y1 - a.y0) + (b.x1 - b.x0) * (b.y1 - b.y0) - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun generateCleanedPreview() {
        val p = project ?: return
        val idx = diagnostics?.pageIndex ?: selectedPage
        val page = p.pages.getOrNull(idx) ?: return
        if (busy != null) return
        val models = modelRepo.modelSet() ?: run { dialogMessage = "需要先下載翻譯模型"; return }
        viewModelScope.launch {
            busy = BusyState("產生擦字預覽", "智慧擦字中（約數秒）…", null)
            try {
                val dir = File(modelsRoot, p.id)
                if (p.sourceLang == "ENGLISH") {
                    generateEnglishCleanedPreview(dir, p, page, models)
                    return@launch
                }
                val loaded = withContext(Dispatchers.IO) { AnalysisCodec.load(dir, page) }
                    ?: throw IllegalStateException("找不到文字區資料")
                val original = withContext(Dispatchers.IO) { ImageIo.decode(File(dir, page.originalFile)) }
                    ?: throw IllegalStateException("原圖讀取失敗")
                val mask = withContext(Dispatchers.IO) { ImageIo.loadMask(loaded.maskFile) }
                    ?: throw IllegalStateException("遮罩讀取失敗")
                val regions = loaded.regions.filter { it.sourceText.isNotBlank() }
                val inpainter = Inpainter(models.aotInpainterNcnn!!, InpainterConfig(method = settings.inpaint))
                try {
                    val mode = if (settings.inpaint == "smart") "smart" else settings.inpaint
                    val erased = withContext(Dispatchers.Default) { inpainter.inpaintSmart(original, regions, mask, mode) }
                    val file = File(diagDir(p, page), "cleaned.jpg")
                    StageImageFactory.save(erased.bitmap, file)
                    erased.bitmap.recycle()
                    diagnostics = diagnostics?.copy(images = diagnostics!!.images + ("cleaned" to file.absolutePath))
                } finally {
                    runCatching { inpainter.close() }
                    original.recycle(); mask.recycle()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                dialogMessage = "擦字預覽失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    private suspend fun generateEnglishCleanedPreview(
        dir: File, p: Project, page: PageEntry, models: li.joye.yakuyomi.engine.ModelSet,
    ) {
        val loaded = AnalysisCodec.load(dir, page) ?: error("找不到文字區資料")
        var original: Bitmap? = null
        var mask: Bitmap? = null
        var result: EnglishImagePipeline.Result? = null
        var inpainter: Legacy020Inpainter? = null
        val latin = MlKitLatinOcr()
        try {
            val source = ImageIo.decode(File(dir, page.originalFile)) ?: error("原圖讀取失敗")
            original = source
            val detectorMask = ImageIo.loadMask(loaded.maskFile) ?: error("遮罩讀取失敗")
            mask = detectorMask
            val engine = Legacy020Inpainter(models.aotInpainterNcnn ?: error("缺少擦字模型"),
                InpainterConfig(method = p.inpaint, maskDilate = 3f, bboxPad = 3))
            inpainter = engine
            val output = withContext(Dispatchers.Default) {
                EnglishImagePipeline.finish(source, detectorMask, loaded.regions, engine,
                    maxFontSize = 48, residualOcr = { latin.recognize(it) })
            }
            result = output
            val file = File(diagDir(p, page), "cleaned.jpg")
            StageImageFactory.save(output.cleaned, file)
            val message = (output.failures.values.distinct() + output.warnings).joinToString("；").take(500)
            diagnostics = diagnostics?.let { current -> current.copy(
                images = current.images + ("cleaned" to file.absolutePath),
                message = message.ifBlank { null },
            ) }
        } finally {
            latin.close()
            runCatching { inpainter?.close() }
            listOfNotNull(result?.cleaned, result?.final, result?.mask, original, mask).distinct().forEach {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    fun exportDiagnosticsReport(uri: android.net.Uri, includeText: Boolean, includeImages: Boolean = false) {
        val p = project ?: return
        val idx = diagnostics?.pageIndex ?: selectedPage
        val page = p.pages.getOrNull(idx) ?: return
        viewModelScope.launch {
            busy = BusyState("匯出診斷報告", "整理 JSON…", null)
            try {
                val dir = File(modelsRoot, p.id)
                val diag = withContext(Dispatchers.IO) { DiagCodec.load(dir, page) }
                val root = withContext(Dispatchers.IO) {
                    val o = JSONObject()
                    o.put("reportVersion", 1)
                    o.put("createdAt", System.currentTimeMillis())
                    o.put("app", JSONObject().apply {
                        put("package", BuildConfig.APPLICATION_ID)
                        put("versionName", BuildConfig.VERSION_NAME)
                        put("versionCode", BuildConfig.VERSION_CODE)
                    })
                    o.put("device", JSONObject().apply {
                        put("manufacturer", Build.MANUFACTURER)
                        put("model", Build.MODEL)
                        put("android", Build.VERSION.RELEASE)
                        put("sdk", Build.VERSION.SDK_INT)
                        put("abi", Build.SUPPORTED_ABIS.joinToString(","))
                    })
                    o.put("page", JSONObject().apply {
                        put("index", page.index); put("name", page.displayName)
                        put("width", page.width); put("height", page.height)
                        put("status", page.status.name); put("error", page.error ?: JSONObject.NULL)
                    })
                    o.put("config", JSONObject().apply {
                        put("provider", p.providerId); put("model", p.modelId)
                        put("targetLang", p.targetLang); put("sourceLang", p.sourceLang); put("inpaint", p.inpaint)
                        put("remedy", p.remedy); put("orientation", p.orientation)
                        put("retries", settings.retries)
                    })
                    if (diag != null) {
                        o.put("stages", JSONObject().apply {
                            put("qualityMode", diag.qualityMode)
                            put("detectionPasses", diag.detectionPasses)
                            put("addedLines", diag.addedLines)
                            put("ocrRemedyCount", diag.ocrRemedyCount)
                            put("detectMs", diag.detectMs); put("ocrMs", diag.ocrMs)
                            put("translateMs", diag.translateMs); put("inpaintMs", diag.inpaintMs)
                            put("renderMs", diag.renderMs); put("wallMs", diag.wallMs)
                            put("lines", diag.lines); put("regions", diag.regions); put("kept", diag.kept)
                            put("promptTokens", diag.promptTokens); put("completionTokens", diag.completionTokens)
                            put("costUsd", diag.costUsd); put("inpaintMethod", diag.inpaintMethod)
                        })
                        o.put("errors", JSONArray().apply { diag.errors.forEach { put(it) } })
                        o.put("warnings", JSONArray().apply { diag.warnings.forEach { put(it) } })
                        o.put("apiError", diag.apiError ?: JSONObject.NULL)
                        if (includeText) {
                            o.put("regions", JSONArray().apply {
                                diag.regionDiag.forEach { r ->
                                    put(JSONObject().apply {
                                        put("index", r.index); put("x0", r.x0.toDouble()); put("y0", r.y0.toDouble())
                                        put("x1", r.x1.toDouble()); put("y1", r.y1.toDouble())
                                        put("direction", r.direction); put("source", r.source); put("translated", r.translated)
                                        put("ocrProb", r.ocrProb.toDouble()); put("ocrStrategy", r.ocrStrategy)
                                        put("translationStatus", r.translationStatus)
                                        put("translationError", r.translationError ?: JSONObject.NULL)
                                        put("eraseStrategy", r.eraseStrategy ?: JSONObject.NULL)
                                        put("layoutFontSize", r.layoutFontSize); put("layoutOverflow", r.layoutOverflow)
                                        put("layoutNote", r.layoutNote ?: JSONObject.NULL)
                                    })
                                }
                            })
                        } else {
                            o.put("regionsOmitted", true)
                        }
                    }
                    if (includeImages) {
                        val imgs = diagnostics?.images ?: emptyMap()
                        o.put("images", JSONObject().apply {
                            imgs.forEach { (stage, path) ->
                                val f = File(path)
                                if (f.isFile) {
                                    put(stage, JSONObject().apply {
                                        put("mime", "image/jpeg")
                                        put("base64", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
                                    })
                                }
                            }
                        })
                    } else {
                        o.put("imagesOmitted", true)
                    }
                    o.toString(2)
                }
                val ctx = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(root.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("無法建立報告檔")
                }
                toast = "診斷報告已匯出（不含 API Key；預設不含原文/譯文）"
            } catch (t: Throwable) {
                dialogMessage = "報告匯出失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    // ---------------- 手動框選補救 ----------------

    fun loadPatches(pageIndex: Int) {
        val p = project ?: return
        val page = p.pages.getOrNull(pageIndex) ?: return
        viewModelScope.launch {
            patches = withContext(Dispatchers.IO) { PatchCodec.load(File(modelsRoot, p.id), page) }
        }
    }

    fun selectPatchRect(pageIndex: Int, rect: RectF) {
        patchManualRequest = null
        createManualPatch(pageIndex, rect, null)
    }

    fun continuePatchWithManualSource(text: String) {
        val req = patchManualRequest ?: return
        patchManualRequest = null
        createManualPatch(req.pageIndex, req.rect, text.trim().ifBlank { null })
    }

    fun dismissPatchManual() { patchManualRequest = null }

    private fun createManualPatch(pageIndex: Int, rect: RectF, manualSource: String?) {
        val p = project ?: return
        val page = p.pages.getOrNull(pageIndex) ?: return
        if (busy != null) return
        val models = modelRepo.modelSet() ?: run { dialogMessage = "需要先下載翻譯模型"; return }
        val mock = BuildConfig.DEBUG && settings.mockTranslation
        val key = settingsStore.apiKey()
        if (!mock && key.isNullOrBlank()) { dialogMessage = "局部補救需要 API Key"; return }
        viewModelScope.launch {
            busy = BusyState("局部補救", "OCR / 翻譯 / 擦字 / 嵌字…", null)
            var original: Bitmap? = null
            var crop: Bitmap? = null
            var patchBmp: Bitmap? = null
            var detector: Detector? = null
            var ocr: Ocr? = null
            var analysisMask: Bitmap? = null
            try {
                val dir = File(modelsRoot, p.id)
                original = withContext(Dispatchers.IO) { ImageIo.decode(File(dir, page.originalFile)) }
                    ?: throw IllegalStateException("原圖讀取失敗")
                val r = clampRect(rect, original.width, original.height)
                if (r.width() < 12f || r.height() < 12f) throw IllegalStateException("框選範圍太小")
                crop = Bitmap.createBitmap(original, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt())
                val cfg = buildEngineConfig(p)
                detector = Detector(models.detectorNcnn!!, cfg.detector)
                ocr = Ocr(models.ocr, alphabetCache, cfg.ocr)
                val analyzed = withContext(Dispatchers.Default) {
                    analyzePatch(crop, manualSource, detector, ocr, cfg, SourceLanguage.parse(p.sourceLang))
                } ?: run {
                    patchManualRequest = PatchSelection(pageIndex, r)
                    return@launch
                }
                analysisMask = analyzed.mask
                val translated = withContext(Dispatchers.IO) {
                    translatePatchText(analyzed.source, models, p, mock, key)
                } ?: run {
                    dialogMessage = "翻譯失敗；請檢查 API 或稍後重試（原圖未修改）"
                    return@launch
                }
                val rendered = withContext(Dispatchers.Default) {
                    renderPatch(crop, analyzed, translated, models, p)
                }
                patchBmp = rendered
                withContext(Dispatchers.IO) {
                    val patch = saveAndApplyPatch(dir, page, r, analyzed.source, translated, rendered, p, models)
                    patches = PatchCodec.load(dir, page)
                    projectStore.save(p)
                }
                revision++
                toast = "局部補救完成（未重新翻譯整本）"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                dialogMessage = "局部補救失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                analysisMask?.let { if (!it.isRecycled) it.recycle() }
                runCatching { detector?.close() }
                runCatching { ocr?.close() }
                runCatching { crop?.recycle() }
                runCatching { patchBmp?.recycle() }
                runCatching { original?.recycle() }
                busy = null
            }
        }
    }

    private class PatchAnalysis(
        val region: TextRegion,
        val mask: Bitmap,
        val source: String,
    )

    private suspend fun analyzePatch(
        crop: Bitmap,
        manualSource: String?,
        detector: Detector,
        ocr: Ocr,
        cfg: EngineConfig,
        language: SourceLanguage,
    ): PatchAnalysis? = withContext(Dispatchers.Default) {
        var mask: Bitmap? = null
        var transferred = false
        try {
            val det = if (language != SourceLanguage.ENGLISH) runCatching {
                detector.detect(crop, cfg.detector.copy(dbnetInputSize = 1024, dbBinThreshold = 0.45f, dbBoxThreshold = 0.60f))
            }.getOrNull() else null
            val lines = ArrayList<li.joye.yakuyomi.engine.TextLine>()
            mask = det?.textMask
            if (language == SourceLanguage.ENGLISH) {
                val mlkit = MlKitLatinOcr()
                try {
                    val found = tw.dafeiyu.mangatranslator.core.HybridOcr(
                        detector, ocr, null, mlkit, OcrMode.FAST,
                        SourceLanguage.ENGLISH, cfg, OcrRemedyMode.OFF,
                    ).detectAndRecognize(crop)
                    mask = found.mask
                    lines.addAll(found.lines)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep manual source entry available, without falling back to Japanese OCR.
                } finally { mlkit.close() }
            } else if (det != null) {
                ocr.recognizeAllBest(crop, det.lines, OcrRemedyMode.BALANCED)
                lines.addAll(det.lines.filter { it.text.isNotBlank() })
            }
            var source = manualSource?.trim().orEmpty()
            if (source.isBlank()) {
                val ordered = if (language == SourceLanguage.ENGLISH) lines.sortedWith(
                    compareBy<li.joye.yakuyomi.engine.TextLine> { line -> line.quad.minOf { it.y } }
                        .thenBy { line -> line.quad.minOf { it.x } }
                ) else lines
                source = ordered.joinToString(if (language == SourceLanguage.ENGLISH) " " else "") { it.text }.trim()
            }
            if (source.isBlank()) return@withContext null
            if (lines.isEmpty()) {
                val width = crop.width.toFloat(); val height = crop.height.toFloat()
                val vertical = language != SourceLanguage.ENGLISH && height > width * 1.15f
                val quad = listOf(
                    li.joye.yakuyomi.engine.Pt(2f, 2f), li.joye.yakuyomi.engine.Pt(width - 2f, 2f),
                    li.joye.yakuyomi.engine.Pt(width - 2f, height - 2f), li.joye.yakuyomi.engine.Pt(2f, height - 2f),
                )
                val line = li.joye.yakuyomi.engine.TextLine(quad, 1f)
                line.text = source
                line.direction = if (vertical) "v" else "h"
                lines.add(line)
            } else if (language == SourceLanguage.ENGLISH && !manualSource.isNullOrBlank()) {
                // Keep every OCR polygon for erasing, while the corrected source governs validation.
                lines.forEachIndexed { index, line -> line.text = if (index == 0) source else "" }
            }
            val effectiveMask = mask ?: fallbackMask(crop).also { mask = it }
            val direction = if (language == SourceLanguage.ENGLISH) "h" else
                lines.groupingBy { it.direction }.eachCount().maxByOrNull { it.value }?.key ?: "v"
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            lines.forEach { line -> line.quad.forEach { point ->
                x0 = minOf(x0, point.x); y0 = minOf(y0, point.y)
                x1 = maxOf(x1, point.x); y1 = maxOf(y1, point.y)
            } }
            val region = TextRegion(lines, direction, 0f, (x0 + x1) / 2f, (y0 + y1) / 2f, x1 - x0, y1 - y0)
            transferred = true
            PatchAnalysis(region, effectiveMask, source)
        } finally {
            if (!transferred) mask?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun fallbackMask(crop: Bitmap): Bitmap {
        val w = crop.width; val h = crop.height
        val px = IntArray(w * h); crop.getPixels(px, 0, w, 0, 0, w, h)
        val hist = IntArray(256)
        var sum = 0.0
        for (p in px) {
            val l = (0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)).toInt()
            hist[l.coerceIn(0, 255)]++; sum += l
        }
        val mean = sum / px.size
        val total = px.size
        var sumB = 0.0; var wB = 0; var best = 0.0; var th = 127
        for (i in 0..255) {
            wB += hist[i]; if (wB == 0) continue
            val wF = total - wB; if (wF == 0) break
            sumB += i * hist[i]
            val mB = sumB / wB; val mF = (sum - sumB) / wF
            val b = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (b > best) { best = b; th = i }
        }
        val whiteText = mean < 128
        val out = IntArray(w * h)
        for (i in px.indices) {
            val p = px[i]
            val l = (0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)).toInt()
            val on = if (whiteText) l > th else l <= th
            out[i] = if (on) Color.WHITE else Color.BLACK
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private suspend fun translatePatchText(source: String, models: li.joye.yakuyomi.engine.ModelSet, p: Project, mock: Boolean, key: String?): String? {
        val english = p.sourceLang == "ENGLISH"
        return try {
            if (mock) {
                val text = MockTranslator().translate(listOf(source)).firstOrNull() ?: return null
                if (english) EnglishTranslationGuard.validate(source, text)?.let { error(it) }
                text
            } else {
                if (key.isNullOrBlank()) return null
                val cfg = buildEngineConfig(p)
                val translator = LlmTranslator(key, cfg.translator)
                val result = translator.translateDetailed(listOf(source), englishGuard = english)
                val text = result.translations.firstOrNull()?.trim().orEmpty()
                if (english) {
                    val invalid = result.regionErrors[0] ?: EnglishTranslationGuard.validate(source, text)
                    if (invalid != null) error(invalid)
                    text
                } else if (text.isBlank() || text.equals(source.trim(), ignoreCase = true)) null else text
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (english) throw failure else null
        }
    }

    private suspend fun renderPatch(crop: Bitmap, a: PatchAnalysis, translated: String, models: li.joye.yakuyomi.engine.ModelSet, p: Project): Bitmap {
        a.region.translatedText = translated
        if (p.sourceLang == "ENGLISH") return renderEnglishPatch(crop, a, models, p)
        // Japanese retains its original 0.2.0 image path.
        val mode = if (p.inpaint == "smart") "smart" else p.inpaint
        val legacyInpainter = Legacy020Inpainter(models.aotInpainterNcnn!!, InpainterConfig(method = p.inpaint))
        var cleaned: Bitmap? = null
        try {
            cleaned = legacyInpainter.inpaintSmart(crop, listOf(a.region), a.mask, mode).bitmap
            val config = RenderConfig(
                orientation = runCatching { TextOrientation.valueOf(settings.orientation) }.getOrDefault(TextOrientation.AUTO),
                fontBorder = settings.fontBorder,
            )
            return Legacy020SmartRenderer.renderDetailed(cleaned, listOf(a.region), config).bitmap
        } finally {
            runCatching { legacyInpainter.close() }
            runCatching { cleaned?.recycle() }
            if (!a.mask.isRecycled) a.mask.recycle()
        }
    }

    private suspend fun renderEnglishPatch(
        crop: Bitmap, analysis: PatchAnalysis, models: li.joye.yakuyomi.engine.ModelSet, p: Project,
    ): Bitmap {
        var inpainter: Legacy020Inpainter? = null
        var result: EnglishImagePipeline.Result? = null
        var transferred = false
        val latin = MlKitLatinOcr()
        try {
            val engine = Legacy020Inpainter(models.aotInpainterNcnn ?: error("缺少擦字模型"),
                InpainterConfig(method = p.inpaint, maskDilate = 3f, bboxPad = 3))
            inpainter = engine
            val output = EnglishImagePipeline.finish(crop, analysis.mask, listOf(analysis.region), engine,
                maxFontSize = 48, residualOcr = { latin.recognize(it) })
            result = output
            if (0 !in output.accepted) error(output.failures[0] ?: "局部擦字或排版檢查未通過，原圖未修改")
            if (output.warnings.isNotEmpty()) error(output.warnings.joinToString("；"))
            transferred = true
            return output.final
        } finally {
            latin.close()
            runCatching { inpainter?.close() }
            val owned = listOfNotNull(result?.cleaned, result?.mask, analysis.mask) +
                (if (transferred) emptyList() else listOfNotNull(result?.final))
            owned.distinct().forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private suspend fun saveAndApplyPatch(
        dir: File,
        page: PageEntry,
        rect: RectF,
        source: String,
        translated: String,
        patchBmp: Bitmap,
        p: Project,
        models: li.joye.yakuyomi.engine.ModelSet,
    ): ManualPatch {
        val id = "p${System.currentTimeMillis()}_${(100..999).random()}"
        val rel = PatchCodec.patchPath(page, id)
        withContext(Dispatchers.IO) { ImageIo.save(patchBmp, File(dir, rel), "png") }
        val patch = ManualPatch(
            id = id, pageIndex = page.index, x = rect.left, y = rect.top,
            w = rect.width(), h = rect.height(), source = source, translated = translated,
            patchFile = rel, status = "applied", createdAt = System.currentTimeMillis(),
            renderRevision = if (p.sourceLang == "ENGLISH") 1 else 0,
        )
        val list = PatchCodec.load(dir, page).toMutableList().apply { add(patch) }
        PatchCodec.save(dir, page, list)
        if (p.sourceLang == "ENGLISH") {
            applyPatchesToPage(dir, page, list, p, models)
            return patch
        }
        val baseFile = page.translatedFile?.let { File(dir, it) } ?: File(dir, page.originalFile)
        val base = ImageIo.decode(baseFile) ?: throw IllegalStateException("頁面讀取失敗")
        val merged = PatchCodec.apply(dir, base, list)
        val outRel = page.translatedFile ?: "translated/%06d.%s".format(page.index, page.ext)
        ImageIo.save(merged, File(dir, outRel), page.ext)
        merged.recycle()
        page.translatedFile = outRel
        page.status = PageStatus.PARTIAL
        page.error = "手動補救 ${list.size} 區（已嵌字）"
        page.updatedAt = System.currentTimeMillis()
        return patch
    }

    fun reOcrPatch(pageIndex: Int, patchId: String) {
        val p = project ?: return
        val page = p.pages.getOrNull(pageIndex) ?: return
        val models = modelRepo.modelSet() ?: run { dialogMessage = "需要先下載模型"; return }
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("重新辨識局部", "OCR…", null)
            var original: Bitmap? = null; var crop: Bitmap? = null
            var detector: Detector? = null; var ocr: Ocr? = null
            var analysisMask: Bitmap? = null
            try {
                val dir = File(modelsRoot, p.id)
                original = withContext(Dispatchers.IO) { ImageIo.decode(File(dir, page.originalFile)) } ?: error("原圖讀取失敗")
                val list = PatchCodec.load(dir, page)
                val patch = list.firstOrNull { it.id == patchId } ?: error("找不到補救紀錄")
                val r = clampRect(patch.rect, original.width, original.height)
                crop = Bitmap.createBitmap(original, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt())
                val cfg = buildEngineConfig(p)
                detector = Detector(models.detectorNcnn!!, cfg.detector)
                ocr = Ocr(models.ocr, alphabetCache, cfg.ocr)
                val a = analyzePatch(crop, null, detector, ocr, cfg, SourceLanguage.parse(p.sourceLang))
                    ?: error("OCR 仍失敗；請用『重新翻譯』前先手動修改原文")
                analysisMask = a.mask
                val updated = patch.copy(source = a.source, status = "ocr_ok",
                    renderRevision = if (p.sourceLang == "ENGLISH") 0 else patch.renderRevision)
                PatchCodec.save(dir, page, list.map { if (it.id == patchId) updated else it })
                patches = PatchCodec.load(dir, page)
                toast = "局部 OCR 更新：${a.source.take(30)}…"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                dialogMessage = "重新辨識失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                analysisMask?.let { if (!it.isRecycled) it.recycle() }
                runCatching { detector?.close() }; runCatching { ocr?.close() }
                runCatching { crop?.recycle() }; runCatching { original?.recycle() }
                busy = null
            }
        }
    }

    fun retranslatePatch(pageIndex: Int, patchId: String, sourceOverride: String? = null) {
        val p = project ?: return
        val page = p.pages.getOrNull(pageIndex) ?: return
        val models = modelRepo.modelSet() ?: run { dialogMessage = "需要先下載模型"; return }
        val mock = BuildConfig.DEBUG && settings.mockTranslation
        val key = settingsStore.apiKey()
        if (!mock && key.isNullOrBlank()) { dialogMessage = "需要 API Key"; return }
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("重新翻譯局部", "呼叫 API…", null)
            var original: Bitmap? = null; var crop: Bitmap? = null; var detector: Detector? = null; var ocr: Ocr? = null
            var patchBmp: Bitmap? = null
            var analysisMask: Bitmap? = null
            try {
                val dir = File(modelsRoot, p.id)
                var list = PatchCodec.load(dir, page)
                var patch = list.firstOrNull { it.id == patchId } ?: error("找不到補救紀錄")
                val source = sourceOverride?.trim()?.ifBlank { null } ?: patch.source
                if (source.isBlank()) { patchManualRequest = PatchSelection(pageIndex, patch.rect); return@launch }
                val translated = translatePatchText(source, models, p, mock, key) ?: error("翻譯失敗或譯文等於原文")
                original = withContext(Dispatchers.IO) { ImageIo.decode(File(dir, page.originalFile)) } ?: error("原圖讀取失敗")
                val r = clampRect(patch.rect, original.width, original.height)
                crop = Bitmap.createBitmap(original, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt())
                val cfg = buildEngineConfig(p)
                detector = Detector(models.detectorNcnn!!, cfg.detector)
                ocr = Ocr(models.ocr, alphabetCache, cfg.ocr)
                val a = analyzePatch(crop, source, detector, ocr, cfg, SourceLanguage.parse(p.sourceLang)) ?: error("無法建立局部文字區")
                analysisMask = a.mask
                patchBmp = withContext(Dispatchers.Default) { renderPatch(crop, a, translated, models, p) }
                withContext(Dispatchers.IO) {
                    ImageIo.save(patchBmp!!, File(dir, patch.patchFile), "png")
                    val updated = patch.copy(source = source, translated = translated, status = "applied",
                        renderRevision = if (p.sourceLang == "ENGLISH") 1 else patch.renderRevision)
                    list = list.map { if (it.id == patchId) updated else it }
                    PatchCodec.save(dir, page, list)
                    applyPatchesToPage(dir, page, list, p, models)
                    patches = PatchCodec.load(dir, page)
                    projectStore.save(p)
                }
                revision++
                toast = "局部重新翻譯並嵌字完成"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                dialogMessage = "局部重譯失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                analysisMask?.let { if (!it.isRecycled) it.recycle() }
                runCatching { detector?.close() }; runCatching { ocr?.close() }
                runCatching { crop?.recycle() }; runCatching { original?.recycle() }
                runCatching { patchBmp?.recycle() }
                busy = null
            }
        }
    }

    fun rerenderPatch(pageIndex: Int, patchId: String) {
        val p = project ?: return
        val page = p.pages.getOrNull(pageIndex) ?: return
        val models = modelRepo.modelSet() ?: run { dialogMessage = "需要先下載模型"; return }
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("重新擦字/嵌字", "不呼叫 API…", null)
            var original: Bitmap? = null; var crop: Bitmap? = null; var detector: Detector? = null; var ocr: Ocr? = null
            var patchBmp: Bitmap? = null
            var analysisMask: Bitmap? = null
            try {
                val dir = File(modelsRoot, p.id)
                var list = PatchCodec.load(dir, page)
                val patch = list.firstOrNull { it.id == patchId } ?: error("找不到補救紀錄")
                if (patch.translated.isBlank()) error("此補救尚無譯文")
                original = withContext(Dispatchers.IO) { ImageIo.decode(File(dir, page.originalFile)) } ?: error("原圖讀取失敗")
                val r = clampRect(patch.rect, original.width, original.height)
                crop = Bitmap.createBitmap(original, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt())
                val cfg = buildEngineConfig(p)
                detector = Detector(models.detectorNcnn!!, cfg.detector)
                ocr = Ocr(models.ocr, alphabetCache, cfg.ocr)
                val a = analyzePatch(crop, patch.source, detector, ocr, cfg, SourceLanguage.parse(p.sourceLang)) ?: error("無法建立局部文字區")
                analysisMask = a.mask
                patchBmp = withContext(Dispatchers.Default) { renderPatch(crop, a, patch.translated, models, p) }
                withContext(Dispatchers.IO) {
                    ImageIo.save(patchBmp!!, File(dir, patch.patchFile), "png")
                    if (p.sourceLang == "ENGLISH") {
                        list = list.map { if (it.id == patchId) it.copy(status = "applied", renderRevision = 1) else it }
                        PatchCodec.save(dir, page, list)
                        patches = list
                    }
                    applyPatchesToPage(dir, page, list, p, models)
                    projectStore.save(p)
                }
                revision++
                toast = "局部重新擦字/嵌字完成（未呼叫 API）"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                dialogMessage = "局部重排失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                analysisMask?.let { if (!it.isRecycled) it.recycle() }
                runCatching { detector?.close() }; runCatching { ocr?.close() }
                runCatching { crop?.recycle() }; runCatching { original?.recycle() }
                runCatching { patchBmp?.recycle() }
                busy = null
            }
        }
    }

    private suspend fun applyPatchesToPage(
        dir: File, page: PageEntry, list: List<ManualPatch>, p: Project,
        models: li.joye.yakuyomi.engine.ModelSet,
    ) {
        if (p.sourceLang == "ENGLISH") {
            val loaded = AnalysisCodec.load(dir, page)
            if (loaded != null) {
                // Rebuild the base too: an old translated composite may already contain stale patches.
                rerenderEnglish(dir, p, page, loaded.regions.map { it.translatedText }, models)
                return
            }
            val eligible = list.filter { eligibleEnglishPatch(it) && File(dir, it.patchFile).isFile }
            val original = ImageIo.decode(File(dir, page.originalFile)) ?: error("原圖讀取失敗")
            var merged: Bitmap? = null
            try {
                val output = PatchCodec.apply(dir, original, eligible)
                merged = output
                val relative = page.translatedFile ?: "translated/%06d.%s".format(page.index, page.ext)
                ImageIo.save(output, File(dir, relative), page.ext)
                page.translatedFile = relative
                page.status = if (eligible.isEmpty()) PageStatus.FAILED else PageStatus.PARTIAL
                page.error = "已套用 ${eligible.size} 個局部修正，尚無整頁翻譯資料" +
                    if (eligible.size < list.size) "；其餘舊版或無效局部尚未套用，請重新擦字／嵌字" else ""
                page.translatedRegions = 0
                page.updatedAt = System.currentTimeMillis()
            } finally {
                listOfNotNull(original, merged).distinct().forEach { if (!it.isRecycled) it.recycle() }
            }
            return
        }
        val baseFile = page.translatedFile?.let { File(dir, it) } ?: File(dir, page.originalFile)
        val base = ImageIo.decode(baseFile) ?: return
        val merged = PatchCodec.apply(dir, base, list)
        val outRel = page.translatedFile ?: "translated/%06d.%s".format(page.index, page.ext)
        ImageIo.save(merged, File(dir, outRel), page.ext)
        merged.recycle()
        page.translatedFile = outRel
        page.status = PageStatus.PARTIAL
        page.error = "手動補救 ${list.size} 區（已嵌字）"
        page.updatedAt = System.currentTimeMillis()
    }

    private fun clampRect(r: RectF, w: Int, h: Int): RectF {
        val l = r.left.coerceIn(0f, (w - 2).toFloat())
        val t = r.top.coerceIn(0f, (h - 2).toFloat())
        val rr = r.right.coerceIn(l + 2f, w.toFloat())
        val bb = r.bottom.coerceIn(t + 2f, h.toFloat())
        return RectF(l, t, rr, bb)
    }

    fun selectExportTree(uri: Uri?) {
        settings = settings.copy(exportTreeUri = uri?.toString())
        settingsStore.save(settings)
        toast = if (uri == null) "已使用預設 Download/瑤心翻譯" else "已設定自訂匯出資料夾"
    }

    private suspend fun exportProjectToConfiguredFolder(p: Project): ExportInfo = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val fileName = (p.name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").take(70).ifBlank { "漫畫" } + "_繁中.zip")
        val customTree = settings.exportTreeUri
        val uri = if (customTree != null) {
            val dir = DocumentFile.fromTreeUri(app, Uri.parse(customTree))
                ?: throw IllegalStateException("自訂資料夾授權已失效，請到設定重新選擇")
            dir.createFile("application/zip", fileName)
                ?.uri ?: throw IllegalStateException("無法在自訂資料夾建立 ZIP")
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw IllegalStateException("Android 9 及以下請先在設定選擇匯出資料夾")
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/瑤心翻譯")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("無法建立 Download/瑤心翻譯 匯出檔")
        }
        try {
            val output = resolver.openOutputStream(uri, "w") ?: error("無法寫入 ZIP")
            val result = output.use { stream ->
                MangaExporter.export(File(modelsRoot, p.id), p, stream)
            }
            if (customTree == null) resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            ExportInfo(uri.toString(), fileName, result)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    fun exportToConfiguredFolder() {
        val p = project ?: return
        if (busy != null || runRunning) {
            toast = "翻譯或匯入進行中，完成後再匯出"
            return
        }
        viewModelScope.launch {
            busy = BusyState("匯出繁中 ZIP", "寫入指定資料夾…")
            try {
                val info = exportProjectToConfiguredFolder(p)
                lastExport = info
                screen = Screen.EXPORT
                toast = "已匯出到指定資料夾：${info.fileName}"
            } catch (e: Exception) {
                dialogMessage = "匯出失敗：${e.message ?: "未知錯誤"}"
            } finally { busy = null }
        }
    }

    // ---------------- 匯出 ----------------

    fun exportFileName(): String = "${project?.name ?: "漫畫"}_繁中.zip"

    fun exportTo(uri: Uri, fileName: String) {
        val p = project ?: return
        if (busy != null) return
        viewModelScope.launch {
            busy = BusyState("匯出 ZIP", "準備中…", 0f)
            try {
                val dir = File(modelsRoot, p.id)
                val result = withContext(Dispatchers.IO) {
                    val out = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                        ?: throw IllegalStateException("無法建立輸出檔")
                    out.use { stream ->
                        MangaExporter.export(dir, p, stream) { prog ->
                            val pct = if (prog.totalBytes > 0) prog.doneBytes.toFloat() / prog.totalBytes else null
                            busy = BusyState("匯出 ZIP", "${prog.doneEntries}/${prog.totalEntries}：${prog.currentName.takeLast(40)}", pct)
                        }
                    }
                }
                lastExport = ExportInfo(uri.toString(), fileName, result)
                screen = Screen.EXPORT
                toast = "匯出完成：${result.writtenPages} 頁繁中、${result.originalPages} 頁保留原圖"
            } catch (t: Throwable) {
                dialogMessage = "匯出失敗：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    // ---------------- 導覽 ----------------

    fun goHome() { screen = Screen.HOME }
    fun openPages() { screen = Screen.PAGES }
    fun openSettings() { screen = Screen.SETTINGS }
    fun openProgress() { screen = Screen.PROGRESS }
    fun openExport() { screen = Screen.EXPORT }
    fun openViewer(index: Int) {
        selectedPage = index
        viewerTranslated = project?.pages?.getOrNull(index)?.status.let {
            it == PageStatus.DONE || it == PageStatus.PARTIAL
        }
        screen = Screen.VIEWER
    }
    fun openEditor(index: Int) { selectedPage = index; screen = Screen.EDITOR }
    fun openSelfTest() { selfTestResult = null; screen = Screen.SELFTEST }

    /** 編輯頁的「重新翻譯」：完成後回到檢視頁看結果。 */
    fun reTranslateFromEditor(index: Int) = translateSinglePage(index)
    fun setViewerMode(translated: Boolean) { viewerTranslated = translated }
    fun back(): Boolean {
        screen = when (screen) {
            Screen.HOME -> return false
            Screen.PAGES -> Screen.HOME
            Screen.SETTINGS -> if (project != null) Screen.PAGES else Screen.HOME
            Screen.PROGRESS -> Screen.PAGES
            Screen.VIEWER -> Screen.PAGES
            Screen.EDITOR -> Screen.VIEWER
            Screen.EXPORT -> Screen.PAGES
            Screen.SELFTEST -> Screen.SETTINGS
            Screen.DIAG -> Screen.VIEWER
        }
        return true
    }

    private fun buildEngineConfig(p: Project): EngineConfig {
        val api = settings.toApiConfig(settingsStore.apiKey() ?: "")
        val target = p.targetLang
        val baseTranslator = TranslatorConfig(
            provider = settings.providerId,
            model = settings.modelId,
            apiBase = api.chatUrl,
            toLangName = when (target) {
                "zh-HK" -> "Traditional Chinese (Hong Kong, 香港慣用的繁體中文)"
                "zh-CN" -> "Simplified Chinese (简体中文)"
                else -> "Traditional Chinese (Taiwan, 台灣慣用的繁體中文用語)"
            },
            fromLangName = if (p.sourceLang == "ENGLISH") "English" else "Japanese",
            // 日文 0.2.0 原樣沿用 TranslatorConfig 內建 few-shot，而不是 0.4.0 的新示例。
            sampleSource = if (p.sourceLang == "ENGLISH") ENGLISH_SAMPLE_SOURCE else TranslatorConfig().sampleSource,
            sampleTarget = if (p.sourceLang == "ENGLISH") ENGLISH_SAMPLE_TARGET else TranslatorConfig().sampleTarget,
            temperature = 0.3,
            thinking = false,
            extraInstructions = if (p.sourceLang == "ENGLISH") ADULT_FAITHFUL_PROMPT else LEGACY020_ADULT_FAITHFUL_PROMPT,
        )
        val translator = if (target == "zh-CN") {
            baseTranslator.copy(sampleTarget = if (p.sourceLang == "ENGLISH")
                tw.dafeiyu.mangatranslator.core.SIMPLIFIED_SAMPLE_TARGET
                else LEGACY020_SIMPLIFIED_SAMPLE_TARGET)
        } else baseTranslator
        return EngineConfig(
            ocr = OcrConfig(),
            inpainter = InpainterConfig(method = p.inpaint, tileSize = 768),
            render = RenderConfig(
                orientation = runCatching { TextOrientation.valueOf(p.orientation) }
                    .getOrDefault(TextOrientation.AUTO),
                fontBorder = settings.fontBorder,
                // 0.2.0：文字框只做小幅外擴，避免壓到對話框邊線；短句不套用全域縮小。
                expandW = 1.15f,
                expandH = 1.20f,
                fontScale = 0.90f,
            ),
            translator = translator,
        )
    }

    private fun fmt(n: Long): String = when {
        n >= 1L shl 30 -> "%.1f GB".format(n / (1L shl 30).toDouble())
        n >= 1L shl 20 -> "%.1f MB".format(n / (1L shl 20).toDouble())
        else -> "${n / 1024} KB"
    }

    override fun onCleared() {
        super.onCleared()
        runJob?.cancel()
        downloadJob?.cancel()
    }
}
