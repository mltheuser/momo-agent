package codes.momo.agent.web

/**
 * The headings found in a page's text, at most [MAX_OUTLINE_HEADINGS] of them: as many heading levels
 * as fit, top level first. [omitted] says what was left out, if anything.
 */
internal data class Outline(val headings: List<Heading>, val omitted: String?) {

    /** [line] is 1-based; [text] is the heading line as written. */
    data class Heading(val line: Int, val level: Int, val text: String)
}

/** Best effort: a heading is a line opening with one to six `#` and a blank, outside fenced code blocks. */
internal fun outline(text: String): Outline {
    val headings = findHeadings(text)
    val levels = headings.map { it.level }.distinct().sorted()
    if (levels.isEmpty()) return Outline(emptyList(), null)

    val depth = levels.lastOrNull { level -> headings.count { it.level <= level } <= MAX_OUTLINE_HEADINGS }
    val shown = when (depth) {
        null -> headings.filter { it.level == levels.first() }.take(MAX_OUTLINE_HEADINGS)
        else -> headings.filter { it.level <= depth }
    }
    val omitted = headings.size - shown.size
    val deepest = "#".repeat(shown.maxOf { it.level })
    val note = when {
        omitted == 0 -> null
        depth == null -> "$omitted headings omitted: only the first $MAX_OUTLINE_HEADINGS $deepest headings are shown"
        else -> "$omitted headings deeper than $deepest omitted"
    }
    return Outline(shown, note)
}

private fun findHeadings(text: String): List<Outline.Heading> {
    var inFence = false
    return text.lineSequence().withIndex().mapNotNull { (index, line) ->
        if (FENCE.containsMatchIn(line)) inFence = !inFence
        if (inFence) return@mapNotNull null
        HEADING.find(line)?.let { Outline.Heading(index + 1, it.groupValues[1].length, line) }
    }.toList()
}

private val FENCE = Regex("^ {0,3}(```|~~~)")

private val HEADING = Regex("^(#{1,6})[ \t]")

private const val MAX_OUTLINE_HEADINGS: Int = 69
