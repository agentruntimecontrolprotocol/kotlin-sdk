package dev.arcp.lease

/** `model.use` lease patterns with segment-aware glob matching. */
public data class ModelUseLease(
    val patterns: List<String>,
) {
    init {
        require(patterns.all { it.isNotBlank() }) { "model.use pattern must not be blank" }
    }

    /**
     * Glob patterns compiled to [Regex] exactly once per lease. Model-use
     * authorization runs on hot operation boundaries (#84); recompiling the
     * same patterns on every [allows]/[subset] check is avoidable allocation
     * and regex-compilation work. Computed lazily so constructing a lease
     * that is never matched stays cheap.
     */
    private val compiled: Map<String, Regex> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        patterns.associateWith(::globToRegex)
    }

    /** Reuses the cached [Regex] for [pattern], compiling on demand for non-lease patterns. */
    internal fun regexFor(pattern: String): Regex = compiled[pattern] ?: globToRegex(pattern)

    /** Returns true when [modelId] is allowed by any pattern. */
    public fun allows(modelId: String): Boolean = patterns.any { regexFor(it).matches(modelId) }

    public companion object {
        /** Returns true when every child pattern is covered by the parent. */
        public fun subset(
            parent: ModelUseLease,
            child: ModelUseLease,
        ): Boolean = child.patterns.all { childPattern ->
            parent.patterns.any { parentPattern ->
                parentPattern == "**" ||
                    parentPattern == childPattern ||
                    (
                        parentPattern.endsWith("/**") &&
                            childPattern.startsWith(parentPattern.dropLast(2))
                    ) ||
                    (
                        parentPattern.endsWith("/*") &&
                            !childPattern.contains("*") &&
                            parent.regexFor(parentPattern).matches(childPattern)
                    )
            }
        }

        private fun globToRegex(pattern: String): Regex {
            val out = StringBuilder("^")
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern.startsWith("**", i) -> {
                        out.append(".*")
                        i += 2
                    }
                    pattern[i] == '*' -> {
                        out.append("[^/]*")
                        i += 1
                    }
                    else -> {
                        out.append(Regex.escape(pattern[i].toString()))
                        i += 1
                    }
                }
            }
            out.append("$")
            return Regex(out.toString())
        }
    }
}
