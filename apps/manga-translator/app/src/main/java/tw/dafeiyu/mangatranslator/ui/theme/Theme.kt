package tw.dafeiyu.mangatranslator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/** 保留 0.3.x 的主題呼叫介面，將全系列統一交給瑤芯 UI Kit。 */
@Composable
fun YaoXinMangaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) = YaoXinTheme(dark = darkTheme, content = content)
