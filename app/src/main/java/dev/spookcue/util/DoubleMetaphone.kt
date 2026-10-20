package dev.spookcue.util

/**
 * Pure Kotlin port of Lawrence Philips' Double Metaphone phonetic algorithm.
 * Translates English words into phonetic keys for speech-to-text fuzzy matching.
 */
class DoubleMetaphone(private val maxCodeLen: Int = 4) {

    data class Result(val primary: String, val alternate: String)

    fun encode(value: String?): Result {
        if (value.isNullOrBlank()) return Result("", "")

        var clean = value.uppercase().trim()
        val primary = StringBuilder()
        val alternate = StringBuilder()

        // Handle initial silent characters
        val length = clean.length
        if (length < 1) return Result("", "")

        var current = 0

        // Handle special prefixes: GN, KN, PN, WR, PS
        if (clean.startsWith("GN") || clean.startsWith("KN") ||
            clean.startsWith("PN") || clean.startsWith("WR") ||
            clean.startsWith("PS")
        ) {
            current = 1
        }

        // Initial 'X' is pronounced as 'S'
        if (clean.startsWith("X")) {
            primary.append('S')
            alternate.append('S')
            current = 1
        }

        while ((primary.length < maxCodeLen || alternate.length < maxCodeLen) && current < length) {
            val ch = clean[current]

            when (ch) {
                'A', 'E', 'I', 'O', 'U', 'Y' -> {
                    if (current == 0) {
                        primary.append('A')
                        alternate.append('A')
                    }
                    current++
                }

                'B' -> {
                    primary.append('P')
                    alternate.append('P')
                    current += if (current + 1 < length && clean[current + 1] == 'B') 2 else 1
                }

                'C' -> {
                    // Various 'C' combinations
                    if (current > 1 && !isVowel(clean, current - 2) &&
                        clean.regionMatches(current - 1, "ACH", 0, 3) &&
                        (current + 2 >= length || (clean[current + 2] != 'I' && clean[current + 2] != 'E'))
                    ) {
                        primary.append('K')
                        alternate.append('K')
                        current += 2
                    } else if (clean.regionMatches(current, "CH", 0, 2)) {
                        primary.append('X')
                        alternate.append('X')
                        current += 2
                    } else if (clean.regionMatches(current, "CZ", 0, 2)) {
                        primary.append('S')
                        alternate.append('X')
                        current += 2
                    } else if (current + 1 < length && (clean[current + 1] == 'I' || clean[current + 1] == 'E' || clean[current + 1] == 'Y')) {
                        primary.append('S')
                        alternate.append('S')
                        current += 2
                    } else if (clean.regionMatches(current, "CK", 0, 2)) {
                        primary.append('K')
                        alternate.append('K')
                        current += 2
                    } else {
                        primary.append('K')
                        alternate.append('K')
                        current += if (current + 1 < length && clean[current + 1] == 'C') 2 else 1
                    }
                }

                'D' -> {
                    if (clean.regionMatches(current, "DG", 0, 2)) {
                        if (current + 2 < length && (clean[current + 2] == 'I' || clean[current + 2] == 'E' || clean[current + 2] == 'Y')) {
                            primary.append('J')
                            alternate.append('J')
                            current += 3
                        } else {
                            primary.append("TK")
                            alternate.append("TK")
                            current += 2
                        }
                    } else if (clean.regionMatches(current, "DT", 0, 2) || clean.regionMatches(current, "DD", 0, 2)) {
                        primary.append('T')
                        alternate.append('T')
                        current += 2
                    } else {
                        primary.append('T')
                        alternate.append('T')
                        current++
                    }
                }

                'F' -> {
                    primary.append('F')
                    alternate.append('F')
                    current += if (current + 1 < length && clean[current + 1] == 'F') 2 else 1
                }

                'G' -> {
                    if (clean.regionMatches(current, "GH", 0, 2)) {
                        if (current > 0 && !isVowel(clean, current - 1)) {
                            primary.append('K')
                            alternate.append('K')
                            current += 2
                        } else if (current == 0) {
                            primary.append('K')
                            alternate.append('K')
                            current += 2
                        } else {
                            current += 2
                        }
                    } else if (clean.regionMatches(current, "GN", 0, 2)) {
                        primary.append('K')
                        alternate.append('N')
                        current += 2
                    } else if (current + 1 < length && (clean[current + 1] == 'I' || clean[current + 1] == 'E' || clean[current + 1] == 'Y')) {
                        primary.append('J')
                        alternate.append('K')
                        current += 2
                    } else {
                        primary.append('K')
                        alternate.append('K')
                        current += if (current + 1 < length && clean[current + 1] == 'G') 2 else 1
                    }
                }

                'H' -> {
                    if ((current == 0 || isVowel(clean, current - 1)) && isVowel(clean, current + 1)) {
                        primary.append('H')
                        alternate.append('H')
                        current += 2
                    } else {
                        current++
                    }
                }

                'J' -> {
                    primary.append('J')
                    alternate.append('A')
                    current += if (current + 1 < length && clean[current + 1] == 'J') 2 else 1
                }

                'K' -> {
                    primary.append('K')
                    alternate.append('K')
                    current += if (current + 1 < length && clean[current + 1] == 'K') 2 else 1
                }

                'L' -> {
                    primary.append('L')
                    alternate.append('L')
                    current += if (current + 1 < length && clean[current + 1] == 'L') 2 else 1
                }

                'M' -> {
                    primary.append('M')
                    alternate.append('M')
                    current += if (current + 1 < length && clean[current + 1] == 'M') 2 else 1
                }

                'N' -> {
                    primary.append('N')
                    alternate.append('N')
                    current += if (current + 1 < length && clean[current + 1] == 'N') 2 else 1
                }

                'P' -> {
                    if (clean.regionMatches(current, "PH", 0, 2)) {
                        primary.append('F')
                        alternate.append('F')
                        current += 2
                    } else {
                        primary.append('P')
                        alternate.append('P')
                        current += if (current + 1 < length && clean[current + 1] == 'P') 2 else 1
                    }
                }

                'Q' -> {
                    primary.append('K')
                    alternate.append('K')
                    current += if (current + 1 < length && clean[current + 1] == 'Q') 2 else 1
                }

                'R' -> {
                    primary.append('R')
                    alternate.append('R')
                    current += if (current + 1 < length && clean[current + 1] == 'R') 2 else 1
                }

                'S' -> {
                    if (clean.regionMatches(current, "SH", 0, 2)) {
                        primary.append('X')
                        alternate.append('X')
                        current += 2
                    } else if (clean.regionMatches(current, "SC", 0, 2)) {
                        if (current + 2 < length && (clean[current + 2] == 'I' || clean[current + 2] == 'E' || clean[current + 2] == 'Y')) {
                            primary.append('S')
                            alternate.append('S')
                            current += 3
                        } else {
                            primary.append("SK")
                            alternate.append("SK")
                            current += 3
                        }
                    } else {
                        primary.append('S')
                        alternate.append('S')
                        current += if (current + 1 < length && clean[current + 1] == 'S') 2 else 1
                    }
                }

                'T' -> {
                    if (clean.regionMatches(current, "TION", 0, 4) || clean.regionMatches(current, "TIA", 0, 3)) {
                        primary.append('X')
                        alternate.append('X')
                        current += 3
                    } else if (clean.regionMatches(current, "TH", 0, 2)) {
                        primary.append('0')
                        alternate.append('T')
                        current += 2
                    } else {
                        primary.append('T')
                        alternate.append('T')
                        current += if (current + 1 < length && clean[current + 1] == 'T') 2 else 1
                    }
                }

                'V' -> {
                    primary.append('F')
                    alternate.append('F')
                    current += if (current + 1 < length && clean[current + 1] == 'V') 2 else 1
                }

                'W' -> {
                    if (clean.regionMatches(current, "WR", 0, 2)) {
                        primary.append('R')
                        alternate.append('R')
                        current += 2
                    } else if (current == 0 && isVowel(clean, current + 1)) {
                        primary.append('A')
                        alternate.append('F')
                        current++
                    } else {
                        current++
                    }
                }

                'X' -> {
                    primary.append("KS")
                    alternate.append("KS")
                    current += if (current + 1 < length && clean[current + 1] == 'X') 2 else 1
                }

                'Z' -> {
                    primary.append('S')
                    alternate.append('S')
                    current += if (current + 1 < length && clean[current + 1] == 'Z') 2 else 1
                }

                else -> current++
            }
        }

        val p = if (primary.length > maxCodeLen) primary.substring(0, maxCodeLen) else primary.toString()
        val a = if (alternate.length > maxCodeLen) alternate.substring(0, maxCodeLen) else alternate.toString()
        return Result(p, a)
    }

    private fun isVowel(s: String, index: Int): Boolean {
        if (index < 0 || index >= s.length) return false
        return when (s[index]) {
            'A', 'E', 'I', 'O', 'U', 'Y' -> true
            else -> false
        }
    }
}
