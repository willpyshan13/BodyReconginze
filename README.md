## 电脑端动作采集工作台

在电脑浏览器中录制或导入单人视频，人工标注动作区间，使用项目内置 RTMPose-t 模型提取 COCO-17 关键点，并按拍摄者分组导出训练数据。默认静音、只监听本机；默认数据保存在 `tools/motion_collection/data/`，不提交到 Git。导出数据不是训练好的模型，Android 仍需后续接入训练结果或动作规则。

需要 Python 3.12。首次配置及启动：

```sh
python3.12 -m venv tools/motion_collection/.venv
tools/motion_collection/.venv/bin/python -m pip install -r tools/motion_collection/requirements.txt
tools/motion_collection/.venv/bin/python tools/motion_collection/collector.py
```

打开 `http://127.0.0.1:8765/`。录制或导入前填写匿名拍摄者编号，然后保存并确认片段标注，提取关节点；至少三个不同拍摄者的合格样本才能按人划分导出。可用 `--data-dir /path/to/data` 自定义存储目录。要导出手工设置的 Android 规则示例：

```sh
tools/motion_collection/.venv/bin/python tools/motion_collection/collector.py --rule-action squat --low-angle 90 --high-angle 160 --rule-output /tmp/squat-rule.json
```

规则阈值为人工输入，需要自行验证；Android 需要相应规则执行器。采样和设备验证限制见 `docs/design/motion-collection-studio-v1.md`。电脑与 Android 端预处理尚未逐帧校准，不能声称输出完全一致。

## AI训练实验室（已按批准设计实现）

训练首页进入“AI训练实验室”。六页共享同一条本机分析记录：自动动作分类、参考动作对比、问题回放、连续动作分段、稳定性测试、基于证据的文字问答。全程静音、不接大模型。

- 输入支持内置真人视频、系统文件选择器导入视频、摄像头采样；最多60秒，每约200ms采样。导入采用MediaExtractor/MediaCodec逐帧解码，不依赖会重复返回同一帧的旧设备Retriever路径。
- 姿态提取使用现有ML Kit；分类是**完整周期后的几何时序规则实验版**，并非已训练好的通用动作分类模型。根据膝/肘/肩角变化与躯干方向区分五类已知动作，未知和过渡保留；规则匹配分不是概率或准确率，不支持任意动作。
- 实际计数沿用C++计数器，分类确认后关联原始帧和事件。已知内置循环接缝会重置未完成周期；导入同一授权视频可按文件指纹识别接缝。其他导入视频应连续拍摄，任意剪辑跳切尚不自动检测。
- 回放为保存的200ms采样帧，不冒充全帧率视频。事件区分完整、幅度不足、关节点缺失、接缝中断和未观察到返回。失败事件类别仅基于附近已确认动作推断，明确标识。
- 对比可从最近记录中手动指定同类完整动作作为参考，按起点/峰值/返回分段线性对齐，比较关节角与用时；参考不是专业标准，不输出安全或姿势合格评分。
- 连续动作页保留未知段，各段可人工标注类别和完整次数，与模型结果分开保存。稳定性页可添加整段人工次数/类别；没有标签不报告准确率。遮挡/短时遮挡/降亮度/低分辨率对同一保存帧重新推理，属于合成压力测试；真实角度变化需要导入对应实拍。
- 问答仅引用选中记录和事件，支持按“第2次”选事件和点击证据回到片段；无记录或无事件明确说明，不编造训练原因。
- 记录及实际推理输入PNG保存在应用私有 `files/ai_lab/`，最多保留5条。更旧记录会清理，保留的数据包含人物画面，可通过系统清除应用数据删除。

设备实测与限制见 `docs/design/ai-lab-device-verification.md`。自动分类、不同动作及视角的泛化仍须用更多带人工标注的真人视频验证，不能凭这一个内置样片宣称准确率。

## 律动健身 · 已批准的产品界面

