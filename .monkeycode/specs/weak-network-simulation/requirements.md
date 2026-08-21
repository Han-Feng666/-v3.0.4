# 弱网模拟功能需求文档

## Introduction

在 HanFeng 广告拦截 APP 中新增"弱网模拟"能力（参考腾讯 QNET）。用户可在设置页配置弱网参数，通过悬浮球一键开启/关闭，并可指定该弱网效果只作用于单个目标 App。

## Glossary

- **目标 App**：被施加弱网效果的单个应用（按包名选择）。
- **弱网参数**：延迟（RTT 增量）、丢包率、抖动（Jitter）、上行/下行限速等可配置值。
- **流量整形**：对 TUN 中的目标流量执行延迟注入、丢包、限速处理。
- **悬浮球开关**：悬浮球上提供弱网模式的快速开关入口。

## Requirements

### 需求 1：设置页弱网配置入口

**User Story:** AS 用户, I want 在设置页找到弱网入口并配置参数, so that 我可以按需自定义弱网效果

#### Acceptance Criteria

1. WHEN 用户打开设置页，系统 SHALL 显示"网络模拟/弱网"入口。
2. WHEN 用户点击弱网入口，系统 SHALL 展示参数配置页。
3. UI：参数页 SHALL 包含延迟(ms)、丢包率(0-100%)、抖动(ms)、下行限速、上行限速字段。
4. UI：用户 SHALL 能选择目标 App（包名列表），且可清除（恢复全局不指定App）。

### 需求 2：参数持久化

**User Story:** AS 用户, I want 我配置的参数被保存, so that 下次开启沿用相同设置

#### Acceptance Criteria

1. WHILE 用户修改任一弱网参数，系统 SHALL 将该参数持久化到本地偏好设置。
2. WHEN 弱网功能重新打开，系统 SHALL 使用最近一次保存的参数。
3. IF 参数值非法（负数、丢包率超过 100），系统 SHALL 拒绝保存并提示合法范围。

### 需求 3：悬浮球开关

**User Story:** AS 用户, I want 点击悬浮球即开关弱网, so that 我不需要进设置页即可快速切换

#### Acceptance Criteria

1. WHEN 悬浮球显示中，用户单击悬浮球，系统 SHALL 切换弱网的开启/关闭状态。
2. WHEN 弱网关闭状态下单击悬浮球，系统 SHALL 使用已保存参数开启弱网。
3. WHILE 弱网生效中，系统 SHALL 在悬浮球上持续显示弱网生效状态（标识/颜色变化）。
4. IF 悬浮球单击不再用于打开主界面，系统 SHALL 保证用户仍可通过设置页/通知方式进入主界面（设计决策）。

_决策记录：用户确认单击悬浮球=直接切换弱网，不再打开主界面。_

### 需求 3b：音量键控制（可选设置项）

**User Story:** AS 用户, I want 用音量键开关弱网（可选）, so that 不方便点悬浮球时也能控制

#### Acceptance Criteria

1. WHEN 用户在设置页开启"音量键控制弱网"选项，系统 SHALL 在弱网相关状态下监听音量键。
2. WHEN 弱网已开启且用户按音量上/下键，系统 SHALL 关闭弱网。
3. WHEN 弱网已关闭且用户按音量上/下键，系统 SHALL 开启弱网。
4. WHILE 该选项关闭，系统 SHALL 不拦截音量键，保持系统原生音量调节行为。

### 需求 4：单目标 App 弱网

**User Story:** AS 测试人员, I want 弱网只对指定 App 生效, so that 其它应用不受影响

#### Acceptance Criteria

1. WHEN 用户指定了目标 App 且开启弱网，系统 SHALL 仅对该 App 的流量实施整形。
2. WHILE 目标 App 弱网生效中，其它应用的网络流量 SHALL 不被整形。
3. IF 未指定目标 App，系统 SHALL 将弱网整形作用于全部经过 VPN 的流量。
4. WHEN 目标 App 弱网模式生效，系统 SHALL 以 `addAllowedApplication(目标App)` 重建 VPN 会话，广告拦截临时仅作用于该目标 App（其它 App 流量绕过 VPN）。

_决策记录：用户接受单 App 弱网时的会话切换取舍，广告拦截对其它 App 临时暂停。_

### 需求 5：开启/关闭动作

**User Story:** AS 用户, I want 一键生效/失效弱网, so that 我能快速对比弱网前后表现

#### Acceptance Criteria

1. WHEN 用户开启弱网，系统 SHALL 确保 VPN 转发链路激活并立即开始整形。
2. WHEN 用户关闭弱网，系统 SHALL 停止整形并恢复目标 App 的正常网络。

### 需求 6：状态反馈

**User Story:** AS 用户, I want 知晓弱网是否在生效, so that 避免误判测试结果

#### Acceptance Criteria

1. WHEN 弱网生效中，系统 SHALL 在设置页弱网入口显示"已开启"状态。
2. WHEN 弱网被关闭，系统 SHALL 在设置页弱网入口显示"已关闭"状态。

### 需求 7：与广告拦截共存

**User Story:** AS 用户, I want 开启弱网不应破坏广告拦截主功能, so that 我无需在功能间反复切换

#### Acceptance Criteria

1. WHILE 弱网生效且未指定目标 App，广告拦截功能 SHALL 保持工作。
2. WHILE 弱网生效且指定了目标 App 且使用"单App限定会话"方案，系统 SHALL 说明广告拦截的作用范围变化（见设计权衡）。

## 待确认问题（已确认）

1. **适用范围**：全局 + 单 App 两种模式都要。
2. **单 App 广告拦截**：接受"addAllowedApplication(目标App) 重建会话、广告拦截临时仅对目标 App 生效"的取舍。
3. **悬浮球交互**：单击悬浮球直接切换弱网开/关（不再打开主界面）。
4. **音量键**：做成可选设置项，开启后音量键切换弱网。

## 技术约束（设计需满足）

- `VpnService` 单会话无法同时"全局广告拦截所有 App + 只整形单个 App"；单 App 弱网采用重建会话方案。
- 弱网整形在现有 TUN 转发管线内实现（延迟注入/丢包/限速），不新增重打包。
- minSdk=24。