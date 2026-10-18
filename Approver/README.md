# Approver: ChatGPT 桌面版审批自动点击工具

Approver 是一款专为 macOS 上 ChatGPT 桌面版（基于 Chromium / Electron 架构的 Codex Agent）打造的**无人值守审批自动点击工具**。

当你在 ChatGPT 桌面版中执行构建、运行测试、读写文件等开发任务时，Agent 会频繁弹出确认审批卡片。本工具通过 macOS Accessibility（无障碍）技术自动识别并派发点击，彻底解决频繁弹窗打断工作、多窗口切屏疲劳的问题。

---

## 🌟 核心特性

- **🎯 精准识别卡片（FR-01）**：采用自底向上（Bottom-up）容器遍历算法，只有当同一最小容器内**同时存在允许类与拒绝类按钮**时才判定为审批卡片，绝不误触普通确认框。
- **⚡ 无感后台派发（FR-02 / FR-04）**：使用 `AXUIElementPerformAction(kAXPressAction)` 原生派发控件动作，**不抢焦点、不激活前台、不移动鼠标**，即使窗口被遮挡或在后台也能静默处理。
- **🪟 多窗口并发支持（FR-03）**：每轮扫描遍历目标应用的所有窗口，无论开多少个任务对话，2 秒内全部并发自动放行。
- **🌿 策略灵活切换（FR-05）**：支持 `once`（允许一次，默认）、`conversation`（允许此对话）、`always`（始终允许），全量兼容中英文界面。
- **🛡️ 智能安全护栏（FR-17）**：内置危险命令黑名单（如 `rm -rf`、`sudo`、`curl | sh` 等），一旦命中卡片正文包含高危指令，自动跳过放行并记录告警。
- **🛑 闪电急停与熔断（FR-08 / FR-10）**：提供一条命令急停；并在 30 秒内点击达 15 次时触发频率熔断退出，杜绝失控连点。
- **🔄 去重与抗复用（FR-09）**：基于「窗口序号 + 拒绝按钮坐标（5pt 网格化）」形成特征签名，提供 3 秒冷却窗口，针对 Chromium 无障碍节点指针复用特性进行深度优化。
- **📦 极简零依赖（NFR-04）**：纯原生 Swift 单文件开发，仅使用系统框架（ApplicationServices / Cocoa），零第三方外部包。

---

## 📋 目录结构

```
Approver/
├── main.swift          # 单文件核心源码 (零第三方依赖，纯 Swift 原生 API)
├── approverctl.sh      # 交互式管理脚本 (启停、状态、干跑、自检、守护进程管理)
├── README.md           # 本说明文档
└── Approver            # 编译后的高性能二进制程序
```

---

## ⚙️ 前置准备与权限配置

本工具**仅需** macOS **「辅助功能」**权限，**无需**屏幕录制权限，最大程度保障隐私与安全。

1. 打开 macOS **「系统设置」** (System Settings)。
2. 进入 **「隐私与安全性」** (Privacy & Security) -> **「辅助功能」** (Accessibility)。
3. 将您运行脚本所使用的终端软件（例如 `Terminal`、`iTerm2`、`VS Code` 等）加入允许列表并勾选启用。
4. 运行自检命令进行验证：
   ```bash
   ./approverctl.sh check
   ```
   若输出 `辅助功能权限状态: ✅ 已授权`，说明配置成功！

---

## 🚀 快速上手

在 `Approver/` 目录下即可通过 `./approverctl.sh` 进行全生命周期管理：

### 1. 运行自检 (`check`)
自检模式会预热 ChatGPT 的无障碍树，扫描所有窗口、统计按钮及审批卡片：
```bash
./approverctl.sh check
```

### 2. 干跑模式 (`dry`)
干跑模式下工具会持续监听，当检测到审批卡片时仅在终端打印命中日志，**不执行实际点击**，用于验证识别准确度：
```bash
./approverctl.sh dry
```

### 3. 后台启动 (`start`)
常驻后台运行，实时自动批准：
```bash
# 默认策略：允许一次 (once) - 纯被动监听当前会话
./approverctl.sh start

# 巡检模式：开启侧边栏多会话自动轮转巡检 (优先处理当前会话，处理完毕自动切其他会话放行)
./approverctl.sh auto-switch
# 或通过参数开启：
./approverctl.sh start -a -s once

# 指定策略：允许此对话 (conversation)
./approverctl.sh start -s conversation

# 指定策略：始终允许 (always)
./approverctl.sh start -s always
```