用户已批准 `docs/design/fitness-coach-v1.md`，应用启动页现为 `CoachActivity`。采用暖白与深绿视觉，底部“训练 / 教练 / 记录”导航，设置中可进入原模型对比调试工具。主页显示本机保存的手动目标与五种动作；设置页选择摄像头训练或内置深蹲视频。真实准备倒计时、计数、暂停继续、提前结束确认、组间倒计时/提前继续、完成总结均接入现有状态机。离开训练页自动暂停，返回后手动继续；视频和本人训练分别记录，不写入设计样例数据。

教练使用独立聊天页，支持键盘输入和快捷问题。记录页按本人训练/视频演示分开，可查看单次详情与空状态。新安装保留短测试目标2组×2次、休息5秒，用户已有设置和历史不覆盖。全程静音。UI调整继续遵守先设计、用户确认再实现的仓库规则。

本地聊天增加42条预设话术，涵盖问候、鼓励、休息、闲聊、感谢及未匹配问题。每类随机打乱后依次使用，整轮用完才重复，并避免跨轮连续重复；新增“随机聊聊”“鼓励我一下”“我有点累”快捷按钮。随机话术不会编造训练成绩，进度仍读取当前训练数据。

## 本地文字对话体验

在教练页面点“教练对话（本地文字体验）”，可输入文字或点击快捷问题。纯本地话术匹配，不连接大模型、网络、麦克风或语音播放。支持问候、鼓励、当前进度、计数原因和所选动作说明；“为什么没算”后可追问“具体一点”。进度使用当前真实计数，并标注视频演示来源，未匹配问题会明确说明是预设演示。聊天不自动修改训练，不生成个性化计划。最近20轮对话仅在当前页面实例中保留，可以清空。

## 多动作本地教练

教练支持手动选择深蹲、双臂上举、哑铃弯举、俯卧撑、侧平举。姿态模型提取关节点，C++规则判断完整往返动作，Kotlin管理准备、组数、休息、暂停和结束；不接入大模型，不自动识别动作类别，不自动生成个性化计划。全程静音。

可自定义1—10组、每组1—30次、组间休息0—300秒；设置在本机保存。文字反馈包括关键点缺失、未达计数幅度、较快节奏、左右角度差，目标阈值与对应底层计数器一致。这些提示是规则反馈，不是完整姿势纠错；弯举不检测器械，俯卧撑仅按肘角计数，单侧可见时无法判断左右对称。历史保留最近20条，按动作记录次数；统计区分摄像头本人训练与视频演示，旧记录按深蹲兼容。

内置真人视频只支持深蹲，其他动作须使用摄像头。目前未连接设备，多动作真人实测尚待完成。

# BodyRecongnize

## 2026-10-21：训练可靠性修复

- 俯卧撑要求至少同一侧肩、肘、腕、髋可见，并确认肩髋轴接近水平；站立弯曲手臂不再创建俯卧撑周期。改变为站姿会中断未完成周期。该条件仅用于排除明显错类，不评价塌腰等全部姿势问题。
- 训练计时仅累计最近有效姿态覆盖的时段；没有有效姿态时停止累计，断流最多容许1秒。视频节奏判断使用输入视频时间，不受设备处理变慢影响。
- 每次完整动作、幅度不足尝试、较快节奏和持续左右角度差保存结构化反馈（最多100条）。最近反馈短暂保留在训练页，对话读取这些记录；正常下一帧和暂停不会删除依据。保存训练时一并写入 `feedbackEvents`。单侧可见时不输出左右对称演示指数。
- 本地对话优先识别休息问题，支持按累计完整次数查询已有反馈；没有相应记录时明确说明。启动新训练会重置临时聊天上下文。
- 视频叠加仅覆盖实际分析范围，不将最后一个骨架无限延长；不跨已知剪辑接缝或长时间缺帧插值。训练页同时修复最新姿态状态更新、识别错误被刷新覆盖，以及后台关闭对话时意外恢复训练的问题。

