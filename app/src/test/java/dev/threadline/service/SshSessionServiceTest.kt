package dev.threadline.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SshSessionServiceTest {
    @Test
    fun `missing notification permission rejects prepared connection before SSH starts`() {
        var permissionFailureCount = 0
        var connectionStartCount = 0

        val started = connectPreparedWithNotificationPermission(
            permissionGranted = false,
            onPermissionMissing = { permissionFailureCount += 1 },
            connectPrepared = {
                connectionStartCount += 1
                true
            },
        )

        assertFalse(started)
        assertEquals(1, permissionFailureCount)
        assertEquals(0, connectionStartCount)
    }

    @Test
    fun `granted notification permission allows prepared connection to start`() {
        var permissionFailureCount = 0
        var connectionStartCount = 0

        val started = connectPreparedWithNotificationPermission(
            permissionGranted = true,
            onPermissionMissing = { permissionFailureCount += 1 },
            connectPrepared = {
                connectionStartCount += 1
                true
            },
        )

        assertTrue(started)
        assertEquals(0, permissionFailureCount)
        assertEquals(1, connectionStartCount)
    }
}
