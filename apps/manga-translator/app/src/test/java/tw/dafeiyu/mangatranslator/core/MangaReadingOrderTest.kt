package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 手動來源語言：日文右欄先讀、英文同一行左側先讀。 */
class MangaReadingOrderTest {
    private val boxes = listOf(
        MangaReadingOrder.Bounds(10f,10f,60f,40f),
        MangaReadingOrder.Bounds(90f,15f,140f,45f),
        MangaReadingOrder.Bounds(12f,80f,60f,115f),
        MangaReadingOrder.Bounds(90f,85f,140f,120f),
    )

    @Test fun japaneseUsesRightColumnFirst() = assertEquals(
        listOf(1,3,0,2), MangaReadingOrder.order(boxes, SourceLanguage.JAPANESE))

    @Test fun englishUsesLeftToRightRows() = assertEquals(
        listOf(0,1,2,3), MangaReadingOrder.order(boxes, SourceLanguage.ENGLISH))
}
