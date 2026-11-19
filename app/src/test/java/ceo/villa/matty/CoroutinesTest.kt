package ceo.villa.matty

import ceo.villa.matty.data.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class CoroutinesTest {
    @Test
    fun realFailuresAreCaught() {
        val r = runCatchingCancellable { throw IOException("offline") }
        assertTrue(r.isFailure)
        assertEquals("offline", r.exceptionOrNull()?.message)
    }

    @Test
    fun successPassesThrough() {
        assertEquals(42, runCatchingCancellable { 42 }.getOrNull())
    }

    @Test
    fun aCancellationIsPassedOnNotCaught() {
        try {
            runCatchingCancellable { throw CancellationException("rememberCoroutineScope left the composition") }
            fail("a cancellation must not be turned into a failure")
        } catch (e: CancellationException) {
            assertEquals("rememberCoroutineScope left the composition", e.message)
        }
    }

    /** The bug the user hit: a screen closing must never surface as an error notice. */
    @Test
    fun aCancelledActionReportsNoFailure() = runBlocking {
        val notices = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { awaitCancellation() }
                .onFailure { notices += it.message ?: "failed" }
        }
        job.cancel(CancellationException("rememberCoroutineScope left the composition"))
        job.join()
        yield()
        assertTrue(job.isCancelled)
        assertFalse("no notice for a cancellation", notices.isNotEmpty())
    }
}
