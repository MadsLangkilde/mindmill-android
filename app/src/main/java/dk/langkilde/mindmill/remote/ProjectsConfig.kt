package dk.langkilde.mindmill.remote

import kotlinx.serialization.json.JsonObject

/** A folder on the Mac a project's code lives in. Paths start with `~` for the Mac user's home folder. */
data class ProjectLocation(val name: String, val path: String, val exists: Boolean = true, val repository: Boolean = false) {
    companion object {
        fun parse(o: JsonObject): ProjectLocation? {
            val path = o.text("path")?.takeIf { it.isNotBlank() } ?: return null
            return ProjectLocation(o.string("name") ?: lastSegment(path), path, o.bool("exists") ?: true, o.bool("repository") == true)
        }

        fun lastSegment(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
    }
}

/**
 * One scope of a project. [key] tells scopes apart on the phone, also new ones that have no [id] until the Mac saves
 * them. [paths] are the project's locations it works on, [includes] other scopes of the project it also shows,
 * [related] scopes whose knowledge it reads.
 */
data class ProjectScope(
    val key: String,
    val id: String?,
    val title: String,
    val wide: Boolean = false,
    val paths: List<String> = emptyList(),
    val remotes: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val related: List<String> = emptyList(),
    /** How many entries it holds, as the Mac counted them; they move to the project-wide scope when it is deleted. */
    val entries: Int? = null,
) {
    companion object {
        fun parse(o: JsonObject): ProjectScope? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return ProjectScope(
                key = keyOf(id), id = id, title = o.string("title") ?: id, wide = o.bool("wide") == true,
                paths = o.strings("paths"), remotes = o.strings("remotes"), includes = o.strings("includes"),
                related = o.strings("related"), entries = o.int("entries"),
            )
        }

        fun keyOf(id: String) = "id:$id"
    }
}

data class ProjectInfo(val id: String, val title: String, val locations: List<ProjectLocation>, val scopes: List<ProjectScope>) {
    val wideScope: ProjectScope? get() = scopes.firstOrNull { it.wide }

    companion object {
        fun parse(o: JsonObject): ProjectInfo? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return ProjectInfo(
                id, o.string("title") ?: id,
                o.objects("locations").mapNotNull(ProjectLocation::parse),
                o.objects("scopes").mapNotNull(ProjectScope::parse),
            )
        }
    }
}

/** A scope another scope can read knowledge from, with its project's title for telling them apart. */
data class ScopeRef(val id: String, val title: String, val project: String, val projectTitle: String)

/** What `projects.config.get` answers. */
data class ProjectsConfig(val current: String?, val home: String?, val projects: List<ProjectInfo>) {
    fun project(id: String?): ProjectInfo? = projects.firstOrNull { it.id == id }

    /** Every saved scope of every project, in the Mac's order. */
    val allScopes: List<ScopeRef>
        get() = projects.flatMap { p -> p.scopes.mapNotNull { s -> s.id?.let { ScopeRef(it, s.title, p.id, p.title) } } }

    fun scopeTitle(id: String?): String? = allScopes.firstOrNull { it.id == id }?.title

    companion object {
        fun parse(o: JsonObject): ProjectsConfig =
            ProjectsConfig(o.text("current")?.takeIf { it.isNotBlank() }, o.string("home"), o.objects("projects").mapNotNull(ProjectInfo::parse))
    }
}

/** What `folders.list` answers: the folders in [path]; [parent] is null at the Mac user's home folder. */
data class FolderListing(val path: String, val parent: String?, val folders: List<Folder>) {
    data class Folder(val name: String, val path: String, val repository: Boolean)

    val name: String get() = ProjectLocation.lastSegment(path)