### 3.1 侧边栏自动轮转巡检模式 (`auto-switch`) 说明
- **当前优先（Current First）**：当前对话窗口如有需要审批的卡片，**绝不切走**，立即自动点击放行，保障当前任务全速前进。
- **审批驻留（Post-Approval Grace）**：审批通过后停留 3 秒，防止连续动作产生的第二道审批被错过。
- **平滑轮转（Carousel Switching）**：当前对话无审批项时，通过系统无障碍事件 `AXPress` 自动切换到侧边栏的下一个待办会话（依然无需移动物理鼠标）。
- **参数调优**：
  - `-a, --auto-switch`：启用自动轮转。
  - `--switch-interval <秒>`：无审批时在每个会话停留巡检的时间（默认 1.8 秒）。
  - `--switch-limit <数量>`：轮转会话上限（默认 10 个）。

### 4. 查看运行状态 (`status`)
查看后台进程 PID、CPU/内存占用、最近日志：
```bash
./approverctl.sh status
```

### 5. 实时日志跟踪 (`log`)
```bash
./approverctl.sh log
```

### 6. 一键急停 (`stop`)
瞬间停止 Approver 后台进程：
```bash
./approverctl.sh stop
```

---

## 🎛️ 审批策略说明 (`-s, --strategy`)

| 策略标识 | 对应中文按钮 | 对应英文按钮 | 适用场景 |
|---|---|---|---|
| `once` (默认) | 允许一次 | Allow once | **推荐**。权限最小化，仅放行当前单次动作，最安全稳妥。 |
| `conversation` | 允许此对话 | Allow for this chat | 适合在信任的长期对话中，该对话后续同类操作不再反复弹窗。 |
| `always` | 始终允许 | Always allow | 适合完全信任的指令，写入用户持久白名单。 |

---

## 🛡️ 安全与防护机制

1. **危险命令跳过 (FR-17)**：
   自动读取卡片文本，若包含以下高危指令特征则自动跳过批准并写入 WARN 告警：
   - `rm -rf`
   - `sudo`
   - `curl | sh` / `curl | bash`
   - `wget | sh` / `wget | bash`
   - `mkfs`
   - `dd if=`
   - Fork 炸弹等
   *(若确实需要关闭护栏，可在启动时加上 `--no-guardrail` 参数)*。

2. **频率熔断机制 (FR-10)**：
   在滑动 30 秒窗口内，若累计点击次数达到 15 次，工具判定为异常高频或连环弹窗，会立即记录 ERROR 日志并以退出码 2 安全熔断退出。

3. **双重防重复点击 (FR-09)**：
   结合窗口编号与控件坐标网格化哈希签名，对已点击卡片施加 3 秒冷却保护，彻底避免重复触发。

4. **即时急停 (FR-08)**：
   脚本执行 `./approverctl.sh stop` 会触发 `/tmp/approver.stop` 信号文件，轮询主循环已切片为 100ms 快速响应，0.1 秒内即可干净退出。

---

## 💻 开机自启常驻服务 (LaunchAgent)

如果您希望本工具在每次开机登录后自动在后台默默守护：

```bash
# 1. 安装并注册开机服务 (支持传入参数)
./approverctl.sh install-agent -s once

# 2. 检查守护状态
./approverctl.sh status

# 3. 如需卸载开机自启
./approverctl.sh uninstall-agent
```

服务配置文件将存放于 `~/Library/LaunchAgents/com.user.approver.plist`，由 macOS 原生 `launchd` 管理，崩溃自动拉起。

---

## 🔍 技术设计要点（解决痛点）

1. **Chromium 惰性无障碍树预热**：
   ChatGPT 桌面版为 Chromium WebView 架构，无障碍树默认未激活。Approver 自动注入 `AXManualAccessibility` 与 `AXEnhancedUserInterface`，并通过 `warmUpTree` 按 PID 缓存预热，确保节点稳定可读。
2. **指针复用与防环**：
   Chromium 的 AXUIElement 内存地址会被重复复用，不能采用简单的指针哈希去重。Approver 采用「最大深度 60 + 单窗口 40,000 节点预算」进行确定性防环。
3. **信号安全派发**：
   使用 `DispatchSourceSignal` 派发系统信号（SIGINT / SIGTERM），避免在底层 Unix Signal Handler 中直接调用非线程安全的文件写操作。

---

## ❓ 常见问题排查 (FAQ)

- **Q: 为什么运行 `check` 显示没有检测到审批卡片？**  
  A: 这是正常现象。自检命令用于确认无障碍树能否读出 ChatGPT 界面上的按钮总数（一般应在 100~200 个控件左右）。当前没有弹出审批卡片时，卡片数量自然显示为 0。

- **Q: 提示 `未获得 macOS 辅助功能权限`？**  
  A: 请进入「系统设置」->「隐私与安全性」->「辅助功能」，确保勾选了当前执行命令的终端。

- **Q: 日志保存在哪里？**  
  A: 默认写入 `~/Library/Logs/Approver.log`（若无写权限则自动保存在当前工作目录的 `Approver.log`）。可通过 `./approverctl.sh log` 随时查看。
