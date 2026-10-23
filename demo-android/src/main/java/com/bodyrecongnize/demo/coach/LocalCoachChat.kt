package com.bodyrecongnize.demo.coach

/** Offline scripted dialogue demo. Never invents measured training results. */
class LocalCoachChat {
    data class Context(val exercise: String, val stage: String, val reps: Int, val target: Int,
                       val total: Int, val set: Int, val sets: Int, val hint: String,
                       val source: String, val activeSeconds: Long,
                       val feedback: List<CoachSession.Feedback> = emptyList(),
                       val calories: TrainingCalories.Estimate? = null)
    private val phrases = CoachPhrases()
    private var topic = ""
    fun reset() { topic = "" }
    fun evidenceFor(question: String, context: Context): CoachSession.Feedback? {
        if (isCalorieQuestion(question)) return null
        val ordinal = Regex("第\\s*(\\d+)\\s*次").find(question)?.groupValues?.get(1)?.toIntOrNull()
        if (ordinal != null) return context.feedback.lastOrNull { it.repetition == ordinal }
        if (listOf("休息", "暂停", "累", "剩", "进度", "几次", "多少").any { question.contains(it) }) return null
        if (listOf("没算", "不算", "没计", "计数").any { question.contains(it) })
            return context.feedback.lastOrNull { it.kind == CoachSession.FeedbackKind.RANGE }
        if (listOf("调整", "纠正", "哪里", "动作问题", "慢一点").any { question.contains(it) })
            return context.feedback.lastOrNull { it.kind != CoachSession.FeedbackKind.COMPLETE }
        return null
    }
    fun reply(message: String, context: Context): String {
        val text = message.trim().lowercase()
        if (text.isEmpty()) return "可以输入问题，也可以点下面的快捷提问。"
        val detail = text.contains("详细") || text.contains("具体") || text.contains("怎么做")
        val answer = when {
            isCalorieQuestion(text) || detail && topic == "calories" -> {
                topic = "calories"
                calorieReply(context)
            }
            listOf("大模型", "联网", "是真人吗", "智能吗").any { text.contains(it) } ->
                "我是本地预设对话演示，不连接大模型或网络。训练数字来自当前页面，其余回答来自内置话术。"
            listOf("累", "休息", "暂停").any { text.contains(it) } -> {
                topic = "rest"
                phrases.next("rest") + "组间休息用于调整状态，时间可在开始前设置。请使用训练页面的暂停或继续按钮；聊天不会直接更改训练。"
            }
            listOf("没算", "不算", "没计", "不计", "计数", "识别").any { text.contains(it) } -> {
                topic = "count"
                val recent = context.feedback.lastOrNull { it.kind == CoachSession.FeedbackKind.RANGE }
                val ordinal = Regex("第\\s*(\\d+)\\s*次").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val counted = ordinal?.let { n -> context.feedback.lastOrNull { it.repetition == n } }
                if (counted != null) "记录里累计第${ordinal}次已经计入完整次数：${counted.message}。"
                else "当前识别提示：${context.hint}。" +
                    (recent?.let { "最近一次未计数记录：第${it.set}组，${it.message}。" } ?: "") +
                    "只有从起始位到目标位再返回，才计完整一次；关键点缺失或幅度不足可能不计数。"
            }
            listOf("剩", "几次", "进度", "多少", "总结").any { text.contains(it) } -> {
                topic = "progress"
                "当前${context.exercise}，${context.stage}；第${context.set}/${context.sets}组，本组${context.reps}/${context.target}次，本组还剩${(context.target - context.reps).coerceAtLeast(0)}次，累计${context.total}次，训练计时${context.activeSeconds}秒。" +
                    if (context.source == "真人视频演示") "这些数字来自视频里的人物。" else if (context.source.isEmpty()) "目前尚未开始训练。" else ""
            }
            listOf("调整", "纠正", "哪里不对", "刚才哪里", "动作问题", "慢一点").any { text.contains(it) } ||
                Regex("第\\s*\\d+\\s*次").containsMatchIn(text) -> {
                topic = "form"
                adjustment(text, context)
            }
            listOf("换", "开始", "结束", "设置", "计划").any { text.contains(it) } ->
                "可以在训练页面选择动作并手动设置组数、次数和休息时间。正在训练时先结束，再调整。这里先演示文字交流，暂不自动生成计划或执行操作。"
            listOf("动作", "姿势", "注意", "怎么", "详细", "具体").any { text.contains(it) } -> {
                if (detail && topic == "count") {
                    "检查画面是否能看到所需关节，再从起始姿势完成一次平稳往返。当前提示仍是：${context.hint}。角度规则只能做计数参考，无法全面评价姿势。"
                } else {
                    topic = "form"
                    exerciseTip(context.exercise) + "画面中的角度提示只是规则参考。"
                }
            }
            listOf("你好", "嗨", "在吗").any { text.contains(it) } -> phrases.next("hello") + "当前选择的是${context.exercise}。"
            text.contains("谢谢") -> phrases.next("thanks")
            listOf("加油", "鼓励", "坚持", "没动力", "不想练").any { text.contains(it) } -> phrases.next("encourage")
            listOf("随机", "聊聊", "无聊", "说点", "陪我").any { text.contains(it) } -> phrases.next("casual")
            else -> phrases.next("fallback")
        }
        return answer
    }

