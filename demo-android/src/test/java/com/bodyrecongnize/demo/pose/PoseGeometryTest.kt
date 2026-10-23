package com.bodyrecongnize.demo.pose

import org.junit.Assert.*
import org.junit.Test

class PoseGeometryTest {
    @Test fun portraitAndLandscapeMapModelCenterToSourceCenter() {
        listOf(360 to 640, 640 to 360).forEach { (w, h) ->
            val transform = PoseGeometry(w, h)
            assertEquals(w / 2f, transform.sourceX(192), .001f)
            assertEquals(h / 2f, transform.sourceY(256), .001f)
            assertEquals(.75f, transform.scaleWidth / transform.scaleHeight, .001f)
            assertTrue(transform.scaleWidth >= w * 1.25f)
            assertTrue(transform.scaleHeight >= h * 1.25f)
        }
    }
    @Test fun paddedImageEdgesRoundTripWithoutStretching() {
        val transform = PoseGeometry(360, 640)
        assertEquals(transform.left, transform.sourceX(0), .001f)
        assertEquals(transform.top, transform.sourceY(0), .001f)
        assertEquals(transform.left + transform.scaleWidth, transform.sourceX(384), .001f)
        assertEquals(transform.top + transform.scaleHeight, transform.sourceY(512), .001f)
    }
    @Test fun cocoMappingKeepsSidesAndDoesNotFabricateFootLandmarks() {
        val map = PoseGeometry.COCO_TO_OVERLAY
        assertEquals(17, map.toSet().size)
        assertEquals(11, map[5]); assertEquals(12, map[6])
        assertEquals(27, map[15]); assertEquals(28, map[16])
        assertFalse(map.any { it in 29..32 })
    }
}
