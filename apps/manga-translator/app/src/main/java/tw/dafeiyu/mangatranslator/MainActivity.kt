package tw.dafeiyu.mangatranslator

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import tw.dafeiyu.mangatranslator.ui.theme.SakuraScreenBackground
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import tw.dafeiyu.mangatranslator.ui.BusyOverlay
import tw.dafeiyu.mangatranslator.ui.screens.DiagnosticsScreen
import tw.dafeiyu.mangatranslator.ui.screens.EditorScreen
import tw.dafeiyu.mangatranslator.ui.screens.ExportScreen
import tw.dafeiyu.mangatranslator.ui.screens.HomeScreen
import tw.dafeiyu.mangatranslator.ui.screens.PagesScreen
import tw.dafeiyu.mangatranslator.ui.screens.ProgressScreen
import tw.dafeiyu.mangatranslator.ui.screens.SelfTestScreen
import tw.dafeiyu.mangatranslator.ui.screens.SettingsScreen
import tw.dafeiyu.mangatranslator.ui.screens.ViewerScreen
import tw.dafeiyu.mangatranslator.ui.theme.YaoXinMangaTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        incomingUri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        setContent {
            // 維持可讀的淺櫻花主題；舊版跟系統暗色時部分字色變黑，造成深底黑字。
            YaoXinMangaTheme(darkTheme = false) {
                MangaApp(
                    vm = vm,
                    initialUri = incomingUri,
                    onUriConsumed = {
                        incomingUri = null
                        // 清掉 launch intent，避免旋轉／重建時重複匯入同一個 ZIP
                        intent?.apply { action = null; data = null }
                    },
                    onExit = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) incomingUri = intent.data
    }
}

@Composable
fun MangaApp(
    vm: AppViewModel,
    initialUri: Uri?,
    onUriConsumed: () -> Unit,
    onExit: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val activity = LocalContext.current as? Activity

    LaunchedEffect(vm.toast) {
        vm.toast?.let {
            snackbar.showSnackbar(it)
            vm.toast = null
        }
    }
    LaunchedEffect(initialUri) {
        val u = initialUri
        if (u != null) {
            vm.importZip(u)
            onUriConsumed()
        }
    }
    DisposableEffect(vm.runRunning, activity) {
        val w = activity?.window
        if (vm.runRunning) {
            w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler {
        if (!vm.back()) onExit()
    }

    Box(Modifier.fillMaxSize()) {
    // 所有業務頁面共用的櫻花底圖：只在 UI 最底層，絕不套到原文或譯文 Bitmap。
    SakuraScreenBackground()
    Scaffold(
        // 必要通知採用上方正常排版區，出現時會把內容向下推，不再覆蓋底部操作鈕。
        // 匯入成功和來源語言切換則直接顯示於頁面，不再產生通知。
        topBar = {
            SnackbarHost(snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(15.dp),
                    containerColor = Color(0xFFFFEBF4),
                    contentColor = Color(0xFF533148),
                )
            }
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (vm.screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.PAGES -> vm.project?.let { PagesScreen(vm, it) } ?: HomeScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
                Screen.PROGRESS -> vm.project?.let { ProgressScreen(vm, it) } ?: HomeScreen(vm)
                Screen.VIEWER -> vm.project?.let { ViewerScreen(vm, it) } ?: HomeScreen(vm)
                Screen.EDITOR -> vm.project?.let { EditorScreen(vm, it) } ?: HomeScreen(vm)
                Screen.EXPORT -> vm.project?.let { ExportScreen(vm, it) } ?: HomeScreen(vm)
                Screen.SELFTEST -> SelfTestScreen(vm)
                Screen.DIAG -> vm.project?.let { DiagnosticsScreen(vm, it) } ?: HomeScreen(vm)
            }
        }
    }

    } // overlay

    vm.busy?.let { BusyOverlay(it) }

    vm.dialogMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.dialogMessage = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { vm.dialogMessage = null }) { Text("知道了") }
            },
        )
    }
}
