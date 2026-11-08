package me.notific.client

import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Contacts and sending from the interface: the person's own contacts (by
 * `@handle`, with consent), the members of their organizations, and the
 * messages and questionnaires they send to them.
 *
 * These calls need the person's own sign-in: a delegated session issued by a
 * company gets `forbidden`.
 */
public class People internal constructor(private val client: NotificClient) {

    /** Contacts, requests received and sent, blocked people and organization members. */
    public suspend fun overview(): ContactsOverview =
        client.call(HttpMethod.Get, "/v0/me/contacts", ContactsOverview.serializer())

    /** The person behind [handle] and how they relate to the signed-in person. */
    public suspend fun lookup(handle: String): PersonLookup =
        client.call(HttpMethod.Get, "/v0/me/contacts/lookup", PersonLookup.serializer(), query = mapOf("handle" to handle))

    /**
     * Adds [handle] to the contacts. With the right [pin] (the PIN of their
     * `@handle`) they become a contact right away; without it a request
     * waits for their answer. Errors: `contact_pin_required`,
     * `contact_pin_invalid`, `contact_requests_closed`, `recipient_blocked`,
     * `already_contact`, `handle_not_found`.
     */
    public suspend fun add(handle: String, pin: String? = null, note: String? = null): AddContactResult =
        client.call(HttpMethod.Post, "/v0/me/contacts", AddContactResult.serializer(), body = buildJsonObject {
            put("handle", handle)
            if (!pin.isNullOrBlank()) put("pin", pin)
            if (!note.isNullOrBlank()) put("note", note)
        })

    /** Invites someone by e-mail; the answer never tells whether the e-mail has an account. */
    public suspend fun invite(email: String, note: String? = null): AddContactResult =
        client.call(HttpMethod.Post, "/v0/me/contacts", AddContactResult.serializer(), body = buildJsonObject {
            put("email", email)
            if (!note.isNullOrBlank()) put("note", note)
        })

    public suspend fun accept(requestId: String): Contact =
        client.call(HttpMethod.Post, "/v0/me/contacts/requests/${requestId.path()}/accept", Accepted.serializer()).contact

    /** Declines quietly: the other person is not told. */
    public suspend fun decline(requestId: String) {
        client.call(HttpMethod.Post, "/v0/me/contacts/requests/${requestId.path()}/decline", JsonElement.serializer())
    }

    /** Cancels a request the person sent. */
    public suspend fun cancel(requestId: String) {
        client.call(HttpMethod.Delete, "/v0/me/contacts/requests/${requestId.path()}", JsonElement.serializer())
    }

    /** Removes the contact on both sides. */
    public suspend fun remove(userId: String) {
        client.call(HttpMethod.Delete, "/v0/me/contacts/${userId.path()}", JsonElement.serializer())
    }

    /** Blocks [userId] without telling them; they only find out if they try to send. */
    public suspend fun block(userId: String) {
        client.call(HttpMethod.Post, "/v0/me/contacts/blocks", JsonElement.serializer(), body = buildJsonObject { put("user_id", userId) })
    }

    public suspend fun unblock(userId: String) {
        client.call(HttpMethod.Delete, "/v0/me/contacts/blocks/${userId.path()}", JsonElement.serializer())
    }

    /** Who may add the person and whether they have a PIN (`GET /v0/me/privacy`). */
    public suspend fun privacy(): Privacy = client.call(HttpMethod.Get, "/v0/me/privacy", Privacy.serializer())

    /**
     * Changes [policy] (`anyone`, `pin` or `nobody`) and/or the [pin] (4 to 8
     * digits; `""` removes it).
     */
    public suspend fun setPrivacy(policy: String? = null, pin: String? = null): Privacy =
        client.call(HttpMethod.Patch, "/v0/me/privacy", Privacy.serializer(), body = buildJsonObject {
            if (policy != null) put("invite_policy", policy)
            if (pin != null) put("pin", pin)
        })

    /**
     * Sends a message or a questionnaire. Errors: `recipient_not_found`,
     * `recipient_blocked`, `compose_forbidden`, `validation_failed` (with
     * the paths in [NotificMeApiException.details]).
     */
    public suspend fun send(compose: Compose): ComposeResult =
        client.call(HttpMethod.Post, "/v0/me/compose", ComposeResult.serializer(),
            body = Wire.json.encodeToJsonElement(Compose.serializer(), compose))

