package dev.spookcue.util

object TokenSetRatio {

    /**
     * Calculates the Token Set Ratio between two lists of phonetic tokens.
     * Returns a float value between 0.0 and 1.0.
     */
    fun calculate(tokensA: List<String>, tokensB: List<String>): Float {
        val setA = tokensA.filter { it.isNotBlank() }.toSet()
        val setB = tokensB.filter { it.isNotBlank() }.toSet()

        if (setA.isEmpty() || setB.isEmpty()) return 0f

        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size

        if (union == 0) return 0f
        return intersection.toFloat() / union.toFloat()
    }
}
