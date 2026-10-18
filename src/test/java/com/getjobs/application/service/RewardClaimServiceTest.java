package com.getjobs.application.service;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import static com.getjobs.application.service.RewardClaimService.ClaimResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RewardClaimServiceTest {
    SingleConnectionDataSource dataSource;
    JdbcTemplate jdbc;
    RewardClaimHistory history;
    RewardClaimService service;
    Clock clock = Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @BeforeEach void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        history = historyAt(clock);
        service = serviceUsing(history);
    }
    @AfterEach void close() { dataSource.destroy(); }

    RewardClaimHistory historyAt(Clock at) {
        var result = spy(new RewardClaimHistory(jdbc, at));
        result.initialize();
        doNothing().when(result).importExistingConfirmations();
        return result;
    }
    RewardClaimService serviceUsing(RewardClaimHistory record) {
        var result = spy(new RewardClaimService(record));
        for (String flag : new String[]{"enabled", "workbuddyEnabled", "qoderEnabled", "closeAfterConfirmed"})
            ReflectionTestUtils.setField(result, flag, true);
        doReturn(CONFIRMED).when(result).claimWorkbuddy();
        doReturn(CONFIRMED).when(result).claimQoder();
        doReturn(true).when(result).closeApplication(anyString());
        return result;
    }

    @Test void bothClaimedTodayDoesNotOpenOrCloseEitherClient() {
        history.recordConfirmed("workbuddy");
        history.recordConfirmed("qoder");
        service.claimAll();
        verify(service, never()).claimWorkbuddy();
        verify(service, never()).claimQoder();
        verify(service, never()).closeApplication(anyString());
    }

    @Test void confirmedPlatformIsIndependentOfTheOtherPlatformsFailure() {
        history.recordConfirmed("qoder");
        doReturn(UNCONFIRMED).when(service).claimWorkbuddy();
        service.claimAll();
        verify(service).claimWorkbuddy();
        verify(service, never()).claimQoder();
        verify(service, never()).closeApplication(anyString());
        assertFalse(history.isConfirmedToday("workbuddy"));
    }

    @Test void freshSuccessIsSavedAndANewServiceDoesNotRelaunchIt() {
        service.claimAll();
        var restarted = serviceUsing(historyAt(clock));
        restarted.claimAll();
        verify(restarted, never()).claimWorkbuddy();
        verify(restarted, never()).claimQoder();
        verify(service).closeApplication("WorkBuddy");
        verify(service).closeApplication("Qoder");
    }

    @Test void unconfirmedResultCanRetryWithoutRepeatingTheSuccessfulClient() {
        doReturn(UNCONFIRMED, CONFIRMED).when(service).claimWorkbuddy();
        service.claimAll();
        assertFalse(history.isConfirmedToday("workbuddy"));
        service.claimAll();
        verify(service, times(2)).claimWorkbuddy();
        verify(service, times(1)).claimQoder();
        assertTrue(history.isConfirmedToday("workbuddy"));
    }

    @Test void nextDayCanClaimBothAgain() {
        service.claimAll();
        var tomorrow = serviceUsing(historyAt(Clock.offset(clock, Duration.ofDays(1))));
        tomorrow.claimAll();
        verify(tomorrow).claimWorkbuddy();
        verify(tomorrow).claimQoder();
    }

    @Test void failureOfOneClientDoesNotPreventTheOtherFromClaiming() {
        doThrow(new IllegalStateException("launch failed")).when(service).claimWorkbuddy();
        service.claimAll();
        assertTrue(history.isConfirmedToday("qoder"));
        assertFalse(history.isConfirmedToday("workbuddy"));
    }

    @Test void disabledClientIsNotLaunched() {
        ReflectionTestUtils.setField(service, "workbuddyEnabled", false);
        service.claimAll();
        verify(service, never()).claimWorkbuddy();
        verify(service).claimQoder();
    }
}
