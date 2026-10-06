# 用户指令记忆

本文件记录用户的指令、偏好和教导，以及 Agent 发现的项目知识，按主题合并整理，供未来交互参考。

## 条目

[版本号要求]
- Date: 2026-06-13 起多次更新（最新 2026-09-30）
- Context: 用户多次调整版本号并要求同步展示
- Instructions:
  - 版本号按修改次数递进，补丁位逢十进一（如 2.6.9 → 2.6.10 → 2.7.0）；同步提升 versionCode。
  - 版本展示位置保持一致：build.gradle.kts、首页版本文案、规则源请求 User-Agent。
  - 当前版本：3.2.2 (322)。

[广告拦截总方针]
- Date: 2026-04-24 起多轮更新
- Context: 用户长期要求增强广告拦截并保持稳定流畅
- Instructions:
  - 稳定性优先：保守实现，避免误杀主业务流量；白名单/敏感鉴权/媒体/业务/游戏/社交核心域名保护放行。
  - 只拦明确广告流量（广告域名/路径/物料/SDK/跳转信号）；普通业务接口、正文、登录鉴权、支付、媒体内容直通。
  - 对齐并超越 AdGuard：规则语义兼容、协议覆盖、HTTPS MITM 成熟度、App 级识别、误伤控制持续增强；每轮尽量落成可测试小步改动。
  - 充分利用已有能力：VPN、HTTP decrypt、QUERY_ALL_PACKAGES、Shizuku（连接归属识别 → 热路径决策，只对高风险应用启用更积极拦截）。
  - MITM 开启后可积极（全流量全路由接管、深度检查），但保留证书门槛、普通联网 passthrough、熔断回退，目标是提覆盖同时不断网。
  - 开 HTTP 解密时性能优先：只对命中动态解密目标/广告厂商/广告特征路径的流量深度处理，普通请求直通。
  - QUIC：MITM 开启时全量阻断强制回 TCP（对齐 AdGuard）；未开启时保守精细判定，正常业务 UDP/443 优先放行。
  - 加密 DNS 反绕过：DoH/DoQ/DoT/HTTPDNS 域名 + 公共 DNS IP 黑名单，最小接管（只给黑名单 CIDR 加 VPN route，命中静默丢弃）。
  - 小说类 App 广告（开屏/Banner/章节插屏/原生/弹窗/任务中心/退出挽留）优先拦截；强拦只对已识别小说应用生效，排除主业务域。
  - 当前阶段不做无障碍自动跳过（李跳跳类功能）。
  - 低耗电低后台占用：高频热路径用内存缓存，收敛后台周期任务；拦截关闭后不留自启动或后台驻留。
  - 免广告领奖励与免费领流量卡功能已全量删除（设置开关/四层放行/AdRewardInterceptor/统计/布局/外链），后续增强不得引用 AdFreeReward/AdReward/TrafficCard/lot-ml 符号；当前为无例外全拦语义，新增拦截层无需考虑奖励放行分支。

