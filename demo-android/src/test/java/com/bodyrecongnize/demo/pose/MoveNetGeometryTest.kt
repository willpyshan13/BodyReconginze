package com.bodyrecongnize.demo.pose

import org.junit.Assert.*
import org.junit.Test

class MoveNetGeometryTest {
    @Test fun portraitLetterboxReversesHorizontalPadding() {
        val geometry = MoveNetGeometry(360, 640)
        assertEquals(180f, geometry.sourceX(.5f), .001f)
        assertEquals(320f, geometry.sourceY(.5f), .001f)
        assertEquals(0f, geometry.sourceX(140f / 640f), .001f)
        assertEquals(360f, geometry.sourceX(500f / 640f), .001f)
        assertEquals(0f, geometry.sourceY(0f), .001f)
    }
    @Test fun landscapeLetterboxReversesVerticalPadding() {
        val geometry = MoveNetGeometry(640, 360)
        assertEquals(0f, geometry.sourceY(140f / 640f), .001f)
        assertEquals(360f, geometry.sourceY(500f / 640f), .001f)
        assertEquals(640f, geometry.sourceX(1f), .001f)
    }
}
