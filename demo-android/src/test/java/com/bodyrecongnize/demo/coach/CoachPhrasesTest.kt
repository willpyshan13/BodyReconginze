package com.bodyrecongnize.demo.coach

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CoachPhrasesTest {
    @Test fun consumesEveryPhraseBeforeRepeatingAndAvoidsBoundaryDuplicate() {
        val picker = CoachPhrases(Random(42))
        CoachPhrases.phrases.forEach { (category, options) ->
            val cycle = List(options.size) { picker.next(category) }
            assertEquals(options.toSet(), cycle.toSet())
            assertNotEquals(cycle.last(), picker.next(category))
        }
    }
    @Test fun randomChatKeepsMeasurementAnswersFactual() {
        val chat = LocalCoachChat()
        val context = LocalCoachChat.Context("深蹲", "训练中", 1, 3, 4, 2, 2, "动作进行中", "真人视频演示", 10)
        val replies = List(10) { chat.reply("随机聊聊", context) }
        assertEquals(10, replies.toSet().size)
        val progress = chat.reply("还剩几次", context)
        assertTrue(progress.contains("还剩2次"))
        assertTrue(progress.contains("累计4次"))
    }
}
