package dev.spookcue.util

import java.text.Normalizer

/**
 * Pure Kotlin implementation of Lawrence Philips' Double Metaphone phonetic algorithm,
 * matching Python's `metaphone` library 1:1.
 */
class DoubleMetaphone(private val maxCodeLen: Int = 32) {

    data class Result(val primary: String, val alternate: String)

    companion object {
        private val SILENT_STARTERS = listOf("GN", "KN", "PN", "WR", "PS")
        private val VOWELS = setOf('A', 'E', 'I', 'O', 'U', 'Y')
    }

    private class Word(val original: String) {
        val upper: String
        val length: Int
        val prepad = "--"
        val start_index = prepad.length
        val end_index: Int
        val buffer: String

        init {
            var decoded = original.replace('\u00C7', 's').replace('\u00E7', 's')
            val normalized = Normalizer.normalize(decoded, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}"), "")
            upper = normalized.uppercase()
            length = upper.length
            end_index = start_index + length - 1
            val postpad = "------"
            buffer = prepad + upper + postpad
        }

        val isSlavoGermanic: Boolean
            get() = upper.contains("W") || upper.contains("K") || upper.contains("CZ") || upper.contains("WITZ")

        fun slice(start: Int, end: Int): String {
            return buffer.substring(start, end)
        }
    }

