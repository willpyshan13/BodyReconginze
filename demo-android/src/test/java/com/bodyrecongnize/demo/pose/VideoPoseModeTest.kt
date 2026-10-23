package com.bodyrecongnize.demo.pose

import org.junit.Assert.*
import org.junit.Test

class VideoPoseModeTest {
    @Test fun threeModelComparisonRotatesWithoutDroppingOrDuplicatingAnEngine() {
        val mode = VideoPoseMode.THREE
        repeat(3) { frame ->
            assertEquals(VideoPoseEngine.values().toSet(), mode.executionOrder(frame).toSet())
            assertEquals(3, mode.executionOrder(frame).size)
        }
        for (slot in 0..2) {
            assertEquals(VideoPoseEngine.values().toSet(), (0..2).map { mode.executionOrder(it)[slot] }.toSet())
        }
    }
    @Test fun standaloneMoveNetDoesNotRunEitherOtherModel() {
        assertEquals(listOf(VideoPoseEngine.MOVENET), VideoPoseMode.MOVENET.executionOrder(10))
    }
}
