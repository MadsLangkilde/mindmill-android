package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject

/** A skill or command the session's AI can use, as `skills.list` gives it. */
data class SkillInfo(
    val name: String,
    val command: String,
    val description: String = "",
    /** plugin, user, project or command. */
    val source: String = "",
    val plugin: String? = null,
    /** user: in every project; project: only in [scope]'s repository. Set for the user's own skills. */
    val location: String? = null,
    val scope: String? = null,
    val editable: Boolean = false,
) {
    /** The user's own skill: one they can open, change and delete. */
    val isOwn: Boolean get() = location == "user" || location == "project" || source == "user" || source == "project"

    /** Where it comes from, in a word or two: the plugin's name, Mine, This project or Command. */
    val sourceLabel: String
        get() = when {
            location == "project" || source == "project" -> "This project"
            location == "user" || source == "user" -> "Mine"
            source == "command" -> "Command"
            else -> plugin?.takeIf { it.isNotBlank() } ?: command.removePrefix("/").substringBefore(':', "").ifBlank { "Plugin" }
        }

    /** What the user typed after the slash is matched against: the command, and the part after `plugin:`. */
    internal val bare: String get() = command.removePrefix("/").lowercase()
    internal val tail: String get() = bare.substringAfter(':')

    companion object {
        fun parse(o: JsonObject): SkillInfo? {
            val name = o.string("name")?.takeIf { it.isNotBlank() }
            val command = o.string("command")?.takeIf { it.isNotBlank() } ?: name?.let { "/$it" } ?: return null
            return SkillInfo(
                name = name ?: command.removePrefix("/"),
                command = if (command.startsWith("/")) command else "/$command",
                description = o.string("description").orEmpty().trim(),
                source = o.string("source").orEmpty(),
                plugin = o.string("plugin")?.takeIf { it.isNotBlank() },
                location = o.string("location")?.takeIf { it.isNotBlank() },
                scope = o.string("scope")?.takeIf { it.isNotBlank() },
                editable = o.bool("editable") == true,
            )
        }

        fun list(o: JsonObject?): List<SkillInfo> = o.objects("skills").mapNotNull(::parse).distinctBy { it.command }
    }
}

/** The slash list over a session's message box: what to offer for what the user typed. */
object SlashSkills {
    /** What follows the slash while the text is a command being typed (starts with `/`, no space yet); else null. */
    fun query(text: String): String? {
        if (!text.startsWith("/")) return null
        if (text.any { it.isWhitespace() }) return null
        return text.substring(1)
    }

    /**
     * The skills matching [query]: exact matches first, then those that start with it (the command, or the part after
     * `plugin:`), then those that contain it, each group shortest first. An empty query lists them all as given.
     */
    fun matches(skills: List<SkillInfo>, query: String): List<SkillInfo> {
        val q = query.trim().lowercase().removePrefix("/")
        if (q.isEmpty()) return skills
        return skills.mapNotNull { s -> rank(s, q)?.let { it to s } }
            .sortedWith(compareBy<Pair<Int, SkillInfo>> { it.first }.thenBy { it.second.tail.length }.thenBy { it.second.bare })
            .map { it.second }
    }

    private fun rank(s: SkillInfo, q: String): Int? = when {
        s.bare == q || s.tail == q -> 0
        s.bare.startsWith(q) || s.tail.startsWith(q) -> 1
        q in s.bare -> 2
        else -> null
    }

    /** What picking [skill] puts in the message box. */
    fun insert(skill: SkillInfo): String = skill.command + " "
}

/** The skills screen's groups, in order; an empty group is left out. */
object SkillGroups {
    data class Group(val id: String, val title: String, val skills: List<SkillInfo>)

    fun of(skills: List<SkillInfo>): List<Group> {
        val mine = skills.filter { it.location == "user" || (it.location == null && it.source == "user") }
        val project = skills.filter { it.location == "project" || (it.location == null && it.source == "project") }
        val commands = skills.filter { it.source == "command" && it !in mine && it !in project }
        val plugins = skills.filter { it !in mine && it !in project && it !in commands }
        return listOf(
            Group("mine", "Mine", mine), Group("project", "This project", project),
            Group("plugins", "From plugins", plugins), Group("commands", "Commands", commands),
        ).filter { it.skills.isNotEmpty() }.map { g -> g.copy(skills = g.skills.sortedBy { it.command.lowercase() }) }
    }
}

data class PluginInfo(
    val id: String,
    val name: String,
    val marketplace: String = "",
    val version: String = "",
    val scope: String = "",
    val enabled: Boolean = true,
    val description: String = "",
) {
    companion object {
        fun parse(o: JsonObject): PluginInfo? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return PluginInfo(
                id, o.string("name")?.takeIf { it.isNotBlank() } ?: id.substringBefore('@'), o.string("marketplace").orEmpty(),
                o.text("version").orEmpty(), o.string("scope").orEmpty(), o.bool("enabled") ?: true, o.string("description").orEmpty(),
            )
        }
    }
}

data class AvailablePlugin(
    val id: String,
    val name: String,
    val marketplace: String = "",
    val description: String = "",
    val installs: Long? = null,
) {
    companion object {
        fun parse(o: JsonObject): AvailablePlugin? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return AvailablePlugin(
                id, o.string("name")?.takeIf { it.isNotBlank() } ?: id.substringBefore('@'), o.string("marketplace").orEmpty(),
                o.string("description").orEmpty(), o.long("installs"),
            )
        }
    }
}

/** `plugins.list`: what is installed, and what the marketplaces offer that isn't. */
data class Plugins(val installed: List<PluginInfo>, val available: List<AvailablePlugin>) {
    companion object {
        fun parse(o: JsonObject): Plugins {
            val installed = o.objects("plugins").mapNotNull(PluginInfo::parse)
            val ids = installed.map { it.id }.toSet()
            return Plugins(installed, o.objects("available").mapNotNull(AvailablePlugin::parse).filter { it.id !in ids })
        }
    }
}

data class McpServer(
    val name: String,
    val target: String = "",
    /** connected, needsAuth, failed or unknown. */
    val status: String = "unknown",
    /** user, claude.ai or plugin. */
    val origin: String = "",
    val removable: Boolean = false,
) {
    val statusTitle: String
        get() = when (status) {
            "connected" -> "Connected"
            "needsAuth" -> "Needs sign-in"
            "failed" -> "Failed"
            else -> "Unknown"
        }

    val originTitle: String
        get() = when (origin) {
            "user" -> "Added by you"
            "claude.ai" -> "Connector"
            "plugin" -> "From a plugin"
            else -> origin
        }

    companion object {
        fun parse(o: JsonObject): McpServer? {
            val name = o.string("name")?.takeIf { it.isNotBlank() } ?: return null
            return McpServer(name, o.string("target").orEmpty(), o.string("status") ?: "unknown", o.string("origin").orEmpty(), o.bool("removable") == true)
        }
    }
}

data class McpServers(val servers: List<McpServer>, val connectorsURL: String?) {
    companion object {
        fun parse(o: JsonObject) = McpServers(
            o.objects("servers").mapNotNull(McpServer::parse),
            o.string("connectorsURL")?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
        )

        /** A name the Mac can add a server under, and an http(s) address: else what is wrong. */
        fun problem(name: String, url: String): String? = when {
            name.isBlank() -> "Give it a name."
            name.any { it.isWhitespace() } -> "The name can't have spaces."
            !(url.trim().startsWith("https://") || url.trim().startsWith("http://")) -> "The address starts with https://."
            else -> null
        }
    }
}
