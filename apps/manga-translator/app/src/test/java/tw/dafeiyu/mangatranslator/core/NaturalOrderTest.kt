package tw.dafeiyu.mangatranslator.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun `numeric pages sort 1 2 3 10`() {
        val names = listOf("10.jpg", "2.jpg", "3.jpg", "1.jpg", "20.jpg", "100.jpg")
        assertEquals(
            listOf("1.jpg", "2.jpg", "3.jpg", "10.jpg", "20.jpg", "100.jpg"),
            names.sortedWith(NaturalOrder),
        )
    }

    @Test
    fun `paths sort per directory and keep chapters together`() {
        val paths = listOf(
            "ch2/1.jpg", "ch1/10.jpg", "ch1/2.jpg", "ch1/1.jpg", "ch10/1.jpg", "ch1/3.jpg",
        )
        assertEquals(
            listOf("ch1/1.jpg", "ch1/2.jpg", "ch1/3.jpg", "ch1/10.jpg", "ch2/1.jpg", "ch10/1.jpg"),
            paths.sortedWith(NaturalOrder.pathComparator),
        )
    }

    @Test
    fun `chinese chapter names sort naturally`() {
        val paths = listOf("第10話/1.jpg", "第2話/1.jpg", "第1話/1.jpg")
        assertEquals(
            listOf("第1話/1.jpg", "第2話/1.jpg", "第10話/1.jpg"),
            paths.sortedWith(NaturalOrder.pathComparator),
        )
    }

    @Test
    fun `leading zeros compare by value then shorter first`() {
        val names = listOf("002.jpg", "2.jpg", "000.jpg", "10.jpg")
        assertEquals(listOf("000.jpg", "2.jpg", "002.jpg", "10.jpg"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun `very long numeric runs are compared without overflow`() {
        val a = "999999999999999999999999999999.jpg"
        val b = "1000000000000000000000000000000.jpg"
        assertEquals(listOf(a, b), listOf(b, a).sortedWith(NaturalOrder))
    }

    @Test
    fun `mixed case names stay deterministic`() {
        val names = listOf("B.jpg", "a.jpg", "A.jpg", "b.jpg")
        val first = names.sortedWith(NaturalOrder)
        assertEquals(4, first.size)
        assertEquals(first, names.shuffled().sortedWith(NaturalOrder))
    }
}