    /** What the person sent, newest first. */
    public suspend fun sent(cursor: String? = null, limit: Int = 50): SentPage =
        client.call(HttpMethod.Get, "/v0/me/sent", SentPage.serializer(), query = mapOf("cursor" to cursor, "limit" to limit))

    /** One sent item with who read, acknowledged and answered, and the answers. */
    public suspend fun sentItem(id: String): SentDetail =
        client.call(HttpMethod.Get, "/v0/me/sent/${id.path()}", SentDetail.serializer())

    public suspend fun templates(): List<FormTemplate> =
        client.call(HttpMethod.Get, "/v0/me/templates", TemplateList.serializer()).items

    /** Saves a questionnaire as a template ([FormTemplate.id] empty = new). */
    public suspend fun saveTemplate(t: FormTemplate): FormTemplate {
        val body = Wire.json.encodeToJsonElement(FormTemplate.serializer(), t)
        return if (t.id.isBlank()) {
            client.call(HttpMethod.Post, "/v0/me/templates", FormTemplate.serializer(), body = body)
        } else {
            client.call(HttpMethod.Put, "/v0/me/templates/${t.id.path()}", FormTemplate.serializer(), body = body)
        }
    }

    public suspend fun deleteTemplate(id: String) {
        client.call(HttpMethod.Delete, "/v0/me/templates/${id.path()}", JsonElement.serializer())
    }

