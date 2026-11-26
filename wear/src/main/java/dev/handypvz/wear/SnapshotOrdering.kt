package dev.handypvz.wear

/** An unknown sender must answer the most recent request before replacing the active sender. */
internal fun acceptSnapshot(
    currentSource: String?, currentRevision: Long, pendingNonce: String?,
    source: String, revision: Long, requestNonce: String?
): Boolean = when {
    currentSource == null -> true
    currentSource == source -> revision > currentRevision
    else -> pendingNonce != null && pendingNonce == requestNonce
}
