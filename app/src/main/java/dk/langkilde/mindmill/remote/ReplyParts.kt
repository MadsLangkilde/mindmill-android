package dk.langkilde.mindmill.remote

/** One option of a ```choices block: its text without "(Recommended)", and whether Claude recommends it. */
data class ChoiceOption(val label: String, val recommended: Boolean)

/** A Claude reply cut into what the phone draws differently: Markdown text, and the options to answer with. */
sealed interface ReplyPart {
    data class Text(val markdown: String) : ReplyPart

    /** A ```choices block (```choices multi: several answers), or a ```strategy block of strategy slugs. */
    data class Choices(val multi: Boolean, val options: List<ChoiceOption>, val strategies: Boolean = false) : ReplyPart
}

/**
 * Claude asks the user with a fenced ```choices block, one option per line, the recommended one first and marked
 * "(Recommended)"; the Mac shows them as buttons (`MarkdownBlocks` in the kb repo). This is the phone's reading of
 * the same text, as `session.get` sends it.
 */
object ReplyParts {
    private val fence = Regex("^\\s*```")

    fun parse(markdown: String): List<ReplyPart> {
        val parts = mutableListOf<ReplyPart>()
        val text = mutableListOf<String>()
        fun flush() {
            val t = text.joinToString("\n").trim('\n')
            if (t.isNotBlank()) parts += ReplyPart.Text(t)
            text.clear()
        }
        val lines = markdown.replace("\r\n", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!fence.containsMatchIn(line)) { text += line; i++; continue }
            val words = line.trim().removePrefix("```").trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var end = i + 1
            while (end < lines.size && !fence.containsMatchIn(lines[end])) end++
            val body = lines.subList(i + 1, minOf(end, lines.size))
            val kind = words.firstOrNull()
            if (kind == "choices" || kind == "strategy" || kind == "strategies") {
                val options = body.map(::option).filter { it.label.isNotEmpty() }
                if (options.isNotEmpty()) {
                    flush()
                    parts += ReplyPart.Choices(multi = kind == "choices" && "multi" in words, options = options, strategies = kind != "choices")
                }
            } else {
                text += lines.subList(i, minOf(end + 1, lines.size))
            }
            i = end + 1
        }
        flush()
        return parts
    }

    /**
     * The options of a reply that ends by asking: its last choices block, with only plain paragraphs after it (as the
     * Mac's `trailingChoices`). Null when the reply doesn't end with one.
     */
    fun trailing(markdown: String): ReplyPart.Choices? {
        val parts = parse(markdown)
        val at = parts.indexOfLast { it is ReplyPart.Choices }
        if (at < 0) return null
        val after = parts.drop(at + 1).filterIsInstance<ReplyPart.Text>().flatMap { it.markdown.lines() }
        return if (after.all(::isPlainLine)) parts[at] as ReplyPart.Choices else null
    }

    private fun isPlainLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        return !(t.startsWith("#") || t.startsWith("```") || t.startsWith("|") || t.startsWith(">") ||
            t == "---" || t == "***" || t == "___" || Regex("^([-*+]|\\d{1,3}[.)])\\s").containsMatchIn(t))
    }

    fun option(line: String): ChoiceOption {
        val clean = cleanOption(line)
        val recommended = clean.contains("(recommended)", ignoreCase = true)
        val label = clean.replace(Regex("\\(recommended\\)", RegexOption.IGNORE_CASE), "").replace(Regex("\\s{2,}"), " ").trim()
        return ChoiceOption(label, recommended)
    }

    /** "- [ ] a", "* a", "• a", "1. a" and "2) a" are all "a". */
    fun cleanOption(line: String): String {
        var t = line.trim()
        for (prefix in listOf("- [ ] ", "- ", "* ", "• ")) {
            if (t.startsWith(prefix)) { t = t.removePrefix(prefix); break }
        }
        val digits = t.takeWhile { it.isDigit() }
        if (digits.isNotEmpty() && t.length > digits.length && (t[digits.length] == '.' || t[digits.length] == ')')) {
            t = t.substring(digits.length + 1).trim()
        }
        return t
    }

    /** What a tap sends: the option itself; a strategy as the Mac words it. */
    fun answer(choices: ReplyPart.Choices, picked: List<ChoiceOption>): String = when {
        choices.strategies -> picked.firstOrNull()?.let { o ->
            if (o.label.equals("No strategy", ignoreCase = true)) "No strategy." else "Use the “${o.label}” strategy (${o.label})."
        } ?: "No strategy."
        else -> picked.joinToString(", ") { it.label }
    }

    /**
     * Which message's options can be answered now: the last reply, when it is the last thing in the session (steps
     * and app notes aside), ends with options, and the session isn't working. Older replies' options are history.
     */
    fun activeIndex(items: List<ChatItem>, busy: Boolean): Int? {
        if (busy) return null
        val at = items.indexOfLast { it.role != "tool" && it.role != "status" }
        if (at < 0) return null
        val last = items[at]
        if (last.role != "assistant" || last.pending) return null
        return at.takeIf { trailing(last.text) != null }
    }
}
