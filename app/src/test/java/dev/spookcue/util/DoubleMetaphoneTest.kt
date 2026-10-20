package dev.spookcue.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubleMetaphoneTest {

    private val metaphone = DoubleMetaphone()

    @Test
    fun testHomophones() {
        val night = metaphone.encode("night")
        val knight = metaphone.encode("knight")
        assertEquals(night.primary, knight.primary)

        val pale = metaphone.encode("pale")
        val pail = metaphone.encode("pail")
        assertEquals(pale.primary, pail.primary)

        val cellar = metaphone.encode("cellar")
        val seller = metaphone.encode("seller")
        assertEquals(cellar.primary, seller.primary)
    }

    @Test
    fun testEmptyAndNull() {
        assertEquals("", metaphone.encode("").primary)
        assertEquals("", metaphone.encode(null).primary)
    }
}
