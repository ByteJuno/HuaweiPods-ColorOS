# ColorOS 17：设备身份、电量状态与一代 FreeClip 控制

基线：`4a3f6fc`（1.9.3.0），开发分支 `feat/coloros-freeclip-state-controls`。

## 设备身份与电量生命周期

- 蓝牙设置列表从宿主 `BluetoothDevicePreference` 的 `CachedBluetoothDevice` 读取真实连接状态和 MAC；详情页从 Activity Intent / fragment arguments 读取设备地址。
- 设置页只有明确地址对应到电量记录时才补全文字，不再靠“同样是 100%”挑选设备。
- 设备空间和种子卡优先使用 MAC 或 `md5(大写 MAC)`。没有身份字段时才允许唯一的完整型号名匹配；冲突身份、同名歧义、FreeClip 与 FreeClip 2 都不混配。
- `ColorOsBatterySource` 先订阅广播，再恢复一次快照。初始化期间收到的实时更新和断连优先；后续查询不反复加载旧快照。关闭时注销接收器。
- `Settings.Global/huaweipods_battery_snapshot` 兼容原来的单条记录，新版写入 `v2\n` 前缀及按地址排序的多行记录。更新和删除只影响对应地址，删除最后一条后清空设置。设备名中的换行和字段分隔符会被清洗。
- 设备空间保存多个仍存活 DTO 的弱引用，按各自地址刷新。通道消失、断连、关闭时撤回模块仍持有的字段覆盖；宿主已更新的对象不被旧值覆盖。
- 设置摘要保存原文和模块渲染值，断连或模块关闭时恢复原文。

这些变化统一跨进程 UI 的状态消费。华为私有协议控制仍针对当前 HFP 会话设备；原生 QS 仍保留单连接设备门禁。

## 一代 FreeClip 系统详情控制

只对 `HUAWEI_FREECLIP` 添加以下控制：

| 项目 | 设备协议 |
| --- | --- |
| 左耳 / 右耳双击、三击 | 现有 HuaweiGestureController |
| 默认、低音增强、高音增强、人声四档音效 | 现有 HuaweiEqualizerCodec |
| 左右自适应 | 现有 HuaweiFreeClipController |
| 掉落提醒 | 现有 HuaweiFreeClipController |

入口位于“设备空间 → FreeClip → HuaweiPods”原生详情行下方。支持纵向 LinearLayout，以及 PLK110 固件实测的 COUI `messageLayout` RelativeLayout：标题和摘要必须是直接子项，控制面板按 `BELOW android.R.id.summary` 排列。未知布局不插入。

控制请求使用独立 nonce、MAC、route 和单调时钟期限。Bluetooth 宿主检查当前会话；UI 只接受可信蓝牙 UID、匹配请求和设备且未过期的结果。写入成功只表示数据已发送，450ms 后再次查询耳机实际状态，CRC 有效回读才更新选择。回读与请求不符时显示实际值并提示未生效。

同一面板只允许一个写入事务；用户写入可以取代正在进行的全量刷新。8 秒超时，查询响应窗口 1500ms。请求在排队后和建链后再次检查有效性；过期任务不再发送。写入与回读在同一个队列任务内复用连接，查询前清理积压输入，查询完成或异常取消时关闭连接。断开、重连、页面回收、模块关闭都会重置会话并释放接收器和回调。

本轮没有添加一代 FreeClip 不具备的 ANC、空间音频或滑动控制，也没有接通 OPLUS `+OBUDS` AT 桥。

## 第二轮：紧凑面板与共享控制状态

- 原生详情缩为五行：双击（左右分组选项）、三击（左右分组选项）、音效、左右自适应、掉落提醒。底层仍是七个独立键；未知开关禁用并标明未确认。
- 原生面板及模块页共用 `FreeClipControlClient`，一代 FreeClip 的上述七项不再由模块页独立直连或用 prefs / 默认值冒充设备确认。佩戴检测、低延迟及其他型号保持原路径。
- Bluetooth 中的 `FreeClipControlCoordinator` 合并同时进行的全量读取，各请求保留独立 nonce 和期限。写入构成版本屏障，取消旧读、清除旧快照；并发写明确拒绝，写期间的新读取合并等待。
- STATE 是全量已确认快照，携带严格递增的会话 epoch、revision 和 pendingKey；RESULT 只结束对应 nonce，不把广播快照当作事务成功。两个消息定向模块 App 和 MyDevices，接收方验证可信 Bluetooth UID、地址及 route。
- 写入后在同一传输任务内查询全部七项。只有 CRC 有效的实际回读进入快照；部分读取不保留缺失字段，失败/超时不恢复旧值。断连使客户端拒绝旧 epoch，页面重新挂接仍可读取同一 Bluetooth 会话。
- 新增显式 opt-in 真机测试 `FreeClipLiveControlsTest`：只有提供 `freeclipAddress` 参数才运行，使用两个客户端逐项改值、确认同步并在 finally 恢复原值；普通设备测试不修改耳机设置。

## 验证记录

初轮最终源码验证：JDK 21，120 个 JVM 测试类、793 项断言通过；Gradle 原生测试 worker 在执行断言前报 `ClassNotFoundException`，使用 Gradle 导出的同一 classpath 直接调用 JUnitCore 完成断言。Debug、Release、设备测试 APK 构建完成；Lint 为 0 error、201 warning、37 hint。

