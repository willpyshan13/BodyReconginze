package com.bodyrecongnize.demo.coach

/** Training flow around real cumulative repetitions from the native angle-based squat counter. */
class CoachSession(val plan: Plan = Plan(), val guide: ExerciseGuide = ExerciseGuide.all.first()) {
    data class Plan(val sets: Int = 2, val reps: Int = 3, val restMs: Long = 5_000, val readyMs: Long = 3_000) {
        init { require(sets in 1..10 && reps in 1..30 && restMs >= 0 && readyMs >= 0) }
    }
    enum class Stage { IDLE, PREPARING, ACTIVE, REST, PAUSED, FINISHED }
    enum class FeedbackKind { COMPLETE, RANGE, FAST, SYMMETRY }
    data class Feedback(val id: Int, val kind: FeedbackKind, val set: Int,
                        val repetition: Int?, val timeMs: Long, val message: String)
    data class Observation(val repetitions: Int, val progress: Float, val leftAngle: Float,
                           val rightAngle: Float, val lastScore: Float, val knownPhase: Boolean,
                           val symmetryAvailable: Boolean = true, val sourceTimeMs: Long? = null)
    data class Snapshot(val stage: Stage, val set: Int, val reps: Int, val totalReps: Int,
                        val completedSets: Int, val remainingSeconds: Int, val hint: String,
                        val activeMs: Long, val averageIndex: Float?, val minimumKnee: Float?,
                        val shallowAttempts: Int, val fastReps: Int, val completed: Boolean,
                        val eventId: Int, val cue: String?, val feedback: List<Feedback> = emptyList())

    var stage = Stage.IDLE
        private set
    private var savedStage = Stage.IDLE
    private var deadline = 0L
    private var savedRemaining = 0L
    private var lastClock = 0L
    private var beganAt = 0L
    private var trackingUntil = 0L
    private var activeMs = 0L
    private var sourceReps = 0
    private var reps = 0
    private var completedSets = 0
    private var totalReps = 0
    private var scoreTotal = 0f
    private var scoreSamples = 0
    private var smallestKnee = 180f
    private var observedKnee = false
    private var peakProgress = 0f
    private var repStart = -1L
    private var shallow = 0
    private var fast = 0
    private var trackingMissing = false
    private var lastWarning = -10_000L
    private var hint = "选择真人视频测试或摄像头训练"
    private var event = 0
    private var cue: String? = null
    private var completed = false
    private val feedback = mutableListOf<Feedback>()
    private var feedbackId = 0
    private var stickyHint: String? = null
    private var stickyUntil = 0L
    private var asymmetrySince = -1L
    private var symmetryRecorded = false

