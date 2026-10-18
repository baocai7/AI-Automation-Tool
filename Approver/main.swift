import Foundation
import ApplicationServices
import Cocoa

// MARK: - Configuration & CLI Arguments

enum ClickStrategy: String, CaseIterable {
    case once = "once"
    case conversation = "conversation"
    case always = "always"

    var displayName: String {
        switch self {
        case .once: return "允许一次 (Allow once)"
        case .conversation: return "允许此对话 (Allow for this chat)"
        case .always: return "始终允许 (Always allow)"
        }
    }
}

struct SelectorConfig {
    static let allowOnceLabels = ["允许一次", "Allow once"]
    static let allowConversationLabels = ["允许此对话", "Allow for this chat"]
    static let alwaysAllowLabels = ["始终允许", "Always allow"]
    static let denyLabels = ["拒绝", "不允许", "Deny", "Reject", "Decline"]
    static let suspiciousApprovalKeywords = ["允许", "批准", "allow", "拒绝", "deny", "reject"]

    static let dangerousKeywords = [
        "rm -rf", "sudo", "curl | sh", "curl | bash",
        "wget | sh", "wget | bash", "mkfs", "dd if=",
        ":(){ :|:& };:"
    ]

    static func matchesLabel(_ title: String, candidates: [String]) -> Bool {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        for c in candidates {
            let clower = c.lowercased()
            if trimmed == clower || trimmed.hasPrefix(clower) {
                return true
            }
        }
        return false
    }

    static func matchesAllowOnce(_ title: String) -> Bool {
        return matchesLabel(title, candidates: allowOnceLabels)
    }

    static func matchesAllowConversation(_ title: String) -> Bool {
        return matchesLabel(title, candidates: allowConversationLabels)
    }

    static func matchesAlwaysAllow(_ title: String) -> Bool {
        return matchesLabel(title, candidates: alwaysAllowLabels)
    }

    static func matchesDeny(_ title: String) -> Bool {
        return matchesLabel(title, candidates: denyLabels)
    }

    static func isSuspiciousApprovalButton(_ title: String) -> Bool {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        // 审批按钮文案通常言简意赅 (如 "允许一次", "拒绝", "请批准" 等)，过滤长段聊天记录或历史标题
        guard trimmed.count <= 30 else { return false }
        let lower = trimmed.lowercased()
        for kw in suspiciousApprovalKeywords {
            if lower.contains(kw) {
                return true
            }
        }
        return false
    }

    static func checkDangerousCommand(_ text: String) -> (hit: Bool, keyword: String?) {
        let lower = text.lowercased()
        for kw in dangerousKeywords {
            if lower.contains(kw) {
                return (true, kw)
            }
        }
        return (false, nil)
    }
}

enum AppMode: String {
    case run = "run"
    case dry = "dry"
    case check = "check"
}

struct AppOptions {
    var mode: AppMode = .run
    var strategy: ClickStrategy = .once
    var pollInterval: TimeInterval = 0.8
    var bundleId: String = "com.openai.codex"
    var enableGuardrail: Bool = true
    var logFilePath: String = (NSString(string: "~/Library/Logs/Approver.log").expandingTildeInPath)
    var stopFilePath: String = "/tmp/approver.stop"
    var maxDepth: Int = 60
    var maxBudget: Int = 40000
    var circuitBreakerWindow: TimeInterval = 30.0
    var circuitBreakerThreshold: Int = 15
    var clickCooldown: TimeInterval = 3.0
    var autoSwitch: Bool = false
    var switchInterval: TimeInterval = 1.8
    var afterApprovalStay: TimeInterval = 3.0
    var switchScopeLimit: Int = 10