PLK110 / `PLK110_17.0.0.105(CN01)` 通过现有 SSH 反向 ADB 端口 `127.0.0.1:18583` 连接，一代 FreeClip 已连接。调试包安装成功，初版面板绑定 / 回收 / 未知布局 3 项设备测试通过。首次真实宿主检查发现 RelativeLayout 入口未插入，已据实测 View 层级修复并补充设备回归测试。

最终验证（2026-11-04）：

- 重连、传输事务和真实布局修复后，120 个 JVM 测试类仍为 **793 项通过、0 失败**；Debug / Release / 设备测试 APK 全部构建成功。Lint 仍为 **0 error、201 warning、37 hint**。
- PLK110 上的 **4 项设备测试全部通过**，覆盖重复绑定、地址/route 回收、未知布局和 ColorOS 17 RelativeLayout 的摘要定位规则。
- Release 包覆盖安装成功；Bluetooth 和 MyDevices 宿主冷启动装载 API 102 入口。真机全量查询确认七项：左右双击播放/暂停、左右三击下一首、默认音效、左右自适应开启、掉落提醒开启。
- 真实音效写入验证：**默认 → 低音增强 → 默认**，每次均经耳机回读确认；最终恢复了测试前设置。日志记录 `freeclip-system-read key=EQUALIZER write=true keepSocket=false`。
- 我的设备详情页电量随真实耳机从左右 100% 更新到左右 95%，盒电量 75%；跨进程快照已实际写为 v2 格式。
- 系统蓝牙列表和“已连接的设备”页均显示 FreeClip 三段电量，已连接手表和其他耳机保持宿主显示。直接启动 AOSP BluetoothDeviceDetailActivity 在此固件落到“已连接的设备”页，因此不将其计为独立设备详情页验收。
- 临时设备测试包已卸载；手机保留本次 Release 验证包，版本号仍为 1.9.3.0，未发布新版本。
- 最终 Release APK SHA-256：`574201ec48b8f9e2f4f775c470ddb9174c30badbc22398931c2cb62bed12fdf0`。

初轮测试截图和原始日志保留在本地 `.local/review/freeclip-*`，不加入发布包。初轮未实测多副实体耳机并发、其余控制项逐一改值以及恶劣射频下的超时/取消；单测不等同于上述实体场景验收。

第二轮构建与回归（2026-11-04）：

- Debug、Release、AndroidTest APK 构建成功。修复了 native client 初始化的 Kotlin 循环类型推断，以及设备测试中 nullable stateDescription 的编译错误。
- **121 个 JVM 测试类、812 项测试通过、0 失败**。标准 Gradle test worker 仍在加载测试类阶段失败；重新生成最新 runtime jar 和 classpath 后直接运行 JUnitCore。首次回退运行误用了上一轮 runtime jar，已纠正并全量重跑，不把该次失败归为业务断言回归。
- PLK110 上 **5 项布局设备测试通过**，新增五行布局和未知开关禁用校验。
- Lint：**0 error、202 warning、37 hint**。新增 Host 的进程级 Session/Context 持有被 StaticFieldLeak 提示；会话在断连/换代/关闭时释放，不持有页面 Context。
- Release 已在真实 MyDevices 宿主显示五行紧凑布局，七项状态全部读回确认。原生面板截图为 `.local/review/freeclip-shared.png`。
- 最终补充中英文“写入等待回读 / 其他页面写入 / 未确认 / 已确认”文案，避免未确认状态显示成持续读取；Debug、Release、AndroidTest 和 Lint 再次通过。
- 七项真实改值及双客户端同步测试最终通过：`FreeClipLiveControlsTest` 返回 **OK (1 test)**，耗时 **14.054 秒**。同一模块进程内两个独立客户端交替写入四个左右手势、音效、自适应声道、掉落提醒；每次要求自身 nonce 的回读成功及两个客户端状态一致，逐项恢复原值，结束时两份完整快照均与初始快照相等。该结果验证真实耳机与 Bluetooth 宿主间的控制及广播同步，不等同于两个可见页面全部逐项点击验收。原始结果：`.local/review/freeclip-live-roundtrip-resumed.txt`。
- 安装曾因反向 ADB `closed` / `EOF` / `offline` 中断；最终通过 Base64 分片、逐片 SHA-256 校验和整包校验完成断点续传，再执行设备侧安装。此前失败没有进入硬件写入测试。
- 多副实体耳机、单耳入盒、实际断连重连、恶劣射频超时，以及模块页面逐项点击仍待实测。原生 QS 多设备门禁保持不变。
- 恢复连接后最终 Release 已完成 20 个分片及整包 SHA-256 校验，设备侧安装返回 `Success`；临时 AndroidTest 包卸载返回 `Success`。手机现保留最终文案修订版，版本号仍为 1.9.3.0。
- 最终 Release 实际页面验证：模块详情显示“当前设置已由耳机回读确认”；音效默认、自适应及掉落提醒开启；滚动到手势区后四项均完成回读，左右双击播放/暂停、左右三击下一曲。点击左耳双击成功打开五项选择对话框，选中项为播放/暂停；未改值，返回关闭。
- 冷启动 MyDevices 后，原生五行面板再次全量读取确认，七项与模块页一致；电量为左100%、右100%、盒63%。截图保留在 `.local/review/freeclip-module-controls-final.png`、`freeclip-module-gestures-final.png`、`freeclip-gesture-dialog-final2.png`、`freeclip-native-final.png`。该页面核验不替代持续打开两个可见页面的实时联动测试。
- 本轮最终 Release 构建 SHA-256：`7f9b906eb3522af17fd743066a116723623518c84a9379e17b3dee12eb98f96b`。