相机切换、开练取景和导入视频与训练教练合并的新交互已按展示的预览继续实现，见 `docs/design/coach-usability-v2.md`。摄像头默认前置，同一启动姿态稳定可见后才允许开始；切换镜头后重新确认站位，保留已接受的训练次数。导入视频直接进入训练页，按选定动作逐帧识别前60秒，视频反馈可打开静音原片片段。输入结束时保存实际次数并说明结束原因。新增交互本轮只做本地构建与回归测试，按用户要求不连接设备验证。

训练设置现可选填体重（20–300 kg，最多一位小数，仅保存在本机），训练页在取景画面上方显示“热量消耗（估算）”，可展开本次体重、参考强度及有效计时依据。按MET、体重和有效训练时间离线估算，准备、休息、暂停及长时间失去姿态输入不持续累计；它包含参与计时时段的静息消耗，并非实际代谢测量。每次开练固定依据，结束总结和历史保存本次结果；未填写体重或旧记录没有依据时显示未估算，内置及导入视频不计算个人消耗。本地教练对话也可回答“消耗多少卡路里”。动作类别近似与误差限制见 `docs/design/calorie-estimate-v1.md`。本轮仅本地编译与单元测试，未打包APK或进行设备验证。

## AI健身教练测试入口

首页点击“AI健身教练测试”。内置视频演示针对深蹲，默认2组×2次、组间休息5秒，支持2×3和2×5测试计划。包含3秒准备、真实完整动作计数、膝角与幅度反馈、幅度未达计数条件/节奏/关键点缺失提示、暂停继续、组间休息、完成总结和最近20次本机记录。它是姿态模型加规则的流程测试版，尚未接入大模型对话、语音识别、更多动作或个性化训练处方。

“开始真人视频教练测试”分析片中真人，记录明确标记“真人视频演示”；“开始摄像头训练”才是本人训练。每种输入均独立创建原有C++深蹲计数会话，必须真实识别到站立—下蹲—站立才增加次数；计时器仅负责准备与休息，不制造动作次数。视频演示最多循环两轮后保存未完成情况。视频在准备、休息、暂停时停在当前进度，恢复后继续。

设备目前没有可用摄像头，实时摄像头入口已实现，但本机只能验证无摄像头提示及真人视频流程。摄像头预览采用等比显示和前摄镜像骨架映射，待连接摄像头后实测。当前规则要求双侧髋、膝、踝置信度达到0.5；关键点不足时不计数。幅度/对称演示指数沿用C++启发式规则，不是完整动作质量、膝盖轨迹或个体适宜性评价。

教练测试全程静音，仅显示文字提示，不播放语音，也不提供试听入口。训练记录保存在应用私有SharedPreferences `coach_history` 中（最近20条），含演示/摄像头来源、实际完成次数、组数、训练时间和规则指标。退出或提前结束会标记为未完成，不与计划完成混淆。按Home键进入后台会暂停，回来后需手动继续。

已发现RK3399的MediaMetadataRetriever对当前真人样片可能在不同时间点重复给出同一帧；内置真人视频现使用由原视频准确时间解码的156张帧图，200ms一帧，保留原始连续运动。教练与原模型对比入口共用这些真实帧。随机混剪和用户导入视频仍走原视频读取路径。旧设备对比报告仅用于耗时参考，识别稳定性请在修正后的真人样片上重测。

相关代码在 `demo-android/src/main/java/com/bodyrecongnize/demo/coach/` 和 `CoachActivity.kt`；状态机单元测试覆盖准备、无效帧、完整次数、休息、暂停、提前结束及延迟帧。实机2组×2次完成记录保存在 `assets/coach/device_smoke_test.json`，输入是演示样片，非用户本人。素材源及哈希见 `assets/coach/sources.json`。

素材重新生成需要macOS系统媒体框架和Tingting中文声音：

```sh
swift tools/extract_real_squat_frames.swift demo-android/src/main/res/raw/real_squats_demo.mp4 demo-android/src/main/assets/real_squat_frames
python3 tools/generate_coach_voice.py
./gradlew :demo-android:assembleDebug :demo-android:testDebugUnitTest
```

