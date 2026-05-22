package dev.arcp.lease

/** `model.use` lease patterns with segment-aware glob matching. */
public data class ModelUseLease(
    val patterns: List<String>,
) {
    init {
        require(patterns.all { it.isNotBlank() }) { "model.use pattern must not be blank" }
    }

    /** Returns true when [modelId] is allowed by any pattern. */
    public fun allows(modelId: String): Boolean = patterns.any { globToRegex(it).matches(modelId) }

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
                            globToRegex(parentPattern).matches(childPattern)
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