    companion object {
        fun parse(o: JsonObject): FolderListing = FolderListing(
            path = o.text("path") ?: "~",
            parent = o.text("parent")?.takeIf { it.isNotBlank() },
            folders = o.objects("folders").mapNotNull { f ->
                val path = f.text("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Folder(f.string("name") ?: ProjectLocation.lastSegment(path), path, f.bool("repository") == true)
            },
        )
    }
}

/**
 * A project being made or changed on the phone. Every edit returns a new draft; [problems] are what the phone can
 * see before asking the Mac, and [forSave] is the `projects.config.save` request.
 */
data class ProjectDraft(
    /** The saved project's id; null for a new one. */
    val id: String?,
    val title: String,
    /** A new project's folder id; blank lets the Mac make it from the title. */
    val newId: String = "",
    val locations: List<ProjectLocation> = emptyList(),
    val scopes: List<ProjectScope> = emptyList(),
    private val nextKey: Int = 1,
) {
    val isNew get() = id == null

    val wideScope: ProjectScope? get() = scopes.firstOrNull { it.wide }

    fun scope(key: String): ProjectScope? = scopes.firstOrNull { it.key == key }

    fun withTitle(t: String) = copy(title = t)
    fun withNewId(t: String) = copy(newId = t)

    /** Adds the folder at [path] as a location (once). The project-wide scope covers everything without paths of its own. */
    fun addLocation(path: String, name: String = ProjectLocation.lastSegment(path), repository: Boolean = false): ProjectDraft {
        if (locations.any { it.path == path }) return this
        return copy(
            locations = locations + ProjectLocation(name, path, exists = true, repository = repository),
        )
    }

    fun renameLocation(path: String, name: String) = copy(locations = locations.map { if (it.path == path) it.copy(name = name) else it })

    /** Removes a location and stops every scope working on it. */
    fun removeLocation(path: String) = copy(
        locations = locations.filter { it.path != path },
        scopes = scopes.map { it.copy(paths = it.paths - path) },
    )

    /** A new scope, which gets its id from its title when saved. The first scope of a project is the project-wide one. */
    fun addScope(title: String = ""): ProjectDraft {
        val wide = scopes.isEmpty()
        val s = ProjectScope("new:$nextKey", null, title, wide = wide, paths = emptyList())
        return copy(scopes = scopes + s, nextKey = nextKey + 1)
    }

    private fun change(key: String, f: (ProjectScope) -> ProjectScope) = copy(scopes = scopes.map { if (it.key == key) f(it) else it })

    fun renameScope(key: String, title: String) = change(key) { it.copy(title = title) }

    /** The project-wide scope can't be removed while it is the only place other scopes' entries could move to. */
    fun canRemove(key: String): Boolean = scope(key)?.wide != true || scopes.size == 1

    /** Removes a scope; scopes that included or read it stop doing so. A saved one is deleted on save ([removedScopes]). */
    fun removeScope(key: String): ProjectDraft {
        val gone = scope(key) ?: return this
        if (!canRemove(key)) return this
        return copy(scopes = scopes.filter { it.key != key }.map { s ->
            gone.id?.let { id -> s.copy(includes = s.includes - id, related = s.related - id) } ?: s
        })
    }

    /** Makes [key] the one project-wide scope. It shows everything, so it no longer needs to include others. */
    fun setWide(key: String) = copy(scopes = scopes.map {
        if (it.key == key) it.copy(wide = true, includes = emptyList()) else it.copy(wide = false)
    })

    fun togglePath(key: String, path: String) = change(key) { it.copy(paths = toggled(it.paths, path)) }

    fun toggleInclude(key: String, otherId: String) = change(key) { it.copy(includes = toggled(it.includes, otherId)) }

    fun toggleRelated(key: String, otherId: String) = change(key) { it.copy(related = toggled(it.related, otherId)) }

    /** Saved scopes of this project that [key] can also show: the others, not the project-wide one. */
    fun includable(key: String): List<ProjectScope> = scopes.filter { it.key != key && it.id != null && !it.wide }

    /** Scopes [key] can read knowledge from: every other saved scope, of this project and the others. */
    fun readable(key: String, all: List<ScopeRef>): List<ScopeRef> {
        val self = scope(key)?.id
        val here = scopes.mapNotNull { s -> s.id?.let { ScopeRef(it, s.title, id.orEmpty(), title) } }
        return (here + all.filter { r -> here.none { it.id == r.id } }).filter { it.id != self }
    }

    /** Saved scopes of [original] that saving this draft deletes. */
    fun removedScopes(original: ProjectInfo?): List<ProjectScope> {
        val kept = scopes.mapNotNull { it.id }.toSet()
        return original?.scopes.orEmpty().filter { it.id != null && it.id !in kept }
    }

    fun problems(): List<String> = buildList {
        if (title.isBlank()) add("Give the project a title.")
        if (isNew && newId.isNotBlank() && newId.trim().any { it.isWhitespace() || it == '/' }) add("The id can't have spaces or slashes.")
        if (locations.isEmpty()) add("Add at least one location: a folder on the Mac the project's code is in.")
        if (locations.any { it.name.isBlank() }) add("Every location needs a name.")
        duplicates(locations.map { it.name }).forEach { add("Two locations are called “$it”.") }
        if (scopes.any { it.title.isBlank() }) add("Every scope needs a title.")
        duplicates(scopes.map { it.title }).forEach { add("Two scopes are called “$it”.") }
    }

    /** What `projects.config.save` gets. */
    fun forSave(): JsonObject = jsonOf(
        "id" to (id ?: ""),
        "project" to jsonOf(
            "title" to title.trim(),
            "newId" to newId.trim().takeIf { isNew && it.isNotEmpty() },
            "locations" to locations.map { jsonOf("name" to it.name.trim(), "path" to it.path) },
            "scopes" to scopes.map { s ->
                jsonOf(
                    "id" to s.id, "title" to s.title.trim(), "wide" to s.wide, "paths" to s.paths.filter { p -> locations.any { it.path == p } },
                    "remotes" to s.remotes, "includes" to s.includes, "related" to s.related,
                )
            },
        ),
    )

    companion object {
        fun from(p: ProjectInfo) = ProjectDraft(p.id, p.title, "", p.locations, p.scopes)

        fun new() = ProjectDraft(null, "")

        private fun toggled(list: List<String>, v: String) = if (v in list) list - v else list + v

        private fun duplicates(names: List<String>): List<String> =
            names.map { it.trim() }.filter { it.isNotEmpty() }.groupBy { it.lowercase() }.values.filter { it.size > 1 }.map { it.first() }
    }
}