[规则导入与管理]
- Date: 2026-06-14 起多轮更新
- Context: 用户对规则导入稳定性、兼容性、管理体验的持续要求
- Instructions:
  - 导入稳定性优先：无论规则多大都快速、不卡死、不崩溃；流式读写；先落可直接拦截的域名规则，后台分批分析复杂语义；单批失败保留已导入结果；导入阶段不做全量去重（交给清理功能）。
  - 只提取"明确域名型"规则；keyword/regex/path/ip-cidr/逻辑组合规则不降级整域拦截，记为 unsupported/complex 样本；带路径的 ||domain/path^ 禁止在任何 fast path 降级；DNS 层匹配跳过 pathPattern/keywordPattern。
  - AdGuard DNS 兼容边界：dnstype=/important/match-case/badfilter 可落地；domain=/app=/denyallow=/third-party=/redirect= 等上下文修饰符保持不支持；$important 语义 = 优先于普通例外，四处索引构建路径行为一致；VPN 命中按真实 DNS qType 匹配。
  - 兼容格式：Hosts（一行多域名）、DOMAIN-SUFFIX/DOMAIN、HOST-SUFFIX/HOST、dnsmasq server=/local=、ipset=/nftset=、行尾注释、hostname/domain 别名变体。
  - 规则源默认空列表，用户手动添加；不自动注入内置源；规则源可删除；同步后疑似正常规则先逐条展示理由、用户确认后才删。
  - 统计口径："规则总数" = 真实参与拦截的 supported rules；暂不支持规则单独统计与清理。
  - 流式导入 fast path 必须 classifyVendorSimple 识别厂商并持久化，禁止硬编码 DEFAULT_VENDOR；存量 vendor==DEFAULT 简单域名规则随加载迁移重算；厂商名称统一"中文名 (国际名)"，normalizeVendorName 支持链式别名归一化。
  - 缓存一致性：规则追加必须同步处理 cachedBloomFilter（增量 put 或置 null），否则 Bloom 预筛选永久否决新域名；simpleIndex/trie 增量更新，ruleMap/universalRuleMap/cnameRuleIndex/regex/keyword 置 null 惰性重建。
  - DNS 决策缓存 TTL 60s（RuleRepository.DECISION_TTL_MS）；规则增删改路径必须 clear；修改判定语义时评估提前失效；save/append 已覆盖。
  - 规则页输入框支持批量粘贴多条域名一次性添加，复用安全解析。
  - 可疑域名观测：样本记录域名/次数/最近应用名/厂商/小说命中次数并节流写入；页面支持搜索、批量添加、只看已添加/只看未添加（互斥）、小说专项筛选；批量添加后刷新规则页。
  - "筛选非广告规则"点击必须弹窗逐条展示 + 勾选删除；规则页摘要显示内置/导入/手动/暂不支持计数。

[界面视觉与交互约定]
- Date: 2026-04-22 起多轮更新
- Context: 用户对视觉、布局、交互的多轮要求
- Instructions:
  - 所有按钮/控件/卡片/弹窗统一白色半透明底（与规则列表容器同源 bg_panel/hf_surface，约 50% 不透明度），浅黑细边框、圆角、深灰文字；主题层防止系统默认样式覆盖成深色。
  - 沉浸式状态栏：背景图延伸到状态栏，控件本身避开状态栏保持安全间距；首页三主控件尺寸不变、位置整体下移。
  - 三页（首页/规则/统计）独立背景图从 app/src/main/assets/custom/ 读取：home_background、rules_background、stats_background；主容器只留 ViewPager2，不加总背景/遮罩；大图用 CustomVisuals.decodeSampledDrawable（maxDimension=1920）降采样防卡顿。
  - 排行榜前三奖牌图标 assets/custom/：medal_gold、medal_silver、medal_bronze；缺失时回退数字名次；应用图标保留自定义资源入口。
  - 主界面标题"寒枫"；应用名"寒枫 · 广告拦截"；包名 com.HanFeng。
  - 排行榜紧凑：默认前五，"查看完整榜单"弹窗展示完整；应用排行只显示名次/应用名/数量（不显示包名）；未知应用靠缓存与回退识别尽量替换真实应用名；厂商识别用规则分组结果优先、域名关键字兜底 + 关键词命中能力。
  - 已删除页面翻页动画，保证左右滑动稳定；开拦截后主按钮文案切换"停止拦截"；所有按钮有稳定点击反馈。
  - 弹窗一律 StableDialog.builder/materialBuilder + showSafely/showMaterialSafely（FLAG_BLUR_BEHIND + dim 兜底 + 入场动画）；create() 后手动 show 的必须补 applyLiquidGlassWindow；禁止直接 new AlertDialog.Builder/MaterialAlertDialogBuilder；批量替换 .show() 时严防误伤 Toast；省电模式系统会拒绝模糊渲染。
  - 应用列表加载放后台线程 + 进度圈；图标异步加载；RecyclerView 避免耗时渲染；TextView 粗体用 setTypeface(Typeface.DEFAULT, BOLD)。
  - 外链：QQ 群按钮群号 573309536；规则下载按钮先复制密码 aehi 再打开 https://hanfengnb.lanzoul.com/b0j1elsrg。
  - 白名单逻辑：默认全部应用受拦截，加入白名单的应用完全放行。
  - 无明确要求时不改动现有界面布局与功能入口。

