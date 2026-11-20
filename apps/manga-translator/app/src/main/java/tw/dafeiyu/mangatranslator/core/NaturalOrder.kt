package tw.dafeiyu.mangatranslator.core

import java.text.Collator
import java.util.Locale

/**
 * 自然排序：1, 2, 3, 10（不是 1, 10, 2, 3）。
 * 中文／日文檔名用 zh-TW Collator 的 PRIMARY 強度比較，避免筆畫/拼音差異讓同一資料夾看起來亂序；
 * 數字以「實際數值」比較（任意長度，前導零不影響），完全相同時再比原始字串，保證穩定。
 *
 * 路徑排序逐段比較：`第1話/2.jpg` < `第1話/10.jpg` < `第2話/1.jpg`。
 */
object NaturalOrder : Comparator<String> {
    private val collator: Collator = Collator.getInstance(Locale.TRADITIONAL_CHINESE).apply {
        strength = Collator.PRIMARY
    }

    override fun compare(a: String, b: String): Int = compareNames(a, b)

    /** 比較單一名稱（可含數字）。 */
    fun compareNames(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i
                while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < b.length && b[j2].isDigit()) j2++
                var ia = i
                while (ia < i2 - 1 && a[ia] == '0') ia++
                var jb = j
                while (jb < j2 - 1 && b[jb] == '0') jb++
                val la = i2 - ia
                val lb = j2 - jb
                if (la != lb) return la - lb
                for (k in 0 until la) {
                    val d = a[ia + k].digitToInt() - b[jb + k].digitToInt()
                    if (d != 0) return d
                }
                // 數值相同：前導零多者往後排（002 vs 2），仍是穩定且可預期的順序
                val z = (i2 - i) - (j2 - j)
                if (z != 0) return z
                i = i2
                j = j2
            } else {
                var i2 = i
                while (i2 < a.length && !a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < b.length && !b[j2].isDigit()) j2++
                val c = collator.compare(a.substring(i, i2), b.substring(j, j2))
                if (c != 0) return c
                i = i2
                j = j2
            }
        }
        val rem = (a.length - i) - (b.length - j)
        if (rem != 0) return rem
        return a.compareTo(b)
    }

    /** 逐段比較 ZIP 內的相對路徑（`/` 分隔）。 */
    fun comparePaths(a: String, b: String): Int {
        val asSeg = a.split('/')
        val bsSeg = b.split('/')
        val n = minOf(asSeg.size, bsSeg.size)
        for (k in 0 until n) {
            val c = compareNames(asSeg[k], bsSeg[k])
            if (c != 0) return c
        }
        if (asSeg.size != bsSeg.size) return asSeg.size - bsSeg.size
        return a.compareTo(b)
    }

    val pathComparator: Comparator<String> = Comparator { x, y -> comparePaths(x, y) }
}
