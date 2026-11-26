package dev.handypvz

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(isNewerVersion("1.0.1", "1.0.1"))
    }

    @Test
    fun higherVersionIsNewer() {
        assertTrue(isNewerVersion("1.0.2", "1.0.1"))
        assertTrue(isNewerVersion("1.1.0", "1.0.9"))
        assertTrue(isNewerVersion("2.0.0", "1.9.9"))
    }

    @Test
    fun lowerVersionIsNotNewer() {
        assertFalse(isNewerVersion("1.0.0", "1.0.1"))
        assertFalse(isNewerVersion("0.9.9", "1.0.0"))
    }

    @Test
    fun differentLengthIsHandled() {
        assertTrue(isNewerVersion("1.0.1.1", "1.0.1"))
        assertFalse(isNewerVersion("1.0", "1.0.0"))
    }
}
