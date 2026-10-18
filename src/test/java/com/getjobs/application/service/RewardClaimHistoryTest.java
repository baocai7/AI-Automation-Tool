package com.getjobs.application.service;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.time.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class RewardClaimHistoryTest {
    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;
    Clock today = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
    RewardClaimHistory history;

    @BeforeEach void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        history = new RewardClaimHistory(jdbc, today);
        history.initialize();
    }
    @AfterEach void close() { dataSource.destroy(); }

    @Test void successSurvivesServiceRestartAndMigrationDoesNotEraseIt() {
        history.recordConfirmed("qoder");
        var restarted = new RewardClaimHistory(jdbc, today);
        restarted.initialize();
        assertTrue(restarted.isConfirmedToday("qoder"));
        assertFalse(restarted.isConfirmedToday("workbuddy"));
    }

    @Test void midnightInShanghaiStartsANewClaimDay() {
        history.recordConfirmed("qoder");
        var nextDay = new RewardClaimHistory(jdbc,
                Clock.fixed(Instant.parse("2026-10-18T16:00:00Z"), ZoneId.of("Asia/Shanghai")));
        assertFalse(nextDay.isConfirmedToday("qoder"));
        nextDay.recordConfirmed("qoder");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM reward_claim_daily", Integer.class));
    }

    @Test void confirmingTwiceDoesNotDuplicateADay() {
        history.recordConfirmed("workbuddy");
        history.recordConfirmed("workbuddy");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM reward_claim_daily", Integer.class));
    }

    @Test void importsOnlyTodaysExactSuccessFromTheClaimLogger() {
        history.importConfirmedLines(Stream.of(
                "2026-10-17T20:00:00+08:00 INFO c.g.a.service.RewardClaimService : WorkBuddy 每日签到已确认（CDP 页面状态）",
                "2026-10-18 20:00:00.000 INFO c.g.a.service.RewardClaimService - WorkBuddy CDP 已连接，但未确认领取结果",
                "2026-10-18T20:00:00+08:00 INFO c.g.a.service.AiService : WorkBuddy 每日签到已确认（CDP 页面状态）",
                "2026-10-18T20:00:00+08:00 INFO c.g.a.service.RewardClaimService : Qoder 积分领取已确认（活动状态为 CLAIMED）"));
        assertTrue(history.isConfirmedToday("qoder"));
        assertFalse(history.isConfirmedToday("workbuddy"));
        history.importConfirmedLines(Stream.of(
                "2026-10-18 20:00:00.000 INFO c.g.a.service.RewardClaimService - WorkBuddy 每日签到已确认（CDP 页面状态）"));
        assertTrue(history.isConfirmedToday("workbuddy"));
    }
}