    static func parse(args: [String]) -> AppOptions {
        var opts = AppOptions()
        var idx = 1
        while idx < args.count {
            let arg = args[idx]
            switch arg {
            case "check", "--check":
                opts.mode = .check
            case "dry", "--dry":
                opts.mode = .dry
            case "run", "--run":
                opts.mode = .run
            case "-a", "--auto-switch":
                opts.autoSwitch = true
            case "--switch-interval":
                if idx + 1 < args.count {
                    idx += 1
                    if let interval = Double(args[idx]), interval >= 0.5 {
                        opts.switchInterval = interval
                    }
                }
            case "--switch-limit":
                if idx + 1 < args.count {
                    idx += 1
                    if let limit = Int(args[idx]), limit > 0 {
                        opts.switchScopeLimit = limit
                    }
                }
            case "-s", "--strategy":
                if idx + 1 < args.count {
                    idx += 1
                    if let s = ClickStrategy(rawValue: args[idx].lowercased()) {
                        opts.strategy = s
                    } else {
                        print("未知策略: \(args[idx])，可用值: once, conversation, always")
                        exit(1)
                    }
                }
            case "-i", "--interval":
                if idx + 1 < args.count {
                    idx += 1
                    if let interval = Double(args[idx]), interval > 0.1 {
                        opts.pollInterval = interval
                    }
                }
            case "--bundle-id":
                if idx + 1 < args.count {
                    idx += 1
                    opts.bundleId = args[idx]
                }
            case "--no-guardrail":
                opts.enableGuardrail = false
            case "--log-file":
                if idx + 1 < args.count {
                    idx += 1
                    opts.logFilePath = (NSString(string: args[idx]).expandingTildeInPath)
                }
            case "--stop-file":
                if idx + 1 < args.count {
                    idx += 1
                    opts.stopFilePath = args[idx]
                }
            case "-h", "--help":
                printUsage()
                exit(0)
            default:
                if !arg.hasPrefix("-") && opts.mode == .run {
                    if let mode = AppMode(rawValue: arg.lowercased()) {
                        opts.mode = mode
                    }
                }
            }
            idx += 1
        }
        return opts
    }

    static func printUsage() {
        print("""
        ChatGPT 桌面版审批自动点击工具 (Approver)
        
        用法:
          Approver [模式] [选项]
        
        模式:
          run                   正常自动审批模式 (默认)
          dry                   干跑模式 (只识别并记录日志，不实际点击)
          check                 自检模式 (扫描并打印当前无障碍树、窗口、卡片及按钮清单)
        
        选项:
          -a, --auto-switch     开启侧边栏多会话自动巡检轮转 (当前对话处理完毕后切下一个)
          --switch-interval <s  会话轮转停留间隔 (秒，默认: 1.8)
          --switch-limit <num>  轮转会话数量上限 (默认: 10)
          -s, --strategy <val>  点击策略: once (默认: 允许一次), conversation (允许此对话), always (始终允许)
          -i, --interval <sec>  轮询扫描间隔 (秒，默认: 0.8)
          --bundle-id <id>      目标应用 Bundle Identifier (默认: com.openai.codex)
          --no-guardrail        关闭危险命令拦截护栏 (默认开启: 跳过包含 rm -rf, sudo 等命令)
          --log-file <path>     日志输出文件路径 (默认: ~/Library/Logs/Approver.log)
          --stop-file <path>    急停信号文件路径 (默认: /tmp/approver.stop)
          -h, --help            查看帮助信息
        """)
    }
}

// MARK: - Logger

class Logger {
    static let shared = Logger()
    private let dateFormatter: DateFormatter
    private let queue = DispatchQueue(label: "com.approver.logger", qos: .utility)
    private var fileHandle: FileHandle? = nil

    private init() {
        dateFormatter = DateFormatter()
        dateFormatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
    }

    func setup(logPath: String) {
        queue.sync {
            var expandedPath = (NSString(string: logPath).expandingTildeInPath)
            let folder = (expandedPath as NSString).deletingLastPathComponent
            var success = false
            do {
                try FileManager.default.createDirectory(atPath: folder, withIntermediateDirectories: true, attributes: nil)
                if !FileManager.default.fileExists(atPath: expandedPath) {
                    FileManager.default.createFile(atPath: expandedPath, contents: nil, attributes: nil)
                }
                fileHandle = FileHandle(forWritingAtPath: expandedPath)
                if fileHandle != nil {
                    success = true
                }
            } catch {
                success = false
            }

            if !success {
                // Fallback to local directory
                expandedPath = "Approver.log"
                if !FileManager.default.fileExists(atPath: expandedPath) {
                    FileManager.default.createFile(atPath: expandedPath, contents: nil, attributes: nil)
                }
                fileHandle = FileHandle(forWritingAtPath: expandedPath)
            }
            fileHandle?.seekToEndOfFile()
        }
    }

    func info(_ msg: String) { log(level: "INFO", msg: msg) }
    func warn(_ msg: String) { log(level: "WARN", msg: msg) }
    func error(_ msg: String) { log(level: "ERROR", msg: msg) }

    private func log(level: String, msg: String) {
        let timestamp = dateFormatter.string(from: Date())
        let line = "[\(timestamp)] [\(level)] \(msg)\n"
        queue.async {
            if level == "ERROR" {
                fputs(line, stderr)
            } else {
                fputs(line, stdout)
            }
            if let handle = self.fileHandle, let data = line.data(using: .utf8) {
                handle.write(data)
            }
        }
    }
}

