package dk.langkilde.mindmill.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject

/** A Build session working in its own copy of one of the scope's repositories, as `test.sources` lists it. */
data class BuildSource(val id: String, val title: String, val branch: String? = null) {
    /** “title” (last part of the branch), both shortened so a menu stays narrow. */
    val label: String get() = "“${shorten(title, 40)}”" + (branch?.let { " (${shorten(lastPart(it), 24)})" } ?: "")

    /** The whole title and branch, for when [label] had to shorten them. */
    val fullLabel: String get() = "“$title”" + (branch?.let { ", on $it" } ?: "")

    val isShortened: Boolean get() = shorten(title, 40) != title || (branch != null && shorten(lastPart(branch), 24) != branch)

    companion object {
        fun parse(o: JsonObject): BuildSource? {
            val id = o.text("id")?.takeIf { it.isNotBlank() } ?: return null
            return BuildSource(id, o.string("title")?.takeIf { it.isNotBlank() } ?: id, o.string("branch")?.takeIf { it.isNotBlank() })
        }

        fun shorten(s: String, limit: Int): String = if (s.length > limit) s.take(limit - 1).trimEnd() + "…" else s

        /** `feature/team/1234-login-flow` → `1234-login-flow`. */
        fun lastPart(branch: String): String = branch.trimEnd('/').substringAfterLast('/').ifEmpty { branch }
    }
}

/**
 * What `test.sources` answers: the branch this Mac's own copy is on, and the sessions whose version can be built
 * instead. [supported] is false for a Mac older than it, which only builds its own copy.
 */
data class TestSources(val localBranch: String?, val sessions: List<BuildSource>, val supported: Boolean = true) {
    val localLabel: String get() = LOCAL + (localBranch?.let { " (${BuildSource.shorten(it, 30)})" } ?: "")
    val localFullLabel: String get() = LOCAL + (localBranch?.let { ", on $it" } ?: "")
    val isLocalShortened: Boolean get() = localBranch != null && BuildSource.shorten(localBranch, 30) != localBranch

    fun find(id: String?): BuildSource? = sessions.firstOrNull { it.id == id }

    companion object {
        const val LOCAL = "This Mac's version"
        val MISSING = TestSources(null, emptyList(), supported = false)

        fun parse(o: JsonObject): TestSources =
            TestSources(o.obj("local").string("branch")?.takeIf { it.isNotBlank() }, o.objects("sessions").mapNotNull(BuildSource::parse))

        /** `test.sources` for [scope], or [MISSING] from a Mac older than it. */
        suspend fun load(scope: String?, call: suspend (method: String, params: JsonObject) -> JsonObject): TestSources = try {
            parse(call("test.sources", jsonOf("scope" to scope)))
        } catch (e: RemoteException) {
            if (!e.isMissingOnMac) throw e
            MISSING
        }

        /**
         * The session whose version Build builds: the [picked] one while [sources] still lists it, or while they are
         * still loading (null); none (the Mac's own copy) once it's gone or from a Mac without sessions' versions.
         */
        fun sessionToBuild(picked: BuildSource?, sources: TestSources?): String? = when {
            picked == null -> null
            sources == null -> picked.id
            !sources.supported -> null
            else -> sources.find(picked.id)?.id
        }
    }
}

/** The Test screen's "Build from" for each scope while the app runs: a session's version, or none for the Mac's own. */
class BuildFromPicks {
    private val _picks = MutableStateFlow<Map<String, BuildSource>>(emptyMap())
    val picks: StateFlow<Map<String, BuildSource>> = _picks

    fun of(scope: String?): BuildSource? = _picks.value[scope.orEmpty()]

    fun pick(scope: String?, source: BuildSource?) = _picks.update { if (source == null) it - scope.orEmpty() else it + (scope.orEmpty() to source) }

    /** After `test.sources`: a pick that is no longer listed goes back to the Mac's version; a listed one takes its new title. */
    fun reconcile(scope: String?, sources: TestSources) {
        val picked = of(scope) ?: return
        pick(scope, if (sources.supported) sources.find(picked.id) else null)
    }

    fun clear() { _picks.value = emptyMap() }
}

/** `test.log`: what the build and tests printed (the end of it), and the first error line in it. */
data class BuildLog(val log: String, val firstError: String?) {
    companion object {
        fun parse(o: JsonObject) = BuildLog(o.string("log").orEmpty(), o.string("firstError")?.trim()?.takeIf { it.isNotEmpty() })
    }
}

/** `test.deliver` for [target] in [scope]: with [session], the version that Build session works on; without, the Mac's own copy. */
suspend fun ActionRunner.deliverTest(target: String, scope: String?, session: String?): ActionOutcome =
    run("test.deliver", jsonOf("target" to target, "scope" to scope, "session" to session?.takeIf { it.isNotBlank() }))