视频画面下方提供三个骨架显示开关：ML Kit（绿）、RTMPose（蓝）、MoveNet（紫）。可任意组合显示，全部取消时保留视频原画面；切换立即生效，不重播、不影响模型推理及耗时统计，并记住选择。只会显示当前识别模式实际运行的模型结果。

视频识别引擎现支持 ML Kit（绿）、RTMPose-t（蓝）、MoveNet Lightning（紫），以及双模型、三模型同帧对比。先选择引擎，再播放内置真人视频；播放中切换会从头重播同一视频。摄像头与照片仍使用 ML Kit。

MoveNet 使用官方 SinglePose Lightning float16 v4，随 APK 打包，通过 TensorFlow Lite 2.10.0 CPU/XNNPACK 运行，2线程，不启用 NNAPI，无需网络、密钥或 Google Play 服务。整帧等比缩放至 192×192 并补黑边，输入 RGB uint8，输出17个关节点并反变换到原图坐标；此版没有基于前帧结果的自适应裁剪，要求单人全身入镜。

三模型处理相同视频帧，并轮换执行顺序，分别统计前处理、推理与解码耗时。ML Kit 绘制阈值0.5，RTMPose和MoveNet阈值0.3，未校准的置信度和视觉差异不等于准确率。播放采样间隔仍为200ms，不代表模型推理帧率。完整报告 `files/video_comparison.json` 记录各模型耗时、关节点和执行顺序。MoveNet 来源、SHA256、许可证与官方模型卡见 `assets/movenet/`，可用 `python3 tools/fetch_movenet_model.py` 恢复固定版本模型。

面向 Android（API 23+）和 iOS（15+）的轻量动作分析核心。C++17 实现通过 C ABI 暴露，平台层分别提供 Kotlin/JNI 和 Swift 接口。当前支持**深蹲、双臂上举、哑铃弯举、俯卧撑和侧平举**：输出动作阶段、完成次数、动作进度、实时预估分数、上一完整动作分数及左右关节角度。

## 输入与适用范围

每帧输入 33 个与 [MediaPipe Pose](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker) 顺序一致的关键点，每点依次为归一化图像坐标 `x`、`y` 和置信度 `confidence`。`x` 向右、`y` 向下，三者均为 `0..1`；还需提供与坐标对应的图像宽高，以及单调递增的毫秒时间戳。宽高用于还原关节角度，尤其是手机竖屏。MediaPipe 的 `visibility`、`presence` 可用于生成置信度（例如取两者较小值）；接入其他模型时需先映射为这 33 个位置。C++ 核心本身只分析关键点；`demo-android/` 使用 CameraX 和随应用打包的 ML Kit 模型从画面提取关键点。

深蹲使用髋、膝、踝，侧面拍摄更适合二维膝角估计；双臂上举和侧平举使用肩、肘、髋，正面拍摄更适合左右对称性估计；哑铃弯举使用双侧肩、肘、腕，俯卧撑使用至少同一侧肩、肘、腕、髋，并要求肩髋轴接近水平。关键点置信度低于 `0.5`、坐标无效或关节退化时返回错误，该帧不更新计数。每人、每种动作使用独立 session；失去跟踪或切换人时调用 `reset()`。

## 评分与计数

完整动作必须经过起始姿态、目标姿态，再回到起始姿态；阶段切换至少间隔 180 ms。有效帧间隔超过 1000 ms 时，放弃未完成动作并重新确定起始姿态。深蹲和俯卧撑以膝/肘屈曲判断下探；双臂上举、哑铃弯举和侧平举以肩/肘角度判断抬起或弯曲。单次评分为 `80 × 最大进度 + 20 × 最低左右对称度`，限制在 `0..100`。这些阈值和分数是可调整的启发式指标，尚未经过运动医学验证，不能用于诊断或康复决策。