// MARK: - AX Data Models

struct DiscoveredButton {
    let element: AXUIElement
    let title: String
    let position: CGPoint
    let role: String
}

struct ApprovalCardMatch {
    let container: AXUIElement
    let windowIndex: Int
    let allowButtons: [ClickStrategy: DiscoveredButton]
    let denyButton: DiscoveredButton
    let allButtons: [DiscoveredButton]
    let cardText: String

    var signature: String {
        let x = Int(round(denyButton.position.x / 5.0) * 5)
        let y = Int(round(denyButton.position.y / 5.0) * 5)
        return "win\(windowIndex)_\(x)_\(y)"
    }
}

struct WindowScanResult {
    let windowIndex: Int
    let windowElement: AXUIElement
    let cards: [ApprovalCardMatch]
    let allButtons: [DiscoveredButton]
    let unknownApprovalButtons: [DiscoveredButton]
}

// MARK: - AX Helper

class AXHelper {
    static var warmedPIDs = Set<pid_t>()

    static func isAccessibilityTrusted(promptIfNeeded: Bool = false) -> Bool {
        if promptIfNeeded {
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
            return AXIsProcessTrustedWithOptions(options)
        } else {
            return AXIsProcessTrusted()
        }
    }

    static func ensureAccessibilityPermission(promptIfNeeded: Bool = true) -> Bool {
        let isTrusted = isAccessibilityTrusted(promptIfNeeded: promptIfNeeded)
        if !isTrusted {
            Logger.shared.error("""
            ❌ [权限错误] 未获得 macOS「辅助功能」权限！
            解决步骤：
            1. 打开「系统设置」->「隐私与安全性」->「辅助功能」
            2. 找到并勾选您当前使用的终端 (Terminal / iTerm2) 或 Approver 程序
            3. 授权后重新运行本程序
            """)
            return false
        }
        return true
    }

    static func getAttribute<T>(_ element: AXUIElement, _ attribute: String) -> T? {
        var value: CFTypeRef?
        let err = AXUIElementCopyAttributeValue(element, attribute as CFString, &value)
        guard err == .success, let val = value else { return nil }
        return val as? T
    }

    static func getChildren(_ element: AXUIElement) -> [AXUIElement] {
        var value: CFTypeRef?
        let err = AXUIElementCopyAttributeValue(element, kAXChildrenAttribute as CFString, &value)
        guard err == .success, let val = value else { return [] }
        return val as? [AXUIElement] ?? []
    }

    static func getRole(_ element: AXUIElement) -> String? {
        return getAttribute(element, kAXRoleAttribute as String)
    }

    static func getTitle(_ element: AXUIElement) -> String? {
        if let title: String = getAttribute(element, kAXTitleAttribute as String), !title.isEmpty {
            return title
        }
        if let desc: String = getAttribute(element, kAXDescriptionAttribute as String), !desc.isEmpty {
            return desc
        }
        if let val: String = getAttribute(element, kAXValueAttribute as String), !val.isEmpty {
            return val
        }
        return nil
    }

    static func getPosition(_ element: AXUIElement) -> CGPoint? {
        var value: CFTypeRef?
        let err = AXUIElementCopyAttributeValue(element, kAXPositionAttribute as CFString, &value)
        guard err == .success, let val = value, CFGetTypeID(val) == AXValueGetTypeID() else { return nil }
        var pt = CGPoint.zero
        if AXValueGetValue(val as! AXValue, .cgPoint, &pt) {
            return pt
        }
        return nil
    }

    static func hasPressAction(_ element: AXUIElement) -> Bool {
        var actions: CFArray?
        let err = AXUIElementCopyActionNames(element, &actions)
        guard err == .success, let actList = actions as? [String] else { return false }
        return actList.contains(kAXPressAction as String)
    }

    static func pressButton(_ button: AXUIElement) -> AXError {
        return AXUIElementPerformAction(button, kAXPressAction as CFString)
    }