[拦截链路与性能技术约定]
- Date: 2026-09-03 起多轮确认
- Context: Agent 在 DNS 延迟、耗电、学习引擎、决策页等优化中确认
- Instructions:
  - 拦截态 DNS 延迟：Android Q+ TUN 阻塞读，异步 DNS 应答必须由独立 drain 协程（launchDnsResultDrainer，10ms 周期）经 tunOutputStream 写出；DNS worker 双线程并发消费 dnsTaskIn；改生命周期需同步 interrupt 两线程并取消 drain 协程。
  - 耗电：上游 DNS UDP 优先，DoH 仅 UDP 失败后并发少量端点回退，全失败进熔断冷却，禁止每次查询新建 TLS；DNS 结果消费用带超时阻塞 poll（秒级），禁 10ms 忙轮询；已判定 TLS 流（0x14/0x15/0x17）跳过 SNI 重组拷贝，集合超限整体清理；高频日志用 ConcurrentHashMap + TTL 限频，禁跨线程抢锁 LRU。
  - DNS 响应缓存命中后仍需再过 RuleRepository.isBlocked，否则新规则在 TTL 内不生效；规则/配置变更重建 VPN 时清 SNI、明文 HTTP、TCP-DNS 流判定缓存；网络切换回调（invalidateNetworkDependentCaches）重置 DoH Network 缓存与熔断时间。
  - SNI 拦截与 MITM 解耦：shouldBlockBySniWithoutMitm 在 httpDecryptEnabled=false 时独立运行（只看 TLS ClientHello 明文 SNI，发 RST，对证书绑定 App 也有效）；SniInterceptor.evaluate 零 MITM 依赖（RuleRepository + ScoredBlockCache + isProtectedTrafficDomain）；QUIC/加密 DNS 反绕过判定不得放在 httpDecryptEnabled 门控内；同域名不同路径的内容级广告（如 api.xxx.com/comment/list）只能靠 MITM 内容层 HttpMitmFilter。
  - TCP DNS（53/TCP）拦截走 handleTcpDnsPacket：首包 2 字节长度前缀校验 + 命中 RST；AAAA 抑制用 suppressAAAARecords（RDATA 清零，不物理删除避免压缩指针失效）。
  - 学习引擎：观测必须与 MITM 路由解耦（observe 之前不得 shouldUseActiveMitmRouting() 提前 return，否则未装证书设备完全不会自动识别）；学习结果必须在 DNS 层被消费（快慢路径都查 ScoredBlockCache.isDomainBlocked 并 sinkhole），查询前过白名单/敏感认证/受保护流量例外；开关 key mitm_learning_mode 默认 true，UI 入口 switchAutoLearnAd，关闭只停新增。
  - DNS 侧学习信号（maybeApplyDnsBehaviorSignals）：IPv4 聚类（同 IP 广告基础设施域名 ≥ DNS_IP_CLUSTER_MIN_AD_HOSTS）+ 未知域名扇出（窗口 ≥10），都是 AD_CONTENT_CLUSTER（权重 2 上限 4），需叠加才够阈值 10；扇出只统计 vendor==UNKNOWN 域名；IPv6 不参与聚类；窗口/集合有上限（64 域名/256 App），新增结构挂进 MitmLearningEngine.prune()。
  - 学习日志行必须保持 "Blocked ... domain=<domain>" 形状（决策页正则依赖）；LogRepository.getDomainDecisionEntries 增量解析（decisionParseOffset），页面 2 秒轮询，禁整文件重扫（日志上限 8MB）；overlay 纠正复用已有条目，禁刷 timestamp/重复回写；行点击走"域名操作"面板（内容/切换拦截放行/复制），内容面板给出规则库现状/厂商/特征/风险/学习依据/原始日志。
  - 用户手工放行域名必须同时 ScoredBlockCache.dropDomain()；入库走 persistDomainToRules/persistLearnedDomainsToRules（内部 addRule IMPORTED）。
  - 上游 DNS 过滤本地虚拟地址（10.99.0.2、fd66:66::2）防回环，系统 DNS 不可用回退公共 DNS；IPv6 本地 DNS 判断先做 InetAddress 标准化；上游失败回 SERVFAIL，被拦域名对非 A/AAAA 也回合法响应；上游健康退避（失败次数/冷却/最近成功）+ 陈旧缓存仅全失败时兜底。
  - 放行实时生效：RuleRepository.clearWhitelistDomainCache() + WhitelistActivity.scheduleVpnReload（delay 350ms + NetworkKernel.reloadIfRunning），cachedWhitelistHits 上限 500_000。
  - VPN/HTTP/HTTPS 决策统一生成 reason 字段供日志面板复用，避免各写一套文案。
  - 排行榜详情在页面内重新读取统计，禁大字符串 Intent 传完整榜单；统计持久化 map 需裁剪上限；统计数据拦截事件后及时刷新 UI。

