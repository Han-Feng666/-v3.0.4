# 弱网模拟功能实施任务列表

参考：`requirements.md` / `design.md`

## 任务清单

- [x] 任务 1：数据模型与持久化
  - 新增 `WeakNetworkParams`（model），`FeatureSettingsRepository` 增加 8 个弱网字段的 getter/setter/钳制
  - 新增 `WeakNetworkController`（状态中枢：toggle / setEnabled / status 通知 / VPN 热重载）
- [x] 任务 2：整形引擎 `WeakNetworkEngine`（延迟/抖动/丢包/限速，线程安全）
  - 内含纯逻辑 `WeakTrafficShaper` / `WeakRateBucket`（可单测）
- [x] 任务 3：AdBlockVpnService 集成
  - `handlePacket` 入口 `applyIngress`（上行）+ `writeTunPacket` 入口 `applyEgress`（下行）
  - 单 App 会话：`applyVpnApplicationScope` 支持弱网 target（`addAllowedApplication` + `addDisallowedApplication`）
  - `onStartCommand` 同步引擎（进程重启恢复）
- [x] 任务 4：FloatingBallService 单击切换弱网 + 状态优先显示
- [x] 任务 5：设置页入口 + 弱网参数设置界面
  - `SettingsActivity` 新增「网络弱网模拟」卡片（含状态描述，onResume 同步）
  - 新增 `WeakNetworkActivity` + `activity_weak_network.xml`（总开关 / 目标 App / 参数编辑 / 音量键开关）
  - 目标 App 选择器：`dialog_pick_weak_net_app.xml` + 内嵌 RecyclerView + 搜索
- [x] 任务 6：音量键控制
  - 实现方式由 MediaSession 改为 **辅助功能服务**（compileSdk 36 已移除 `onAdjustVolume`）
  - 新增 `WeakNetVolumeKeyAccessibilityService`（过滤全局音量键并切换弱网）+ 设置页引导授权
- [x] 任务 7：单元测试 + 编译验证
  - `WeakNetworkEngineTest`：丢包概率/延迟抖动范围/限速桶阈值/引擎开关短路（13 例全部通过）
  - `:app:assembleDebug` 编译通过
- [x] 任务 8：清单文件注册新 Activity 与辅助服务 + 汇总文档更新

## 实现备注

- 参数变更走热更新（`WeakNetworkEngine.refresh`），目标 App 变更（含清除）需重建 VPN 会话限定流量。
- 总开关开启且存在目标 App 时，重建会话使 `addAllowedApplication` 生效；开关关闭时仅停用引擎，不重建（保留原全量拦截）。
- 音量键开关默认关闭；开启时若未授予辅助功能权限会引导跳转系统设置。