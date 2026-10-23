package com.bodyrecongnize.demo.coach

import org.junit.Assert.*
import org.junit.Test

class LocalCoachChatTest {
    private val context = LocalCoachChat.Context("深蹲", "训练中", 2, 5, 7, 2, 3, "关键点不清楚", "真人视频演示", 35)
    @Test fun progressComesFromTrainingAndLabelsVideo() {
        val reply = LocalCoachChat().reply("还剩几次", context)
        assertTrue(reply.contains("还剩3次"))
        assertTrue(reply.contains("累计7次"))
        assertTrue(reply.contains("视频里的人物"))
    }
    @Test fun whyThenDetailsRetainsTopic() {
        val chat = LocalCoachChat()
        assertTrue(chat.reply("为什么没算", context).contains("关键点不清楚"))
        assertTrue(chat.reply("具体一点", context).contains("平稳往返"))
    }
    @Test fun demoDoesNotPretendToExecuteCommands() {
        assertTrue(LocalCoachChat().reply("换动作", context).contains("暂不"))
        assertTrue(LocalCoachChat().reply("暂停", context).contains("不会直接更改"))
    }
    @Test fun formUsesSelectedExerciseAndUnknownTextHasHonestFallback() {
        val chat = LocalCoachChat()
        assertTrue(chat.reply("动作怎么做", context.copy(exercise = "俯卧撑")).contains("肘角"))
        assertTrue(chat.reply("宇宙飞船", context) in CoachPhrases.phrases.getValue("fallback"))
    }

    @Test fun formCorrectionUsesOnlyTheCurrentMeasuredCue() {
        val chat = LocalCoachChat()
        val unclear = chat.reply("刚才哪里要调整？", context.copy(hint = "关键点不清楚：请让膝盖留在画面内"))
        assertTrue(unclear.contains("不能判断具体动作问题"))
        val shallow = chat.reply("刚才哪里要调整？", context.copy(hint = "本次未达到测试计数幅度"))
        assertTrue(shallow.contains("没有达到当前计数阈值"))
    }

    @Test fun restQuestionIsNotMistakenForAMissedRepetition() {
        val reply = LocalCoachChat().reply("为什么要休息？", context)
        assertTrue(reply.contains("组间休息"))
        assertFalse(reply.contains("才计完整一次"))
    }

    @Test fun recentCorrectionComesFromRecordedEventAfterTheLiveHintChanges() {
        val evidence = CoachSession.Feedback(1, CoachSession.FeedbackKind.RANGE, 2, null, 3500,
            "本次未达到测试计数幅度，未记为完整一次")
        val reply = LocalCoachChat().reply("刚才哪里要调整？", context.copy(
            hint = "站直准备，按自己的舒适幅度开始动作", feedback = listOf(evidence)))
        assertTrue(reply.contains("没有达到当前计数阈值"))
        assertTrue(reply.contains("第2组"))
        assertTrue(reply.contains("视频里的人物"))
    }

    @Test fun missingEvidenceDoesNotTurnIntoAGoodFormClaim() {
        val chat = LocalCoachChat()
        val reply = chat.reply("刚才哪里要调整？", context.copy(hint = "站直准备"))
        assertTrue(reply.contains("没有可追溯的纠正记录"))
        assertFalse(reply.contains("没有检测到明确的角度异常"))
        assertTrue(chat.reply("第9次哪里要调整？", context).contains("没有累计第9次"))
    }

    @Test fun videoEvidenceMatchesTheQuestionAndNeverLinksAnUnrelatedEvent() {
        val complete = CoachSession.Feedback(1, CoachSession.FeedbackKind.COMPLETE, 1, 1, 1000, "已完成一次完整往返动作")
        val range = CoachSession.Feedback(2, CoachSession.FeedbackKind.RANGE, 1, null, 2000, "本次未达到测试计数幅度")
        val c = context.copy(feedback = listOf(complete, range))
        val chat = LocalCoachChat()
        assertNull(chat.evidenceFor("为什么要休息？", c))
        assertEquals(range, chat.evidenceFor("刚才哪里要调整？", c))
        assertEquals(complete, chat.evidenceFor("第1次为什么没计数？", c))
        assertTrue(chat.reply("第1次为什么没计数？", c).contains("已经计入完整次数"))
    }

    @Test fun caloriesQuestionUsesSessionEstimateInsteadOfMatchingHowManyRepetitions() {
        val calories = TrainingCalories.forSession("SQUAT", TrainingCalories.Source.CAMERA, 60.0).estimate(18_000)
        val chat = LocalCoachChat()
        val c = context.copy(source = "摄像头训练", calories = calories)
        val answer = chat.reply("消耗多少卡路里？", c)
        assertTrue(answer.contains("≈ 0.9 千卡"))
        assertTrue(answer.contains("60 kg"))
        assertTrue(answer.contains("估算"))
        assertFalse(answer.contains("还剩"))
        assertTrue(chat.reply("具体一点", c).contains("3.0 MET"))
    }

    @Test fun caloriesAreNotInventedForVideoMissingWeightOrBeforeTraining() {
        val chat = LocalCoachChat()
        assertTrue(chat.reply("热耗多少", context).contains("不计算你的个人"))
        val missing = TrainingCalories.forSession("SQUAT", TrainingCalories.Source.CAMERA, null).estimate(18_000)
        assertTrue(chat.reply("消耗多少", context.copy(source = "摄像头训练", calories = missing)).contains("未填写体重"))
        assertTrue(chat.reply("卡路里", context.copy(source = "", stage = "未开始")).contains("尚未开始"))
    }

    @Test fun calorieQuestionDoesNotAttachAnUnrelatedVideoRepetition() {
        val feedback = CoachSession.Feedback(1, CoachSession.FeedbackKind.COMPLETE, 1, 1, 1000, "完整动作")
        assertNull(LocalCoachChat().evidenceFor("第1次消耗多少千卡", context.copy(feedback = listOf(feedback))))
    }
}