[Root 区域与设备标识]
- Date: 2026-07-11 起多轮确认
- Context: Root 功能集成与修复中确认的模式
- Instructions:
  - SuSession API：getInstance()/open(timeoutSeconds)/execute(command, timeoutSeconds)→ShellResult(exitCode, output)/isSessionOpen()；单例在 com.HanFeng.adblocker.shizuku.SuSession；内联命令不得含 exit。
  - 长期守护任务遵循"JVM 仅作 launcher"：SuSession 拼 watcher.sh 写 /data/adb/<FeatureName>/（watcher.sh/watcher.pid/watcher.log），nohup sh 启动，PID 写 .pid；JVM 重启后 kill -0 $(cat pid) 判断守护存活；禁 JVM 内 while(true) 轮询。
  - Kotlin 字符串内 shell 变量统一用 ${'$'} 注入，禁 \$ 转义（早期 \$ 写法已被取代）。
  - Android 8+ Android ID 是按包名隔离的 SSAID（settings_ssaid.xml 带 package= 的行），只改全局 secure.android_id 无效；必须重写全部 App 级 SSAID 条目并以 SSAID 验证。
  - prop 伪装失效根因：RIL/modem 重启从 NV 回读覆盖 prop，模块 service.sh 只开机跑一次；修复 = HanFengPropWatch 守护（/data/adb/HanFengPropWatch/watcher.sh，nohup + pid 幂等，30 秒周期按 props.list 增量恢复）+ service.sh 重写规则并拉起守护。
  - 主板 ID = SoC 平台代号（prop 优先级：ro.board.platform > ro.boot.board.platform > ro.board.hardware > ro.hardware > ro.boot.hardware > ro.soc.model），校验 ^[a-z0-9._\-]+$；随机生成从真实 SoC 代号表选；SN 码与设备序列号不同源（2026-10-04 修正）：拨号盘 *#06# SN 行由 gsm.sn/persist.sys.sn/ril.sn 等驱动，ro.serialno/ro.boot.serialno/persist.sys.serialno 承载设备序列号，SN 读取/预填只从 SN prop 组 + EFS 取，序列号单独标注展示；IMEI 双卡槽 writeImeiDual 用 IMEI2 专属键（gsm.imei2/ril.imei2/persist.sys.imei2），IMEI2 留空回退 writeImei。
  - DeviceIdModifier.runRootShell 统一入口：execute 前 isSessionOpen 检查 + open(timeoutSeconds=30)，覆盖 backup/restore/read/write 全路径。
  - prop 持久化双根因（2026-10-04 修复，勿回退）：HyperOS/toybox 无 awk，props.list upsert 用 awk 临时文件会塌陷成只剩最后一条 → 必须用 sed（sed -i '/^key=/d' + printf 追加，key 含 . 要转义）；模块目录内 post-fs-data.d/service.d 子目录 Magisk/KSU/APatch 均不执行，zygote 前应用 ro.* 必须写全局 /data/adb/{post-fs-data.d,service.d}；service.sh 的 `[ -r "$PL" ]` 括号前空格缺失会让脚本首行 exit（shell 测试空格是语法）。
  - EFS 提取增强：IMEI 用 14-17 位数字段滑 15 窗 Luhn 校验（modem NV 常嵌在更长数字串）；提取失败时附 efsDiagnostics()（分区 MISSING/DENIED/OK + getenforce + strings 存在性），"无法读取"必须有原因。
  - API 层伪装（内置 Xposed/LSPosed 模块，LSPosed 即 Zygisk hook）：hook 类 com.HanFeng.xposed.HanFengSpoofHook（assets/xposed_init + manifest xposedmodule meta-data）；hook TelephonyManager.getImei/getMeid/getDeviceId（含 int 变体按 slot 匹配）+ Build.getSerial/SERIAL；配置 /data/local/tmp/hf_spoof.conf（644，App 可读；/data/adb 对 App 进程不可读不可用）；Xposed 桩必须用 Java 类（Kotlin object 调用点生成 INSTANCE 字段访问会 NoSuchFieldError），桩方法描述符与 API 82 精确一致（findAndHookMethod 返回 void），桩依赖父优先委托运行时被 LSPosed 真实实现取代。
  - MEID 正则只匹配带 meid/mMeid 标签的行（telephony.registry 兜底与 RIL 汇总均是），防 dumpsys 任意 14 位 hex 误匹配（2026-09-30 修复）。
  - 用户确认有效必须保留：腾讯游戏防设备标记、证书安装到系统。
  - 性能调优：设置页 btnPerformanceTuner → PerformanceTunerActivity；SCENE standalone/scene_dep 双模式（RadioGroup 切换，按 ro.board.platform 前缀 mt*/MT* 判天玑）；部署产物 /data/adb/HanFengPerf（scene/、appopt/，日志 scene.log/appopt.log），模块经 filesDir/perf_staging 中转复制；AppOpt 仅 arm64-v8a；cpu_control.sh 用 mksh 风格 function() 语法（本地 dash 检查会误报）；默认精简 AppOpt 模板（约 50 行），不带完整 289KB applist.prop；SceneParamsEditorActivity/AppOptRulesEditorActivity 全屏编辑器需 Manifest 注册；scene_fmax_cap（默认 auto）/scene_thermal（默认 49500，UI 显示 °C、输入 *1000）独立持久化键，restoreDefault* 一键回默认；CPU/GPU 频率监控读 /sys/devices/system/cpu/cpu*/cpufreq/ 与 /sys/class/kgsl/kgsl-3d0/gpuclk。