    fun encode(value: String?): Result {
        if (value.isNullOrBlank()) return Result("", "")

        val word = Word(value)
        if (word.length == 0) return Result("", "")

        var position = word.start_index
        val primaryPhone = StringBuilder()
        val secondaryPhone = StringBuilder()

        fun slice(start: Int, end: Int): String = word.slice(start, end)
        fun charAt(idx: Int): Char = word.buffer[idx]

        // Check word start
        if (SILENT_STARTERS.any { word.upper.startsWith(it) }) {
            position += 1
        }
        if (word.upper.startsWith("X")) {
            primaryPhone.append('S')
            secondaryPhone.append('S')
            position += 1
        }

        while (position <= word.end_index && (primaryPhone.length < maxCodeLen || secondaryPhone.length < maxCodeLen)) {
            val character = charAt(position)
            var nextPrimary: String? = null
            var nextSecondary: String? = null
            var advance = 1

            when {
                character in VOWELS -> {
                    if (position == word.start_index) {
                        nextPrimary = "A"
                    }
                    advance = 1
                }
                character == ' ' -> {
                    position += 1
                    continue
                }
                character == 'B' -> {
                    nextPrimary = "P"
                    advance = if (charAt(position + 1) == 'B') 2 else 1
                }
                character == 'C' -> {
                    val sIdx = word.start_index
                    if (position > sIdx + 1 &&
                        charAt(position - 2) !in VOWELS &&
                        slice(position - 1, position + 2) == "ACH" &&
                        charAt(position + 2) != 'I' &&
                        (charAt(position + 2) != 'E' || slice(position - 2, position + 4) in listOf("BACHER", "MACHER"))
                    ) {
                        nextPrimary = "K"
                        advance = 2
                    } else if (position == sIdx && slice(sIdx, sIdx + 6) == "CAESAR") {
                        nextPrimary = "S"
                        advance = 2
                    } else if (slice(position, position + 4) == "CHIA") {
                        nextPrimary = "K"
                        advance = 2
                    } else if (slice(position, position + 2) == "CH") {
                        if (position > sIdx && slice(position, position + 4) == "CHAE") {
                            nextPrimary = "K"
                            nextSecondary = "X"
                            advance = 2
                        } else if (position == sIdx &&
                            (slice(position + 1, position + 6) in listOf("HARAC", "HARIS") ||
                                slice(position + 1, position + 4) in listOf("HOR", "HYM", "HIA", "HEM")) &&
                            slice(sIdx, sIdx + 5) != "CHORE"
                        ) {
                            nextPrimary = "K"
                            advance = 2
                        } else if (
                            slice(sIdx, sIdx + 4) in listOf("VAN ", "VON ") ||
                            slice(sIdx, sIdx + 3) == "SCH" ||
                            slice(position - 2, position + 4) in listOf("ORCHES", "ARCHIT", "ORCHID") ||
                            charAt(position + 2) in listOf('T', 'S') ||
                            ((charAt(position - 1) in listOf('A', 'O', 'U', 'E') || position == sIdx) &&
                                charAt(position + 2) in listOf('L', 'R', 'N', 'M', 'B', 'H', 'F', 'V', 'W'))
                        ) {
                            nextPrimary = "K"
                            advance = 2
                        } else {
                            if (position > sIdx) {
                                if (slice(sIdx, sIdx + 2) == "MC") {
                                    nextPrimary = "K"
                                } else {
                                    nextPrimary = "X"
                                    nextSecondary = "K"
                                }
                            } else {
                                nextPrimary = "X"
                            }
                            advance = 2
                        }
                    } else if (slice(position, position + 2) == "CZ" && slice(position - 2, position + 2) != "WICZ") {
                        nextPrimary = "S"
                        nextSecondary = "X"
                        advance = 2
                    } else if (slice(position + 1, position + 4) == "CIA") {
                        nextPrimary = "X"
                        advance = 3
                    } else if (slice(position, position + 2) == "CC" && !(position == sIdx + 1 && charAt(sIdx) == 'M')) {
                        if (charAt(position + 2) in listOf('I', 'E', 'H') && slice(position + 2, position + 4) != "HU") {
                            if ((position == sIdx + 1 && charAt(sIdx) == 'A') ||
                                slice(position - 1, position + 4) in listOf("UCCEE", "UCCIS")
                            ) {
                                nextPrimary = "KS"
                                nextSecondary = "X"
                            } else {
                                nextPrimary = "KS"
                            }
                            advance = 3
                        } else {
                            nextPrimary = "K"
                            advance = 2
                        }
                    } else if (slice(position, position + 2) in listOf("CK", "CG", "CQ")) {
                        nextPrimary = "K"
                        advance = 2
                    } else if (slice(position, position + 2) in listOf("CI", "CE", "CY")) {
                        if (slice(position, position + 3) in listOf("CIO", "CIE", "CIA")) {
                            nextPrimary = "S"
                            nextSecondary = "X"
                        } else {
                            nextPrimary = "S"
                        }
                        advance = 2
                    } else {
                        nextPrimary = "K"
                        advance = if (charAt(position + 1) in listOf('C', 'Q', 'G')) 2 else 1
                    }
                }
                character == 'D' -> {
                    if (slice(position, position + 2) == "DG") {
                        if (charAt(position + 2) in listOf('I', 'E', 'Y')) {
                            nextPrimary = "J"
                            advance = 3
                        } else {
                            nextPrimary = "TK"
                            advance = 2
                        }
                    } else if (slice(position, position + 2) in listOf("DT", "DD")) {
                        nextPrimary = "T"
                        advance = 2
                    } else {
                        nextPrimary = "T"
                        advance = 1
                    }
                }
                character == 'F' -> {
                    nextPrimary = "F"
                    advance = if (charAt(position + 1) == 'F') 2 else 1
                }
                character == 'G' -> {
                    val sIdx = word.start_index
                    if (charAt(position + 1) == 'H') {
                        if (position > sIdx && charAt(position - 1) !in VOWELS) {
                            nextPrimary = "K"
                            advance = 2
                        } else if (position < sIdx + 3) {
                            if (position == sIdx) {
                                nextPrimary = if (charAt(position + 2) == 'I') "J" else "K"
                            }
                            advance = 2
                        } else if (
                            (position > sIdx + 1 && charAt(position - 2) in listOf('B', 'H', 'D')) ||
                            (position > sIdx + 2 && charAt(position - 3) in listOf('B', 'H', 'D')) ||
                            (position > sIdx + 3 && charAt(position - 4) in listOf('B', 'H'))
                        ) {
                            advance = 2
                        } else {
                            if (position > sIdx + 2 && charAt(position - 1) == 'U' &&
                                charAt(position - 3) in listOf('C', 'G', 'L', 'R', 'T')
                            ) {
                                nextPrimary = "F"
                            } else if (position > sIdx && charAt(position - 1) != 'I') {
                                nextPrimary = "K"
                            }
                            advance = 2
                        }
                    } else if (charAt(position + 1) == 'N') {
                        if (position == sIdx + 1 && charAt(sIdx) in VOWELS && !word.isSlavoGermanic) {
                            nextPrimary = "KN"
                            nextSecondary = "N"
                        } else {
                            if (slice(position + 2, position + 4) != "EY" && charAt(position + 1) != 'Y' && !word.isSlavoGermanic) {
                                nextPrimary = "N"
                                nextSecondary = "KN"
                            } else {
                                nextPrimary = "KN"
                            }
                        }
                        advance = 2
                    } else if (slice(position + 1, position + 3) == "LI" && !word.isSlavoGermanic) {
                        nextPrimary = "KL"
                        nextSecondary = "L"
                        advance = 2
                    } else if (position == sIdx &&
                        (charAt(position + 1) == 'Y' ||
                            slice(position + 1, position + 3) in listOf(
                                "ES", "EP", "EB", "EL", "EY", "IB", "IL", "IN", "IE", "EI", "ER"
                            ))
                    ) {
                        nextPrimary = "K"
                        nextSecondary = "J"
                        advance = 2
                    } else if (
                        (slice(position + 1, position + 3) == "ER" || charAt(position + 1) == 'Y') &&
                        slice(sIdx, sIdx + 6) !in listOf("DANGER", "RANGER", "MANGER") &&
                        charAt(position - 1) !in listOf('E', 'I') &&
                        slice(position - 1, position + 2) !in listOf("RGY", "OGY")
                    ) {
                        nextPrimary = "K"
                        nextSecondary = "J"
                        advance = 2
                    } else if (
                        charAt(position + 1) in listOf('E', 'I', 'Y') ||
                        slice(position - 1, position + 3) in listOf("AGGI", "OGGI")
                    ) {
                        if (slice(sIdx, sIdx + 4) in listOf("VON ", "VAN ") ||
                            slice(sIdx, sIdx + 3) == "SCH" ||
                            slice(position + 1, position + 3) == "ET"
                        ) {
                            nextPrimary = "K"
                        } else {
                            if (slice(position + 1, position + 5) == "IER ") {
                                nextPrimary = "J"
                            } else {
                                nextPrimary = "J"
                                nextSecondary = "K"
                            }
                        }
                        advance = 2
                    } else if (charAt(position + 1) == 'G') {
                        nextPrimary = "K"
                        advance = 2
                    } else {
                        nextPrimary = "K"
                        advance = 1
                    }
                }
                character == 'H' -> {
                    val sIdx = word.start_index
                    if ((position == sIdx || charAt(position - 1) in VOWELS) && charAt(position + 1) in VOWELS) {
                        nextPrimary = "H"
                        advance = 2
                    } else {
                        advance = 1
                    }
                }
                character == 'J' -> {
                    val sIdx = word.start_index
                    if (slice(position, position + 4) == "JOSE" || slice(sIdx, sIdx + 4) == "SAN ") {
                        if ((position == sIdx && charAt(position + 4) == ' ') || slice(sIdx, sIdx + 4) == "SAN ") {
                            nextPrimary = "H"
                        } else {
                            nextPrimary = "J"
                            nextSecondary = "H"
                        }
                    } else if (position == sIdx && slice(position, position + 4) != "JOSE") {
                        nextPrimary = "J"
                        nextSecondary = "A"
                    } else {
                        if (charAt(position - 1) in VOWELS && !word.isSlavoGermanic && charAt(position + 1) in listOf('A', 'O')) {
                            nextPrimary = "J"
                            nextSecondary = "H"
                        } else {
                            if (position == word.end_index) {
                                nextPrimary = "J"
                                nextSecondary = " "
                            } else {
                                if (charAt(position + 1) !in listOf('L', 'T', 'K', 'S', 'N', 'M', 'B', 'Z') &&
                                    charAt(position - 1) !in listOf('S', 'K', 'L')
                                ) {
                                    nextPrimary = "J"
                                }
                            }
                        }
                    }
                    advance = if (charAt(position + 1) == 'J') 2 else 1
                }
                character == 'K' -> {
                    nextPrimary = "K"
                    advance = if (charAt(position + 1) == 'K') 2 else 1
                }
                character == 'L' -> {
                    val eIdx = word.end_index
                    if (charAt(position + 1) == 'L') {
                        if ((position == eIdx - 2 && slice(position - 1, position + 3) in listOf("ILLO", "ILLA", "ALLE")) ||
                            ((slice(eIdx - 1, eIdx + 1) in listOf("AS", "OS") || charAt(eIdx) in listOf('A', 'O')) &&
                                slice(position - 1, position + 3) == "ALLE")
                        ) {
                            nextPrimary = "L"
                            nextSecondary = ""
                        } else {
                            nextPrimary = "L"
                        }
                        advance = 2
                    } else {
                        nextPrimary = "L"
                        advance = 1
                    }
                }
                character == 'M' -> {
                    val eIdx = word.end_index
                    if ((slice(position + 1, position + 4) == "UMB" &&
                            (position + 1 == eIdx || slice(position + 2, position + 4) == "ER")) ||
                        charAt(position + 1) == 'M'
                    ) {
                        nextPrimary = "M"
                        advance = 2
                    } else {
                        nextPrimary = "M"
                        advance = 1
                    }
                }
                character == 'N' -> {
                    nextPrimary = "N"
                    advance = if (charAt(position + 1) == 'N') 2 else 1
                }
                character == 'P' -> {
                    if (charAt(position + 1) == 'H') {
                        nextPrimary = "F"
                        advance = 2
                    } else if (charAt(position + 1) in listOf('P', 'B')) {
                        nextPrimary = "P"
                        advance = 2
                    } else {
                        nextPrimary = "P"
                        advance = 1
                    }
                }
                character == 'Q' -> {
                    nextPrimary = "K"
                    advance = if (charAt(position + 1) == 'Q') 2 else 1
                }
                character == 'R' -> {
                    val eIdx = word.end_index
                    if (position == eIdx && !word.isSlavoGermanic &&
                        slice(position - 2, position) == "IE" &&
                        slice(position - 4, position - 2) !in listOf("ME", "MA")
                    ) {
                        nextPrimary = ""
                        nextSecondary = "R"
                    } else {
                        nextPrimary = "R"
                    }
                    advance = if (charAt(position + 1) == 'R') 2 else 1
                }
                character == 'S' -> {
                    val sIdx = word.start_index
                    val eIdx = word.end_index
                    if (slice(position - 1, position + 2) in listOf("ISL", "YSL")) {
                        advance = 1
                    } else if (position == sIdx && slice(sIdx, sIdx + 5) == "SUGAR") {
                        nextPrimary = "X"
                        nextSecondary = "S"
                        advance = 1
                    } else if (slice(position, position + 2) == "SH") {
                        if (slice(position + 1, position + 5) in listOf("HEIM", "HOEK", "HOLM", "HOLZ")) {
                            nextPrimary = "S"
                        } else {
                            nextPrimary = "X"
                        }
                        advance = 2
                    } else if (slice(position, position + 3) in listOf("SIO", "SIA") || slice(position, position + 4) == "SIAN") {
                        if (!word.isSlavoGermanic) {
                            nextPrimary = "S"
                            nextSecondary = "X"
                        } else {
                            nextPrimary = "S"
                        }
                        advance = 3
                    } else if ((position == sIdx && charAt(position + 1) in listOf('M', 'N', 'L', 'W')) || charAt(position + 1) == 'Z') {
                        nextPrimary = "S"
                        nextSecondary = "X"
                        advance = if (charAt(position + 1) == 'Z') 2 else 1
                    } else if (slice(position, position + 2) == "SC") {
                        if (charAt(position + 2) == 'H') {
                            if (slice(position + 3, position + 5) in listOf("OO", "ER", "EN", "UY", "ED", "EM")) {
                                if (slice(position + 3, position + 5) in listOf("ER", "EN")) {
                                    nextPrimary = "X"
                                    nextSecondary = "SK"
                                } else {
                                    nextPrimary = "SK"
                                }
                            } else {
                                if (position == sIdx && charAt(sIdx + 3) !in VOWELS && charAt(sIdx + 3) != 'W') {
                                    nextPrimary = "X"
                                    nextSecondary = "S"
                                } else {
                                    nextPrimary = "X"
                                }
                            }
                        } else if (charAt(position + 2) in listOf('I', 'E', 'Y')) {
                            nextPrimary = "S"
                        } else {
                            nextPrimary = "SK"
                        }
                        advance = 3
                    } else if (position == eIdx && slice(position - 2, position) in listOf("AI", "OI")) {
                        nextPrimary = ""
                        nextSecondary = "S"
                        advance = 1
                    } else {
                        nextPrimary = "S"
                        advance = if (charAt(position + 1) in listOf('S', 'Z')) 2 else 1
                    }
                }
                character == 'T' -> {
                    val sIdx = word.start_index
                    if (slice(position, position + 4) == "TION") {
                        nextPrimary = "X"
                        advance = 3
                    } else if (slice(position, position + 3) in listOf("TIA", "TCH")) {
                        nextPrimary = "X"
                        advance = 3
                    } else if (slice(position, position + 2) == "TH" || slice(position, position + 3) == "TTH") {
                        if (slice(position + 2, position + 4) in listOf("OM", "AM") ||
                            slice(sIdx, sIdx + 4) in listOf("VON ", "VAN ") ||
                            slice(sIdx, sIdx + 3) == "SCH"
                        ) {
                            nextPrimary = "T"
                        } else {
                            nextPrimary = "0"
                            nextSecondary = "T"
                        }
                        advance = 2
                    } else if (charAt(position + 1) in listOf('T', 'D')) {
                        nextPrimary = "T"
                        advance = 2
                    } else {
                        nextPrimary = "T"
                        advance = 1
                    }
                }
                character == 'V' -> {
                    nextPrimary = "F"
                    advance = if (charAt(position + 1) == 'V') 2 else 1
                }
                character == 'W' -> {
                    val sIdx = word.start_index
                    val eIdx = word.end_index
                    if (slice(position, position + 2) == "WR") {
                        nextPrimary = "R"
                        advance = 2
                    } else if (position == sIdx && (charAt(position + 1) in VOWELS || slice(position, position + 2) == "WH")) {
                        if (charAt(position + 1) in VOWELS) {
                            nextPrimary = "A"
                            nextSecondary = "F"
                        } else {
                            nextPrimary = "A"
                        }
                        advance = 1
                    } else if (
                        (position == eIdx && charAt(position - 1) in VOWELS) ||
                        slice(position - 1, position + 4) in listOf("EWSKI", "EWSKY", "OWSKI", "OWSKY") ||
                        slice(sIdx, sIdx + 3) == "SCH"
                    ) {
                        nextPrimary = ""
                        nextSecondary = "F"
                        advance = 1
                    } else if (slice(position, position + 4) in listOf("WICZ", "WITZ")) {
                        nextPrimary = "TS"
                        nextSecondary = "FX"
                        advance = 4
                    } else {
                        advance = 1
                    }
                }
                character == 'X' -> {
                    val eIdx = word.end_index
                    if (!(position == eIdx &&
                            (slice(position - 3, position) in listOf("IAU", "EAU") ||
                                slice(position - 2, position) in listOf("AU", "OU")))
                    ) {
                        nextPrimary = "KS"
                    }
                    advance = if (charAt(position + 1) in listOf('C', 'X')) 2 else 1
                }
                character == 'Z' -> {
                    val sIdx = word.start_index
                    if (charAt(position + 1) == 'H') {
                        nextPrimary = "J"
                    } else if (
                        slice(position + 1, position + 3) in listOf("ZO", "ZI", "ZA") ||
                        (word.isSlavoGermanic && position > sIdx && charAt(position - 1) != 'T')
                    ) {
                        nextPrimary = "S"
                        nextSecondary = "TS"
                    } else {
                        nextPrimary = "S"
                    }
                    advance = if (charAt(position + 1) in listOf('Z', 'H')) 2 else 1
                }
                else -> {
                    advance = 1
                }
            }

            if (nextPrimary != null) {
                primaryPhone.append(nextPrimary)
                if (nextSecondary != null) {
                    secondaryPhone.append(nextSecondary)
                } else {
                    secondaryPhone.append(nextPrimary)
                }
            } else if (nextSecondary != null) {
                secondaryPhone.append(nextSecondary)
            }

            position += advance
        }

        var p = primaryPhone.toString()
        var a = secondaryPhone.toString()

        if (p.length > maxCodeLen) p = p.substring(0, maxCodeLen)
        if (a.length > maxCodeLen) a = a.substring(0, maxCodeLen)
        if (p == a) a = ""

        return Result(p, a)
    }
}