    private fun String.path(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

    @Serializable
    private data class Accepted(val contact: Contact)

    @Serializable
    private data class TemplateList(val items: List<FormTemplate> = emptyList())
}

/** A person as contacts show them (never their e-mail). */
@Serializable
public data class Person(
    val id: String,
    val handle: String = "",
    @SerialName("display_name") val displayName: String = "",
    /** Path on the API; full URL with [NotificClient.urlOf]. */
    @SerialName("avatar_url") val avatarUrl: String? = null,
) {
    public val name: String get() = displayName.ifBlank { handle }
}

@Serializable
public data class Contact(
    val id: String,
    val handle: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val since: String = "",
    /** `request` (accepted a request) or `pin` (added with the @handle PIN). */
    val via: String = "",
) {
    public val person: Person get() = Person(id, handle, displayName, avatarUrl)
}

@Serializable
public data class ContactRequest(
    val id: String,
    /** Null on a request sent by e-mail. */
    val person: Person? = null,
    /** Set on a request sent by e-mail. */
    val email: String? = null,
    val note: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
public data class OrgMember(
    val id: String,
    val handle: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** `owner`, `admin`, `member` or `guest`. */
    val role: String = "",
) {
    public val person: Person get() = Person(id, handle, displayName, avatarUrl)
}

/** An organization of the person and its members (its contacts). */
@Serializable
public data class OrgContacts(
    val id: String,
    val handle: String = "",
    val name: String = "",
    @SerialName("logo_url") val logoUrl: String? = null,
    val role: String = "",
    /** The person's role lets them send messages and questionnaires to members. */
    @SerialName("can_compose") val canCompose: Boolean = false,
    val members: List<OrgMember> = emptyList(),
)

/** `GET /v0/me/contacts`. */
@Serializable
public data class ContactsOverview(
    val contacts: List<Contact> = emptyList(),
    val received: List<ContactRequest> = emptyList(),
    val sent: List<ContactRequest> = emptyList(),
    val blocked: List<Person> = emptyList(),
    val orgs: List<OrgContacts> = emptyList(),
    /** The person has a PIN: whoever knows it adds them directly. */
    @SerialName("has_pin") val hasPin: Boolean = false,
    /** `anyone`, `pin` or `nobody`: who may send them a contact request. */
    @SerialName("invite_policy") val invitePolicy: String = "anyone",
)

/** `GET /v0/me/contacts/lookup`. */
@Serializable
public data class PersonLookup(
    val id: String,
    val handle: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    /** `self`, `contact`, `sent`, `received` or `none`. */
    val relation: String = "none",
    /** Without their PIN they don't take requests. */
    @SerialName("pin_required") val pinRequired: Boolean = false,
    /** They blocked the person asking. */
    val blocked: Boolean = false,
) {
    public val person: Person get() = Person(id, handle, displayName, avatarUrl)
}

@Serializable
public data class AddContactResult(
    /** `accepted` (a contact now) or `pending` (request sent). */
    val status: String,
    val contact: Contact? = null,
    val request: ContactRequest? = null,
)

/** Organization the person blocked from inviting them. */
@Serializable
public data class BlockedOrg(
    @SerialName("workspace_id") val workspaceId: String,
    val handle: String = "",
    val name: String = "",
)

/** `GET /v0/me/privacy`. */
@Serializable
public data class Privacy(
    @SerialName("invite_policy") val invitePolicy: String = "anyone",
    @SerialName("has_pin") val hasPin: Boolean = false,
    val blocked: List<BlockedOrg> = emptyList(),
)

/** A question of a questionnaire built in the app. */
@Serializable
public data class ComposeQuestion(
    val id: String = "",
    val type: QuestionType,
    val text: String,
    val options: List<String> = emptyList(),
    @SerialName("allow_other") val allowOther: Boolean = false,
    val optional: Boolean = false,
)

/** `POST /v0/me/compose`. */
@Serializable
public data class Compose(
    /** `message` (subject + text) or `form` (questionnaire). */
    val kind: String,
    /** Handles: contacts (from the personal workspace) or members (from [from]). */
    val to: List<String>,
    val title: String,
    val text: String = "",
    val questions: List<ComposeQuestion> = emptyList(),
    /** Handle of the organization it is sent from; null = personal workspace. */
    val from: String? = null,
    /** Verification of the answer: 0 tap, 1 biometrics, 2 authenticator code. */
    val level: Int? = null,
    val timeout: String? = null,
) {
    public companion object {
        public const val MESSAGE: String = "message"
        public const val FORM: String = "form"
    }
}

@Serializable
public data class ComposeResult(
    val kind: String = "",
    val workspace: String = "",
    @SerialName("notification_id") val notificationId: String? = null,
    @SerialName("question_set_id") val questionSetId: String? = null,
    /** A questionnaire goes as one question set per recipient, so each answers their own. */
    @SerialName("question_set_ids") val questionSetIds: List<String> = emptyList(),
    /** The item in [People.sent]. */
    @SerialName("sent_id") val sentId: String? = null,
    val recipients: List<Person> = emptyList(),
    /** Handles the questionnaire could not go to (partial send). */
    val failed: List<String> = emptyList(),
)

@Serializable
public data class SentWorkspace(
    val id: String = "",
    val handle: String = "",
    val name: String = "",
    val kind: String = "",
)

@Serializable
public data class SentCounts(
    val recipients: Int = 0,
    val delivered: Int = 0,
    val read: Int = 0,
    val acknowledged: Int = 0,
    val answered: Int = 0,
)

@Serializable
public data class SentRecipient(
    val person: Person,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("acknowledged_at") val acknowledgedAt: String? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
)

/** Something the person sent from the app. */
@Serializable
public data class SentItem(
    val id: String,
    val kind: String = "",
    val workspace: SentWorkspace = SentWorkspace(),
    val title: String = "",
    val text: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("notification_id") val notificationId: String? = null,
    @SerialName("question_set_id") val questionSetId: String? = null,
    /** Message: `sent`; questionnaire: `open`, `answered`, `expired`, `cancelled`. */
    val status: String = "",
    val counts: SentCounts = SentCounts(),
    val recipients: List<SentRecipient> = emptyList(),
)

@Serializable
public data class SentPage(
    val items: List<SentItem> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
public data class SentAnswer(
    @SerialName("question_id") val questionId: String = "",
    @SerialName("question_text") val questionText: String = "",
    val value: JsonElement = JsonNull,
    @SerialName("free_text") val freeText: String? = null,
)

@Serializable
public data class SentResponse(
    val person: Person,
    @SerialName("answered_at") val answeredAt: String = "",
    val answers: List<SentAnswer> = emptyList(),
)

/** `GET /v0/me/sent/{id}`. */
@Serializable
public data class SentDetail(
    val id: String,
    val kind: String = "",
    val workspace: SentWorkspace = SentWorkspace(),
    val title: String = "",
    val text: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("notification_id") val notificationId: String? = null,
    @SerialName("question_set_id") val questionSetId: String? = null,
    val status: String = "",
    val counts: SentCounts = SentCounts(),
    val recipients: List<SentRecipient> = emptyList(),
    val questions: List<Question> = emptyList(),
    val answers: List<SentResponse> = emptyList(),
)

/** A questionnaire saved to reuse. */
@Serializable
public data class FormTemplate(
    val id: String = "",
    val name: String,
    val title: String = "",
    val text: String = "",
    val questions: List<ComposeQuestion> = emptyList(),
    val level: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)