    static func warmUpTree(appElement: AXUIElement, pid: pid_t) {
        if warmedPIDs.contains(pid) {
            return
        }

        // Chromium lazy AX tree enablement
        AXUIElementSetAttributeValue(appElement, "AXManualAccessibility" as CFString, kCFBooleanTrue)
        AXUIElementSetAttributeValue(appElement, "AXEnhancedUserInterface" as CFString, kCFBooleanTrue)

        // If app has no windows, no need to wait
        guard let windows: [AXUIElement] = getAttribute(appElement, kAXWindowsAttribute as String), !windows.isEmpty else {
            warmedPIDs.insert(pid)
            return
        }

        Logger.shared.info("🌱 [无障碍树预热] 正在预热 PID: \(pid) 的 Chromium 无障碍树...")
        let startTime = Date()
        var lastCount = 0
        var stableRounds = 0

        while Date().timeIntervalSince(startTime) < 3.0 {
            let count = quickCountButtons(appElement)
            if count > 0 {
                if count == lastCount {
                    stableRounds += 1
                    if stableRounds >= 2 {
                        break
                    }
                } else {
                    stableRounds = 0
                }
            }
            lastCount = count
            usleep(150_000) // 150ms
        }

        warmedPIDs.insert(pid)
        Logger.shared.info("🌱 [预热完成] PID: \(pid)，共检测到 \(lastCount) 个按钮节点")
    }

    private static func quickCountButtons(_ appElement: AXUIElement) -> Int {
        guard let windows: [AXUIElement] = getAttribute(appElement, kAXWindowsAttribute as String) else {
            return 0
        }
        var total = 0
        var budget = 5000
        for win in windows {
            total += countSubtreeButtons(win, depth: 0, budget: &budget)
            if budget <= 0 { break }
        }
        return total
    }

    private static func countSubtreeButtons(_ element: AXUIElement, depth: Int, budget: inout Int) -> Int {
        if depth > 40 || budget <= 0 { return 0 }
        budget -= 1
        var count = 0
        if let role: String = getAttribute(element, kAXRoleAttribute as String), role == (kAXButtonRole as String) {
            count += 1
        }
        let children = getChildren(element)
        for child in children {
            count += countSubtreeButtons(child, depth: depth + 1, budget: &budget)
            if budget <= 0 { break }
        }
        return count
    }
}

// MARK: - Approval Scanner

class ApprovalScanner {
    static func scanWindow(window: AXUIElement, windowIndex: Int, maxDepth: Int = 60, maxBudget: Int = 40000) -> WindowScanResult {
        var budget = maxBudget
        let (cards, allButtons, _) = scanSubtree(
            window,
            depth: 0,
            budget: &budget,
            windowIndex: windowIndex,
            maxDepth: maxDepth
        )

        // Find unknown approval buttons for warning (FR-13)
        // Buttons that contain approval keywords but are NOT matched in any recognized card
        var unknownButtons: [DiscoveredButton] = []
        for btn in allButtons {
            let isCardButton = cards.contains { card in
                card.allButtons.contains { cb in
                    abs(cb.position.x - btn.position.x) < 0.1 &&
                    abs(cb.position.y - btn.position.y) < 0.1 &&
                    cb.title == btn.title
                }
            }
            if !isCardButton && SelectorConfig.isSuspiciousApprovalButton(btn.title) {
                unknownButtons.append(btn)
            }
        }

        return WindowScanResult(
            windowIndex: windowIndex,
            windowElement: window,
            cards: cards,
            allButtons: allButtons,
            unknownApprovalButtons: unknownButtons
        )
    }

    private static func scanSubtree(
        _ element: AXUIElement,
        depth: Int,
        budget: inout Int,
        windowIndex: Int,
        maxDepth: Int
    ) -> (cards: [ApprovalCardMatch], buttons: [DiscoveredButton], texts: [String]) {
        if depth > maxDepth || budget <= 0 {
            return ([], [], [])
        }
        budget -= 1

        let role = AXHelper.getRole(element) ?? ""
        var currentButtons: [DiscoveredButton] = []
        var currentTexts: [String] = []

        if role == (kAXButtonRole as String) || AXHelper.hasPressAction(element) {
            if let title = AXHelper.getTitle(element), !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let pos = AXHelper.getPosition(element) ?? .zero
                currentButtons.append(DiscoveredButton(element: element, title: title, position: pos, role: role))
            }
        }

