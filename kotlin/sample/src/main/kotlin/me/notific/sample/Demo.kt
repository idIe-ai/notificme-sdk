package me.notific.sample

import android.content.res.AssetManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.notific.client.Connection
import me.notific.client.DeliveryState
import me.notific.client.InboxState
import me.notific.client.Notification
import me.notific.client.NotificationContent
import me.notific.client.QuestionSet
import me.notific.client.Severity
import me.notific.client.Wire

/**
 * Offline inbox built from the protocol examples (`sdk/protocol/examples`,
 * packed as assets): every card block and every question type.
 */
object Demo {
    const val WORKSPACE = "demo"

    fun load(assets: AssetManager): InboxState {
        val files = assets.list("").orEmpty().filter { it.endsWith(".json") }.sorted()
        val now = Instant.now()
        val notifications = mutableListOf<Notification>()
        val questions = mutableListOf<QuestionSet>()
        files.forEachIndexed { i, name ->
            val json = runCatching { Wire.json.parseToJsonElement(assets.open(name).bufferedReader().use { it.readText() }).jsonObject }.getOrNull()
                ?: return@forEachIndexed
            val at = now.minus((i * 37L) + 3, ChronoUnit.MINUTES).toString()
            when {
                name.endsWith(".event.json") -> notifications += event(name, json, at)
                name.endsWith(".question-set.json") -> {
                    val qs = Wire.json.decodeFromJsonElement(QuestionSet.serializer(), json)
                    val id = name.removeSuffix(".question-set.json")
                    questions += qs.copy(
                        id = id,
                        workspaceId = WORKSPACE,
                        createdAt = at,
                        timeoutAt = now.plus(2, ChronoUnit.HOURS).toString(),
                        answerable = qs.level <= 1,
                        answerableWithTotp = qs.level in 2..3,
                    )
                }
            }
        }
        return InboxState(
            notifications = notifications.sortedByDescending { it.createdAt },
            questions = questions,
            loading = false,
            connection = Connection.Offline,
        )
    }

    private fun event(name: String, o: JsonObject, at: String): Notification {
        val content = o["content"]?.jsonObject?.let { Wire.json.decodeFromJsonElement(NotificationContent.serializer(), it) }
        val severity = o["severity"]?.jsonPrimitive?.contentOrNull
            ?.let { s -> Severity.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } } ?: Severity.Info
        return Notification(
            id = name.removeSuffix(".event.json"),
            workspaceId = WORKSPACE,
            createdAt = at,
            source = o["source"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            type = o["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            severity = severity,
            title = content?.card?.title,
            summary = content?.summary,
            deliveries = listOf(DeliveryState(id = "d-$name")),
            content = content,
        )
    }
}
