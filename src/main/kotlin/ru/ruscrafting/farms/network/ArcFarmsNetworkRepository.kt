package ru.ruscrafting.farms.network

import com.google.gson.Gson
import ru.arc.network.BackendServerId
import ru.arc.network.NetworkPlayerName
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.RedisReplyRejection
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisHashConsumeResult
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
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
    val protocolVersion: Int = PROTOCOL_VERSION,
) {
    fun validated(): NetworkEvent = apply {
        require(protocolVersion == PROTOCOL_VERSION) { "Unsupported network event protocol" }
        require(eventId.isCanonicalUuid()) { "Invalid network event id" }
        require(occurredAtMs > 0) { "Invalid network event timestamp" }
        actorName?.let(NetworkPlayerName::of)
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
    val protocolVersion: Int = PROTOCOL_VERSION,
) {
    fun validated(): WorkdayState = apply {
        require(protocolVersion == PROTOCOL_VERSION) { "Unsupported workday protocol" }
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
    val protocolVersion: Int = PROTOCOL_VERSION,
) {
    fun validated(): TravelTicket = apply {
        require(protocolVersion == PROTOCOL_VERSION) { "Unsupported travel protocol" }
        BackendServerId.of(destinationServer)
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
    gson: Gson = Gson(),
) {
    private val eventCodec = codec(
        gson,
        NetworkEvent::class.java,
        EVENT_FIELDS,
        EVENT_REQUIRED_FIELDS,
        MAX_EVENT_CHARS,
        NetworkEvent::validated,
    )
    private val workdayCodec = codec(
        gson,
        WorkdayState::class.java,
        WORKDAY_FIELDS,
        WORKDAY_FIELDS,
        MAX_WORKDAY_CHARS,
        WorkdayState::validated,
    )
    private val travelCodec = codec(
        gson,
        TravelTicket::class.java,
        TRAVEL_FIELDS,
        TRAVEL_FIELDS,
        MAX_TRAVEL_CHARS,
        TravelTicket::validated,
    )
    private val workday = RedisHashUpdater(redis, WORKDAY_KEY, workdayCodec, MAX_CAS_ATTEMPTS)
    private val travel = RedisHashUpdater(redis, TRAVEL_KEY, travelCodec, MAX_CAS_ATTEMPTS)

    fun openEvents(
        originAllowed: (String) -> Boolean,
        replyAllowed: (request: NetworkEvent, reply: NetworkEvent, origin: String) -> Boolean,
        onReplyRejected: (RedisReplyRejection) -> Unit = {},
        listener: (NetworkEvent, String) -> Unit,
    ): RedisRequestReplyChannel<NetworkEvent> = RedisRequestReplyChannel(
        redis = redis,
        channel = EVENT_CHANNEL,
        codec = eventCodec,
        originAllowed = originAllowed,
        requestId = NetworkEvent::eventId,
        replyTo = NetworkEvent::replyTo,
        replyAllowed = replyAllowed,
        timeoutMillis = NODE_REPLY_TIMEOUT_MS,
        maxPending = MAX_PENDING_PROBES,
        replay = RedisReplayPolicy(NetworkEvent::eventId, EVENT_DEDUPLICATION_MS, MAX_SEEN_EVENTS),
        onMessage = listener,
        onReplyRejected = onReplyRejected,
    )

    fun loadWorkday(): CompletableFuture<WorkdayState> =
        redis.loadMapEntries(WORKDAY_KEY, WORKDAY_FIELD).thenApply { values ->
            values.firstOrNull()?.let(workdayCodec::decode) ?: WorkdayState()
        }

    fun markCompleted(activity: ActivityKind): CompletableFuture<WorkdayUpdate> = workday.update(WORKDAY_FIELD) { current ->
        val before = current ?: WorkdayState()
        if (activity in before.completed) {
            RedisHashDecision.Write(before)
        } else {
            val completed = before.completed + activity
            val finished = completed.size == ActivityKind.entries.size
            RedisHashDecision.Write(
                if (finished) WorkdayState(cycle = before.cycle + 1, revision = before.revision + 1)
                else before.copy(revision = before.revision + 1, completed = completed),
            )
        }
    }.thenCompose { result ->
        when (result) {
            is RedisHashUpdateResult.Changed -> {
                val before = result.before ?: WorkdayState()
                val after = requireNotNull(result.after)
                CompletableFuture.completedFuture(
                    if (after.cycle > before.cycle) WorkdayUpdate.Completed(after, before.cycle, activity)
                    else WorkdayUpdate.Stamped(after, activity),
                )
            }
            is RedisHashUpdateResult.Unchanged -> CompletableFuture.completedFuture(WorkdayUpdate.AlreadyStamped(result.current))
            is RedisHashUpdateResult.Rejected -> error("Workday update was unexpectedly rejected")
            is RedisHashUpdateResult.Contended -> loadWorkday().thenApply(WorkdayUpdate::Contended)
        }
    }

    fun createTravelTicket(
        playerId: UUID,
        activity: ActivityKind,
        destinationServer: String,
        nowMs: Long,
        lifetimeMs: Long,
    ): CompletableFuture<Boolean> {
        val ticket = TravelTicket(activity = activity, destinationServer = destinationServer, createdAtMs = nowMs, expiresAtMs = nowMs + lifetimeMs).validated()
        return redis.loadMap(TRAVEL_KEY).thenCompose { current ->
            if (current.size >= MAX_TRAVEL_TICKETS && playerId.toString() !in current) {
                CompletableFuture.completedFuture(false)
            } else {
                travel.update(playerId.toString()) { RedisHashDecision.Write(ticket) }.thenApply { result ->
                    result is RedisHashUpdateResult.Changed || result is RedisHashUpdateResult.Unchanged
                }
            }
        }
    }

    fun claimTravelTicket(playerId: UUID, currentServer: String, nowMs: Long): CompletableFuture<TravelTicket?> {
        BackendServerId.of(currentServer)
        val field = playerId.toString()
        return travel.update(field) { ticket ->
            when {
                ticket == null -> RedisHashDecision.Reject
                ticket.expiresAtMs < nowMs -> RedisHashDecision.Delete
                ticket.destinationServer != currentServer -> RedisHashDecision.Reject
                else -> RedisHashDecision.Delete
            }
        }.thenApply { result ->
            val consumed = (result as? RedisHashUpdateResult.Changed)?.before
            consumed?.takeIf { it.expiresAtMs >= nowMs && it.destinationServer == currentServer }
        }
    }

    fun cleanupExpiredTravelTickets(nowMs: Long): CompletableFuture<Int> = redis.loadMap(TRAVEL_KEY).thenCompose { entries ->
        val expired = entries.entries.mapNotNull { (field, raw) ->
            field.takeIf { travelCodec.decode(raw).expiresAtMs < nowMs }
        }.take(MAX_TRAVEL_CLEANUP)
        val removals = expired.map { field -> travel.consume(field) { it.expiresAtMs < nowMs } }
        CompletableFuture.allOf(*removals.toTypedArray()).thenApply {
            removals.count { it.join() is RedisHashConsumeResult.Consumed }
        }
    }

    companion object {
        const val EVENT_CHANNEL = "arc:farms:v1:events"
        const val WORKDAY_KEY = "arc:farms:v1:workday"
        const val WORKDAY_FIELD = "state"
        const val TRAVEL_KEY = "arc:farms:v1:travel"
        private const val EVENT_DEDUPLICATION_MS = 15 * 60 * 1000L
        private const val NODE_REPLY_TIMEOUT_MS = 30 * 1000L
        private const val MAX_EVENT_CHARS = 2_048
        private const val MAX_WORKDAY_CHARS = 1_024
        private const val MAX_TRAVEL_CHARS = 512
        private const val MAX_TRAVEL_TICKETS = 10_000
        private const val MAX_TRAVEL_CLEANUP = 512
        private const val MAX_CAS_ATTEMPTS = 12
        private const val MAX_SEEN_EVENTS = 4_096
        private const val MAX_PENDING_PROBES = 8
        private val EVENT_FIELDS = setOf(
            "protocolVersion", "eventId", "signal", "activity", "actorName", "cycle", "completed", "replyTo", "occurredAtMs",
        )
        private val EVENT_REQUIRED_FIELDS = EVENT_FIELDS - setOf("activity", "actorName", "cycle", "replyTo")
        private val WORKDAY_FIELDS = setOf("protocolVersion", "cycle", "revision", "completed")
        private val TRAVEL_FIELDS = setOf("protocolVersion", "activity", "destinationServer", "createdAtMs", "expiresAtMs")

        private fun <T : Any> codec(
            gson: Gson,
            type: Class<T>,
            allowedFields: Set<String>,
            requiredFields: Set<String>,
            maxCharacters: Int,
            validate: (T) -> T,
        ): BoundedJsonCodec<T> = BoundedJsonCodec(
            gson = gson,
            type = type,
            rootContract = JsonObjectContract(allowedFields, requiredFields),
            bounds = JsonResourceBounds(
                maxCharacters = maxCharacters,
                maxDepth = 6,
                maxContainerEntries = 16,
                maxTotalNodes = 48,
                maxStringCharacters = 64,
            ),
            validate = { value -> validate(value) },
        )
    }
}

private const val PROTOCOL_VERSION = 1