        if role == (kAXStaticTextRole as String) {
            if let text = AXHelper.getTitle(element), !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                currentTexts.append(text)
            }
        } else if let desc: String = AXHelper.getAttribute(element, kAXDescriptionAttribute as String), !desc.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            currentTexts.append(desc)
        }

        let children = AXHelper.getChildren(element)
        var childCards: [ApprovalCardMatch] = []
        var allSubtreeButtons = currentButtons
        var allSubtreeTexts = currentTexts

        for child in children {
            if budget <= 0 { break }
            let (cCards, cBtns, cTxts) = scanSubtree(child, depth: depth + 1, budget: &budget, windowIndex: windowIndex, maxDepth: maxDepth)
            childCards.append(contentsOf: cCards)
            allSubtreeButtons.append(contentsOf: cBtns)
            allSubtreeTexts.append(contentsOf: cTxts)
        }

        // FR-01 / A5: Bottom-up rule:
        // If any descendant already matched approval cards, propagate them upward directly.
        // Return without grouping again at this container level.
        if !childCards.isEmpty {
            return (childCards, allSubtreeButtons, allSubtreeTexts)
        }

        // Check if current container has BOTH allow-type and deny-type buttons
        var allowButtons: [ClickStrategy: DiscoveredButton] = [:]
        var denyButton: DiscoveredButton? = nil

        for btn in allSubtreeButtons {
            let t = btn.title
            if SelectorConfig.matchesAllowOnce(t) {
                if allowButtons[.once] == nil { allowButtons[.once] = btn }
            } else if SelectorConfig.matchesAllowConversation(t) {
                if allowButtons[.conversation] == nil { allowButtons[.conversation] = btn }
            } else if SelectorConfig.matchesAlwaysAllow(t) {
                if allowButtons[.always] == nil { allowButtons[.always] = btn }
            } else if SelectorConfig.matchesDeny(t) {
                if denyButton == nil { denyButton = btn }
            }
        }

        if !allowButtons.isEmpty, let deny = denyButton {
            let combinedText = allSubtreeTexts.joined(separator: "\n")
            let card = ApprovalCardMatch(
                container: element,
                windowIndex: windowIndex,
                allowButtons: allowButtons,
                denyButton: deny,
                allButtons: allSubtreeButtons,
                cardText: combinedText
            )
            return ([card], allSubtreeButtons, allSubtreeTexts)
        }

        return ([], allSubtreeButtons, allSubtreeTexts)
    }
}

// MARK: - Sidebar Navigator

struct SidebarConversation {
    let title: String
    let element: AXUIElement
    let y: CGFloat
}

class SidebarNavigator {
    static func findConversations(in window: AXUIElement, limit: Int = 10) -> [SidebarConversation] {
        var results: [SidebarConversation] = []
        var seenTitles = Set<String>()

        func scanNode(_ elem: AXUIElement, depth: Int) {
            if depth > 30 || results.count >= limit * 2 { return }

            let role: String = AXHelper.getRole(elem) ?? ""
            if role == (kAXButtonRole as String) {
                let pos = AXHelper.getPosition(elem) ?? .zero
                // Conversations are situated in left sidebar area
                if pos.x > 80 && pos.x < 320 {
                    if hasChatActionsChild(elem) {
                        let title = (AXHelper.getTitle(elem) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                        if !title.isEmpty && !seenTitles.contains(title) {
                            seenTitles.insert(title)
                            results.append(SidebarConversation(title: title, element: elem, y: pos.y))
                        }
                    }
                }
            }

            let children = AXHelper.getChildren(elem)
            for c in children {
                scanNode(c, depth: depth + 1)
            }
        }

        scanNode(window, depth: 0)
        results.sort { $0.y < $1.y }
        if results.count > limit {
            return Array(results.prefix(limit))
        }
        return results
    }

    private static func hasChatActionsChild(_ elem: AXUIElement) -> Bool {
        let children = AXHelper.getChildren(elem)
        for c in children {
            let grandChildren = AXHelper.getChildren(c)
            for gc in grandChildren {
                let gcTitle = AXHelper.getTitle(gc) ?? ""
                let gcRole = AXHelper.getRole(gc) ?? ""
                if gcTitle == "聊天操作" || gcTitle == "Chat actions" || gcRole == "AXPopUpButton" {
                    return true
                }
            }
        }
        return false
    }
}

// MARK: - Engine

class ApproverEngine {
    let options: AppOptions
    private var recentClicks: [String: Date] = [:]
    private var clickTimestamps: [Date] = []
    private var warnedButtons: Set<String> = []
    private var lastApprovalTime: Date = .distantPast
    private var lastSwitchTime: Date = Date()
    private var currentSidebarIndex: Int = 0

    init(options: AppOptions) {
        self.options = options
        Logger.shared.setup(logPath: options.logFilePath)
    }

    func checkEmergencyStop() -> Bool {
        if FileManager.default.fileExists(atPath: options.stopFilePath) {
            Logger.shared.info("🛑 [急停生效] 检测到急停文件 \(options.stopFilePath)，正在安全退出...")
            return true
        }
        return false
    }

