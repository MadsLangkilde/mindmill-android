package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject

/** A scope from `hello`: part of a [project] (a Mac older than projects sends none), or the project-wide one when [wide]. */
data class Scope(
    val id: String,
    val title: String,
    val project: String? = null,
    val projectTitle: String? = null,
    val wide: Boolean = false,
) {
    companion object {
        fun parse(o: JsonObject): Scope? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return Scope(id, o.string("title") ?: id, o.text("project")?.takeIf { it.isNotBlank() }, o.string("projectTitle"), o.bool("wide") == true)
        }
    }
}

/** One project's scopes for a picker: the project-wide scope first. [title] is null for scopes of no project. */
data class ScopeGroup(val project: String?, val title: String?, val scopes: List<Scope>)

object Scopes {
    /** The Mac's scopes by project, projects in the order they first appear, each with its project-wide scope first. */
    fun grouped(scopes: List<Scope>): List<ScopeGroup> =
        scopes.groupBy { it.project }.map { (project, list) ->
            val title = project?.let { p ->
                list.firstNotNullOfOrNull { s -> s.projectTitle?.takeIf { t -> t.isNotBlank() } } ?: list.firstOrNull { s -> s.wide }?.title ?: p
            }
            ScopeGroup(project, title, list.sortedByDescending { it.wide })
        }

    /**
     * The scope the phone works in: its own choice while the Mac still has it, else the Mac's current one. Without a
     * list from the Mac (not connected yet), the choice as it is.
     */
    fun resolve(chosen: String?, macCurrent: String?, scopes: List<Scope>?): String? = when {
        scopes == null -> chosen ?: macCurrent
        chosen != null && scopes.any { it.id == chosen } -> chosen
        macCurrent != null && (scopes.isEmpty() || scopes.any { it.id == macCurrent }) -> macCurrent
        else -> scopes.firstOrNull()?.id ?: chosen
    }

    /** How a scope reads on its own, outside its group ("Acme Go · Android"). */
    fun label(s: Scope): String =
        if (s.projectTitle.isNullOrBlank() || s.projectTitle == s.title) s.title else "${s.projectTitle} · ${s.title}"
}
