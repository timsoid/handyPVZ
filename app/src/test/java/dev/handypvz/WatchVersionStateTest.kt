package dev.handypvz

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchVersionStateTest {
    @Test
    fun reportsUnknownWithoutAWatchResponse() {
        assertEquals(WatchVersionState.UNKNOWN, watchVersionState(null, 3))
        assertEquals(WatchVersionState.UNKNOWN, watchVersionState(-1, 3))
    }

    @Test
    fun comparesOnlyAgainstTheRequiredWatchVersion() {
        assertEquals(WatchVersionState.UPDATE_REQUIRED, watchVersionState(0, 3))
        assertEquals(WatchVersionState.UPDATE_REQUIRED, watchVersionState(2, 3))
        assertEquals(WatchVersionState.CURRENT, watchVersionState(3, 3))
        assertEquals(WatchVersionState.CURRENT, watchVersionState(4, 3))
    }
}
