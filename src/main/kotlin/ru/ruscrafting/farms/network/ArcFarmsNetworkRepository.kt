package ru.ruscrafting.farms.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.ruscrafting.farms.domain.ActivityKind
import java.util.UUID
import java.util.concurrent.CompletableFuture

private const val MAX_TRAVEL_TICKET_MS = 5 * 60 * 1000L

enum class NetworkSignal {
    FARM_INCIDENT,
    FARM_RESCUED,
    LUMBER_PROCESSING,
    MINE_HAZARD,
    MINE_STABLE,
    MINE_EXTRACTION,
    ACTIVITY_COMPLETED,
    WORKDAY_STAMP,
    WORKDAY_COMPLETED,
    NODE_PROBE,
    NODE_ACK,
}

data class NetworkEvent(
    val eventId: String,
    val signal: NetworkSignal,
    val activity: ActivityKind? = null,
    val actorName: String? = null,
    val cycle: Long? = null,
    val completed: Set<ActivityKind> = emptySet(),
    val replyTo: String? = null,
    val occurredAtMs: Long,
) {
    fun validated(): NetworkEvent = apply {
        require(eventId.isCanonicalUuid()) { "Invalid network event id" }
        require(occurredAtMs > 0) { "Invalid network event timestamp" }
        actorName?.let { require(it.matches(PLAYER_NAME_PATTERN)) { "Invalid network actor name" } }
        cycle?.let { require(it > 0) { "Invalid workday cycle" } }
        require(completed.size <= ActivityKind.entries.size) { "Too many completed activities" }
        replyTo?.let { require(it.isCanonicalUuid()) { "Invalid reply event id" } }
        when (signal) {
            NetworkSignal.FARM_INCIDENT,
            NetworkSignal.FARM_RESCUED -> require(activity == ActivityKind.FARM) { "$signal requires FARM" }
            NetworkSignal.LUMBER_PROCESSING -> require(activity == ActivityKind.LUMBER) { "$signal requires LUMBER" }
            NetworkSignal.MINE_HAZARD,
            NetworkSignal.MINE_STABLE,
            NetworkSignal.MINE_EXTRACTION -> require(activity == ActivityKind.MINE) { "$signal requires MINE" }
            NetworkSignal.ACTIVITY_COMPLETED -> require(activity != null) { "$signal requires an activity" }
            NetworkSignal.WORKDAY_STAMP -> {
                require(activity != null && cycle != null && activity in completed) { "Invalid workday stamp" }
            }
            NetworkSignal.WORKDAY_COMPLETED -> {
                require(cycle != null && completed == ActivityKind.entries.toSet()) { "Invalid completed workday" }
            }
            NetworkSignal.NODE_PROBE -> requireNodeSignal(replyExpected = false)
            NetworkSignal.NODE_ACK -> requireNodeSignal(replyExpected = true)
        }
    }

    private fun requireNodeSignal(replyExpected: Boolean) {
        require((replyTo != null) == replyExpected) { "Invalid node probe acknowledgement" }
        require(activity == null && actorName == null && cycle == null && completed.isEmpty()) { "Node signals cannot carry gameplay data" }
    }

    companion object {
        private val PLAYER_NAME_PATTERN = Regex("[A-Za-z0-9_]{1,16}")

        private fun String.isCanonicalUuid(): Boolean = runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

        fun create(
            signal: NetworkSignal,
            activity: ActivityKind? = null,
            actorName: String? = null,
            cycle: Long? = null,
            completed: Set<ActivityKind> = emptySet(),
            replyTo: String? = null,
            nowMs: Long = System.currentTimeMillis(),
        ): NetworkEvent = NetworkEvent(
            eventId = UUID.randomUUID().toString(),
            signal = signal,
            activity = activity,
            actorName = actorName,
            cycle = cycle,
            completed = completed,
            replyTo = replyTo,
            occurredAtMs = nowMs,
        ).validated()
    }
}

data class WorkdayState(
    val cycle: Long = 1,
    val revision: Long = 0,
    val completed: Set<ActivityKind> = emptySet(),
) {
    fun validated(): WorkdayState = apply {
        require(cycle > 0) { "Workday cycle must be positive" }
        require(revision >= 0) { "Workday revision cannot be negative" }
        require(completed.size <= ActivityKind.entries.size) { "Workday contains too many seals" }
    }

    fun recommended(): ActivityKind = ActivityKind.entries.firstOrNull { it !in completed } ?: ActivityKind.FARM
}

data class TravelTicket(
    val activity: ActivityKind,
    val destinationServer: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    fun validated(): TravelTicket = apply {
        require(destinationServer.matches(SERVER_ID_PATTERN)) { "Invalid travel destination server" }
        require(createdAtMs > 0 && expiresAtMs > createdAtMs) { "Invalid travel ticket lifetime" }
        require(expiresAtMs - createdAtMs <= MAX_TRAVEL_TICKET_MS) { "Travel ticket lifetime is too long" }
    }
}

sealed interface WorkdayUpdate {
    val state: WorkdayState

    data class Stamped(
        override val state: WorkdayState,
        val activity: ActivityKind,
    ) : WorkdayUpdate

