package dev.handypvz.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotOrderingTest {
    @Test
    fun sameSenderRequiresStrictlyNewerRevision() {
        assertTrue(acceptSnapshot(null, -1, null, "phone-a", 1, null))
        assertTrue(acceptSnapshot("phone-a", 3, null, "phone-a", 4, null))
        assertFalse(acceptSnapshot("phone-a", 3, null, "phone-a", 3, null))
        assertFalse(acceptSnapshot("phone-a", 3, null, "phone-a", 2, null))
    }

    @Test
    fun changingSenderRequiresCurrentRequestNonce() {
        assertFalse(acceptSnapshot("phone-a", 5, null, "phone-b", 1, null))
        assertFalse(acceptSnapshot("phone-a", 5, "new-request", "phone-b", 1, "old-request"))
        assertTrue(acceptSnapshot("phone-a", 5, "new-request", "phone-b", 1, "new-request"))
        assertFalse(acceptSnapshot("phone-b", 1, null, "phone-a", 6, "old-request"))
    }
}
