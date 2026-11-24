package dk.langkilde.mindmill.remote

/** A session's transcript as the chat draws it: messages, and the steps Claude took between them folded into one row. */
sealed interface ChatRow {
    val key: String

    data class Message(val index: Int, val item: ChatItem) : ChatRow {
        override val key get() = "m$index"
    }

    /** Consecutive tool calls (and the app's notes among them) with no user or assistant message in between. */
    data class Steps(val first: Int, val items: List<ChatItem>) : ChatRow {
        override val key get() = "s$first"
        val count: Int get() = items.size
        val latest: String get() = items.lastOrNull()?.let { ToolSteps.step(it).title }.orEmpty()
    }
}

object ChatRows {
    private fun quiet(i: ChatItem) = i.role == "tool" || i.role == "status"

    /**
     * Folds each run of tool and status items into one [ChatRow.Steps]; a user, assistant or error message ends it.
     * A run of app notes alone (no tool call) stays as messages.
     */
    fun group(items: List<ChatItem>): List<ChatRow> {
        val rows = mutableListOf<ChatRow>()
        var i = 0
        while (i < items.size) {
            if (!quiet(items[i])) {
                rows += ChatRow.Message(i, items[i])
                i++
                continue
            }
            var end = i
            while (end < items.size && quiet(items[end])) end++
            val run = items.subList(i, end)
            if (run.any { it.role == "tool" }) rows += ChatRow.Steps(i, run.toList())
            else run.forEachIndexed { k, item -> rows += ChatRow.Message(i + k, item) }
            i = end
        }
        return rows
    }
}

/**
 * A tool line in words, as the Mac's `ToolDescription`: `<tool>\t<summary>[\t<command>]`. A shell command keeps its
 * text as [Step.code] for whoever opens the steps.
 */
object ToolSteps {
    data class Step(val title: String, val code: String? = null)

    fun step(item: ChatItem): Step = if (item.role == "tool") step(item.text) else Step(item.text.trim())

    fun step(text: String): Step {
        val parts = text.split("\t")
        val name = parts.firstOrNull().orEmpty()
        val summary = parts.getOrNull(1).orEmpty()
        val code = if (parts.size > 2) parts.drop(2).joinToString("\t") else null
        if (name == "Bash") {
            val command = code ?: summary
            val described = code != null && summary != oneLine(command)
            return Step(if (described && summary.isNotBlank()) summary else bash(command), command)
        }
        return Step(describe(name, summary))
    }

    private fun oneLine(raw: String): String {
        val one = raw.replace('\n', ' ').replace('\t', ' ').trim()
        return if (one.length > 160) one.take(157) + "…" else one
    }

    fun describe(name: String, s: String): String = when (name) {
        "Read" -> if (s.isEmpty()) "Read a file" else "Read $s"
        "Write" -> if (s.isEmpty()) "Wrote a file" else "Wrote $s"
        "Edit", "MultiEdit", "NotebookEdit" -> if (s.isEmpty()) "Edited a file" else "Edited $s"
        "Grep" -> if (s.isEmpty()) "Searched the code" else "Searched the code for “$s”"
        "Glob" -> if (s.isEmpty()) "Looked for files" else "Looked for files: $s"
        "WebFetch" -> "Read " + (runCatching { java.net.URI(s).host }.getOrNull() ?: "a web page")
        "WebSearch" -> if (s.isEmpty()) "Searched the web" else "Searched the web for “$s”"
        "Agent", "Task" -> if (s.isEmpty()) "Asked a helper" else "Helper: $s"
        "Skill" -> if (s.isEmpty()) "Used a skill" else "Used the ${s.split(' ').first()} skill"
        "TodoWrite" -> "Updated its to-do list"
        "ToolSearch" -> "Loaded tools"
        else -> if (name.startsWith("mcp__")) {
            val bits = name.split('_').filter { it.isNotEmpty() }
            val server = bits.getOrNull(1) ?: "a connector"
            val tool = bits.drop(2).joinToString(" ")
            "$server: $tool" + if (s.isEmpty()) "" else " “$s”"
        } else if (s.isEmpty()) name else "$name: $s"
    }