    private fun advance(now: Long) {
        require(now >= lastClock) { "Coach clock must be monotonic" }
        if (stage == Stage.ACTIVE) activeMs += (minOf(now, trackingUntil) - lastClock).coerceAtLeast(0)
        lastClock = now
    }
    private fun record(kind: FeedbackKind, now: Long, text: String, repetition: Int? = null, sourceTimeMs: Long? = null) {
        feedback.add(Feedback(++feedbackId, kind, minOf(completedSets + 1, plan.sets),
            repetition, sourceTimeMs ?: (now - beganAt).coerceAtLeast(0), text))
        if (feedback.size > 100) feedback.removeAt(0)
    }
    private fun hold(text: String, now: Long) {
        stickyHint = text; stickyUntil = now + 3000; hint = text
    }
    private fun announce(key: String, text: String) { event++; cue = key; hint = text }
    fun start(now: Long): Snapshot {
        check(stage == Stage.IDLE)
        lastClock = now
        beganAt = now
        stage = Stage.PREPARING
        deadline = now + plan.readyMs
        announce("ready", guide.preparation)
        return snapshot(now)
    }
    fun tick(now: Long): Snapshot {
        advance(now)
        if ((stage == Stage.PREPARING || stage == Stage.REST) && now >= deadline) {
            stage = Stage.ACTIVE
            reps = 0
            peakProgress = 0f
            repStart = -1
            trackingUntil = now
            stickyHint = null
            symmetryRecorded = false
            asymmetrySince = -1
            announce(if (completedSets == 0) "start" else "next", "第${completedSets + 1}组开始，先${guide.startPose}准备")
        }
        return snapshot(now)
    }
    fun observe(observation: Observation?, now: Long): Snapshot {
        tick(now)
        if (stage != Stage.ACTIVE) {
            if (observation != null) sourceReps = observation.repetitions
            return snapshot(now)
        }
        if (observation == null) {
            trackingMissing = true
            trackingUntil = now
            peakProgress = 0f
            repStart = -1
            stickyHint = null
            asymmetrySince = -1
            hint = "关键点不清楚：请让${guide.visibility}留在画面内"
            if (now - lastWarning >= 8_000) {
                lastWarning = now
                announce("tracking", hint)
            }
            return snapshot(now)
        }
        val o = observation
        require(o.repetitions >= 0 && listOf(o.progress, o.leftAngle, o.rightAngle, o.lastScore).all { it.isFinite() })
        trackingMissing = false
        trackingUntil = if (o.knownPhase) now + 1000 else now
        if (o.knownPhase) {
            val knee = (o.leftAngle + o.rightAngle) / 2
            smallestKnee = minOf(smallestKnee, knee)
            observedKnee = true
        }
        val delta = (o.repetitions - sourceReps).coerceAtLeast(0)
        sourceReps = o.repetitions
        val movementTime = o.sourceTimeMs ?: now
        if (o.progress > .2f && repStart < 0) repStart = movementTime
        peakProgress = maxOf(peakProgress, o.progress)
        if (delta > 0) {
            val accepted = minOf(delta, plan.reps - reps)
            val duration = if (repStart >= 0) movementTime - repStart else 0
            if (duration in 1..1199) fast += accepted
            val feedbackText = if (duration in 1..1199) "已计数，节奏较快，可放慢往返速度" else "已完成一次完整往返动作"
            repeat(accepted) { index ->
                record(if (duration in 1..1199) FeedbackKind.FAST else FeedbackKind.COMPLETE,
                    now, feedbackText, totalReps + index + 1, o.sourceTimeMs)
            }
            totalReps += accepted
            reps += accepted
            if (o.symmetryAvailable) {
                scoreTotal += o.lastScore.coerceIn(0f, 100f) * accepted
                scoreSamples += accepted
            }
            peakProgress = 0f
            repStart = -1
            symmetryRecorded = false; asymmetrySince = -1
            announce("rep_$reps", feedbackText)
            hold(feedbackText, now)
            if (reps >= plan.reps) {
                completedSets++
                if (completedSets == plan.sets) {
                    completed = true
                    stage = Stage.FINISHED
                    announce("finish", "测试完成，可以查看本次训练总结")
                } else {
                    stage = Stage.REST
                    deadline = now + plan.restMs
                    announce("rest", "第${completedSets}组完成，休息后进入下一组")
                }
            }
        } else if (o.progress <= .2f && peakProgress >= .25f && peakProgress < guide.target) {
            shallow++
            peakProgress = 0f
            repStart = -1
            hint = "本次未达到测试计数幅度，未记为完整一次"
            record(FeedbackKind.RANGE, now, hint, sourceTimeMs = o.sourceTimeMs)
            hold(hint, now)
            if (now - lastWarning >= 5_000) { lastWarning = now; announce("range", hint) }
        } else {
            val asymmetric = o.symmetryAvailable && o.knownPhase && o.progress > .2f &&
                kotlin.math.abs(o.leftAngle - o.rightAngle) > guide.symmetryLimit
            if (asymmetric) {
                if (asymmetrySince < 0) asymmetrySince = now
                if (!symmetryRecorded && now - asymmetrySince >= 500) {
                    val warning = "左右角度差较大，请检查站位和动作同步"
                    record(FeedbackKind.SYMMETRY, now, warning, sourceTimeMs = o.sourceTimeMs)
                    hold(warning, now)
                    symmetryRecorded = true
                }
            } else asymmetrySince = -1
            val current = when {
                !o.knownPhase -> "先${guide.startPose}建立起始姿势"
                o.progress >= guide.target -> "已到本次计数目标位，平稳回到${guide.startPose}"
                o.progress > .2f -> "动作进行中，保持平稳"
                else -> "${guide.startPose}准备，按自己的舒适幅度开始动作"
            }
            hint = if (o.knownPhase && now < stickyUntil) stickyHint ?: current else current
            if (o.progress <= .2f) symmetryRecorded = false
        }
        return snapshot(now)
    }
    fun pause(now: Long): Snapshot {
        tick(now)
        if (stage in listOf(Stage.PREPARING, Stage.ACTIVE, Stage.REST)) {
            savedStage = stage
            savedRemaining = (deadline - now).coerceAtLeast(0)
            stage = Stage.PAUSED
            trackingUntil = now
            hint = "已暂停，继续后恢复当前训练"
        }
        return snapshot(now)
    }
    fun resume(now: Long): Snapshot {
        advance(now)
        if (stage == Stage.PAUSED) {
            stage = savedStage
            deadline = now + savedRemaining
            repStart = -1
            trackingUntil = now
            announce("resume", "继续训练，保持全身入镜")
        }
        return snapshot(now)
    }
    fun skipRest(now: Long): Snapshot {
        tick(now)
        if (stage == Stage.REST) { deadline = now; return tick(now) }
        return snapshot(now)
    }
    /** A camera/input reset invalidates unfinished motion, while accepted training totals remain. */
    fun rebaseCounter() { sourceReps = 0; peakProgress = 0f; repStart = -1; trackingUntil = lastClock }
    fun finish(now: Long): Snapshot {
        advance(now)
        if (stage != Stage.FINISHED && stage != Stage.IDLE) {
            stage = Stage.FINISHED
            announce("stopped", "本次测试已结束，已保存当前完成情况")
        }
        return snapshot(now)
    }
    fun snapshot(now: Long) = Snapshot(stage, minOf(completedSets + 1, plan.sets), reps,
        totalReps, completedSets,
        if (stage == Stage.PREPARING || stage == Stage.REST) ((deadline - now).coerceAtLeast(0) + 999).div(1000).toInt() else 0,
        hint, activeMs, if (scoreSamples > 0) scoreTotal / scoreSamples else null,
        if (observedKnee) smallestKnee else null, shallow, fast, completed, event, cue, feedback.toList())
}
