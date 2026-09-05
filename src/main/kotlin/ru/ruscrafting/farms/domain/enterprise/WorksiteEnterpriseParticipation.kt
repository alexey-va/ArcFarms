package ru.ruscrafting.farms.domain.enterprise

import ru.ruscrafting.farms.domain.ActivityKind
import java.util.UUID

enum class WorksiteEnterprisePlan { STEADY, TEAM, CHALLENGE }

data class WorksiteEnterpriseBallot(
    val playerId: UUID,
    val plan: WorksiteEnterprisePlan,
    val shareholder: Boolean,
    val acceptedWeight: Int = 0,
)

data class WorksiteEnterpriseProjectProgress(
    val contributions: Int,
    val completedMilestones: Int,
    val nextMilestone: Int?,
    val personalContributions: Int = 0,
    val personalCompletedOrders: Int = 0,
)

data class WorksiteEnterprisePlanTotals(
    val shareholderWeights: Map<WorksiteEnterprisePlan, Int>,
    val advisoryCounts: Map<WorksiteEnterprisePlan, Int>,
)

data class WorksiteEnterpriseParticipationView(
    val activity: ActivityKind,
    val companyId: String,
    val currentWeek: Long?,
    val targetPlan: WorksiteEnterprisePlan,
    val ballots: WorksiteEnterprisePlanTotals,
    val project: WorksiteEnterpriseProjectProgress,
)

data class WorksiteEnterpriseParticipationSnapshot(
    val currentWeeks: Map<String, Long> = emptyMap(),
    val ballots: Map<String, List<WorksiteEnterpriseBallot>> = emptyMap(),
    val lastCompletedSequenceByWorksite: Map<String, Long> = emptyMap(),
    val weeklyActivity: Map<String, Map<UUID, Int>> = emptyMap(),
    val cumulativeContributions: Map<String, Int> = emptyMap(),
    val completedOrders: Map<String, Int> = emptyMap(),
    val playerContributions: Map<String, Map<UUID, Int>> = emptyMap(),
    val playerCompletedOrders: Map<String, Map<UUID, Int>> = emptyMap(),
    val selectedPlans: Map<String, WorksiteEnterprisePlan> = emptyMap(),
)

enum class WorksiteEnterpriseVoteOutcome { ACCEPTED, STALE_WEEK, ALREADY_VOTED, NOT_ELIGIBLE }
data class WorksiteEnterpriseVoteResult(val outcome: WorksiteEnterpriseVoteOutcome, val changed: Boolean)

data class WorksiteEnterpriseAdvanceResult(
    val changed: Boolean,
    val plan: WorksiteEnterprisePlan,
    val quorumMet: Boolean,
    val shareholderWeight: Int,
    val outstandingShares: Int,
)

/** Pure, bounded governance and participation state. No platform or money concerns. */
internal class WorksiteEnterpriseParticipationLedger {
    private var state = WorksiteEnterpriseParticipationSnapshot()

    fun replace(snapshot: WorksiteEnterpriseParticipationSnapshot?): Boolean {
        val normalized = snapshot ?: WorksiteEnterpriseParticipationSnapshot()
        validate(normalized)
        val changed = normalized != state
        state = normalized
        return changed
    }

    fun snapshot(): WorksiteEnterpriseParticipationSnapshot = state

    fun recordCompleted(
        activity: ActivityKind,
        companyId: String,
        worksiteId: String,
        sequence: Long,
        weekStart: Long,
        contributions: Map<UUID, Int>,
    ): Boolean {
        validateIds(companyId, worksiteId, weekStart, sequence)
        val company = key(activity, companyId)
        val worksite = key(activity, worksiteId)
        if (sequence <= (state.lastCompletedSequenceByWorksite[worksite] ?: -1L)) return false
        val positive = contributions.filterValues { it > 0 }
        if (positive.isEmpty()) return false
        require(positive.size <= MAX_PARTICIPANTS) { "Enterprise contributors are unbounded" }
        val activityKey = activityWeek(company, weekStart)
        val updatedActivity = state.weeklyActivity + (activityKey to mergeActivity(
            state.weeklyActivity[activityKey].orEmpty(), positive,
        ))
        state = state.copy(
            lastCompletedSequenceByWorksite = state.lastCompletedSequenceByWorksite + (worksite to sequence),
            weeklyActivity = pruneActivity(updatedActivity, company, weekStart),
            cumulativeContributions = state.cumulativeContributions + (company to addCount(
                state.cumulativeContributions[company] ?: 0, 1,
            )),
            completedOrders = state.completedOrders + (company to addCount(state.completedOrders[company] ?: 0, 1)),
            playerContributions = state.playerContributions + (company to mergeCounts(
                state.playerContributions[company].orEmpty(), positive,
            )),
            playerCompletedOrders = state.playerCompletedOrders + (company to mergeCounts(
                state.playerCompletedOrders[company].orEmpty(), positive.keys.associateWith { 1 },
            )),
        )
        return true
    }

