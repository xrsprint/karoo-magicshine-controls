package com.lenne0815.karoomagicshine.extension

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Owns read-only polling for exactly one live BLE connection. */
internal class ConnectedSession(private val scope: CoroutineScope) {
    companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }

    private var job: Job? = null

    fun start(
        connected: Flow<Boolean>,
        poll: suspend () -> Unit,
        onPollError: (Exception) -> Unit,
        onDisconnected: suspend () -> Unit,
    ) {
        stop()
        job = scope.launch {
            coroutineScope {
                val polling = launch {
                    while (isActive) {
                        try {
                            poll()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            onPollError(error)
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                }
                try {
                    connected.first { !it }
                } finally {
                    polling.cancelAndJoin()
                }
            }
            onDisconnected()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

// Nordic GATT writes must finish before cancelling a repeating command. Cancelling
// a write in flight can tear down the connection just as FLASH restores its state.
internal suspend fun <T> completeGattWrite(write: suspend () -> T): T =
    withContext(NonCancellable) {
        try {
            withTimeout(1_500) { write() }
        } catch (timeout: TimeoutCancellationException) {
            // A transport timeout is a failed operation, not cancellation of the poller.
            throw IllegalStateException("GATT write timed out", timeout)
        }
    }

/** A failed OFF write must never prevent releasing the GATT connection. */
internal suspend fun disconnectSafely(
    turnOff: suspend () -> Unit,
    disconnect: suspend () -> Unit,
    cleanup: () -> Unit,
    onError: (Exception) -> Unit,
) {
    try {
        try {
            withTimeout(1_500) { turnOff() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onError(error)
        }
    } finally {
        try {
            withContext(NonCancellable) {
                withTimeout(3_000) { disconnect() }
            }
        } catch (error: Exception) {
            onError(error)
        } finally {
            cleanup()
        }
    }
}