    @discardableResult
    func checkCircuitBreaker() -> Bool {
        let now = Date()
        clickTimestamps = clickTimestamps.filter { now.timeIntervalSince($0) <= options.circuitBreakerWindow }
        if clickTimestamps.count >= options.circuitBreakerThreshold {
            Logger.shared.error("💥 [频率熔断] \(Int(options.circuitBreakerWindow))秒内累计点击达到 \(clickTimestamps.count) 次，触发安全熔断！立即终止程序。")
            exit(2)
        }
        return true
    }

    func findTargetApplications() -> [NSRunningApplication] {
        let apps = NSWorkspace.shared.runningApplications
        var matched: [NSRunningApplication] = []
        for app in apps {
            if let bid = app.bundleIdentifier {
                if bid == options.bundleId || bid == "com.openai.codex" || bid == "com.openai.chat" {
                    matched.append(app)
                }
            } else if let name = app.localizedName, name == "ChatGPT" {
                matched.append(app)
            }
        }
        return matched
    }

    func runCheck() {
        print("========================================")
        print("🔎 Approver 自检模式 (Check Mode)")
        print("========================================")
        let isTrusted = AXHelper.isAccessibilityTrusted(promptIfNeeded: false)
        print("辅助功能权限状态: \(isTrusted ? "✅ 已授权" : "❌ 未授权 (需在系统设置中允许)")")
        if !isTrusted {
            print("⚠️ 提示: 当前进程未获得辅助功能权限。未授权时 macOS 可能会限制遍历其他应用的窗口及子控件。")
            print("如需授权，请前往: 「系统设置」->「隐私与安全性」->「辅助功能」，开启当前终端或应用。")
        }

        let apps = findTargetApplications()
        print("找到目标应用数量: \(apps.count)")
        if apps.isEmpty {
            print("⚠️ 未发现正在运行的目标应用 (目标 Bundle ID: \(options.bundleId))")
            print("========================================")
            return
        }

        for app in apps {
            let pid = app.processIdentifier
            let name = app.localizedName ?? "未知"
            let bid = app.bundleIdentifier ?? "无"
            print("\n----------------------------------------")
            print("📱 目标应用: \(name) (PID: \(pid), Bundle ID: \(bid))")

            let appElement = AXUIElementCreateApplication(pid)
            AXHelper.warmUpTree(appElement: appElement, pid: pid)

            guard let windows: [AXUIElement] = AXHelper.getAttribute(appElement, kAXWindowsAttribute as String) else {
                print("❌ 无法获取窗口列表 (可能受权限限制)")
                continue
            }

            print("窗口数量: \(windows.count)")
            for (winIdx, win) in windows.enumerated() {
                let winTitle: String = AXHelper.getAttribute(win, kAXTitleAttribute as String) ?? "无标题"
                print("\n  🪟 窗口 #\(winIdx): '\(winTitle)'")

                let result = ApprovalScanner.scanWindow(
                    window: win,
                    windowIndex: winIdx,
                    maxDepth: options.maxDepth,
                    maxBudget: options.maxBudget
                )

                print("     识别到审批卡片数量: \(result.cards.count) 张")
                print("     窗口控件按钮总数: \(result.allButtons.count) 个")

                for (cIdx, card) in result.cards.enumerated() {
                    print("     Card #\(cIdx + 1) [签名: \(card.signature)]:")
                    print("       拒绝按钮: '\(card.denyButton.title)' (坐标: \(card.denyButton.position))")
                    print("       允许按钮清单:")
                    for (strategy, btn) in card.allowButtons {
                        print("         - [\(strategy.rawValue)]: '\(btn.title)' (坐标: \(btn.position))")
                    }
                    if !card.cardText.isEmpty {
                        let preview = card.cardText.replacingOccurrences(of: "\n", with: " ")
                        let truncated = preview.count > 100 ? String(preview.prefix(100)) + "..." : preview
                        print("       卡片文本摘要: \"\(truncated)\"")
                    }
                }

                if !result.unknownApprovalButtons.isEmpty {
                    print("     ⚠️ 未成对/可疑审批按钮: \(result.unknownApprovalButtons.count) 个")
                    for ub in result.unknownApprovalButtons {
                        print("       - '\(ub.title)' (坐标: \(ub.position))")
                    }
                }

                print("     按钮列表 (前 15 个):")
                let displayButtons = result.allButtons.prefix(15)
                for b in displayButtons {
                    print("       • '\(b.title)' at \(b.position)")
                }
                if result.allButtons.count > 15 {
                    print("       ... 剩余 \(result.allButtons.count - 15) 个按钮省略")
                }

                let convs = SidebarNavigator.findConversations(in: win, limit: options.switchScopeLimit)
                print("     侧边栏可巡检对话: \(convs.count) 个 (轮转上限: \(options.switchScopeLimit))")
                for (cIdx, conv) in convs.prefix(6).enumerated() {
                    print("       [\(cIdx + 1)] '\(conv.title.prefix(35))'")
                }
                if convs.count > 6 {
                    print("       ... 其余 \(convs.count - 6) 个对话省略")
                }
            }
        }
        print("\n========================================")
        print("自检完成。")
    }

