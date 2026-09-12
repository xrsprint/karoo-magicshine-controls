package com.lenne0815.karoomagicshine.extension

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedSessionTest {
    @Test
    fun gattTimeoutIsRetriedInsteadOfSilentlyCancellingTelemetry() = runTest {
        var polls = 0
        var errors = 0
        val session = ConnectedSession(backgroundScope)
        session.start(MutableStateFlow(true), {
            polls++
            completeGattWrite { if (polls == 1) awaitCancellation() }
        }, { errors++ }, {})
        runCurrent()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, errors)
        advanceTimeBy(ConnectedSession.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, polls)
        session.stop()
    }

    @Test
    fun stoppingFlashLetsInFlightWriteFinishBeforeRestore() = runTest {
        val events = mutableListOf<String>()
        val job = launch {
            completeGattWrite {
                events += "write started"
                delay(100)
                events += "write finished"
            }
            delay(1_500)
            events += "unexpected repeat"
        }
        runCurrent()
        job.cancelAndJoin()
        events += "restore"
        assertEquals(listOf("write started", "write finished", "restore"), events)
    }

    @Test
    fun pollsImmediatelyThenEveryThirtySecondsAndStopsOnLinkLoss() = runTest {
        val connected = MutableStateFlow(true)
        var polls = 0
        var disconnects = 0
        val session = ConnectedSession(backgroundScope)
        session.start(connected, { polls++ }, { throw it }, { disconnects++ })
        runCurrent()
        assertEquals(1, polls)
        advanceTimeBy(ConnectedSession.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, polls)
        connected.value = false
        runCurrent()
        assertEquals(1, disconnects)
        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(2, polls)
    }

    @Test
    fun failedPollDoesNotDisableFutureTelemetry() = runTest {
        var polls = 0
        var errors = 0
        val session = ConnectedSession(backgroundScope)
        session.start(MutableStateFlow(true), {
            polls++
            if (polls == 1) error("Temporary write failure")
        }, { errors++ }, {})
        runCurrent()
        advanceTimeBy(ConnectedSession.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, polls)
        assertEquals(1, errors)
        session.stop()
    }

    @Test
    fun replacingSessionCancelsOldPollingAndObserver() = runTest {
        val oldConnection = MutableStateFlow(true)
        var oldPolls = 0
        var newPolls = 0
        var oldDisconnects = 0
        val session = ConnectedSession(backgroundScope)
        session.start(oldConnection, { oldPolls++ }, { throw it }, { oldDisconnects++ })
        runCurrent()
        session.start(MutableStateFlow(true), { newPolls++ }, { throw it }, {})
        runCurrent()
        oldConnection.value = false
        advanceTimeBy(ConnectedSession.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(1, oldPolls)
        assertEquals(2, newPolls)
        assertEquals(0, oldDisconnects)
        session.stop()
    }

    @Test
    fun explicitStopCancelsInFlightPollWithoutReportingLinkFailure() = runTest {
        var cancelled = false
        var disconnected = false
        val session = ConnectedSession(backgroundScope)
        session.start(MutableStateFlow(true), {
            try { awaitCancellation() } finally { cancelled = true }
        }, { throw it }, { disconnected = true })
        runCurrent()
        session.stop()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(disconnected)
    }

    @Test
    fun offWriteFailureStillDisconnectsAndCleansUp() = runTest {
        val events = mutableListOf<String>()
        disconnectSafely(
            turnOff = { events += "off"; error("GATT write failed") },
            disconnect = { events += "disconnect" },
            cleanup = { events += "cleanup" },
            onError = { events += "error" },
        )
        assertEquals(listOf("off", "error", "disconnect", "cleanup"), events)
    }

    @Test
    fun disconnectFailureStillCleansUp() = runTest {
        var cleaned = false
        var errors = 0
        disconnectSafely({}, { error("GATT disconnect failed") }, { cleaned = true }, { errors++ })
        assertTrue(cleaned)
        assertEquals(1, errors)
    }

    @Test
    fun cancellationStillDisconnectsAndIsNotSwallowed() = runTest {
        var disconnected = false
        var cleaned = false
        var cancelled = false
        val job = launch {
            try {
                disconnectSafely(
                    { awaitCancellation() },
                    { disconnected = true },
                    { cleaned = true },
                    { throw it },
                )
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(disconnected)
        assertTrue(cleaned)
        assertTrue(cancelled)
    }

    @Test
    fun stuckDisconnectTimesOutAndCleansUp() = runTest {
        var cleaned = false
        var errors = 0
        disconnectSafely({}, { awaitCancellation() }, { cleaned = true }, { errors++ })
        assertTrue(cleaned)
        assertEquals(1, errors)
    }

    @Test
    fun stuckOffWriteTimesOutButStillDisconnectsAndCleansUp() = runTest {
        var disconnected = false
        var cleaned = false
        var timedOut = false
        try {
            disconnectSafely(
                { awaitCancellation() },
                { disconnected = true },
                { cleaned = true },
                { throw it },
            )
        } catch (_: CancellationException) {
            timedOut = true
        }
        assertTrue(timedOut)
        assertTrue(disconnected)
        assertTrue(cleaned)
    }
}
