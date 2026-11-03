package dev.spookcue.util

object TokenSetRatio {

    // High-frequency functional tokens in Double Metaphone:
    // "0" (the, they), "A" (a, an, I, who, why), "AN" (and, in, on, when), "T" (to, it, do),
    // "AT" (that, at, what), "AR" (are, our, there, where), "AS" (is, as, us, was),
    // "H" (he, how), "X" (she), "P" (be, by)
    private val FUNCTIONAL_TOKENS = setOf("0", "A", "AN", "T", "AT", "AR", "AS", "H", "X", "P")

    private fun levenshtein(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)
        for (i in 1..m) {
            curr[0] = i
            val c1 = s1[i - 1]
            for (j in 1..n) {
                val cost = if (c1 == s2[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[n]
    }

    /**
     * Determines phonetic similarity between two Metaphone tokens.
     * Returns:
     * - 1.0f for exact match
     * - 0.80f for near match (edit distance 1 on tokens where maxLen >= 3, e.g. HKNS vs HKN, KT vs KRT)
     * - 0.65f for longer near match (edit distance 2 on tokens where minLen >= 5, e.g. SKRFSS vs SKRFS)
     * - 0.0f otherwise
     *
     * Functional tokens (0, A, T, etc.) NEVER fuzzy match.
     */
    fun tokenSimilarity(a: String, b: String): Float {
        if (a == b) return 1.0f
        if (a in FUNCTIONAL_TOKENS || b in FUNCTIONAL_TOKENS) return 0.0f

        val lenA = a.length
        val lenB = b.length
        val minLen = minOf(lenA, lenB)
        val maxLen = maxOf(lenA, lenB)

        if (minLen < 2) return 0.0f

        val dist = levenshtein(a, b)
        if (dist == 1 && maxLen >= 3) {
            return 0.80f
        }
        if (dist == 2 && minLen >= 5) {
            return 0.65f
        }
        return 0.0f
    }

    /**
     * Calculates the Token Set Ratio between two lists of phonetic tokens.
     * Incorporates information-theoretic weighting and fuzzy token matching
     * so acoustic misrecognitions and minor phonetic divergences do not break alignment.
     *
     * Returns a float value between 0.0 and 1.0.
     */
    fun calculate(tokensA: List<String>, tokensB: List<String>): Float {
        val setA = tokensA.filter { it.isNotBlank() }.toSet()
        val setB = tokensB.filter { it.isNotBlank() }.toSet()

        if (setA.isEmpty() || setB.isEmpty()) return 0f

        // 1. Identify exact matches
        val exactMatches = setA.intersect(setB)
        val remainingA = (setA - exactMatches).toMutableList()
        val remainingB = (setB - exactMatches).toMutableList()

        // 2. Identify fuzzy matches greedily between remaining content tokens
        data class FuzzyCandidate(val a: String, val b: String, val score: Float)
        val candidates = mutableListOf<FuzzyCandidate>()
        for (a in remainingA) {
            for (b in remainingB) {
                val sim = tokenSimilarity(a, b)
                if (sim > 0f) {
                    candidates.add(FuzzyCandidate(a, b, sim))
                }
            }
        }
        candidates.sortByDescending { it.score }

        val pairedA = mutableSetOf<String>()
        val pairedB = mutableSetOf<String>()
        val fuzzyPairs = mutableListOf<FuzzyCandidate>()

        for (cand in candidates) {
            if (cand.a !in pairedA && cand.b !in pairedB) {
                pairedA.add(cand.a)
                pairedB.add(cand.b)
                fuzzyPairs.add(cand)
            }
        }

        val totalMatchedCount = exactMatches.size + fuzzyPairs.size
        if (totalMatchedCount == 0) return 0f

        // Distinctive content tokens score
        var contentMatchedScore = 0f
        for (t in exactMatches) {
            if (t !in FUNCTIONAL_TOKENS) contentMatchedScore += 1.0f
        }
        for (f in fuzzyPairs) {
            contentMatchedScore += f.score
        }

        // A valid match must have at least 1 distinctive content token (or strong fuzzy content score),
        // or at least 4 matching tokens if all are functional.
        if (contentMatchedScore < 0.70f && totalMatchedCount < 4) {
            return 0f
        }

        // If BOTH sets are long clauses (>= 4 tokens each), require at least 3 matched tokens
        // (exact or fuzzy) so long clauses don't match on coincidental single words.
        if (setA.size >= 4 && setB.size >= 4 && totalMatchedCount < 3) {
            return 0.30f
        }

        // Weight content tokens at 1.0f (or fuzzy score * 1.0f) and ubiquitous functional tokens at 0.35f
        var weightInter = 0f
        for (t in exactMatches) {
            weightInter += if (t in FUNCTIONAL_TOKENS) 0.35f else 1.0f
        }
        for (f in fuzzyPairs) {
            weightInter += f.score * 1.0f
        }

        var weightA = 0f
        for (t in setA) {
            weightA += if (t in FUNCTIONAL_TOKENS) 0.35f else 1.0f
        }

        var weightB = 0f
        for (t in setB) {
            weightB += if (t in FUNCTIONAL_TOKENS) 0.35f else 1.0f
        }

        val scoreA = (2f * weightInter) / (weightInter + weightA)
        val scoreB = (2f * weightInter) / (weightInter + weightB)
        val scoreAB = (2f * weightInter) / (weightA + weightB)

        return maxOf(scoreA, scoreB, scoreAB).coerceIn(0f, 1.0f)
    }
}
