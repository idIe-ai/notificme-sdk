package me.notific.client

import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Groups (`#finance`) of an organization and its authority ladder
 * (operator < supervisor < manager < director). Each person has at most one
 * authority level per group; `#finance>=manager` in [Compose.to] (or in
 * `decision.by` of a question) reaches only the managers and above of that
 * group.
 *
 * Members of the workspace read; only owners and admins write (`forbidden`
 * otherwise, see [GroupList.canManage]). Groups come with the Teams plan and
 * above: other plans get `plan_required` on writes and
 * [GroupList.groupsEnabled] false. [workspace] is the organization's handle.
 */
public class Groups internal constructor(private val client: NotificClient) {

    /** The organization's groups (members read them: "To" suggests them). */
    public suspend fun list(workspace: String): GroupList =
        client.call(HttpMethod.Get, "${base(workspace)}/groups", GroupList.serializer())

    /** One group (by handle or id) and its people, the highest authority level first. */
    public suspend fun get(workspace: String, group: String): GroupDetail =
        client.call(HttpMethod.Get, "${base(workspace)}/groups/${group.removePrefix("#").path()}", GroupDetail.serializer())

    /**
     * Creates a group. [handle] follows the @handle rules (`all`, `everyone`,
     * `admins`… are reserved). The first group of a workspace saves the
     * default authority ladder. Errors: `group_handle_taken`, `group_limit`,
     * `validation_failed` (see [NotificMeApiException.fieldErrors]).
     */
    public suspend fun create(workspace: String, handle: String, name: String, description: String? = null): GroupDetail =
        client.call(HttpMethod.Post, "${base(workspace)}/groups", GroupDetail.serializer(), body = buildJsonObject {
            put("handle", handle.trim().removePrefix("#"))
            put("name", name.trim())
            if (description != null) put("description", description.trim())
        })

    /** Renames a group or changes its handle or description; null keeps a field. */
    public suspend fun update(
        workspace: String,
        group: String,
        handle: String? = null,
        name: String? = null,
        description: String? = null,
    ): GroupDetail =
        client.call(HttpMethod.Patch, "${base(workspace)}/groups/${group.removePrefix("#").path()}", GroupDetail.serializer(), body = buildJsonObject {
            if (handle != null) put("handle", handle.trim().removePrefix("#"))
            if (name != null) put("name", name.trim())
            if (description != null) put("description", description.trim())
        })