    private fun quoted(command: String): String? {
        val a = command.indexOfFirst { it == '"' || it == '\'' }
        if (a < 0) return null
        val rest = command.substring(a + 1)
        val b = rest.indexOf(command[a])
        return if (b < 0) rest.take(80) else rest.substring(0, b)
    }

    /** The first command of a shell line, in words. */
    fun bash(command: String): String {
        val segments = command.split("&&").flatMap { it.split(";") }.map { it.trim() }
        val c = segments.firstOrNull { it.isNotEmpty() && !it.startsWith("cd ") } ?: command
        val words = c.split(' ', '\n').filter { it.isNotEmpty() }
        val tool = words.firstOrNull()?.split('/')?.lastOrNull().orEmpty()
        val sub = words.getOrNull(1).orEmpty()
        val third = words.getOrNull(2).orEmpty()
        val q = quoted(c)
        return when (tool) {
            "kb" -> when {
                sub == "crew" && third == "send" -> "Messaged a session" + (q?.let { ": “${it.take(70)}${if (it.length > 70) "…" else ""}”" } ?: "")
                sub == "crew" && third == "status" -> "Checked the sessions"
                sub == "crew" && third == "start" -> "Started a session"
                sub == "crew" && third == "stop" -> "Stopped a session"
                sub == "show" -> "Read ${third.ifEmpty { "an entry" }} in the knowledge base"
                sub == "search" -> "Searched the knowledge base" + (q?.let { " for “$it”" } ?: "")
                sub == "list" -> "Listed the knowledge base"
                sub == "new" -> "Wrote a new ${third.ifEmpty { "entry" }}"
                sub == "inbox" -> if (third == "add") "Proposed it in the inbox" else "Worked on the inbox"
                sub == "tracker" -> "Looked in Azure DevOps" + (q?.let { " for “$it”" } ?: "")
                sub == "backlog" && third == "create" -> "Created the work item"
                sub == "followup" -> "Closed a follow-up"
                else -> "Used the knowledge base ($sub)"
            }
            "git" -> when (sub) {
                "status" -> "Checked what changed"
                "diff" -> "Looked at the changes"
                "log", "show" -> "Read the history"
                "worktree" -> "Set up a worktree"
                "commit" -> "Committed"
                "push" -> "Pushed"
                "fetch", "pull" -> "Fetched from the server"
                "checkout", "switch" -> "Switched branch"
                "branch" -> "Looked at branches"
                else -> "Git: $sub"
            }
            "gradlew" -> {
                val tasks = words.drop(1).filter { !it.startsWith("-") }
                when {
                    tasks.any { "test" in it || "Test" in it } -> "Ran the tests"
                    tasks.any { "assemble" in it.lowercase() || it == "build" } -> "Built the app"
                    else -> "Ran Gradle" + if (tasks.isEmpty()) "" else ": " + tasks.take(3).joinToString(", ")
                }
            }
            "swift" -> if (sub == "test") "Ran the tests" else "Built with Swift"
            "grep", "rg" -> "Searched the code" + (q?.let { " for “$it”" } ?: "")
            "cat", "head", "tail", "sed", "less" -> "Read " + (words.lastOrNull()?.substringAfterLast('/') ?: "a file")
            "ls", "find", "tree" -> "Listed files"
            "gh", "az" -> "Called " + if (tool == "gh") "GitHub" else "Azure DevOps"
            "curl" -> "Called a web service"
            "adb" -> "Talked to the phone"
            "python3", "python" -> "Ran a script"
            "mkdir", "cp", "mv", "rm" -> "Moved files around"
            else -> if (tool.isEmpty()) "Ran a command" else "Ran $tool"
        }
    }
}
