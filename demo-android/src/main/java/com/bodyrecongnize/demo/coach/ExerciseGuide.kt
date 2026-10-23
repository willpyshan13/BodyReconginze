package com.bodyrecongnize.demo.coach

/** Thresholds match the native counter; text feedback is a heuristic, not a form diagnosis. */
data class ExerciseGuide(val label: String, val angle: String, val target: Float,
                         val preparation: String, val visibility: String, val startPose: String,
                         val symmetryLimit: Float) {
    companion object {
        val all = listOf(
            ExerciseGuide("深蹲", "膝角", .7f, "请侧身站立，让髋、膝、脚踝完整入镜", "髋、膝、脚踝", "站直", 30f),
            ExerciseGuide("双臂上举", "肩角", .8f, "请正面全身入镜，双臂自然放下后开始上举", "肩、肘、手腕和髋", "双臂放下", 35f),
            ExerciseGuide("哑铃弯举", "肘角", .8f, "请让双肩、肘和手腕入镜，从手臂伸展开始弯举", "肩、肘和手腕", "手臂伸展", 35f),
            ExerciseGuide("俯卧撑", "肘角", .7f, "请侧面入镜，让肩、肘、手腕和髋可见，从直臂支撑开始", "至少同一侧肩、肘、手腕和髋", "直臂支撑", 30f),
            ExerciseGuide("侧平举", "肩角", .8f, "请正面全身入镜，双臂放下后向两侧抬起", "肩、肘和髋", "双臂放下", 30f)
        )
    }
}
