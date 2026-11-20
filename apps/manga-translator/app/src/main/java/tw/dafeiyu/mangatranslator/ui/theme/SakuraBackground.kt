package tw.dafeiyu.mangatranslator.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import tw.dafeiyu.mangatranslator.R

/** 僅用在應用操作介面；不覆蓋漫畫頁面 Bitmap、編輯畫布、翻譯輸出。 */
@Composable
fun SakuraScreenBackground(modifier: Modifier = Modifier) {
    val yx = LocalYaoXinColors.current
    Box(modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(yx.background, yx.surfaceAlt.copy(alpha = 0.88f), yx.background)),
    )) {
        Image(
            painter = painterResource(R.drawable.yx_manga_sakura), contentDescription = null,
            modifier = Modifier.align(Alignment.TopEnd).width(150.dp).height(145.dp).alpha(0.11f),
            contentScale = ContentScale.Fit,
        )
        Image(
            painter = painterResource(R.drawable.yx_manga_sakura), contentDescription = null,
            modifier = Modifier.align(Alignment.BottomStart).width(140.dp).height(125.dp).alpha(0.08f),
            contentScale = ContentScale.Fit,
        )
    }
}