    func performSingleCycle() -> (cardsFound: Int, clicksPerformed: Int) {
        if checkEmergencyStop() {
            exit(0)
        }

        let apps = findTargetApplications()
        guard !apps.isEmpty else {
            return (0, 0)
        }

        var totalCards = 0
        var totalClicks = 0

        for app in apps {
            let pid = app.processIdentifier
            let appElement = AXUIElementCreateApplication(pid)
            AXHelper.warmUpTree(appElement: appElement, pid: pid)

            guard let windows: [AXUIElement] = AXHelper.getAttribute(appElement, kAXWindowsAttribute as String) else {
                continue
            }

            for (winIdx, win) in windows.enumerated() {
                let result = ApprovalScanner.scanWindow(
                    window: win,
                    windowIndex: winIdx,
                    maxDepth: options.maxDepth,
                    maxBudget: options.maxBudget
                )

                // Warning on unknown approval buttons (FR-13)
                for ub in result.unknownApprovalButtons {
                    let warnKey = "win\(winIdx)_\(ub.title)"
                    if !self.warnedButtons.contains(warnKey) {
                        Logger.shared.warn("⚠️ [文案变更告警] 窗口 #\(winIdx) 发现含审批关键字但未成对卡片的按钮: '\(ub.title)'，跳过点击并告警")
                        self.warnedButtons.insert(warnKey)
                    }
                }

                for card in result.cards {
                    totalCards += 1
                    handleCard(card: card, clicksPerformed: &totalClicks)
                }

                if !result.cards.isEmpty {
                    self.lastApprovalTime = Date()
                } else if options.autoSwitch {
                    maybeSwitchSidebar(window: win, windowIndex: winIdx)
                }
            }
        }

        return (totalCards, totalClicks)
    }