## 构建与测试

```sh
make test                 # 编译并运行 C++ 核心测试
cmake -S . -B build       # 可选：CMake 构建
cmake --build build
ctest --test-dir build
swift build               # 构建 iOS 可复用的 Swift Package
./gradlew :demo-android:assembleDebug # 命令行构建 Android 演示 APK（需要 JDK 17）
```

Android 演示应用需要 API 23+，使用随 APK 打包的 ML Kit 姿态检测 SDK，无需在设备上下载模型。APK 输出在 `demo-android/build/outputs/apk/debug/demo-android-debug.apk`。连接设备并开启 USB 调试后，可用 `adb install -r demo-android/build/outputs/apk/debug/demo-android-debug.apk` 安装，再通过 `adb shell am start -n com.bodyrecongnize.demo/.MainActivity` 启动。下拉动作列表可选择五种动作进行实时计数；点击“主板性能测试”可查看 CPU、内存和各动作的 C++ 评分速度；点击“选择内置动作样例”可测试现有的深蹲及双臂上举照片，其余动作可使用“选照片估分并测速”选择自己的照片。测试完成后，照片会与识别骨架叠加显示（绿线为骨架连线，黄点为关键点）。结果包含姿态模型耗时和单帧姿势演示指数；指数由动作幅度（80%）与左右对称性（20%）组成，仅用于展示当前角度规则，不评价膝盖轨迹、躯干稳定、脚掌受力或动作过程，不代表完整动作质量分或次数。实时摄像头识别仍需要可用摄像头与相机权限。仓库中的备用样片来自 [Pexels](https://www.pexels.com/photo/man-doing-squat-exercise-4720244/)。

选择“播放60秒随机动作视频”可播放一分钟随机混剪样片，五种动作的顺序、重复次数和停留时间随机变化，并穿插站立停顿；“选择视频并显示肢体”可导入自己的视频。播放时直接在视频帧上叠加绿色肢体连线和黄色关节点，每 200 ms 取一帧并识别后显示，骨架与画面来自同一帧。设备识别较慢时播放会相应减速；当前播放不含音频，最多展示前 60 秒。再次点击视频按钮可重播，切换照片、相机或离开应用会停止。内置样片由姿势示意图制成，用于验证播放与骨架叠加，不是连续真人训练录像。

在 iOS 工程中通过 **Add Package Dependencies → Add Local** 选择本目录，并 `import BodyRecongnize`。创建 `BodySession(exercise: .squat)`，每帧调用 `try session.process(landmarks: values, imageWidth: width, imageHeight: height, timestampMs: time)`；`values` 是长度 99 的 `[Float]`。

在 Android 工程中将 `android/` 作为库模块引入，确保宿主工程配置 Android Gradle Plugin、Kotlin 插件与 NDK/CMake。在宿主的 `settings.gradle.kts` 中添加 `include(":bodyrecongnize")` 和 `project(":bodyrecongnize").projectDir = file("<本仓库路径>/android")`，再在应用模块中添加 `implementation(project(":bodyrecongnize"))`。创建 `BodySession(BodySession.Exercise.SQUAT)`，调用 `process(values, imageWidth, imageHeight, timestampMs)`，结束时调用 `close()`。JNI 库随模块构建。

底层 C 接口在 [`include/body_recongnize.h`](include/body_recongnize.h)。`br_create`、`br_process`、`br_reset`、`br_destroy` 可直接用于其他平台；每个 session 只在一个线程上使用。

## 扩展动作与自训练

演示版用已训练的 ML Kit 模型提取关键点；深蹲和双臂上举仍由 C++ 规则识别。新增少量动作时，可在 C 接口中增加动作枚举及关节、阶段和评分规则。需要识别更多动作时，先采集各动作及非动作的标注视频，提取并归一化连续关键点，再训练轻量时序分类模型并导出到移动端；计数和评分规则仍需按动作设计。验证时按人划分训练集和测试集，用真实设备视频校准不同视角、身高与速度下的效果。

## 随机混剪素材

`assets/random_actions_60s.mp4` 是 640×720、24 fps、60 秒的无声混剪视频，使用现有姿势图硬切拼接。它用于测试动作切换和逐帧骨架识别，画面中的姿态切换不是连续真人运动。随机时间轴保存在 `assets/random_montage_timeline.json`，固定种子可复现。macOS 上可用以下命令重新生成（需要 Xcode 命令行工具和系统 H.264 编码器）：

```sh
python3 tools/create_random_montage.py
swift tools/render_pose_montage.swift assets/five_actions_pose_sheet.png assets/random_montage_timeline.json assets/random_actions_60s.mp4
cp assets/random_actions_60s.mp4 demo-android/src/main/res/raw/five_actions_demo.mp4
./gradlew :demo-android:assembleDebug
```

## 真人连续动作视频

应用新增“播放真人连续动作视频”入口，播放约 31 秒的真人全身连续深蹲录像（6.233 秒原片循环 5 次，保持原速），并逐帧在人物上绘制关节与骨架。原有 60 秒随机姿势混剪入口仍保留。两个视频均随 APK 打包，安装后无需联网或手动导入。

真人样片由 [Polina Tankilevitch / Pexels](https://www.pexels.com/video/young-woman-exercising-6525519/) 提供，按 [Pexels License](https://www.pexels.com/license/) 使用。文件及来源记录见 `assets/REAL_VIDEO_SOURCE.md`。

## ML Kit 与 RTMPose-t 视频对比

视频按钮上方的“视频识别引擎”可选 ML Kit（绿）、RTMPose-t（蓝）或同帧对比。选好后点击“播放真人连续动作视频”；播放时切换引擎会从头重播同一视频。原摄像头及照片入口继续使用 ML Kit。

RTMPose-t 使用官方 BODY7 17 点 ONNX 模型和 ONNX Runtime Android 1.17.3，在本机 CPU 上运行，无需联网、密钥或 Google SDK。当前遵循官方单图示例：以整帧作为单人区域，扩大1.25倍并保持192:256的输入比例，不带人物检测器；要求单人全身入镜，远景、多人及混剪切换可能表现较差。不会借用 ML Kit 的人物位置或编造缺失的手脚关键点。

同帧模式给两个模型传入同一张不超过640像素长边的图，交替执行顺序。显示各模型平均耗时和P95（含各自前处理、推理、关键点解码；不含读帧、绘制、初始化及前3帧预热）。ML Kit采用SDK流模式和CPU配置，RTMPose使用2个CPU推理线程。播放取帧间隔仍为200ms，这不是模型FPS测量。两模型置信度未校准，分别以0.5和0.3绘图，不能用显示点数或骨架差异直接宣称准确率。

完整播放后，应用私有目录中的 `files/video_comparison.json` 保存逐帧耗时及汇总；可用 `adb shell run-as com.bodyrecongnize.demo cat files/video_comparison.json` 读取最近一次完成的运行。停止或切换时不会把未完成结果覆盖为完整报告。模型来源、SHA256及官方部署参数保存在 `assets/rtmpose/`，模型本体随APK打包。代码许可证与具体预训练权重许可需分别核对。

```sh
./gradlew :demo-android:assembleDebug :demo-android:testDebugUnitTest
```

该命令构建APK并运行坐标映射、关键点对应关系及计时统计单元测试。

一次 RK3399 / Android 7.1（API 25）的同帧实测（31秒真人样片，156帧，排除3帧预热后153帧）：ML Kit平均75.2ms、P95 195.9ms；RTMPose-t平均149.4ms、P95 224.1ms。该次测试中ML Kit更快；这不是跨设备基准，也不代表准确率结论。逐帧耗时记录在 `assets/rtmpose/device_latency_first_run.json`。若模型文件缺失，可运行 `python3 tools/fetch_rtmpose_model.py` 按固定SHA256恢复官方模型。
