# 自动连招功能实施任务列表

参考：`requirements.md` / `design.md`

## 任务清单

- [x] 任务 1：数据层与模型
- [x] 任务 2：回放引擎 + 测试
- [x] 任务 3：无障碍服务泛化（`AutoComboAccessibilityService` 替换 `WeakNetVolumeKeyAccessibilityService`）
- [x] 任务 4：录制器 `AutoComboRecorder`（浮层联动录制 + 尽力即时注入）
- [x] 任务 5：连招悬浮服务 + 液态玻璃弹窗面板（`AutoComboFloatingService` + 面板布局）
- [x] 任务 6：脚本列表与编辑页占位 Activity（`AutoComboActivity`/`AutoComboEditorActivity` + 布局）
- [x] 任务 7：设置页入口 + Manifest 注册（`activity_settings.xml` 卡片 / `SettingsActivity` 绑定 / 服务与 Activity 清单）
- [x] 任务 8：单元测试 + 编译验证 + 文档更新（`ComboPlaybackPlannerTest` 8 例通过；`:app:testDebugUnitTest` 编译通过）
- [ ] 任务 3：无障碍服务泛化
  - `WeakNetVolumeKeyAccessibilityService` 泛化为按 `volume_key_shortcut` 分发（弱网或连招总开关）
- [ ] 任务 4：录制器 `AutoComboRecorder`
  - 浮层联动录制（捕获触摸坐标 + dispatchGesture 即时回放给下层）；手动添加步骤兜底
  - Shizuku 输入事件读取作为预留增强（本次不实现 getevent 解析）
- [ ] 任务 5：连招悬浮服务 + 液态玻璃弹窗面板
  - `AutoComboFloatingService`：可拖动悬浮球 + 点按展开面板（overlay 窗口）
  - `layout_auto_combo_panel.xml`：矩形圆角液态玻璃（半透明+高光+渐变的 `bg_panel` 体系），含总开关/脚本切换/播放模式/录制/回放/状态/变速/编辑入口
- [ ] 任务 6：脚本列表与编辑页
  - `AutoComboActivity`（列表/重命名/删除/新建/导入导出 JSON）
  - `AutoComboEditorActivity`（逐步编辑 + 0.5x-2.0x 变速）
- [ ] 任务 7：设置页入口 + Manifest 注册
  - `activity_settings.xml` 新增"自动连招"卡片 + `SettingsActivity` 绑定
  - Manifest 注册新 Activity 与无障碍服务（复用/更新音量键服务）
- [ ] 任务 8：单元测试 + 编译验证 + 文档更新
  - `AutoComboEngine`/`AutoComboRepository` 单测；`:app:assembleDebug` 编译
  - 更新 tasklist/design 实现标注

## 实现备注

- 音量键快捷功能互斥：`volume_key_shortcut` 单车值，弱网与连招二选一；弱网设置页原"音量键控制"UI 保持布尔但改由派生状态驱动。
- 面板与悬浮球同属一个前台服务，都在 overlay window；Android 12+ 尝试 `blurBehindRadius`，低版本回落渐变模拟。
- 录制浮层联动：录制时面板/悬浮球变成透明虚拟层接收触摸坐标，同时用 `dispatchGesture` 回放给下层目标应用，实现"边录边响应"。