[Shizuku 集成与授权约定]
- Date: 2026-07-18 起多轮更新（最新 2026-09-30 重构）
- Context: Shizuku fork 内置、授权流程、区域重构
- Instructions:
  - shizuku-fork 子工程 11 个 Gradle module（aidl/shared/common/api/provider/server-shared/rish/starter/server/manager）全部内置主 APK（lib/<abi>/libshizuku.so、librish.so、libadb.so × 4 ABI）；客户端 SDK 仍用 dev.rikka.shizuku:api:13.1.5（A 路线，只内置 starter）。
  - BuiltInShizukuStarter.init(context) 保存 context；activateViaRoot 从自身 nativeLibraryDir/libshizuku.so 以 root shell 启动 server；SERVER_NAME=hanfeng_shizuku_server（stop 时 pkill -f + rm /dev/socket）。
  - License 第 6 条字符串约束：applicationId=com.HanFeng.shizuku、permission=com.HanFeng.permission.shizuku.*、extra prefix=com.HanFeng.shizuku.intent.extra.*、REQUEST_PERMISSION action 同前缀；禁用 moe.shizuku.privileged.api / moe.shizuku.manager.permission.*；Java 内部包名 moe.shizuku.*/rikka.shizuku.* 保留。
  - fork:manager 不得声明 moe.shizuku.manager.permission.API_V23（否则官方 Shizuku 安装报 INSTALL_FAILED_DUPLICATE_PERMISSION），只保留 uses-permission；server 端 isClientPermissionRequested 同时识别官方权限串。
  - 主 app 强制 androidx.core 1.13.1（resolutionStrategy.force），否则 fork:manager 拉 1.16.0 与 AGP 8.5.0 不兼容。
  - fork:aidl 必须 buildFeatures.aidl=true；子 modules plugin 版本集中在 settings.gradle.kts pluginManagement；NDK 26.1.10909125，cmake 3.22.1（CMakeLists 降到 3.22）。
  - 设置页 Shizuku 入口统一 requestShizukuThen：未授权主动 ShizukuRepository.requestPermission()；binder 不可达先 BuiltInShizukuStarter.activateViaRoot() 自动拉起 server；授权成功预热增强服务后接续 pendingShizukuAction；监听 onCreate 注册/onDestroy 移除（REQUEST_CODE=4096，与 MainActivity 同码不冲突）。
  - 就绪检查统一流程：预热 user service → 检查 connection owner/ad control 存活 → 再给不可用提示；Binder 可达但权限异常时参考增强服务是否存活决定"兼容模式可用"或"增强服务尚未就绪"。
  - 2026-09-30 重构：ShizukuServiceBinder<T> 基类统一绑定状态机（AdControl/ConnectionOwner 两 Repository object 继承，getService 主线程 200ms 快速失败、isReady public、ensureBoundAndWait AdControl 侧带 checkServiceHealth 兜底）；删除死代码（ShizukuHostsModifier/ShizukuBackgroundRestrictor/ShizukuNetworkPermissionController/RootScriptExecutor 四文件 + ShizukuHideManager 死方法/死常量）；ShizukuPermissionManageActivity 已恢复设置页入口 btnShizukuPermission（此前"不从设置页跳转"的约定已失效）；应用列表逻辑提取到 ShizukuAppListController。
  - 无线调试配对"发送没反应"根因（2026-10-04 修复，勿回退）：shizuku-fork 的 AdbPairingClient/AdbClient 用 `Socket(host, port)` 无连接超时、TLS 握手无 soTimeout → 配对端口失效（HyperOS 每次开配对弹窗端口会变）时阻塞挂死，`pairing=true` 永久卡住吞掉后续点击。修复 = connect 5s + soTimeout 10s（阻塞 IO 协程取消不了，必须 OS 级超时）+ WirelessDebugFloatingService.pairAndActivate 外层 withTimeoutOrNull(45s) 兜底 + 失败重扫 mDNS；root 激活点击即给 toast 反馈，失败走 showActivationFailureDialog。

