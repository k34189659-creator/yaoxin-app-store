package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多引擎 OCR 融合的迴歸測試。
 * 目標：第二套 OCR 只能「補」不能「亂蓋」——低信心才覆蓋、延伸才取長、其餘保留主引擎並標記。
 */
class OcrFusionTest {

    @Test
    fun ncnnOnlyWhenPpIsBlank() {
        val c = OcrFusion.choose("", 0f, "テスト", 0.91f)
        assertEquals("テスト", c.text)
        assertEquals("ncnn-only", c.source)
    }

    @Test
    fun ppOnlyWhenNcnnIsBlank() {
        val c = OcrFusion.choose("テスト", 0.95f, "", 0f)
        assertEquals("テスト", c.text)
        assertFalse(c.disagree)
    }

    @Test
    fun identicalTextsAgree() {
        val c = OcrFusion.choose("その通りじゃ", 0.98f, "その通りじ ゃ", 0.90f)
        assertEquals("その通りじゃ", c.text.replace(" ", ""))
        assertEquals("agree", c.source)
        assertFalse(c.disagree)
    }

    @Test
    fun lowConfidencePpYieldsToNcnn() {
        val c = OcrFusion.choose("世界ジョン例", 0.42f, "世界的に前例", 0.88f)
        assertEquals("世界的に前例", c.text)
        assertEquals("ncnn-lowconf", c.source)
        assertTrue(c.disagree)
    }

    @Test
    fun longerSupersetWinsToAvoidTruncation() {
        val c = OcrFusion.choose("体内から", 0.93f, "体内か", 0.97f)
        assertEquals("体内から", c.text)
        assertEquals("pp-longer", c.source)
    }

    @Test
    fun ncnnLongerSupersetWinsWhenConfident() {
        val c = OcrFusion.choose("待ってくださ", 0.90f, "待ってください", 0.92f)
        assertEquals("待ってください", c.text)
        assertEquals("ncnn-longer", c.source)
    }

    @Test
    fun conflictingReadingsKeepPrimaryAndFlag() {
        // 兩套完全不同 → 不拼字、不自行猜測；保留 PP 並標記待人工/容錯
        val c = OcrFusion.choose("これはまあしゃーてるw", 0.94f, "これはまあしゃーないww", 0.93f)
        assertTrue(c.disagree)
        assertEquals("pp", c.source)
    }

    @Test
    fun junkFilterKeepsSingleCharacterDialogue() {
        assertTrue("空白是垃圾", OcrFusion.isJunk("", 0.9f))
        assertFalse("單字台詞（ま）信心高，必須保留", OcrFusion.isJunk("ま", 0.998f))
        assertTrue("單字＋低信心＝誤偵測", OcrFusion.isJunk("あ", 0.31f))
        assertTrue("整體低信心", OcrFusion.isJunk("何か", 0.22f))
        assertFalse("正常台詞", OcrFusion.isJunk("その通りじゃ", 0.8f))
    }

    @Test
    fun modeParsingDefaultsToStandard() {
        assertEquals(OcrMode.STANDARD, OcrMode.parse(null))
        assertEquals(OcrMode.STANDARD, OcrMode.parse("garbage"))
        assertEquals(OcrMode.FAST, OcrMode.parse("fast"))
        assertEquals(OcrMode.HIGH, OcrMode.parse("HIGH"))
        assertEquals(SourceLanguage.ENGLISH, SourceLanguage.parse("en"))
        assertEquals(SourceLanguage.JAPANESE, SourceLanguage.parse("ja"))
        assertEquals(SourceLanguage.JAPANESE, SourceLanguage.parse(null))
    }
}
