package com.getjobs.worker.boss;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BossChatReaderTest {
    @Test
    void refusesMessagePaneFromAnotherSelectedRecruiter() {
        var session = new BossChatReader.Session("同公司", "hr-1", "你好");
        assertFalse(BossChatReader.matchesSession(session,
                new JSONObject().put("text", "你好").put("selectedKey", "hr-2")));
    }

    @Test
    void refusesOldPaneWhileNewSessionIsStillLoading() {
        var session = new BossChatReader.Session("同公司", "hr-1", "介绍一下项目吗");
        assertFalse(BossChatReader.matchesSession(session,
                new JSONObject().put("text", "上一位HR的消息").put("selectedKey", "hr-1")));
    }

    @Test
    void allowsTruncatedPreviewAndReadPrefix() {
        var session = new BossChatReader.Session("同公司", "hr-1", "[已读] 介绍一下…");
        assertTrue(BossChatReader.matchesSession(session,
                new JSONObject().put("text", "介绍一下项目吗").put("selectedKey", "hr-1")));
    }

    @Test
    void readWordInsideMessageIsPreserved() {
        var session = new BossChatReader.Session("同公司", "hr-1", "已读简历，可以聊聊吗");
        assertTrue(BossChatReader.matchesSession(session,
                new JSONObject().put("text", "已读简历，可以聊聊吗")));
    }
}
