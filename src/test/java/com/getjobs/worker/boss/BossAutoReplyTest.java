package com.getjobs.worker.boss;

import com.getjobs.worker.utils.PlaywrightUtil;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossAutoReplyTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"未读", "未读 2", "未读(12)", "未读（99+）", "未读99+"})
    void unreadTabAcceptsCounterBadges(String text) {
        assertTrue(Boss.UNREAD_TAB_TEXT.matcher(text).matches());
        assertFalse(Boss.UNREAD_TAB_TEXT.matcher("全部").matches());
        assertFalse(Boss.UNREAD_TAB_TEXT.matcher("该消息未读").matches());
    }
    private static JSONObject incoming(String id) {
        return new JSONObject().put("found", true).put("text", "你好").put("identity", id)
                .put("self", false).put("ownCount", 1).put("context", "HR：你好");
    }

    @Test
    void commitsOnlyAfterConfirmedSendAndUsesExactSessionIdentity() {
        runSendCase(true, false, false);
    }

    @Test
    void failedSendDoesNotCommitState() {
        runSendCase(false, false, false);
    }

    @Test
    void messageChangingDuringAiGenerationCancelsSend() {
        runSendCase(true, true, false);
    }

    @Test
    void existingDraftIsNotOverwritten() {
        runSendCase(true, false, true);
    }

    private void runSendCase(boolean confirmed, boolean changed, boolean hasDraft) {
        Boss boss = new Boss(null, null, null);
        Page page = mock(Page.class);
        Locator items = mock(Locator.class);
        Locator row = mock(Locator.class);
        ElementHandle node = mock(ElementHandle.class);
        Locator unread = mock(Locator.class);
        Locator input = mock(Locator.class);
        Locator send = mock(Locator.class);
        boss.setPage(page);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/chat");
        when(page.locator(Locators.CHAT_LIST_ITEM)).thenReturn(items);
        AtomicBoolean opened = new AtomicBoolean();
        when(items.count()).thenAnswer(invocation -> opened.get() ? 0 : 1);
        when(items.nth(0)).thenReturn(row);
        when(row.elementHandle()).thenReturn(node);
        doAnswer(invocation -> { opened.set(true); return null; })
                .when(node).click(any(ElementHandle.ClickOptions.class));
        when(page.getByText(any(java.util.regex.Pattern.class))).thenReturn(unread);
        when(unread.count()).thenReturn(1);
        when(unread.nth(0)).thenReturn(unread);
        when(unread.isVisible()).thenReturn(true);
        when(unread.evaluate(anyString())).thenReturn(true);
        when(page.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area")).thenReturn(input);
        when(input.first()).thenReturn(input);
        when(input.count()).thenReturn(1);
        when(input.isVisible()).thenReturn(true);
        AtomicReference<String> draft = new AtomicReference<>(hasDraft ? "我正在编辑" : "");
        when(input.evaluate(anyString())).thenAnswer(invocation -> draft.get());
        doAnswer(invocation -> { draft.set(invocation.getArgument(0)); return null; })
                .when(input).fill(anyString(), any(Locator.FillOptions.class));
        when(page.locator("div.send-message, button[type='send'].btn-send, button.btn-send")).thenReturn(send);
        when(send.first()).thenReturn(send);
        when(send.count()).thenReturn(1);
        when(send.isVisible()).thenReturn(true);
        when(send.isEnabled()).thenReturn(true);
        AtomicBoolean clicked = new AtomicBoolean();
        AtomicBoolean aiReturned = new AtomicBoolean();
        AtomicInteger commits = new AtomicInteger();
        doAnswer(invocation -> {
            clicked.set(true);
            if (confirmed) draft.set("");
            return null;
        }).when(send).click(any(Locator.ClickOptions.class));

        try (var sleep = mockStatic(PlaywrightUtil.class);
             var reader = mockStatic(BossChatReader.class)) {
            reader.when(() -> BossChatReader.snapshot(any(BossChatReader.Session.class), any(JSONObject.class)))
                    .thenCallRealMethod();
            reader.when(() -> BossChatReader.matchesSession(any(BossChatReader.Session.class), any(JSONObject.class)))
                    .thenCallRealMethod();
            reader.when(() -> BossChatReader.session(row)).thenReturn(new BossChatReader.Session("同公司", "hr-exact-id"));
            reader.when(() -> BossChatReader.session(node)).thenReturn(new BossChatReader.Session("同公司", "hr-exact-id"));
            reader.when(() -> BossChatReader.message(page)).thenAnswer(invocation -> {
                if (clicked.get() && confirmed) {
                    return incoming("own-2").put("self", true).put("text", "您好").put("ownCount", 2);
                }
                return incoming(changed && aiReturned.get() ? "new-message" : "hr-message");
            });
            Function<Boss.ChatSnapshot, String> decider = snapshot -> {
                assertEquals("hr-exact-id", snapshot.stateKey());
                aiReturned.set(true);
                return "您好";
            };
            BiConsumer<Boss.ChatSnapshot, String> commit = (snapshot, reply) -> {
                assertTrue(clicked.get(), "State cannot be committed before clicking send");
                assertEquals("hr-message", snapshot.messageId());
                assertEquals("您好", reply);
                commits.incrementAndGet();
            };
            boolean success = confirmed && !changed && !hasDraft;
            assertEquals(success ? 1 : 0, boss.autoReplyUnread(decider, 2, commit));
            assertEquals(success ? 1 : 0, commits.get());
            assertEquals(!changed && !hasDraft, clicked.get());
            assertEquals(hasDraft ? "我正在编辑" : "", draft.get());
            verify(unread).click(any(Locator.ClickOptions.class));
            assertTrue(opened.get(), "Only a captured unread target may be opened");
            if (hasDraft) verify(input, never()).fill(anyString(), any(Locator.FillOptions.class));
        }
    }

    @Test
    void neverFallsBackToAllConversationsWhenUnreadFilterIsMissing() {
        Boss boss = new Boss(null, null, null);
        Page page = mock(Page.class);
        Locator missing = mock(Locator.class);
        boss.setPage(page);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/chat");
        when(page.getByText(any(java.util.regex.Pattern.class))).thenReturn(missing);
        try (var ignored = mockStatic(PlaywrightUtil.class)) {
            assertEquals(0, boss.autoReplyUnread(snapshot -> fail("Must not inspect all conversations"), 10));
        }
        verify(page, never()).locator(Locators.CHAT_LIST_ITEM);
    }

    @Test
    void refusesToReadRowsUnlessUnreadSelectionIsConfirmed() {
        Boss boss = new Boss(null, null, null);
        Page page = mock(Page.class);
        Locator unread = mock(Locator.class);
        boss.setPage(page);
        when(page.getByText(any(java.util.regex.Pattern.class))).thenReturn(unread);
        when(unread.count()).thenReturn(1);
        when(unread.nth(0)).thenReturn(unread);
        when(unread.isVisible()).thenReturn(true);
        when(unread.evaluate(anyString())).thenReturn(true, false);
        try (var ignored = mockStatic(PlaywrightUtil.class)) {
            assertFalse(boss.selectUnreadConversations());
        }
        verify(page, never()).locator(Locators.CHAT_LIST_ITEM);
    }

    @Test
    void retriesExactCapturedUnreadMessageWithoutScanningAllOrReopeningRemovedRow() {
        Boss boss = new Boss(null, null, null);
        Page page = mock(Page.class);
        Locator input = mock(Locator.class);
        Locator send = mock(Locator.class);
        boss.setPage(page);
        when(page.url()).thenReturn("https://www.zhipin.com/web/geek/chat");
        when(page.locator("div#chat-input.chat-input[contenteditable='true'], textarea.input-area")).thenReturn(input);
        when(input.first()).thenReturn(input);
        when(input.count()).thenReturn(1);
        when(input.isVisible()).thenReturn(true);
        when(input.evaluate(anyString())).thenReturn("");
        when(page.locator("div.send-message, button[type='send'].btn-send, button.btn-send")).thenReturn(send);
        when(send.first()).thenReturn(send);
        when(send.count()).thenReturn(1);
        when(send.isVisible()).thenReturn(true);
        when(send.isEnabled()).thenReturn(true);
        AtomicBoolean sent = new AtomicBoolean();
        AtomicInteger committed = new AtomicInteger();
        doAnswer(invocation -> { sent.set(true); return null; }).when(send).click(any(Locator.ClickOptions.class));
        var saved = new Boss.ChatSnapshot("同公司", "你好", "hr-1", "incoming-1", false, "HR：你好");
        try (var sleep = mockStatic(PlaywrightUtil.class);
             var reader = mockStatic(BossChatReader.class)) {
            reader.when(() -> BossChatReader.message(page)).thenAnswer(invocation -> sent.get()
                    ? incoming("outgoing-1").put("self", true).put("text", "您好").put("ownCount", 2)
                    : incoming("incoming-1"));
            reader.when(() -> BossChatReader.snapshot(any(BossChatReader.Session.class), any(JSONObject.class)))
                    .thenCallRealMethod();
            reader.when(() -> BossChatReader.matchesSession(any(BossChatReader.Session.class), any(JSONObject.class)))
                    .thenCallRealMethod();
            assertEquals(1, boss.autoReplyUnread(snapshot -> "您好", 1,
                    (snapshot, reply) -> committed.incrementAndGet(), java.util.List.of(saved),
                    snapshot -> fail("Retry must use the persisted unread target"),
                    (snapshot, reply) -> fail("Send should succeed"), snapshot -> false));
        }
        assertEquals(1, committed.get());
        verify(page, never()).locator(Locators.CHAT_LIST_ITEM);
        verify(page, never()).getByText(eq("全部"), any(Page.GetByTextOptions.class));
    }
}