    fun vote(
        activity: ActivityKind,
        companyId: String,
        playerId: UUID,
        plan: WorksiteEnterprisePlan,
        expectedWeek: Long,
        holdings: Map<UUID, Int>,
    ): WorksiteEnterpriseVoteResult {
        validateIds(companyId, companyId, expectedWeek, expectedWeek)
        validateHoldings(holdings)
        val company = key(activity, companyId)
        val current = state.currentWeeks[company]
        if (current == null || expectedWeek != current + WEEK_LENGTH) {
            return WorksiteEnterpriseVoteResult(WorksiteEnterpriseVoteOutcome.STALE_WEEK, false)
        }
        val ballotKey = ballotKey(company, expectedWeek)
        if (state.ballots[ballotKey].orEmpty().any { it.playerId == playerId }) {
            return WorksiteEnterpriseVoteResult(WorksiteEnterpriseVoteOutcome.ALREADY_VOTED, false)
        }
        val shares = (holdings[playerId] ?: 0).coerceAtLeast(0)
        val shareholder = shares > 0
        val recent = state.weeklyActivity[activityWeek(company, current)].orEmpty()[playerId] ?: 0
        val last = state.weeklyActivity[activityWeek(company, current - WEEK_LENGTH)].orEmpty()[playerId] ?: 0
        if (!shareholder && recent <= 0 && last <= 0) return WorksiteEnterpriseVoteResult(WorksiteEnterpriseVoteOutcome.NOT_ELIGIBLE, false)
        val ballot = WorksiteEnterpriseBallot(playerId, plan, shareholder, if (shareholder) shares else 0)
        state = state.copy(ballots = state.ballots + (ballotKey to (state.ballots[ballotKey].orEmpty() + ballot)))
        return WorksiteEnterpriseVoteResult(WorksiteEnterpriseVoteOutcome.ACCEPTED, true)
    }

    fun advance(
        activity: ActivityKind,
        companyId: String,
        expectedWeek: Long,
        currentHoldings: Map<UUID, Int>,
    ): WorksiteEnterpriseAdvanceResult {
        validateIds(companyId, companyId, expectedWeek, expectedWeek)
        validateHoldings(currentHoldings)
        val company = key(activity, companyId)
        val old = state.currentWeeks[company]
        if (old != null && expectedWeek <= old) {
            return WorksiteEnterpriseAdvanceResult(false, plan(company), false, 0, currentHoldings.values.sum())
        }
        val votes = state.ballots[ballotKey(company, expectedWeek)].orEmpty()
        val outstanding = currentHoldings.values.sum()
        val weights = votes.filter { it.shareholder }.groupingBy { it.plan }.fold(0) { total, ballot -> Math.addExact(total, currentHoldings[ballot.playerId] ?: 0) }
        val total = weights.values.sum()
        val quorum = outstanding > 0 && total.toLong() * 100L >= outstanding.toLong() * QUORUM_PERCENT
        val winner = if (quorum) WorksiteEnterprisePlan.entries.maxByOrNull { weights[it] ?: 0 }?.takeIf {
            (weights[it] ?: 0) > 0 && weights.values.count { value -> value == (weights[it] ?: 0) } == 1
        } else null
        val chosen = winner ?: plan(company)
        val changed = old != expectedWeek || winner != null
        state = state.copy(
            currentWeeks = state.currentWeeks + (company to expectedWeek),
            selectedPlans = state.selectedPlans + (company to chosen),
            // Keep only a future click window; skipped rollovers retire old ballots.
            ballots = state.ballots.filterKeys { ballotKey ->
                if (!ballotKey.startsWith("$company:")) true
                else ballotKey.substringAfterLast(':').toLongOrNull()?.let { it > expectedWeek } ?: false
            },
        )
        return WorksiteEnterpriseAdvanceResult(changed, chosen, quorum, total, outstanding)
    }

    fun view(activity: ActivityKind, companyId: String, playerId: UUID? = null): WorksiteEnterpriseParticipationView {
        val company = key(activity, companyId)
        val week = state.currentWeeks[company]
        val votes = week?.let { state.ballots[ballotKey(company, it + WEEK_LENGTH)] }.orEmpty()
        val shareholderWeights = WorksiteEnterprisePlan.entries.associateWith { plan -> votes.filter { it.shareholder && it.plan == plan }.sumOf { it.acceptedWeight } }
        val advisoryCounts = WorksiteEnterprisePlan.entries.associateWith { plan -> votes.count { !it.shareholder && it.plan == plan } }
        val contributions = state.completedOrders[company] ?: 0
        val personal = playerId?.let { state.playerContributions[company].orEmpty()[it] } ?: 0
        val personalOrders = playerId?.let { state.playerCompletedOrders[company].orEmpty()[it] } ?: 0
        return WorksiteEnterpriseParticipationView(activity, companyId, week, plan(company), WorksiteEnterprisePlanTotals(shareholderWeights, advisoryCounts), project(company, contributions, personal, personalOrders))
    }