    private func maybeSwitchSidebar(window: AXUIElement, windowIndex: Int) {
        let now = Date()

        // 1. If an approval recently occurred, stay on this conversation to let subsequent actions complete
        if now.timeIntervalSince(lastApprovalTime) < options.afterApprovalStay {
            return
        }

        // 2. Cooldown between switches to let DOM mount and avoid hyperactive switching
        if now.timeIntervalSince(lastSwitchTime) < options.switchInterval {
            return
        }

        // 3. Find sidebar conversations
        let convs = SidebarNavigator.findConversations(in: window, limit: options.switchScopeLimit)
        guard convs.count > 1 else {
            return
        }

        // 4. Determine current conversation from window title
        let winTitle = (AXHelper.getAttribute(window, kAXTitleAttribute as String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        var currentIdx = currentSidebarIndex
        var foundCurrent = false
        for (i, c) in convs.enumerated() {
            if !winTitle.isEmpty && (winTitle.contains(c.title) || c.title.contains(winTitle.prefix(20))) {
                currentIdx = i
                foundCurrent = true
                break
            }
        }

        // 5. Select next conversation
        let nextIdx = (currentIdx + 1) % convs.count
        if foundCurrent && nextIdx == currentIdx {
            return
        }
        let targetConv = convs[nextIdx]

        Logger.shared.info("🔄 [自动巡检切会话] 当前对话无待审批，自动切换至侧边栏对话 [\(nextIdx + 1)/\(convs.count)]: '\(targetConv.title.prefix(30))'")
        let err = AXHelper.pressButton(targetConv.element)
        if err == .success {
            lastSwitchTime = now
            currentSidebarIndex = nextIdx
            usleep(350_000) // 350ms
        } else {
            Logger.shared.warn("⚠️ 切换对话 '\(targetConv.title.prefix(25))' 失败，AXError: \(err.rawValue)")
        }
    }

    private func handleCard(card: ApprovalCardMatch, clicksPerformed: inout Int) {
        // FR-17: Check dangerous commands
        if options.enableGuardrail {
            let check = SelectorConfig.checkDangerousCommand(card.cardText)
            if check.hit, let kw = check.keyword {
                Logger.shared.warn("🛡️ [安全护栏拦截] 窗口 #\(card.windowIndex) 审批卡片包含危险命令关键字 '\(kw)'，已跳过自动批准！正文: \(card.cardText)")
                return
            }
        }

        // FR-09: Deduplication cooldown
        let now = Date()
        if let lastClick = recentClicks[card.signature], now.timeIntervalSince(lastClick) < options.clickCooldown {
            return
        }

        // Select target allow button according to strategy (FR-05)
        let targetButton: DiscoveredButton?
        if let preferred = card.allowButtons[options.strategy] {
            targetButton = preferred
        } else if let once = card.allowButtons[.once] {
            targetButton = once
        } else {
            targetButton = card.allowButtons.values.first
        }

        guard let buttonToClick = targetButton else {
            Logger.shared.warn("⚠️ 窗口 #\(card.windowIndex) 卡片中未找到可用的允许按钮")
            return
        }

        // FR-07: Dry run mode
        if options.mode == .dry {
            Logger.shared.info("🧪 [干跑命中] 窗口 #\(card.windowIndex) 识别到审批卡片 [签名: \(card.signature)]，策略: \(options.strategy.rawValue)，准备点击按钮: '\(buttonToClick.title)'")
            recentClicks[card.signature] = now
            return
        }

        // Check circuit breaker before real clicking
        checkCircuitBreaker()

        // FR-02 & FR-04: Non-stealing focus AXPress click
        Logger.shared.info("⚡ [自动批准] 窗口 #\(card.windowIndex) 执行点击 [签名: \(card.signature)]，策略: \(options.strategy.rawValue)，按钮: '\(buttonToClick.title)'")
        let err = AXHelper.pressButton(buttonToClick.element)
        if err == .success {
            recentClicks[card.signature] = now
            clickTimestamps.append(now)
            clicksPerformed += 1
        } else {
            Logger.shared.error("❌ 点击按钮 '\(buttonToClick.title)' 失败，AXError 代码: \(err.rawValue)")
        }
    }

    func startLoop() {
        Logger.shared.info("🚀 Approver 启动成功！模式: \(options.mode.rawValue)，策略: \(options.strategy.rawValue)，轮询间隔: \(options.pollInterval)s")
        if options.autoSwitch {
            Logger.shared.info("🔄 侧边栏自动巡检: 已开启 (单会话无审批停留: \(options.switchInterval)s, 审批后驻留: \(options.afterApprovalStay)s, 巡检上限: \(options.switchScopeLimit)个)")
        } else {
            Logger.shared.info("🔄 侧边栏自动巡检: 关闭 (纯被动监听，可使用 -a 或 --auto-switch 开启)")
        }
        Logger.shared.info("日志写入: \(options.logFilePath)")
        Logger.shared.info("按 Ctrl+C 或在 \(options.stopFilePath) 创建文件即可随时急停。")

        while true {
            if checkEmergencyStop() { exit(0) }
            _ = performSingleCycle()
            let slices = max(1, Int(round(options.pollInterval / 0.1)))
            for _ in 0..<slices {
                if checkEmergencyStop() { exit(0) }
                usleep(100_000) // 100ms
            }
        }
    }
}

// MARK: - Signal Setup & Main Entry

var sigintSource: DispatchSourceSignal?
var sigtermSource: DispatchSourceSignal?

func setupSignalHandlers() {
    signal(SIGINT, SIG_IGN)
    signal(SIGTERM, SIG_IGN)

    let sigint = DispatchSource.makeSignalSource(signal: SIGINT, queue: .main)
    sigint.setEventHandler {
        Logger.shared.info("收到退出信号 (SIGINT)，安全退出...")
        exit(0)
    }
    sigint.resume()
    sigintSource = sigint

    let sigterm = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
    sigterm.setEventHandler {
        Logger.shared.info("收到终止信号 (SIGTERM)，安全退出...")
        exit(0)
    }
    sigterm.resume()
    sigtermSource = sigterm
}

let options = AppOptions.parse(args: CommandLine.arguments)

if options.mode == .check {
    let engine = ApproverEngine(options: options)
    engine.runCheck()
    exit(0)
}

if !AXHelper.ensureAccessibilityPermission(promptIfNeeded: true) {
    exit(1)
}

setupSignalHandlers()
let engine = ApproverEngine(options: options)
engine.startLoop()
