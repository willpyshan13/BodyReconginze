package com.bodyrecongnize.demo.lab

import kotlin.math.abs

/** Retrospective temporal geometry classifier; match values are NOT calibrated probabilities. */
object LabAnalyzer {
    private fun range(values: List<Float>) = if (values.isEmpty()) 0f else values.maxOrNull()!! - values.minOrNull()!!
    fun classify(kind: LabKind, frames: List<LabFrame>): Float {
        if (frames.size < 3) return 0f
        val knees = frames.mapNotNull { it.knee }
        val elbows = frames.mapNotNull { it.elbow }
        val shoulders = frames.mapNotNull { it.shoulder }
        val orientation = frames.mapNotNull { it.horizontal }
        if (orientation.size < frames.size * .6f) return 0f
        val horizontal = orientation.count { it }.toFloat() / orientation.size
        val upright = horizontal < .25f
        return when (kind) {
            LabKind.SQUAT -> if (upright && knees.size >= frames.size * .7 && range(knees) > 35 &&
                knees.minOrNull()!! < 115 && knees.maxOrNull()!! > 145) (.7f + (range(knees) / 400).coerceAtMost(.25f)) else 0f
            LabKind.PUSH_UP -> if (horizontal > .7f && elbows.size >= frames.size * .6 && range(elbows) > 35)
                .8f else 0f
            LabKind.CURL -> if (upright && elbows.size >= frames.size * .7 && shoulders.size >= frames.size * .7 &&
                range(elbows) > 60 && shoulders.maxOrNull()!! < 75 && range(knees) < 30) .8f else 0f
            LabKind.ARM_RAISE -> if (upright && shoulders.size >= frames.size * .7 &&
                shoulders.maxOrNull()!! > 140 && range(shoulders) > 85 && range(knees) < 30) .85f else 0f
            LabKind.LATERAL -> if (upright && shoulders.size >= frames.size * .7 &&
                shoulders.maxOrNull()!! in 84f..125f && range(shoulders) > 55 && range(knees) < 30) .75f else 0f
        }
    }
    fun candidates(kind: LabKind, frames: List<LabFrame>, measures: List<LabMeasure?>): List<LabEvent> {
        require(frames.size == measures.size)
        val result = mutableListOf<LabEvent>()
        var start = -1; var peak = -1; var peakProgress = 0f; var lastReps = 0; var anchored = false
        fun event(end: Int, status: String, reason: String) {
            if (start < 0 || peak < 0) return
            result.add(LabEvent("${kind.name}-${start}-$end-$status", kind, start, end, peak, peakProgress, status, reason))
            start = -1; peak = -1; peakProgress = 0f
        }
        measures.forEachIndexed { i, m ->
            if (frames[i].cut) {
                if (start >= 0) event((i - 1).coerceAtLeast(start), "discontinuity", "输入在视频剪辑接缝中断，不跨接缝拼接一次动作。")
                anchored = false
            }
            if (m == null) {
                if (start >= 0) event(i, "missing", "该次过程所需关节点缺失，无法确认完整往返；不能据此判定姿势错误。")
                anchored = false
                return@forEachIndexed
            }
            val delta = m.reps > lastReps
            lastReps = m.reps
            if (m.known && m.progress <= .2f) anchored = true
            if (start < 0 && anchored && m.progress > .2f) { start = (i - 1).coerceAtLeast(0); peak = i }
            if (start >= 0 && m.progress >= peakProgress) { peak = i; peakProgress = m.progress }
            if (start >= 0 && delta) event(i, "complete", "计数器确认：从起始位到目标位再返回，记录一次完整动作。")
            else if (start >= 0 && m.progress <= .2f) {
                if (peakProgress in .25f..<kind.threshold) event(i, "shallow", "峰值幅度${(peakProgress * 100).toInt()}%，未达当前${(kind.threshold * 100).toInt()}%计数目标，未计为完整一次。")
                else event(i, "unconfirmed", "未满足计数器的完整周期或时间条件，不补计。")
            }
        }
        if (start >= 0) event(frames.lastIndex, "unfinished", "输入在动作完成前结束，未完整观察到返回过程。")
        return result
    }
    fun analyze(frames: List<LabFrame>, measures: Map<LabKind, List<LabMeasure?>>): Pair<List<LabEvent>, List<LabSegment>> {
        if (frames.isEmpty()) return emptyList<LabEvent>() to emptyList()
        val all = LabKind.values().flatMap { kind -> candidates(kind, frames, measures.getValue(kind)) }
        val proposals = all.filter { it.complete }.map { event ->
            event.copy(match = classify(event.kind, frames.subList(event.start, event.end + 1)))
        }.filter { it.match > 0 }.sortedByDescending { it.match }
        val selected = mutableListOf<LabEvent>()
        for (event in proposals) {
            if (selected.none { other -> event.start < other.end && event.end > other.start }) selected.add(event)
        }
        selected.sortBy { it.start }
        // Only a confirmed cycle can name a segment. Pending/unknown footage stays unknown.
        val groups = mutableListOf<LabSegment>()
        selected.forEach { event ->
            val last = groups.lastOrNull()
            if (last != null && last.kind == event.kind && frames[event.start].timeMs - frames[last.end].timeMs <= 3500 &&
                (last.end + 1..event.start).none { frames[it].cut }) {
                groups[groups.lastIndex] = last.copy(end = event.end, eventIds = last.eventIds + event.id)
            } else groups.add(LabSegment(event.kind, event.start, event.end, listOf(event.id)))
        }
        val events = selected.toMutableList()
        groups.forEach { group ->
            all.filter { !it.complete && it.kind == group.kind && it.start >= group.start && it.end <= group.end }
                .forEach { events.add(it) }
        }
        // Attach adjacent failed attempts only if the same action is confirmed within 2 seconds.
        all.filter { !it.complete }.forEach { candidate ->
            val same = groups.any { group -> group.kind == candidate.kind &&
                (candidate.start <= group.end && candidate.end >= group.start ||
                 kotlin.math.min(abs(frames[candidate.end].timeMs - frames[group.start].timeMs),
                     abs(frames[candidate.start].timeMs - frames[group.end].timeMs)) <= 2000) }
            val conflicts = selected.any { it.kind != candidate.kind && candidate.start < it.end && candidate.end > it.start }
            if (same && !conflicts && events.none { it.id == candidate.id }) events.add(candidate)
        }
        val timeline = mutableListOf<LabSegment>()
        var cursor = 0
        groups.forEach { group ->
            val start = maxOf(cursor, group.start)
            if (start > cursor) timeline.add(LabSegment(null, cursor, start - 1, emptyList()))
            if (group.end >= start) timeline.add(group.copy(start = start))
            cursor = maxOf(cursor, group.end + 1)
        }
        if (cursor < frames.size) timeline.add(LabSegment(null, cursor, frames.lastIndex, emptyList()))
        return events.sortedBy { it.start } to timeline
    }
    fun answer(question: String, event: LabEvent?, run: LabRun?): String {
        if (run == null) return "还没有实际分析记录。先分析视频，再讨论具体动作。"
        if (event == null) return "尚未选中可追溯的动作事件。当前确认${run.completed}次完整动作；无法确定的片段保留为未知。"
        return if (listOf("为什么", "没算", "原因", "详细", "这次", "第").any { question.contains(it) })
            "${event.kind.label}，${time(run.frames[event.start].timeMs)}—${time(run.frames[event.end].timeMs)}：${event.reason}"
        else if (listOf("几次", "多少", "进度").any { question.contains(it) }) "这条记录确认${run.completed}次完整动作。分类是本地时序规则实验结果，不是完整姿势评价。"
        else "目前可根据当前记录解释计数原因和次数。可以问“这次为什么没算”，并打开关联片段核对；没有依据时不会猜测。"
    }
    fun time(ms: Long) = "%02d:%02d.%01d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)
}
