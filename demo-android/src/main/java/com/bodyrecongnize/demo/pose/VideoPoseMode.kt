package com.bodyrecongnize.demo.pose

enum class VideoPoseEngine(val title: String, val recordPrefix: String) {
    ML_KIT("ML Kit", "mlKit"), RTMPOSE("RTMPose-t", "rtmpose"), MOVENET("MoveNet Lightning", "moveNet")
}

enum class VideoPoseMode(val label: String, val engines: List<VideoPoseEngine>) {
    ML_KIT("ML Kit（绿）", listOf(VideoPoseEngine.ML_KIT)),
    RTMPOSE("RTMPose-t（蓝）", listOf(VideoPoseEngine.RTMPOSE)),
    TWO("同帧对比：ML Kit + RTMPose-t", listOf(VideoPoseEngine.ML_KIT, VideoPoseEngine.RTMPOSE)),
    MOVENET("MoveNet Lightning（紫）", listOf(VideoPoseEngine.MOVENET)),
    THREE("三模型同帧对比：绿 / 蓝 / 紫", VideoPoseEngine.values().toList());

    /** Rotate execution order so each model gets the first/second/third slot equally often. */
    fun executionOrder(frame: Int) = engines.indices.map { engines[(it + frame) % engines.size] }
}