    fun canAdvise(activity: ActivityKind, companyId: String, playerId: UUID): Boolean {
        val company = key(activity, companyId)
        val week = state.currentWeeks[company] ?: return false
        return (state.weeklyActivity[activityWeek(company, week)].orEmpty()[playerId] ?: 0) > 0 ||
            (state.weeklyActivity[activityWeek(company, week - WEEK_LENGTH)].orEmpty()[playerId] ?: 0) > 0
    }

    fun selectedBallot(activity: ActivityKind, companyId: String, playerId: UUID): WorksiteEnterpriseBallot? {
        val company = key(activity, companyId)
        val week = state.currentWeeks[company] ?: return null
        return state.ballots[ballotKey(company, week + WEEK_LENGTH)].orEmpty().firstOrNull { it.playerId == playerId }
    }

    private fun plan(company: String): WorksiteEnterprisePlan = state.selectedPlans[company] ?: WorksiteEnterprisePlan.TEAM
    private fun project(company: String, total: Int, personal: Int, orders: Int) = WorksiteEnterpriseProjectProgress(total, MILESTONES.count { total >= it }, MILESTONES.firstOrNull { total < it }, personal, orders)

    private companion object {
        const val WEEK_LENGTH = 7L
        const val QUORUM_PERCENT = 20
        const val MAX_PARTICIPANTS = 10_000
        const val MAX_COMPANIES = 256
        const val MAX_COUNT = 1_000_000_000
        val MILESTONES = intArrayOf(10, 25, 50)
        fun key(activity: ActivityKind, id: String) = "${activity.name.lowercase()}:$id"
        fun activityWeek(company: String, week: Long) = "$company:$week"
        fun ballotKey(company: String, week: Long) = "$company:$week"
        fun validateIds(company: String, worksite: String, week: Long, sequence: Long) {
            require(company.matches(Regex("[a-z0-9_-]{1,48}")))
            require(worksite.matches(Regex("[a-z0-9_-]{1,48}")))
            require(week >= 0 && sequence >= 0)
        }
        fun addCount(a: Int, b: Int) = Math.addExact(a, b)
        fun validateHoldings(holdings: Map<UUID, Int>) {
            require(holdings.size <= MAX_PARTICIPANTS && holdings.values.all { it >= 0 }) { "Enterprise holdings are invalid" }
            require(holdings.values.sumOf { it.toLong() } <= MAX_COUNT) { "Enterprise holdings exceed safe bounds" }
        }
        fun mergeCounts(a: Map<UUID, Int>, b: Map<UUID, Int>): Map<UUID, Int> = (a.keys + b.keys).associateWith { Math.addExact(a[it] ?: 0, b[it] ?: 0) }.also { require(it.size <= MAX_PARTICIPANTS) }
        fun mergeActivity(a: Map<UUID, Int>, b: Map<UUID, Int>) = mergeCounts(a, b)
        fun pruneActivity(values: Map<String, Map<UUID, Int>>, company: String, week: Long): Map<String, Map<UUID, Int>> =
            values.filterKeys { key ->
                if (!key.startsWith("$company:")) true
                else key.substringAfterLast(':').toLongOrNull()?.let { it >= week - WEEK_LENGTH } ?: false
            }
        fun validate(snapshot: WorksiteEnterpriseParticipationSnapshot) {
            require(snapshot.lastCompletedSequenceByWorksite.size <= MAX_PARTICIPANTS)
            require(snapshot.lastCompletedSequenceByWorksite.values.all { it >= 0L })
            require(snapshot.ballots.size <= MAX_COMPANIES * 2)
            require(snapshot.weeklyActivity.size <= MAX_COMPANIES * 3)
            require(snapshot.cumulativeContributions.size <= MAX_COMPANIES)
            require(snapshot.playerContributions.size <= MAX_COMPANIES)
            require(snapshot.playerCompletedOrders.size <= MAX_COMPANIES)
            require(snapshot.selectedPlans.size <= MAX_COMPANIES)
            require(snapshot.ballots.values.all { ballots -> ballots.size <= MAX_PARTICIPANTS && ballots.map { it.playerId }.distinct().size == ballots.size })
            require(snapshot.currentWeeks.values.all { it >= 0L })
            require(snapshot.cumulativeContributions.values.all { it in 0..MAX_COUNT })
            require(snapshot.completedOrders.values.all { it in 0..MAX_COUNT })
            require(snapshot.weeklyActivity.values.all { it.size <= MAX_PARTICIPANTS && it.values.all { value -> value > 0 } })
            require(snapshot.playerContributions.values.all { it.size <= MAX_PARTICIPANTS && it.values.all { value -> value >= 0 } })
            require(snapshot.playerCompletedOrders.values.all { it.size <= MAX_PARTICIPANTS && it.values.all { value -> value >= 0 } })
            require(snapshot.ballots.values.flatten().all { it.acceptedWeight >= 0 && (it.shareholder || it.acceptedWeight == 0) })
        }
    }
}
