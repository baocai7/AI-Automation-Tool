package com.getjobs.application.service;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 启动时唤起已安装的积分客户端，并触发它们自己的领取流程。
 *
 * WorkBuddy 通过 Electron CDP 连接到客户端自己的渲染页，复用客户端登录上下文。
 * 不读取或复制 Cookie/token，也不依赖 macOS 辅助功能权限。
 * Qoder 同样会在客户端启动后检查并打开可领取活动，因此这里复用客户端自己的认证上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardClaimService {
    private final RewardClaimHistory history;
    private final AtomicBoolean running = new AtomicBoolean();

    private static final String WORKBUDDY_CLAIM_SCRIPT = """
            () => {
                const successMarkers = [
                    "已领取", "签到成功", "今日已领取", "领取成功",
                    "claimed", "checked in", "check-in complete", "already claimed"
                ];
                const claimMarkers = [
                    "签到", "领取每日", "check in", "check-in", "daily check-in", "claim daily"
                ];
                const normalize = (value) => String(value || "").trim().toLowerCase();
                const visible = (element) => {
                    const style = window.getComputedStyle(element);
                    return style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0";
                };
                const allElements = [...document.querySelectorAll("button, [role='button'], a")];
                const bodyText = normalize(document.body?.innerText);
                if (successMarkers.some((marker) => bodyText.includes(normalize(marker)))) {
                    return { state: "claimed" };
                }
                const candidate = allElements.find((element) => {
                    if (!visible(element)) return false;
                    const text = normalize(
                        `${element.innerText || ""} ${element.getAttribute("aria-label") || ""} ${element.getAttribute("title") || ""}`
                    );
                    return claimMarkers.some((marker) => text.includes(normalize(marker)));
                });
                if (candidate) {
                    candidate.click();
                    return { state: "clicked" };
                }
                return { state: "not-found" };
            }
            """;

    @Value("${reward-claim.enabled:true}")
    private boolean enabled;

    @Value("${reward-claim.workbuddy.enabled:true}")
    private boolean workbuddyEnabled;

    @Value("${reward-claim.workbuddy.cdp-automation:true}")
    private boolean workbuddyCdpAutomation;

    @Value("${reward-claim.workbuddy.cdp-port:9229}")
    private int workbuddyCdpPort;

    @Value("${reward-claim.qoder.enabled:true}")
    private boolean qoderEnabled;

    @Value("${reward-claim.close-after-confirmed:true}")
    private boolean closeAfterConfirmed;

    enum ClaimResult {
        CONFIRMED,
        ALREADY_CONFIRMED,
        UNCONFIRMED,
        FAILED
    }

    /**
     * 不阻塞 Spring Boot 主线程。客户端启动、网络请求和 UI 加载不应影响招聘服务启动。
     */
    public void claimOnStartup() {
        if (!enabled) {
            log.info("启动积分领取已关闭");
            return;
        }

        if (!running.compareAndSet(false, true)) return;
        CompletableFuture.runAsync(() -> {
                    try { claimAll(); }
                    finally { running.set(false); }
                })
                .exceptionally(error -> {
                    log.warn("启动积分领取任务异常: {}", rootMessage(error));
                    return null;
                });
    }

    synchronized void claimAll() {
        history.importExistingConfirmations();
        if (workbuddyEnabled) {
            claimForToday("workbuddy", "WorkBuddy", this::claimWorkbuddy);
        } else {
            log.debug("WorkBuddy 启动积分领取已关闭");
        }

        if (qoderEnabled) {
            claimForToday("qoder", "Qoder", this::claimQoder);
        } else {
            log.debug("Qoder 启动积分领取已关闭");
        }

    }

    private ClaimResult claimForToday(String platform, String app, Supplier<ClaimResult> claim) {
        if (history.isConfirmedToday(platform)) {
            log.info("{} 今日已确认领取，跳过启动客户端", app);
            return ClaimResult.ALREADY_CONFIRMED;
        }
        try {
            ClaimResult result = claim.get();
            if (result == ClaimResult.CONFIRMED) {
                history.recordConfirmed(platform);
                log.info("{} 今日领取成功已保存，当天重启不会再次打开", app);
                if (closeAfterConfirmed) closeApplication(app);
            } else {
                log.warn("{} 领取结果未确认，不记录成功，保留客户端窗口", app);
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("{} 启动领取失败：{}", app, rootMessage(e));
            return ClaimResult.FAILED;
        }
    }

    ClaimResult claimWorkbuddy() {
        if (!launchWorkbuddyForCdp()) {
            return ClaimResult.FAILED;
        }

        if (!isMac() || !workbuddyCdpAutomation) {
            log.info("WorkBuddy 已唤起；当前平台未启用 CDP 自动签到");
            return ClaimResult.UNCONFIRMED;
        }

        return claimWorkbuddyViaCdp();
    }

    private ClaimResult claimWorkbuddyViaCdp() {
        String endpoint = "http://127.0.0.1:" + workbuddyCdpPort;
        long deadline = System.currentTimeMillis() + 20_000;
        try (Playwright playwright = Playwright.create()) {
            Browser browser = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    browser = playwright.chromium().connectOverCDP(endpoint);
                    break;
                } catch (RuntimeException e) {
                    Thread.sleep(500);
                }
            }
            if (browser == null) {
                log.warn("WorkBuddy CDP 未在端口 {} 开放，无法执行无障碍签到", workbuddyCdpPort);
                return ClaimResult.UNCONFIRMED;
            }

            while (System.currentTimeMillis() < deadline) {
                for (Page page : allPages(browser)) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = (Map<String, Object>) page.evaluate(WORKBUDDY_CLAIM_SCRIPT);
                        String state = String.valueOf(result.get("state"));
                        if ("claimed".equals(state)) {
                            log.info("WorkBuddy 每日签到已确认（CDP 页面状态）");
                            return ClaimResult.CONFIRMED;
                        }
                        if ("clicked".equals(state)) {
                            log.info("WorkBuddy 每日签到按钮已通过 CDP 触发，等待结果确认");
                        }
                    } catch (RuntimeException e) {
                        log.debug("读取 WorkBuddy CDP 页面失败: {}", e.getMessage());
                    }
                }
                Thread.sleep(500);
            }
            log.warn("WorkBuddy CDP 已连接，但未确认领取结果");
            return ClaimResult.UNCONFIRMED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("WorkBuddy CDP 自动签到被中断");
            return ClaimResult.FAILED;
        }
    }

    private List<Page> allPages(Browser browser) {
        return browser.contexts().stream()
                .flatMap(context -> context.pages().stream())
                .toList();
    }

    ClaimResult claimQoder() {
        long startedAt = System.currentTimeMillis();
        if (!launchApplication("Qoder")) {
            return ClaimResult.FAILED;
        }

        if (waitForQoderClaimConfirmation(startedAt)) {
            log.info("Qoder 积分领取已确认（活动状态为 CLAIMED）");
            return ClaimResult.CONFIRMED;
        }
        log.warn("Qoder 已唤起，但未在客户端日志中确认活动状态为 CLAIMED");
        return ClaimResult.UNCONFIRMED;
    }

    private boolean waitForQoderClaimConfirmation(long startedAt) {
        if (!isMac()) {
            return false;
        }

        Path logDirectory = Paths.get(System.getProperty("user.home"), "Library", "Application Support",
                "com.qoder.app.stable", "logs");
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (hasRecentClaimedStatus(logDirectory, startedAt)) {
                    return true;
                }
                Thread.sleep(1_000);
            } catch (IOException e) {
                log.debug("读取 Qoder 活动日志失败: {}", e.getMessage());
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean hasRecentClaimedStatus(Path logDirectory, long startedAt) throws IOException {
        if (!Files.isDirectory(logDirectory)) {
            return false;
        }

        try (var paths = Files.walk(logDirectory, 3)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("main.log"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis() >= startedAt - 2_000;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .anyMatch(this::containsClaimedStatus);
        }
    }

    private boolean containsClaimedStatus(Path path) {
        try {
            return Files.readString(path).contains("\"claimStatus\":\"CLAIMED\"");
        } catch (IOException e) {
            return false;
        }
    }

    private boolean launchWorkbuddyForCdp() {
        if (!isMac() || !workbuddyCdpAutomation) {
            return launchApplication("WorkBuddy");
        }

        try {
            Process process = new ProcessBuilder(
                    "open", "-a", "WorkBuddy", "--args", "--remote-debugging-port=" + workbuddyCdpPort)
                    .start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                log.info("WorkBuddy 启动命令已发出（CDP 端口 {}）", workbuddyCdpPort);
                return true;
            }
            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readAllBytes()).trim();
                log.warn("无法唤起 WorkBuddy（CDP）{}", error.isBlank() ? "" : ": " + error);
                return false;
            }
            log.info("已唤起 WorkBuddy（CDP 端口 {}）", workbuddyCdpPort);
            return true;
        } catch (IOException e) {
            log.warn("无法唤起 WorkBuddy（CDP）: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("唤起 WorkBuddy（CDP）被中断");
            return false;
        }
    }

    private boolean launchApplication(String appName) {
        try {
            ProcessBuilder builder;
            if (isMac()) {
                builder = new ProcessBuilder("open", "-a", appName);
            } else if (isWindows()) {
                builder = new ProcessBuilder("cmd", "/c", "start", "", appName);
            } else {
                builder = new ProcessBuilder(appName);
            }

            Process process = builder.start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                log.info("{} 启动命令已发出", appName);
                return true;
            }
            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readAllBytes()).trim();
                log.warn("无法唤起 {}{}", appName, error.isBlank() ? "" : ": " + error);
                return false;
            }
            log.info("已唤起 {}", appName);
            return true;
        } catch (IOException e) {
            log.warn("无法唤起 {}: {}", appName, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("唤起 {} 被中断", appName);
            return false;
        }
    }

    boolean closeApplication(String appName) {
        try {
            ProcessBuilder builder;
            if (isMac()) {
                builder = new ProcessBuilder("osascript", "-e", "tell application \"" + appName + "\" to quit");
            } else if (isWindows()) {
                builder = new ProcessBuilder("taskkill", "/IM", appName + ".exe", "/T");
            } else {
                log.info("当前平台不执行 {} 的自动关闭", appName);
                return false;
            }

            Process process = builder.start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("关闭 {} 超时", appName);
                return false;
            }
            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readAllBytes()).trim();
                log.warn("关闭 {} 失败{}", appName, error.isBlank() ? "" : ": " + error);
                return false;
            }
            return true;
        } catch (IOException e) {
            log.warn("无法关闭 {}: {}", appName, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("关闭 {} 被中断", appName);
            return false;
        }
    }

    private boolean isMac() {
        return osName().contains("mac");
    }

    private boolean isWindows() {
        return osName().contains("win");
    }

    private String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
