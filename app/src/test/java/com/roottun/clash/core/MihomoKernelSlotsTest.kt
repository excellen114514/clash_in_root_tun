package com.roottun.clash.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoKernelSlotsTest {
    private val old = KernelRef("v2.0.0", "0".repeat(64))
    private val active = KernelRef("v2.1.0", "1".repeat(64))
    private val update = KernelRef("v2.2.0", "2".repeat(64))
    private val newer = KernelRef("v2.3.0", "3".repeat(64))

    @Test fun successfulUpdateKeepsPreviousAndConcurrentPending() {
        val plan = KernelSlots(active = active, pending = update, previous = old)
        assertEquals(listOf(update, active, old, null), plan.bootOrder())
        val persisted = plan.copy(pending = newer).afterBoot(update, plan, emptySet())
        assertEquals(update, persisted.active)
        assertEquals(active, persisted.previous)
        assertEquals(newer, persisted.pending)
    }

    @Test fun failedPendingIsQuarantinedWhenFallbackWorks() {
        val plan = KernelSlots(active = active, pending = update, previous = old)
        val persisted = plan.afterBoot(active, plan, setOf(update))
        assertNull(persisted.pending)
        assertEquals(update.tag, persisted.newest)
        assertEquals(listOf(active, old, null), persisted.bootOrder())
    }

    @Test fun concurrentBundledRequestIsPreservedUntilNextStart() {
        val plan = KernelSlots(active = active, pending = update)
        val rollback = plan.copy(pending = null, rollback = true).afterBoot(update, plan, emptySet())
        assertTrue(rollback.rollback)
        assertEquals(listOf(null, update, active), rollback.bootOrder())
        val persisted = rollback.afterBoot(null, rollback, emptySet())
        assertNull(persisted.active)
        assertEquals(update, persisted.previous)
        assertEquals(listOf(null, update), persisted.bootOrder())
    }
}
