# 寒枫 Adblocker 开发进度

## 已完成

### 终端模拟器功能增强
- [x] 快捷按钮栏：Tab、↑、↓、Ctrl+C、Ctrl+Z、Ctrl+D
- [x] 工具栏：字体大小切换（7档10-16sp）、清屏、复制输出
- [x] 复制粘贴：长按输入框粘贴，按钮复制全部输出
- [x] 命令历史：200条历史，上下方向键导航

### 广告拦截规则格式扩展
- [x] 点前缀域名：`.example.com` → `example.com`
- [x] Surge/Quantumult X/Loon：`DOMAIN-SUFFIX,example.com`、`DOMAIN,example.com`
- [x] dnsmasq/smartdns：`address=/domain/`、`domain-rules=/domain/`
- [x] 跳过类型：`IP-CIDR`、`DOMAIN-KEYWORD` 等非精确域名

### 规则导入进度优化
- [x] 导入对话框新增「取消」按钮
- [x] 进度更新频率：1000行/500ms
- [x] `ProgressDialogHandle` 新增 `isCancelled` 字段

### 证书安装简化
- [x] 新增 `auto_install_system_cert` 设置
- [x] 每次 VPN 启动时自动检测并安装证书
- [x] 设置页新增 CheckBox

### 后台静默导入
- [x] 导入进度对话框新增「静默导入（后台等待）」按钮
- [x] 点击后关闭对话框，后台继续执行
- [x] 完成后 Toast 提示结果

### 智能识别增强
- [x] 域名长度异常检测（>30字符 +2分）
- [x] 随机字符模式检测（辅音比例>70% + 数字比例>20% +3分）
- [x] 数字后缀模式检测（尾部≥3位数字 +2分）
- [x] 多级子域名广告模式（≥3级子域 + 含广告关键词 +3分）
- [x] 连字符广告模式（`-ad-`/`-track-` 等 +2分）
- [x] 降低拦截阈值：5分 → 4分

### 自定义背景图
- [x] 设置页新增「自定义背景图」设置项
- [x] 「选择图片」按钮：打开系统图片选择器
- [x] 「移除」按钮：移除自定义背景图
- [x] 三个界面（首页、规则、统计）统一使用同一张背景图
- [x] 背景图保存到 `files/backgrounds/custom_background.jpg`

### 免广告领奖励功能增强
- [x] 扩展域名检测：新增 S2S callback、verify、grant、coin、diamond 等模式
- [x] 扩展广告 SDK 厂商：华为、小米、OPPO、vivo、三星、Facebook、Firebase 等
- [x] 扩展 HTTP 响应检测：新增大量奖励回调 JSON 字段模式
- [x] 扩展 SDK 识别：AdRewardInterceptor 新增 Chartboost、InMobi、TapJoy 等
- [x] 通用奖励回调检测：自动检测并拦截奖励回调

## 待完成

### 功能优化
- [ ] 奖励回调智能学习：记录用户标记的奖励回调域名
- [ ] 奖励回调用户反馈：让用户标记哪些域名是奖励回调
- [ ] 奖励回调统计分析：显示拦截的奖励回调数量和应用分布

### 性能优化
- [ ] 规则导入内存优化：大文件分块处理
- [ ] DNS 查询缓存优化：增加 TTL 自适应
- [ ] HTTP 过滤性能：减少正则表达式使用

### 用户体验
- [ ] 规则导入进度条：显示百分比和预计剩余时间
- [ ] 可疑域名页面：显示智能识别的域名列表
- [ ] 背景图预览：设置前预览效果
