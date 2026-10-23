package com.bodyrecongnize.demo.coach

import kotlin.random.Random

/** Shuffle each category before reuse, avoiding adjacent duplicate phrases. */
class CoachPhrases(private val random: Random = Random.Default) {
    private val decks = mutableMapOf<String, MutableList<String>>()
    private val previous = mutableMapOf<String, String>()
    fun next(category: String): String {
        val options = phrases.getValue(category)
        val deck = decks.getOrPut(category) { mutableListOf() }
        if (deck.isEmpty()) {
            deck.addAll(options.shuffled(random))
            if (deck.size > 1 && deck.last() == previous[category]) {
                val last = deck.lastIndex
                val temporary = deck[0]; deck[0] = deck[last]; deck[last] = temporary
            }
        }
        return deck.removeAt(deck.lastIndex).also { previous[category] = it }
    }
    companion object {
        val phrases = mapOf(
            "hello" to listOf(
                "你好，今天想先体验哪个动作？", "我在，我们可以边练边聊。", "欢迎回来，先看看动作和训练目标是否合适。",
                "你好，先从熟悉页面和动作开始吧。", "准备好时再开始，不需要赶时间。", "今天也按自己的节奏来。",
                "很高兴见到你，可以问我进度或计数规则。", "你好，训练前可以先调整好入镜位置。"),
            "encourage" to listOf(
                "一步一步来，先把动作做平稳。", "不用和别人比，找到自己的节奏就好。", "有需要就休息，暂停也是训练的一部分。",
                "先关注眼前这一组，不必急着想后面。", "慢一点也没关系，让每个往返更清楚。", "今天先熟悉动作，之后再逐步调整目标。",
                "把注意力放回动作本身，数字只是参考。", "能按自己的状态调整节奏，也是一种进步。",
                "不用追求一次做很多，先体验完整流程。", "训练过程中随时可以暂停，不必硬撑。"),
            "rest" to listOf(
                "可以先放慢节奏，准备好再继续。", "先暂停缓一缓，让自己有一点调整时间。", "需要休息时就休息，不必追赶计时器。",
                "这一会儿先调整呼吸，等状态合适再继续。", "如果今天感觉累，可以减少本次目标。", "训练不是赶进度，休息时间可以手动调整。"),
            "casual" to listOf(
                "要不要先看看这一组的进度？可以直接问我“还剩几次”。", "我们可以聊聊入镜位置，画面清楚会更容易识别关节点。",
                "如果计数没变化，可以先问我“为什么没算”。", "今天可以先熟悉一个动作，再体验其他动作的流程。",
                "小提示：视频演示的次数来自片中人物，会与本人训练分开记录。", "我可以陪你聊训练，不过现在用的是本地预设话术。",
                "想了解当前动作，可以问我“动作怎么做”。", "你可以在训练记录里回看已保存的次数和组数。",
                "这里一直保持文字交流，不会突然播放语音。", "自定义目标可以调整组数、次数和组间休息。"),
            "thanks" to listOf("不客气，我们按自己的节奏来。", "随时可以继续问我训练相关的问题。", "不客气，需要时可以随时暂停。", "收到，祝你体验顺利。"),
            "fallback" to listOf(
                "这个问题我还没有对应的本地话术。可以试试问“还剩几次”或“动作怎么做”。",
                "我目前是预设对话演示，可以结合当前训练回答进度、计数原因和动作说明。",
                "这是预设对话体验。你可以问训练进度，也可以点“随机聊聊”看看训练话题。",
                "我还不能自由理解所有问题，目前可以聊计数、动作说明和休息设置。"))
    }
}
