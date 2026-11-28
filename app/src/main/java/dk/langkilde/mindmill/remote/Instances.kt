package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject

/** The Mindmill instance open on the Mac, as `hello` names it; a Mac older than instances sends none. */
data class InstanceRef(val id: String, val name: String) {
    companion object {
        fun parse(o: JsonObject?): InstanceRef? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return InstanceRef(id, o.string("name")?.takeIf { it.isNotBlank() } ?: id)
        }
    }
}

/**
 * A knowledge base of its own on the Mac, with its own projects. [path] starts with `~` for the home folder; [exists]
 * is false when its folder is gone; [projects] is how many projects it has.
 */
data class InstanceInfo(val id: String, val name: String, val path: String, val exists: Boolean = true, val projects: Int? = null) {
    companion object {
        fun parse(o: JsonObject): InstanceInfo? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return InstanceInfo(id, o.string("name")?.takeIf { it.isNotBlank() } ?: id, o.text("path").orEmpty(), o.bool("exists") ?: true, o.int("projects"))
        }
    }
}

/** What `instances.list` answers. */
data class InstanceList(val current: String?, val instances: List<InstanceInfo>) {
    val open: InstanceInfo? get() = instances.firstOrNull { it.id == current }

    fun find(id: String?): InstanceInfo? = instances.firstOrNull { it.id == id }

    /** Choosing where a new project goes only makes sense with somewhere else to put it. */
    val hasChoice: Boolean get() = instances.size > 1

    companion object {
        fun parse(o: JsonObject): InstanceList =
            InstanceList(o.text("current")?.takeIf { it.isNotBlank() }, o.objects("instances").mapNotNull(InstanceInfo::parse))

        /** `instance.create`: a new instance called [name] in the Mac folder [path], opened at once with [open]. */
        fun createParams(name: String, path: String, open: Boolean): JsonObject =
            jsonOf("name" to name.trim(), "path" to path, "open" to open.takeIf { it })

        /** Why the new-instance form can't be sent yet, or null when it can. */
        fun createProblem(name: String, path: String?): String? = when {
            name.isBlank() -> "Give the instance a name."
            path.isNullOrBlank() -> "Choose a folder on the Mac for it."
            else -> null
        }
    }
}

/** What `instance.switch` and `instance.create` answer: [switching] is true when Mindmill now restarts on the Mac. */
data class InstanceSwitchAnswer(val id: String?, val switching: Boolean) {
    companion object {
        fun parse(o: JsonObject) = InstanceSwitchAnswer(o.text("id")?.takeIf { it.isNotBlank() }, o.bool("switching") == true)
    }
}

/** The phone asked the Mac to open another instance and waits for Mindmill to come back with it. */
data class InstanceSwitch(val id: String?, val name: String, val sinceMillis: Long)
