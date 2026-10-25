package com.music.bitchord.data.net

/**
 * When to use the backup proxy, and when to go back to the primary.
 *
 * The decision is made only at the moments a request already gives us an
 * answer — a proxy request failed, a proxy request succeeded — so nothing here
 * polls, schedules or wakes the radio. Kept a pure function because getting it
 * wrong is expensive in exactly one direction: a dead primary must move traffic
 * to the backup at once, while a healthy backup must not be abandoned on a
 * timer that fires before the primary has had a chance to answer again.
 *
 * Concretely the lifecycle is:
 *
 *  - a failure on [PRIMARY] with a backup configured moves to [BACKUP];
 *  - a failure on [BACKUP] leaves the route there for the policy, but the
 *    caller records "the proxy is down" and retries directly (see
 *    [ProxyHealthMonitor]);
 *  - a success on [BACKUP] keeps it until [PRIMARY_RETRY_AFTER_MS] has passed,
 *    after which that success is taken as the signal to try the primary again;
 *    a failed primary then moves back to the backup, so the listener loses one
 *    request (which the retry masks) at most once in that window rather than on
 *    every request;
 *  - with no backup every answer leaves the route at [PRIMARY], which is the
 *    old behaviour.
 */
object ProxyRoutePolicy {

    /**
     * How long the backup is trusted before the next successful proxy request
     * is allowed to send the following one to the primary again.
     *
     * Long enough that a dead primary costs roughly one failed connect per
     * quarter of an hour, short enough that a recovered primary is used again
     * within the session instead of only after a restart.
     */
    const val PRIMARY_RETRY_AFTER_MS = 15L * 60L * 1000L

    /**
     * The route a request should use after the proxy route just failed.
     *
     * A configured backup is always the answer: from the primary it is the
     * move this exists for, and from the backup it is the server that has just
     * failed, so the caller follows this with a direct retry.
     */
    fun nextRouteAfterFailure(hasBackup: Boolean): ProxyRoute =
        if (hasBackup) ProxyRoute.BACKUP else ProxyRoute.PRIMARY

    /**
     * The route for the next request after a proxy request succeeded on
     * [current], [msSinceSwitch] after the route last changed.
     */
    fun nextRouteAfterSuccess(
        current: ProxyRoute,
        hasBackup: Boolean,
        msSinceSwitch: Long,
    ): ProxyRoute = when {
        !hasBackup -> ProxyRoute.PRIMARY
        current != ProxyRoute.BACKUP -> ProxyRoute.PRIMARY
        msSinceSwitch >= PRIMARY_RETRY_AFTER_MS -> ProxyRoute.PRIMARY
        else -> ProxyRoute.BACKUP
    }
}
