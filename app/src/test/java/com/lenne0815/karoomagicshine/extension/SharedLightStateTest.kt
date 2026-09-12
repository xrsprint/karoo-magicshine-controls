package com.lenne0815.karoomagicshine.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLightStateTest {
    @Test
    fun allActiveOutputsAndModesAreOnWithoutASeparateToggleFlag() {
        for (target in listOf(SharedLightState.OutputTarget.LOW, SharedLightState.OutputTarget.HIGH)) {
            for (mode in SharedLightState.Mode.entries) {
                assertTrue(snapshot(target, mode).isOn)
            }
        }
    }

    @Test
    fun offIsOffEvenWhenPreviousOutputWasOn() {
        assertFalse(snapshot(SharedLightState.OutputTarget.OFF, SharedLightState.Mode.STEADY).isOn)
    }

    private fun snapshot(target: SharedLightState.OutputTarget, mode: SharedLightState.Mode) =
        SharedLightState.Snapshot(target, 75, mode, SharedLightState.OutputTarget.HIGH, 100, mode)
}