    public suspend fun delete(workspace: String, group: String) {
        client.call(HttpMethod.Delete, "${base(workspace)}/groups/${group.removePrefix("#").path()}", JsonElement.serializer())
    }

    /**
     * Puts workspace members (guests too) in the group, with [authorityLevel]
     * (a key of the ladder; null = none). Who is already there stays as is.
     * Errors: `member_not_found`, `unknown_authority_level`.
     */
    public suspend fun addMembers(workspace: String, group: String, userIds: List<String>, authorityLevel: String? = null): GroupDetail =
        client.call(HttpMethod.Post, membersPath(workspace, group), GroupDetail.serializer(), body = buildJsonObject {
            putJsonArray("user_ids") { userIds.distinct().forEach { add(it) } }
            if (authorityLevel != null) put("authority_level", authorityLevel)
        })

    /** Changes a person's authority level in the group; null leaves them without one. */
    public suspend fun setMemberLevel(workspace: String, group: String, userId: String, authorityLevel: String?): GroupMember =
        client.call(HttpMethod.Patch, "${membersPath(workspace, group)}/${userId.path()}", GroupMember.serializer(), body = buildJsonObject {
            if (authorityLevel != null) put("authority_level", authorityLevel) else put("authority_level", JsonNull)
        })

    public suspend fun removeMember(workspace: String, group: String, userId: String) {
        client.call(HttpMethod.Delete, "${membersPath(workspace, group)}/${userId.path()}", JsonElement.serializer())
    }

    /**
     * The authority ladder, lowest first. Until someone saves it,
     * [AuthorityLevels.stored] is false and it is the default ladder in the
     * reader's language.
     */
    public suspend fun authorityLevels(workspace: String): AuthorityLevels =
        client.call(HttpMethod.Get, "${base(workspace)}/authority-levels", AuthorityLevels.serializer())

    /**
     * Replaces the whole ladder, lowest first (up to 12). A level with its
     * [AuthorityLevelInput.id] keeps its people; one without is new; a level
     * left out is deleted, or `authority_level_in_use` (409, details `key`,
     * `name`, `members`) when someone still has it.
     */
    public suspend fun setAuthorityLevels(workspace: String, levels: List<AuthorityLevelInput>): AuthorityLevels =
        client.call(HttpMethod.Put, "${base(workspace)}/authority-levels", AuthorityLevels.serializer(), body = buildJsonObject {
            putJsonArray("items") {
                levels.forEach { l ->
                    add(buildJsonObject {
                        l.id?.let { put("id", it) }
                        put("key", l.key.trim().lowercase())
                        put("name", l.name.trim())
                    })
                }
            }
        })

    /** Everyone in the workspace (to put in a group), guests included. */
    public suspend fun members(workspace: String): List<WorkspaceMember> =
        client.call(HttpMethod.Get, "${base(workspace)}/members", MemberList.serializer()).items

    private fun base(workspace: String) = "/v0/workspaces/${workspace.removePrefix("@").path()}"

    private fun membersPath(workspace: String, group: String) = "${base(workspace)}/groups/${group.removePrefix("#").path()}/members"

    private fun String.path(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

    @Serializable
    private data class MemberList(val items: List<WorkspaceMember> = emptyList())
}

/** A group of an organization (`GET …/groups`). */
@Serializable
public data class Group(
    val id: String,
    val handle: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    /** `#finance`, as it goes in [Compose.to]. */
    public val tag: String get() = "#$handle"
}

/** A group and its people (`GET …/groups/{group}`). */
@Serializable
public data class GroupDetail(
    val id: String,
    val handle: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    /** The highest authority level first, then by name. */
    val members: List<GroupMember> = emptyList(),
) {
    public val group: Group get() = Group(id, handle, name, description, memberCount, createdAt, updatedAt)
    public val tag: String get() = "#$handle"
}

/** A person in a group, with their authority level in it. */
@Serializable
public data class GroupMember(
    @SerialName("user_id") val userId: String,
    val handle: String? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** The membership e-mail (who manages the group sees it). */
    val email: String = "",
    /** `owner`, `admin`, `member` or `guest`. */
    val role: String = "",
    val guest: Boolean = false,
    /** Null: no authority level in this group (left out of `#group>=level`). */
    @SerialName("authority_level") val authorityLevel: AuthorityLevel? = null,
    @SerialName("added_at") val addedAt: String = "",
) {
    public val name: String get() = displayName.ifBlank { handle ?: email }
    public val person: Person get() = Person(userId, handle.orEmpty(), displayName, avatarUrl)
}

/** A step of the workspace's authority ladder. */
@Serializable
public data class AuthorityLevel(
    /** Null while the default ladder is not saved yet. */
    val id: String? = null,
    /** What goes after `>=` (`manager`). */
    val key: String = "",
    val name: String = "",
    /** 0 = the lowest. */
    val rank: Int = 0,
    /** People with this level in some group. */
    val members: Int = 0,
) {
    public val input: AuthorityLevelInput get() = AuthorityLevelInput(id, key, name)
}

/** A step to save with [Groups.setAuthorityLevels]: with [id] it keeps its people. */
@Serializable
public data class AuthorityLevelInput(
    val id: String? = null,
    val key: String,
    val name: String,
)

/** `GET …/groups`. */
@Serializable
public data class GroupList(
    val items: List<Group> = emptyList(),
    /** The workspace is an organization on a plan with groups. */
    @SerialName("groups_enabled") val groupsEnabled: Boolean = false,
    /** The person may create and edit groups (owner or admin, plan with groups). */
    @SerialName("can_manage") val canManage: Boolean = false,
    val plan: String = "",
    /** Plans that have groups. */
    val plans: List<String> = emptyList(),
    /** People one send reaches at most on this plan. */
    @SerialName("send_limit") val sendLimit: Int = 0,
)

/** `GET …/authority-levels`. */
@Serializable
public data class AuthorityLevels(
    /** Lowest first. */
    val items: List<AuthorityLevel> = emptyList(),
    /** False: the default ladder, not saved yet. */
    val stored: Boolean = false,
    @SerialName("groups_enabled") val groupsEnabled: Boolean = false,
    @SerialName("can_manage") val canManage: Boolean = false,
    val plan: String = "",
    val plans: List<String> = emptyList(),
)

/** A member of a workspace (`GET /v0/workspaces/{handle}/members`). */
@Serializable
public data class WorkspaceMember(
    @SerialName("user_id") val userId: String,
    val email: String = "",
    val handle: String? = null,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** `owner`, `admin`, `member` or `guest`. */
    val role: String = "",
    @SerialName("joined_at") val joinedAt: String = "",
) {
    public val name: String get() = displayName.ifBlank { handle ?: email }
    public val guest: Boolean get() = role == "guest"
    public val person: Person get() = Person(userId, handle.orEmpty(), displayName, avatarUrl)
}

/**
 * A `#group` or `#group>=level` entry of [Compose.to] (or `decision.by`), the
 * same rules as the server: lowercase, spaces around `>=` allowed, built-in
 * groups (`#all`, `#admins`, `#owners`) without a level filter.
 */
public data class GroupTag(
    /** Handle of the group, without `#`. */
    val group: String,
    /** Key of the lowest authority level that counts; null = the whole group. */
    val minLevel: String? = null,
) {
    public val builtin: Boolean get() = group in BUILTIN

    /** The canonical form: `#finance` or `#finance>=manager`. */
    override fun toString(): String = if (minLevel == null) "#$group" else "#$group>=$minLevel"

    public companion object {
        /** Built-in groups (from roles): no level filter. */
        public val BUILTIN: Set<String> = setOf("all", "admins", "owners")

        /** Handles a new group can't take. */
        public val RESERVED: Set<String> = setOf("all", "everyone", "owners", "admins", "members", "guests", "here", "me")

        private val HANDLE = Regex("^[a-z0-9][a-z0-9_-]{1,38}$")
        private val LEVEL_KEY = Regex("^[a-z0-9][a-z0-9_-]{0,38}$")

        /** Starts with `#`. */
        public fun isGroupTag(raw: String): Boolean = raw.trim().startsWith("#")

        /** Reads `#group` or `#group>=level`; null when it is not a valid tag. */
        public fun parse(raw: String): GroupTag? {
            val s = raw.trim().lowercase()
            if (!s.startsWith("#")) return null
            val body = s.drop(1)
            val i = body.indexOf(">=")
            val group = (if (i >= 0) body.substring(0, i) else body).trim()
            val level = if (i >= 0) body.substring(i + 2).trim() else null
            if (level != null && !LEVEL_KEY.matches(level)) return null
            if (group in BUILTIN) return if (level == null) GroupTag(group) else null
            if (!HANDLE.matches(group)) return null
            return GroupTag(group, level)
        }

        /** A valid handle for a new group (the @handle rules, not reserved). */
        public fun isValidHandle(handle: String): Boolean = HANDLE.matches(handle) && handle !in RESERVED

        /** A valid authority level key (what goes after `>=`). */
        public fun isValidLevelKey(key: String): Boolean = LEVEL_KEY.matches(key)
    }
}
