package com.getjobs.application.service;

import com.getjobs.worker.boss.Boss;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BossAutoReplyServiceTest {
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private AiService ai;
    private BossAutoReplyService service;
    private Map<String, Integer> skipped;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(dataSource);
        ai = mock(AiService.class);
        when(ai.sendRequest(anyString())).thenReturn("您好，方便进一步介绍一下岗位吗？");
        service = new BossAutoReplyService(null, null, null, ai, null, jdbc);
        service.initTables();
        skipped = new LinkedHashMap<>();
    }

    @AfterEach
    void close() { dataSource.destroy(); }

    private BossAutoReplyService.Settings settings(boolean dryRun) {
        return new BossAutoReplyService.Settings(true, dryRun, 1, 2, 3, List.of(), null);
    }

    private Boss.ChatSnapshot hr(String key, String id, String text) {
        return new Boss.ChatSnapshot("同一家公司", text, key, id, false, "我：您好\nHR：" + text);
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM boss_chat_reply_state", Integer.class);
    }

    @Test
    void shutdownDoesNotGenerateOrReturnNewReplies() {
        service.stopOnShutdown();
        assertNull(service.decide(hr("hr-1", "msg-1", "你好"), settings(false), skipped));
        verifyNoInteractions(ai);
    }

    @Test
    void shutdownDuringAiRequestDoesNotSendItsResult() {
        when(ai.sendRequest(anyString())).thenAnswer(invocation -> {
            service.stopOnShutdown();
            return "您好";
        });
        assertNull(service.decide(hr("hr-1", "msg-1", "你好"), settings(false), skipped));
        assertEquals(0, rowCount());
    }

    @Test
    void firstSeenAndPreviouslyReadMessagesAreAnsweredWithoutPrematureStateWrite() {
        var message = hr("hr-1", "msg-1", "你好，了解一下吗");
        assertNotNull(service.decide(message, settings(false), skipped));
        assertEquals(0, rowCount(), "Generating a reply must not acknowledge the message");
        jdbc.update("INSERT INTO boss_chat_reply_state(session_title,last_seen_msg) VALUES (?,?)",
                "hr-1", message.lastMessage());
        assertNotNull(service.decide(message, settings(false), skipped), "Old baseline text must not suppress a real HR message");
    }

    @Test
    void confirmsOnceButAnswersANewMessageWithIdenticalText() {
        var first = hr("hr-1", "msg-1", "你好");
        String reply = service.decide(first, settings(false), skipped);
        service.recordSent(first, reply);
        assertNull(service.decide(first, settings(false), skipped));
        assertNotNull(service.decide(hr("hr-1", "msg-2", "你好"), settings(false), skipped));
        assertEquals(1, jdbc.queryForObject("SELECT reply_count FROM boss_chat_reply_state WHERE session_title='hr-1'", Integer.class));
    }

    @Test
    void ownMessageDoesNotTriggerAiButHrCanEchoOurText() {
        var own = new Boss.ChatSnapshot("公司", "你好", "hr-1", "own-1", true, "");
        assertNull(service.decide(own, settings(false), skipped));
        verifyNoInteractions(ai);
        service.recordSent(hr("hr-1", "previous", "您好"), "你好");
        assertNotNull(service.decide(hr("hr-1", "new", "你好"), settings(false), skipped));
    }

    @Test
    void dryRunDoesNotConsumeMessageBeforeSwitchingToLive() {
        var message = hr("hr-1", "msg-1", "还在看机会吗");
        assertNull(service.decide(message, settings(true), skipped));
        assertEquals(0, rowCount());
        assertNull(service.decide(message, settings(true), skipped));
        verify(ai, times(1)).sendRequest(anyString());
        assertNotNull(service.decide(message, settings(false), skipped));
        verify(ai, times(2)).sendRequest(anyString());
    }

    @Test
    void aiFailureCanRetrySameMessage() {
        when(ai.sendRequest(anyString())).thenThrow(new RuntimeException("timeout")).thenReturn("您好");
        var message = hr("hr-1", "msg-1", "你好");
        assertNull(service.decide(message, settings(false), skipped));
        assertEquals(0, rowCount());
        assertEquals("您好", service.decide(message, settings(false), skipped));
    }

    @Test
    void quotaDoesNotConsumeMessageAndResetsNextDay() {
        var previous = hr("hr-1", "old", "你好");
        for (int i = 0; i < 3; i++) service.recordSent(previous, "您好");
        var pending = hr("hr-1", "new", "还在吗");
        assertNull(service.decide(pending, settings(false), skipped));
        assertEquals(previous.seenKey(), jdbc.queryForObject("SELECT last_seen_msg FROM boss_chat_reply_state WHERE session_title='hr-1'", String.class));
        jdbc.update("UPDATE boss_chat_reply_state SET last_reply_date=?", LocalDate.now().minusDays(1).toString());
        assertNotNull(service.decide(pending, settings(false), skipped));
    }

    @Test
    void separateRecruitersAtSameCompanyDoNotShareState() {
        var first = hr("hr-1", "msg-1", "你好");
        service.recordSent(first, "您好");
        assertNotNull(service.decide(hr("hr-2", "msg-1", "你好"), settings(false), skipped));
    }

    @Test
    void textMentioningReadStatusIsNotAPlaceholder() {
        assertNotNull(service.decide(hr("hr-1", "msg-1", "资料已经已读，可以介绍一下项目吗"), settings(false), skipped));
    }

    @Test
    void sensitiveExclusionCanBeChangedWithoutLosingPendingMessage() {
        var message = hr("hr-1", "msg-1", "方便介绍薪资吗");
        var filtered = new BossAutoReplyService.Settings(true, false, 1, 2, 3, List.of("薪资"), null);
        assertNull(service.decide(message, filtered, skipped));
        assertEquals(0, rowCount());
        assertNotNull(service.decide(message, settings(false), skipped));
    }

    @Test
    void refusesUnverifiedListPreview() {
        assertNull(service.decide(new Boss.ChatSnapshot("公司", "你好"), settings(false), skipped));
        verifyNoInteractions(ai);
    }

    @Test
    void sentReplyIsDurableWithOriginalTextAndFinishesPendingTarget() {
        var message = hr("hr-1", "msg-1", "你好\n方便了解吗？");
        service.rememberPending(message);
        assertEquals(1, service.loadPending().size());
        service.recordSent(message, "您好\n可以聊聊。");
        assertTrue(service.loadPending().isEmpty());
        var event = jdbc.queryForMap("SELECT * FROM boss_auto_reply_event WHERE status='SENT'");
        assertEquals(message.lastMessage(), event.get("hr_message"));
        assertEquals("您好\n可以聊聊。", event.get("reply"));
        assertEquals("hr-1", event.get("session_key"));
        assertNotNull(event.get("occurred_at"));
        service.initTables();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM boss_auto_reply_event", Integer.class));
    }

    @Test
    void pendingUnreadTargetSurvivesServiceRecreationAndFailureDoesNotCountAsSent() {
        var message = hr("hr-1", "msg-1", "你好");
        service.rememberPending(message);
        service.recordEvent(message, "您好", "SEND_UNCONFIRMED", "发送未确认");
        var restarted = new BossAutoReplyService(null, null, null, ai, null, jdbc);
        restarted.initTables();
        assertEquals(List.of(message), restarted.loadPending());
        assertEquals(0, rowCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM boss_auto_reply_event WHERE status='SENT'", Integer.class));
    }

    @Test
    void previewHasItsOwnAuditStatusAndDoesNotIncreaseSentStatistics() {
        service.decide(hr("hr-1", "msg-1", "你好"), settings(true), skipped);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM boss_auto_reply_event WHERE status='PREVIEW'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM boss_auto_reply_event WHERE status='SENT'", Integer.class));
    }
}