[外设管理与已删除功能]
- Date: 2026-09-30 起多轮确认（最新 2026-10-04）
- Context: 外设管理功能完成与多项功能删除
- Instructions:
  - 散热器：BLE + CoolerProtocol 接口隔离（红魔/黑鲨/通用透传各自实现）；功率 0-100 为风扇档位（PWM 占空比），固件联动调 TEC 电流 + 风扇转速，单档位行为与官方 App 一致；CoolAutoTuner 纯迟滞门控（HYSTERESIS_CELSIUS=3.0）；一键散热模式自动关闭智能温控；ANC 状态诚实呈现，不伪造成功。
  - 兼容未知散热器（派威=Piva 无公开协议）：扫描候选全保留排序（已知协议→关键词→其余），可写特征放宽到 WRITE|WRITE_NO_RESPONSE，订阅所有可通知特征，UI 提供红魔/黑鲨/ASCII 三协议试探按钮（sendRaw + hex 回显），设备有响应后再固化协议。
  - 蓝牙耳机 ANC 适配表：小米/Redmi 关键词（REDMI/XIAOMI/小米/MI BUDS）必须排在三星前，三星匹配限 GALAXY BUDS/BUDS（裸 BUDS 会抢匹配 Redmi Buds 误判）；小米/Redmi 无公开 ANC GATT 协议，仅记录偏好+引导厂商 App。
  - keybox 功能已删除（市面成熟方案如 TickyStore 存在）；蓝牙耳机低电量提醒已删除（耳机/手机自带）；免广告领奖励/免费领流量卡已删除（见广告拦截总方针）。

