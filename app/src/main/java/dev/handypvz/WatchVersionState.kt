package dev.handypvz

enum class WatchVersionState { UNKNOWN, UPDATE_REQUIRED, CURRENT }

/** The phone's own version is intentionally not part of this comparison. */
fun watchVersionState(installedCode: Int?, requiredCode: Int): WatchVersionState = when {
    installedCode == null || installedCode < 0 -> WatchVersionState.UNKNOWN
    installedCode < requiredCode -> WatchVersionState.UPDATE_REQUIRED
    else -> WatchVersionState.CURRENT
}
