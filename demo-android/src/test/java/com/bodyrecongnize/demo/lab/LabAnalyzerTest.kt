package com.bodyrecongnize.demo.lab

import org.junit.Assert.*
import org.junit.Test

class LabAnalyzerTest {
    private fun frame(t: Long, bent: Boolean = false): LabFrame {
        val p = FloatArray(99)
        fun put(i: Int, x: Float, y: Float) { p[i * 3] = x; p[i * 3 + 1] = y; p[i * 3 + 2] = .9f }
        for (side in 0..1) {
            val x = .4f + side * .2f
            put(11 + side, x, .1f); put(13 + side, x, .25f); put(15 + side, x, .4f)
            put(23 + side, x, if (bent) .5f else .35f); put(25 + side, x, .6f)
            put(27 + side, if (bent) x + .15f else x, if (bent) .6f else .85f)
        }
        return LabFrame(t, 100, 100, p, "f.png", 10.0)
    }
    private val start = LabMeasure(0, 0f, 180f, 180f, true)
    private val bent = LabMeasure(0, 1f, 90f, 90f, true)
    private val finish = LabMeasure(1, 0f, 180f, 180f, true)
    @Test fun fullNativeCycleProducesOneClassifiedEventAndCoversTimeline() {
        val frames = listOf(frame(0), frame(400, true), frame(800), frame(1200))
        val measures = LabKind.values().associateWith { k -> if (k == LabKind.SQUAT) listOf(start, bent, finish, finish) else List(4) { null } }
        val (events, timeline) = LabAnalyzer.analyze(frames, measures)
        assertEquals(1, events.count { it.complete })
        assertEquals(LabKind.SQUAT, events.first().kind)
        assertEquals(frames.indices.toList(), timeline.flatMap { (it.start..it.end).toList() })
        assertNull(timeline.last().kind)
    }
    @Test fun shallowReturnDoesNotBecomeComplete() {
        val frames = listOf(frame(0), frame(400, true), frame(800))
        val events = LabAnalyzer.candidates(LabKind.SQUAT, frames, listOf(start, bent.copy(progress = .5f), start))
        assertEquals(1, events.size); assertEquals("shallow", events.single().status)
        assertTrue(events.single().reason.contains("50%")); assertFalse(events.single().complete)
    }
    @Test fun missingFrameDiscardsIncompleteCycleAndDoesNotInventError() {
        val frames = List(4) { frame(it * 400L) }
        val events = LabAnalyzer.candidates(LabKind.SQUAT, frames, listOf(start, bent, null, finish))
        assertEquals(1, events.size); assertEquals("missing", events.single().status)
        assertTrue(events.single().reason.contains("不能据此判定姿势错误"))
        assertFalse(events.any { it.complete })
    }
    @Test fun unknownSkeletonCannotBeClassifiedByNativeCountAlone() {
        val frames = List(3) { frame(it * 400L).copy(points = FloatArray(99)) }
        val measures = LabKind.values().associateWith { listOf(start, bent, finish) }
        val (events, timeline) = LabAnalyzer.analyze(frames, measures)
        assertTrue(events.isEmpty()); assertTrue(timeline.all { it.kind == null })
    }
    @Test fun startsInTargetAndTruncatedCycleCannotCreateCounts() {
        val frames = List(3) { frame(it * 400L) }
        val targetFirst = LabAnalyzer.candidates(LabKind.SQUAT, frames, listOf(bent.copy(known = false), start, start))
        assertTrue(targetFirst.isEmpty())
        val truncated = LabAnalyzer.candidates(LabKind.SQUAT, frames.take(2), listOf(start, bent))
        assertEquals("unfinished", truncated.single().status)
    }
    @Test fun scaledCoordinatesYieldSameJointAngle() {
        val square = frame(0, true)
        val points = square.points.copyOf()
        for (i in 0..32) points[i * 3 + 1] *= .5f
        val portrait = square.copy(height = 200, points = points)
        assertEquals(square.knee!!, portrait.knee!!, .001f)
    }
    @Test fun answersOnlyCiteSelectedActualEvent() {
        assertTrue(LabAnalyzer.answer("为什么没算", null, null).contains("没有实际分析记录"))
        val event = LabEvent("e", LabKind.SQUAT, 0, 1, 1, .5f, "shallow", "实际原因")
        val run = LabRun("r", "实际输入", "原片", listOf(frame(0), frame(400)), listOf(event), emptyList(), 500, 0)
        val answer = LabAnalyzer.answer("为什么没算", event, run)
        assertTrue(answer.contains("实际原因")); assertTrue(answer.contains("00:00.4"))
    }
    @Test fun knownVideoCutCannotCompleteAnArtificialCycle() {
        val frames = listOf(frame(0), frame(400, true).copy(cut = true), frame(800))
        val events = LabAnalyzer.candidates(LabKind.SQUAT, frames, listOf(start, bent.copy(known = false), finish))
        assertFalse(events.any { it.complete })
    }
    @Test fun upperBodyMotionIsSeparatedByOrientationAndJointTrajectory() {
        fun upper(t: Long, kind: LabKind, target: Boolean): LabFrame {
            val p = FloatArray(99)
            fun put(i: Int, x: Float, y: Float) { p[i * 3] = x; p[i * 3 + 1] = y; p[i * 3 + 2] = .9f }
            for (side in 0..1) {
                val x = .35f + side * .3f
                put(11 + side, x, .25f); put(23 + side, x, .65f)
                put(25 + side, x, .8f); put(27 + side, x, .95f)
                when (kind) {
                    LabKind.ARM_RAISE -> { put(13 + side, x, if (target) .1f else .43f); put(15 + side, x, if (target) .02f else .6f) }
                    LabKind.LATERAL -> { put(13 + side, if (target) x + if (side == 0) -.2f else .2f else x, if (target) .25f else .43f); put(15 + side, x, .6f) }
                    else -> { put(13 + side, x, .43f); put(15 + side, if (target) x + .1f else x, if (target) .3f else .6f) }
                }
            }
            return LabFrame(t, 100, 100, p, "f.png", 1.0)
        }
        listOf(LabKind.ARM_RAISE, LabKind.LATERAL, LabKind.CURL).forEach { kind ->
            val frames = listOf(upper(0, kind, false), upper(400, kind, true), upper(800, kind, false))
            assertTrue(kind.name, LabAnalyzer.classify(kind, frames) > 0)
            assertEquals(0f, LabAnalyzer.classify(LabKind.SQUAT, frames), 0f)
        }
        fun push(t: Long, target: Boolean): LabFrame {
            val p = FloatArray(99)
            fun put(i: Int, x: Float, y: Float) { p[i * 3] = x; p[i * 3 + 1] = y; p[i * 3 + 2] = .9f }
            for (side in 0..1) {
                val y = .25f + side * .1f
                put(11 + side, .2f, y); put(23 + side, .6f, y); put(25 + side, .8f, y); put(27 + side, .95f, y)
                put(13 + side, .2f, y + .2f); put(15 + side, if (target) .4f else .2f, if (target) y + .2f else y + .4f)
            }
            return LabFrame(t, 100, 100, p, "f.png", 1.0)
        }
        assertTrue(LabAnalyzer.classify(LabKind.PUSH_UP, listOf(push(0,false),push(400,true),push(800,false))) > 0)
    }
    @Test fun mixedCyclesKeepSeparateKindsAndPreserveUnknownGap() {
        fun push(t: Long, bentArm: Boolean): LabFrame {
            val p = FloatArray(99)
            fun put(i: Int, x: Float, y: Float) { p[i * 3] = x; p[i * 3 + 1] = y; p[i * 3 + 2] = .9f }
            for (side in 0..1) {
                val y = .2f + side * .1f
                put(11 + side, .2f, y); put(23 + side, .6f, y); put(25 + side, .8f, y); put(27 + side, .95f, y)
                put(13 + side, .2f, y + .2f); put(15 + side, if (bentArm) .4f else .2f, if (bentArm) y + .2f else y + .4f)
            }
            return LabFrame(t, 100, 100, p, "push.png", 1.0)
        }
        val frames = listOf(frame(0), frame(400,true), frame(800), frame(1200), push(1600,false),push(2000,true),push(2400,false))
        val measures = LabKind.values().associateWith { kind -> when(kind) {
            LabKind.SQUAT -> listOf(start,bent,finish,finish,finish,finish,finish)
            LabKind.PUSH_UP -> listOf(null,null,null,null,start,bent,finish)
            else -> List(7) { null }
        } }
        val (events, segments) = LabAnalyzer.analyze(frames, measures)
        assertEquals(listOf(LabKind.SQUAT,LabKind.PUSH_UP),events.filter { it.complete }.map { it.kind })
        assertEquals(listOf(LabKind.SQUAT,null,LabKind.PUSH_UP),segments.map { it.kind })
        assertEquals(frames.indices.toList(),segments.flatMap { (it.start..it.end).toList() })
    }
}
