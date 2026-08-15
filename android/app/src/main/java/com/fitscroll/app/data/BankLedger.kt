package com.fitscroll.app.data

/**
 * One immutable thing that happened to the bank.
 *
 * [id] is generated on the device that recorded it, which is what makes
 * uploading idempotent: a retry after a dropped connection collides on the
 * primary key instead of banking the same set twice.
 */
data class BankEvent(
    val id: String,
    val kind: Kind,
    /** Always positive. [kind] carries the direction. */
    val seconds: Int,
    /**
     * When it happened on the device that recorded it, not when it synced.
     *
     * Expiry is anchored here, so a phone that was offline for an hour does not
     * gain an hour of extra shelf life on the minutes it earned while away.
     */
    val occurredAt: Long,
) {
    enum class Kind { EARN, SPEND }
}

/**
 * Rebuilds a balance from an event log.
 *
 * The synced bank is an append-only log rather than a stored number, because
 * devices go offline and a number cannot be merged: whichever device wrote
 * second would silently erase the other's work. A log can be merged, and this
 * fold is what turns it back into something spendable.
 *
 * The fold is deterministic. Events are replayed in `occurredAt` order with
 * ties broken by id, and each is applied against the balance *as it stood at
 * that moment* — a spend can only consume credits that were live when it
 * happened. Any device holding the same events therefore computes the same
 * balance, in any order they happened to arrive.
 */
object BankLedger {

    /**
     * How far back events remain relevant.
     *
     * Nothing older than the 24h expiry window can affect a balance, so clients
     * only ever need to fetch or keep a little more than a day. The margin
     * covers clock skew between devices.
     */
    const val RELEVANT_WINDOW_MILLIS: Long = BankMath.EXPIRY_MILLIS + 60L * 60L * 1000L

    /**
     * Replays [events] into the credits they leave behind.
     *
     * @param capSeconds the bank ceiling to apply as each earn is replayed.
     */
    fun fold(events: List<BankEvent>, now: Long, capSeconds: Int): List<Credit> {
        var credits = emptyList<Credit>()

        for (event in ordered(events)) {
            credits = when (event.kind) {
                BankEvent.Kind.EARN ->
                    BankMath.earnSeconds(credits, event.seconds, event.occurredAt, capSeconds).credits

                // A spend larger than the balance simply takes what is there.
                // Two devices scrolling offline at once can overspend between
                // syncs; clamping rather than going negative keeps the merged
                // result sane without inventing minutes to claw back.
                BankEvent.Kind.SPEND ->
                    BankMath.spend(credits, event.seconds, event.occurredAt).credits
            }
        }

        return BankMath.purge(credits, now)
    }

    fun balanceSeconds(events: List<BankEvent>, now: Long, capSeconds: Int): Int =
        fold(events, now, capSeconds).sumOf { it.remainingSeconds }

    /**
     * Merges two logs, keeping one copy of each event.
     *
     * De-duplication is by id, so the same event arriving from the server and
     * from the local queue collapses to one rather than being counted twice.
     */
    fun merge(local: List<BankEvent>, remote: List<BankEvent>): List<BankEvent> =
        ordered(local + remote)

    /** Drops events too old to influence any future balance. */
    fun prune(events: List<BankEvent>, now: Long): List<BankEvent> =
        events.filter { now - it.occurredAt <= RELEVANT_WINDOW_MILLIS }

    /**
     * Canonical replay order.
     *
     * Sorting by id as well as time is not cosmetic: two devices can record
     * events in the same millisecond, and without a tie-break they would each
     * fold them in their own arrival order and disagree about the balance.
     */
    private fun ordered(events: List<BankEvent>): List<BankEvent> =
        events.distinctBy { it.id }.sortedWith(
            compareBy({ it.occurredAt }, { it.id }),
        )
}