    data class Completed(
        override val state: WorkdayState,
        val completedCycle: Long,
        val closingActivity: ActivityKind,
    ) : WorkdayUpdate

    data class AlreadyStamped(override val state: WorkdayState) : WorkdayUpdate

    data class Contended(override val state: WorkdayState) : WorkdayUpdate
}

class ArcFarmsNetworkRepository(
    private val redis: RedisOperations,
    private val gson: Gson = Gson(),
) {
    fun registerEvents(listener: (NetworkEvent, String) -> Unit): ChannelListener {
        val channelListener = ChannelListener { _, raw, origin ->
            decodeEvent(raw)?.let { listener(it, origin) }
        }
        redis.registerChannelUnique(EVENT_CHANNEL, channelListener)
        return channelListener
    }

    fun unregisterEvents(listener: ChannelListener) = redis.unregisterChannel(EVENT_CHANNEL, listener)

    fun publish(event: NetworkEvent) {
        redis.publish(EVENT_CHANNEL, encodeEvent(event))
    }

    fun loadWorkday(): CompletableFuture<WorkdayState> =
        redis.loadMapEntries(WORKDAY_KEY, WORKDAY_FIELD).thenApply { values ->
            values.firstOrNull()?.let(::decodeWorkday) ?: WorkdayState()
        }

    fun markCompleted(activity: ActivityKind): CompletableFuture<WorkdayUpdate> = markAttempt(activity, 0)

    fun createTravelTicket(
        playerId: UUID,
        activity: ActivityKind,
        destinationServer: String,
        nowMs: Long,
        lifetimeMs: Long,
    ): CompletableFuture<Boolean> {
        val ticket = TravelTicket(activity, destinationServer, nowMs, nowMs + lifetimeMs).validated()
        return redis.loadMap(TRAVEL_KEY).thenCompose { current ->
            if (current.size >= MAX_TRAVEL_TICKETS && playerId.toString() !in current) {
                CompletableFuture.completedFuture(false)
            } else {
                redis.saveMapEntries(TRAVEL_KEY, playerId.toString(), encodeTravelTicket(ticket)).thenApply { true }
            }
        }
    }

    fun claimTravelTicket(playerId: UUID, currentServer: String, nowMs: Long): CompletableFuture<TravelTicket?> {
        require(currentServer.matches(SERVER_ID_PATTERN)) { "Invalid current server id" }
        val field = playerId.toString()
        return redis.loadMapEntries(TRAVEL_KEY, field).thenCompose { values ->
            val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(null)
            val ticket = runCatching { decodeTravelTicket(raw) }.getOrNull()
            if (ticket == null || ticket.expiresAtMs < nowMs) {
                return@thenCompose redis.compareAndSetMapEntry(TRAVEL_KEY, field, raw, null).thenApply { null }
            }
            if (ticket.destinationServer != currentServer) return@thenCompose CompletableFuture.completedFuture(null)
            redis.compareAndSetMapEntry(TRAVEL_KEY, field, raw, null).thenApply { claimed -> ticket.takeIf { claimed } }
        }
    }

    fun cleanupExpiredTravelTickets(nowMs: Long): CompletableFuture<Int> = redis.loadMap(TRAVEL_KEY).thenCompose { entries ->
        val expired = entries.entries.filter { (_, raw) ->
            val ticket = runCatching { decodeTravelTicket(raw) }.getOrNull()
            ticket == null || ticket.expiresAtMs < nowMs
        }.take(MAX_TRAVEL_CLEANUP)
        CompletableFuture.allOf(
            *expired.map { (field, raw) -> redis.compareAndSetMapEntry(TRAVEL_KEY, field, raw, null) }.toTypedArray(),
        ).thenApply { expired.size }
    }

    private fun markAttempt(activity: ActivityKind, attempt: Int): CompletableFuture<WorkdayUpdate> {
        if (attempt >= MAX_CAS_ATTEMPTS) {
            return loadWorkday().thenApply { WorkdayUpdate.Contended(it) }
        }
        return redis.loadMapEntries(WORKDAY_KEY, WORKDAY_FIELD).thenCompose { values ->
            val beforeRaw = values.firstOrNull()
            val before = beforeRaw?.let(::decodeWorkday) ?: WorkdayState()
            if (activity in before.completed) {
                return@thenCompose CompletableFuture.completedFuture(WorkdayUpdate.AlreadyStamped(before))
            }
            val completed = before.completed + activity
            val finished = completed.size == ActivityKind.entries.size
            val after = if (finished) {
                WorkdayState(cycle = before.cycle + 1, revision = before.revision + 1)
            } else {
                before.copy(revision = before.revision + 1, completed = completed)
            }.validated()
            val afterRaw = encodeWorkday(after)
            redis.compareAndSetMapEntry(WORKDAY_KEY, WORKDAY_FIELD, beforeRaw, afterRaw).thenCompose { changed ->
                if (!changed) return@thenCompose markAttempt(activity, attempt + 1)
                CompletableFuture.completedFuture(
                    if (finished) WorkdayUpdate.Completed(after, before.cycle, activity)
                    else WorkdayUpdate.Stamped(after, activity),
                )
            }
        }
    }

    private fun encodeEvent(event: NetworkEvent): String = event.validated().let { validated ->
        JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            addProperty("eventId", validated.eventId)
            addProperty("signal", validated.signal.name)
            validated.activity?.let { addProperty("activity", it.name) }
            validated.actorName?.let { addProperty("actorName", it) }
            validated.cycle?.let { addProperty("cycle", it) }
            add("completed", gson.toJsonTree(validated.completed.map(ActivityKind::name).sorted()))
            validated.replyTo?.let { addProperty("replyTo", it) }
            addProperty("occurredAtMs", validated.occurredAtMs)
        }.toString().also { require(it.length <= MAX_EVENT_CHARS) { "Network event is too large" } }
    }

    private fun decodeEvent(raw: String): NetworkEvent? = runCatching {
        if (raw.length > MAX_EVENT_CHARS) return null
        val root = JsonParser.parseString(raw).asJsonObject
        if (root.int("protocolVersion") != PROTOCOL_VERSION) return null
        NetworkEvent(
            eventId = root.string("eventId"),
            signal = NetworkSignal.valueOf(root.string("signal")),
            activity = root.optionalString("activity")?.let(ActivityKind::valueOf),
            actorName = root.optionalString("actorName"),
            cycle = root.optionalLong("cycle"),
            completed = root.getAsJsonArray("completed")?.map { ActivityKind.valueOf(it.asString) }?.toSet().orEmpty(),
            replyTo = root.optionalString("replyTo"),
            occurredAtMs = root.long("occurredAtMs"),
        ).validated()
    }.getOrNull()

    private fun encodeWorkday(state: WorkdayState): String = state.validated().let { validated ->
        JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            addProperty("cycle", validated.cycle)
            addProperty("revision", validated.revision)
            add("completed", gson.toJsonTree(validated.completed.map(ActivityKind::name).sorted()))
        }.toString().also { require(it.length <= MAX_WORKDAY_CHARS) { "Workday state is too large" } }
    }

    private fun decodeWorkday(raw: String): WorkdayState {
        if (raw.length > MAX_WORKDAY_CHARS) throw JsonParseException("Workday state is too large")
        val root = JsonParser.parseString(raw).asJsonObject
        if (root.int("protocolVersion") != PROTOCOL_VERSION) throw JsonParseException("Unsupported workday protocol")
        return WorkdayState(
            cycle = root.long("cycle"),
            revision = root.long("revision"),
            completed = root.getAsJsonArray("completed")?.map { ActivityKind.valueOf(it.asString) }?.toSet().orEmpty(),
        ).validated()
    }

    private fun encodeTravelTicket(ticket: TravelTicket): String = ticket.validated().let { validated ->
        JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            addProperty("activity", validated.activity.name)
            addProperty("destinationServer", validated.destinationServer)
            addProperty("createdAtMs", validated.createdAtMs)
            addProperty("expiresAtMs", validated.expiresAtMs)
        }.toString().also { require(it.length <= MAX_TRAVEL_CHARS) { "Travel ticket is too large" } }
    }

    private fun decodeTravelTicket(raw: String): TravelTicket {
        if (raw.length > MAX_TRAVEL_CHARS) throw JsonParseException("Travel ticket is too large")
        val root = JsonParser.parseString(raw).asJsonObject
        if (root.int("protocolVersion") != PROTOCOL_VERSION) throw JsonParseException("Unsupported travel protocol")
        return TravelTicket(
            activity = ActivityKind.valueOf(root.string("activity")),
            destinationServer = root.string("destinationServer"),
            createdAtMs = root.long("createdAtMs"),
            expiresAtMs = root.long("expiresAtMs"),
        ).validated()
    }

    private fun JsonObject.string(name: String): String = get(name)?.takeUnless { it.isJsonNull }?.asString
        ?: throw JsonParseException("Missing $name")

    private fun JsonObject.optionalString(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.int(name: String): Int = get(name)?.takeUnless { it.isJsonNull }?.asInt
        ?: throw JsonParseException("Missing $name")

    private fun JsonObject.long(name: String): Long = get(name)?.takeUnless { it.isJsonNull }?.asLong
        ?: throw JsonParseException("Missing $name")

    private fun JsonObject.optionalLong(name: String): Long? = get(name)?.takeUnless { it.isJsonNull }?.asLong

    companion object {
        const val EVENT_CHANNEL = "arc:farms:v1:events"
        const val WORKDAY_KEY = "arc:farms:v1:workday"
        const val WORKDAY_FIELD = "state"
        const val TRAVEL_KEY = "arc:farms:v1:travel"
        private const val PROTOCOL_VERSION = 1
        private const val MAX_EVENT_CHARS = 2_048
        private const val MAX_WORKDAY_CHARS = 1_024
        private const val MAX_TRAVEL_CHARS = 512
        private const val MAX_TRAVEL_TICKETS = 10_000
        private const val MAX_TRAVEL_CLEANUP = 512
        private const val MAX_CAS_ATTEMPTS = 12
    }
}

private val SERVER_ID_PATTERN = Regex("[a-z0-9_-]{1,32}")