    private fun isCalorieQuestion(text: String) = listOf("热量", "热耗", "卡路里", "千卡", "kcal", "消耗")
        .any { text.lowercase().contains(it) }

    private fun calorieReply(context: Context): String {
        if (context.source == "真人视频演示" || context.calories?.status == TrainingCalories.Status.VIDEO)
            return "这是视频人物的动作分析，不计算你的个人热量消耗。"
        if (context.source.isEmpty() || context.stage == "未开始") return "目前尚未开始本人训练。可以先在训练设置里填写体重，开练后按有效训练时长估算热量。"
        val calories = context.calories ?: return "本次没有可用的热量估算依据，不能推测消耗。"
        return when (calories.status) {
            TrainingCalories.Status.ESTIMATED -> calories.summaryLabel + "。\n" + calories.explanation()
            TrainingCalories.Status.MISSING_WEIGHT -> "本次未填写体重，没有估算热量。可以在下一次训练前填写体重。"
            TrainingCalories.Status.UNSUPPORTED -> "当前动作暂无适用的热量估算依据，不能根据次数推测消耗。"
            else -> "本次没有可用的热量估算依据，不能推测消耗。"
        }
    }

    private fun adjustment(question: String, context: Context): String {
        val ordinal = Regex("第\\s*(\\d+)\\s*次").find(question)?.groupValues?.get(1)?.toIntOrNull()
        val record = if (ordinal != null) context.feedback.lastOrNull { it.repetition == ordinal }
            else context.feedback.lastOrNull { it.kind != CoachSession.FeedbackKind.COMPLETE }
        if (ordinal != null && record == null) return "当前保留的记录里没有累计第${ordinal}次动作的反馈，不能推测那一次的问题。"
        val cue = record?.message ?: context.hint
        val detail = when {
            cue.contains("关键点") -> "没有获得足够清晰的关节点，所以我不能判断具体动作问题。请让${context.exercise}需要的部位完整入镜，再试一次。"
            cue.contains("角度差") -> "识别到左右关节角度差较大。可以检查机位是否歪斜、身体是否偏向一侧，并让两侧动作更同步。这只是角度规则提示。"
            cue.contains("未达到") -> "动作幅度没有达到当前计数阈值，所以没有记为完整次数。可以在舒适范围内多完成一些幅度，不必勉强。"
            cue.contains("较快") -> "这次动作的往返时间较短，可以稍微放慢速度。这条提示来自计数时间，不代表完整姿势评分。"
            record?.kind == CoachSession.FeedbackKind.COMPLETE -> "这次记录确认了完整往返；现有指标没有覆盖全部姿势问题，不能据此判断姿势完全正确。"
            else -> "当前还没有可追溯的纠正记录，不能仅凭眼前提示判断刚才的动作没有问题。${exerciseTip(context.exercise)}"
        }
        val location = record?.let {
            "记录依据：第${it.set}组${it.repetition?.let { n -> "，累计第${n}次" } ?: "的一次尝试"}，输入时间${it.timeMs / 1000}秒。"
        }.orEmpty()
        return location + detail + if (context.source == "真人视频演示") "这条反馈来自视频里的人物。" else ""
    }

    private fun exerciseTip(exercise: String) = when (exercise) {
        "深蹲" -> "深蹲测试请侧身全身入镜，保持髋、膝、踝可见，从站立开始完成下蹲和站起。"
        "俯卧撑" -> "俯卧撑测试请侧面入镜，让同一侧肩、肘、手腕和髋可见，从直臂支撑开始完成下降和撑起。先确认身体方向，再按肘角计数。"
        "哑铃弯举" -> "弯举测试请让肩、肘、手腕可见，手臂从伸展到弯曲再返回。当前无法判断是否持有哑铃。"
        "侧平举" -> "侧平举测试请正面入镜，让肩、肘、髋可见，双臂从放下到向两侧抬起再返回。"
        else -> "双臂上举测试请正面全身入镜，双臂从自然放下到上举再返回，让肩、肘、手腕和髋保持可见。"
    }
}
