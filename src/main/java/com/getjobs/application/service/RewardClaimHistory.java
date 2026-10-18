package com.getjobs.application.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 只保存已确认成功的领取日期，按北京时间跨进程保留。 */
@Slf4j
@Service
public class RewardClaimHistory {
    private static final Pattern LEGACY_CONFIRMATION = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})[ T].*\\bRewardClaimService\\s*[:-]\\s*"
                    + "(WorkBuddy 每日签到已确认|Qoder 积分领取已确认)[（(].*[）)]\\s*$");
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public RewardClaimHistory(JdbcTemplate jdbc) {
        this(jdbc, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    RewardClaimHistory(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @PostConstruct
    public void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261018_02__reward_claim_daily.sql")).execute(jdbc.getDataSource());
    }

    boolean isConfirmedToday(String platform) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reward_claim_daily WHERE platform=? AND claim_date=?",
                Integer.class, platform, LocalDate.now(clock).toString());
        return count != null && count > 0;
    }

    void recordConfirmed(String platform) {
        recordConfirmedForDate(platform, LocalDate.now(clock).toString());
    }

    private void recordConfirmedForDate(String platform, String day) {
        jdbc.update("INSERT OR IGNORE INTO reward_claim_daily(platform,claim_date,confirmed_at) VALUES (?,?,?)",
                platform, day, OffsetDateTime.now(clock).toString());
    }

    void importExistingConfirmations() {
        Path logPath = Path.of("target", "logs", "get-jobs.log");
        if (!Files.isRegularFile(logPath)) return;
        try (Stream<String> lines = Files.lines(logPath)) {
            importConfirmedLines(lines);
        } catch (IOException e) {
            log.debug("无法补入旧领取日志，仍按已保存记录检查：{}", e.getMessage());
        }
    }

    void importConfirmedLines(Stream<String> lines) {
        String today = LocalDate.now(clock).toString();
        lines.filter(line -> line.length() <= 4096 && line.startsWith(today))
                .map(LEGACY_CONFIRMATION::matcher).filter(java.util.regex.Matcher::matches)
                .map(match -> match.group(2).startsWith("WorkBuddy") ? "workbuddy" : "qoder")
                .distinct().forEach(platform -> recordConfirmedForDate(platform, today));
    }
}