[构建环境]
- Date: 2026-09-29 起多次重建确认
- Context: 构建环境重置与重建经验
- Instructions:
  - 依赖 Android SDK：local.properties 写 sdk.dir=/opt/android-sdk（或 ANDROID_HOME）；JDK 17（Debian bookworm 用 apt-get install -y default-jdk-headless，没有 openjdk-17-jdk-headless 包名）。
  - 工作区缺 gradle-wrapper.jar，./gradlew 必然失败（Could not find GradleWrapperMain）；用发行版 bin/gradle（Gradle 8.8，历史路径 /tmp/opencode/gradle-8.8 或 /opt/toolset/gradle-8.8；重置后从腾讯镜像 https://mirrors.cloud.tencent.com/gradle/gradle-8.8-bin.zip 重下）。
  - 环境重置重建顺序：JDK → gradle 解压 → cmdline-tools 移 /opt/android-sdk/cmdline-tools/latest → yes | sdkmanager --licenses → sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.x" → local.properties。
  - 内存受限参数：--no-daemon -Dorg.gradle.jvmargs='-Xmx1100m -XX:MaxMetaspaceSize=384m' -Dkotlin.compiler.execution.strategy=in-process（gradle.properties 默认 4G+4G 堆会 OOM）；native CMake 阶段 daemon 被杀 → 加 -Dorg.gradle.workers.max=2 + background terminal memory_percent 兜底。
  - 构建任务必须用 background terminal（先 list 再 create）；Kotlin daemon 偶发 NoSuchObjectException: no such object in table 会自动回退重试，重跑即可。
  - :app 依赖 :shizuku-fork:*，首次构建自动下载 NDK 26.1.10909125（约 2G、16 分钟+），装好后增量编译几分钟；需联网解析 AGP 8.5.0。
  - Kotlin 编译错误在 native 阶段前暴露；日志带 | tail 会缓冲到结束，查进度用 ls app/build/intermediates 和 ps aux | grep GradleDaemon。
  - Kotlin 语法要点：object 可继承泛型基类（object X : Base<T>()）；inner class 不能有 companion object（DIFF 引用 unresolved），嵌套 Adapter 用 nested class；com.HanFeng.ui 包内文件需 import com.HanFeng.R；类体内不能声明 private const val（只能 companion/顶层）。
  - 沙盒增量编译与 Android Studio 结果可能不一致（扩展符号解析差异）；用户本地构建成功以用户为准，勿反复重试沙盒编译。
  - 测试：JVM 单测不 mock Android（相关依赖 stub/flag 关闭）；测试目录 app/src/test/java/com/hanfeng/adblocker/**（package 用 com.HanFeng.*，目录与包名不一致是既有约定）；当前 280 测试全绿。

[项目基础事实]
- Date: 2026-04-22 起累积
- Context: 项目初始化与历史约定
- Instructions:
  - 单模块 app/ + shizuku-fork 子工程；Kotlin + XML + ViewPager2 三屏；minSdk 24，兼容 Android 7–16；V1/V2/V3 三种签名方案。
  - VPN 仅接管本地 DNS 地址与常见 DoT 目标路由（最小接管架构）；POST_NOTIFICATIONS 在开 VPN 前运行时申请；VPN 线程降优先级 + 阻塞模式防空转；白名单变更后需重载 AdBlockVpnService。
  - 首次启动只申请真正需要的标准运行时权限；应用列表被系统额外限制时弹窗引导去系统设置手动允许。
  - 组件治理：SettingsActivity 按 splash/push/recommend/ad 关键词给 Activity/Receiver/Service 打分展示候选，操作格式 package/class（pm disable-user/pm enable）。
  - 磁贴/冻结：TileService/Activity 注册自定义 action 广播必须带 RECEIVER_NOT_EXPORTED/EXPORTED（Android 13+），否则 SecurityException 且表现为"APP 打不开"；包启用状态（enabledState/suspended）以本地 PackageManager 为权威（Shizuku serviceContext 未就绪返回 DEFAULT 会覆盖真实状态）；冻结语义 = disabled 或 suspended，解冻需 enable + unsuspend。
  - 进程监控：ProcessMonitor STARTED 生命周期 + processFlow/sampleError 采集；SamplingMode 维护独立 job（modeJobs map），stopSampling(mode) 只停自己；进程页主线程禁 SuSession.open()（fork su 最长 60s ANR），isBackingShellAvailable 只做 findSuBinary 无阻塞探测；RunningAppsActivity 是 AppCompatActivity，授权结果在 onRequestPermissionsResult/onActivityResult（requestCode=4096）里 refreshShellStatus()。
  - 使用说明：GuideActivity.DEFAULT_GUIDE_CONTENT 章节编号固定（一至二十四），用户未要求不新增/调整章节；Root 区域变化只更新"二十四、Root 区域功能说明"。
  - 日志导出：下载目录固定文件名，每次覆盖并清理同名旧副本。
  - 开源导出：排除 .git（重建新历史，作者 Han-Feng666@users.noreply.github.com）、.monkeycode/、.ai-ready/、根目录开发过程 *.md、adb_logcat_full_dir、ProxyPinCA_extracted、*.patch、local.properties、build 产物、CHANGE_TRIGGER.txt；.gitignore 的 "# AI Tools" 段替换为 Android 标准模板；导出后复扫 monkeycode/chaitin/claude/opencode 关键词（注意 grep 退出码判断）；README（功能/构建/架构）+ LICENSE（MIT + shizuku-fork Apache 2.0 声明）；首个导出包 /workspace/hanfeng-opensource-v3.1.1.tar.gz。
