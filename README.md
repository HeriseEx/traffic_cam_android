# 行车实时取证 App

后端在 `../backend`。本次链路面向随车移动的手机/行车记录仪。

## 使用

- 监看画面常驻“标记重点”和“操作菜单”。上方显示连接、自动取证、缓存时长和待上传数量；下方显示车辆、车牌、灯色及当前疑似动作。
- 车辆框显示 `#轨迹编号`、类别、置信度和车牌；`✓` 表示同一轨迹多帧确认，`?` 表示待确认。路径线显示最近的运动轨迹，遮挡保留的预测框为黄色虚线。
- 自动取证默认开启。检测到同一车辆红灯期间持续移动或明显横向移动后，从缓存导出动态片段并自动上传；没有认清车牌也会保留视频，交由服务器识别。
- “更多设置”可调整自动取证、车辆阈值、运动阈值、遮挡保留时间、事件尾部补录、画面方向、车牌和框显示。默认遮挡保留 3 秒，可调 1.5–5 秒。
- 手动按钮或本地语音“开始标记”保留之前约 10 秒，以及之后所选 10 / 15 / 30 秒。随时可“结束片段”。这些时长只控制手动/语音模式。
- “服务器与记录”支持状态筛选、重试上传、播放本地/服务器原片、确认后删除未上传片段。可导入整段视频，也可“按事件提取”并使用与实时相同的跟踪和动作规则。

## 实时识别与身份关联

CameraX + EfficientDet-Lite0，最多 8 次分析/秒。手机检测车辆和灯色，每隔至少 1.5 秒尝试提交原始分析分辨率 JPEG（质量 90）；同时最多一个服务器请求，慢网自动降频。超 2 MiB 的帧不发送。

车辆关联使用归一化位置、速度预测、尺寸和车身颜色直方图；一帧中的检测与轨迹一对一匹配。车牌回复绑定到**发送时的帧快照和轨迹编号**，不拿迟到的车牌位置去匹配当前车辆。超过 6 秒的回复丢弃，切换画面和方向时使旧请求失效。车牌按轨迹累计投票，单次响应不可重复投票；候选冲突时不确认。

短暂遮挡保留编号和轨迹；预测帧不产生新的动作证据。几何和颜色都难以区分的目标会新建编号，避免贸然继承另一辆车的车牌。当前未配置深度 ReID 模型，**不能保证同色密集车流、完全遮挡、急转和大幅镜头运动时零误认**；需要真实道路样本衡量 ID 切换率，再决定是否加入 ReID。

移动机位的位移仅是疑似动作，不能直接证明越过停止线、压实线、不打灯或逆行。横向移动单独标记 `LATERAL_MOVEMENT`，服务器仍可独立复核固定机位已标定场景。服务器要求被判定的同一轨迹有足够的车牌帧，不能借用另一辆车的稳定车牌。自动上传不等于自动举报。

## 动态视频缓存

- CameraX 以 5 秒段连续滚动缓存约 20 秒，目标视频码率 4 Mbps。触发不切断缓存，导出期间继续录制。
- 自动事件从轨迹中最早的动作时刻前 3 秒开始，持续动作会延长事件；默认最后一次动作后保留 3 秒消失容忍和 2.5 秒尾部补录。短暂遮挡不会立刻结束片段。相邻/并发动作合并到当前片段，记录各自轨迹和起止时间，最多 16 个动作提示。
- 连续长事件按约 65 秒补录、80 秒总时间窗口或 42 MiB 容量提前分段；后续持续动作可继续取证。输出受 50 MiB / 89 秒限制，低于 150 MiB 可用空间停止新录制。
- 导出只选择与事件窗口相交的缓存段，并回退到可解码的关键帧开始；不是精确到任意帧的重编码剪辑。保留编码顺序、方向及分段间的实际时间间隙，记录真实保留范围和缓存不足提示。
- 导出先写 `.partial`，成功后原子改名。页面退出或重建等待最后一段完成并入队；拼接失败将源段保存在手机私有 `files/recovery/事件编号/`，不删掉证据。进程被强杀时，尚未完成的当前编码段不保证可恢复。

## 上传与后台恢复

队列、视频哈希、请求元数据和重试时间持久化。网络错误指数退避至最多 5 分钟；4xx 参数/容量/幂等冲突保留原片并显示“上传受阻”，其他片段仍可继续。服务器确认事件 ID 和哈希后才删除本地待上传副本。已完成导出但来不及入队的文件，下次同步自动恢复。

前台约每 3 秒同步。离开 App 后由 Android JobScheduler 在网络可用时上传、补齐服务器结果，并在设备重启后恢复；实际执行时间受 Android 和厂商省电策略调度，不承诺即时。系统“强行停止”后需再次打开 App。相机采集仍只在前台运行。

旧服务器兼容：上传前检查 `/health.capture_metadata`，旧服务器使用原有视频接口，详细动作时间保留在手机；新后端会接收 `metadata.capture`。每次重试沿用首次持久化的上传格式，不因服务器升级改变同一事件的元数据。

默认服务器 `https://traffic.muqin.ccwu.cc`。本机联调 `http://127.0.0.1:61616`，先 `adb reverse tcp:61616 tcp:61616`；远程必须 HTTPS。访问凭据由 Android Keystore 加密，网络请求不跟随携带令牌的重定向。

## 构建与验证

JDK 21、SDK 36.1、Gradle Wrapper 9.5.0，沿用当前固定依赖，不新增库。

```powershell
$env:JAVA_HOME='C:\Home\Software\Android\Android Studio\jbr'
$env:GRADLE_USER_HOME='C:\Users\Herise\.gradle'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
cd ../backend
.\.venv\Scripts\python.exe -m unittest test_backend -q
.\.venv\Scripts\python.exe pipeline_smoke.py
```

`TrafficPipelineTest` 覆盖遮挡恢复/过期、同帧 OCR 去重、延迟车牌归属、交叉车辆、歧义身份保护和动态事件时间。`pipeline_smoke.py` 启动独立本机测试服务与临时数据库，验证原生 MP4 导出、真机录制/页面重建、幂等上传和独立服务端判定；结束后恢复手机原服务器设置，结果与截图在 `backend/validation/`。未修改线上部署。

## 第三方资源

- [EfficientDet 模型说明](https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector)，[固定权重](https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detection/android/lite-model_efficientdet_lite0_detection_metadata_1.tflite)，Apache-2.0。
- 权重 SHA-256：`2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b`。
- [Vosk](https://alphacephei.com/vosk/models) small-cn-0.22，Apache-2.0；Vosk Android 0.3.75 和 JNA 5.18.1。
- `traffic.jpg`：Richard Croft，2012，*A1 traffic*，[原始来源](https://www.geograph.org.uk/photo/2974654)，[CC BY-SA 2.0](https://creativecommons.org/licenses/by-sa/2.0/)。来自 [ageron/data](https://github.com/ageron/data/tree/main/images) 的未修改副本；检测框单独叠加。
- `traffic.mp4`：上述照片生成的 3 秒静态无声视频，底部补一行，继续按 CC BY-SA 2.0 提供，仅用于推理和链路验证。
- 许可及署名随 APK 存放在 assets 中。
