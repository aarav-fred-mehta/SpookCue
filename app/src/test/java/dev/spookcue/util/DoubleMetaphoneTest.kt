package dev.spookcue.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubleMetaphoneTest {

    private val metaphone = DoubleMetaphone()

    private val pythonFixtures = mapOf(
        "the" to "0",
        "that" to "0T",
        "this" to "0S",
        "there" to "0R",
        "with" to "A0",
        "they" to "0",
        "them" to "0M",
        "then" to "0N",
        "than" to "0N",
        "think" to "0NK",
        "thought" to "0T",
        "through" to "0R",
        "thinking" to "0NKNK",
        "get" to "KT",
        "give" to "JF",
        "girl" to "JRL",
        "got" to "KT",
        "getting" to "KTNK",
        "goes" to "KS",
        "going" to "KNK",
        "gone" to "KN",
        "good" to "KT",
        "great" to "KRT",
        "god" to "KT",
        "she" to "X",
        "her" to "HR",
        "he" to "H",
        "him" to "HM",
        "his" to "HS",
        "we" to "A",
        "us" to "AS",
        "our" to "AR",
        "what" to "AT",
        "who" to "A",
        "where" to "AR",
        "when" to "AN",
        "why" to "A",
        "how" to "H",
        "is" to "AS",
        "are" to "AR",
        "was" to "AS",
        "were" to "AR",
        "be" to "P",
        "been" to "PN",
        "being" to "PNK",
        "have" to "HF",
        "has" to "HS",
        "had" to "HT",
        "do" to "T",
        "did" to "TT",
        "does" to "TS",
        "say" to "S",
        "said" to "ST",
        "tell" to "TL",
        "told" to "TLT",
        "speak" to "SPK",
        "spoke" to "SPK",
        "see" to "S",
        "saw" to "S",
        "look" to "LK",
        "looked" to "LKT",
        "hear" to "HR",
        "heard" to "HRT",
        "doll" to "TL",
        "scare" to "SKR",
        "scares" to "SKRS",
        "scared" to "SKRT",
        "room" to "RM",
        "house" to "HS",
        "annabelle" to "ANPL",
        "camilla" to "KML",
        "medium" to "MTM",
        "possessed" to "PSST",
        "higgins" to "HKNS",
        "different" to "TFRNT",
        "completely" to "KMPLTL",
        "something" to "SM0NK",
        "nothing" to "N0NK",
        "someone" to "SMN",
        "everyone" to "AFRN",
        "anybody" to "ANPT",
        "nobody" to "NPT",
        "night" to "NT",
        "knight" to "NT",
        "pale" to "PL",
        "pail" to "PL",
        "cellar" to "SLR",
        "seller" to "SLR",
        "sugar" to "XKR",
        "thomas" to "TMS",
        "school" to "SKL",
        "cough" to "KF",
        "rough" to "RF",
        "touch" to "TX",
        "learned" to "LRNT",
        "parents" to "PRNTS",
        "taking" to "TKNK",
        "liking" to "LKNK",
        "friends" to "FRNTS",
        "table" to "TPL",
        "kitchen" to "KXN",
        "weather" to "A0R",
        "outside" to "ATST",
    )

    @Test
    fun testPythonFixturesParity() {
        for ((word, expectedPrimary) in pythonFixtures) {
            val actual = metaphone.encode(word)
            assertEquals("Mismatch for word '$word'", expectedPrimary, actual.primary)
        }
    }

    @Test
    fun testSpecificWords() {
        assertEquals("KT", metaphone.encode("get").primary)
        assertEquals("KT", metaphone.encode("got").primary)
        assertEquals("0T", metaphone.encode("that").primary)
        assertEquals("0S", metaphone.encode("this").primary)
        assertEquals("0NKNK", metaphone.encode("thinking").primary)
        assertEquals("AT", metaphone.encode("what").primary)
        assertEquals("AR", metaphone.encode("where").primary)
        assertEquals("AN", metaphone.encode("when").primary)
        assertEquals("A", metaphone.encode("who").primary)
        assertEquals("A", metaphone.encode("why").primary)
        assertEquals("ANPL", metaphone.encode("annabelle").primary)
        assertEquals("KML", metaphone.encode("camilla").primary)
    }

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
