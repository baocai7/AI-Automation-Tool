package com.getjobs.worker.boss;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import org.json.JSONObject;

/** Reads public chat DOM only; unread badges are deliberately irrelevant. */
final class BossChatReader {
    private BossChatReader() {}

    static final String SESSION_SCRIPT = """
            el => {
              const text = selector => (el.querySelector(selector)?.textContent || '').trim();
              const title = text('.title-box .name-box span:nth-child(2)')
                || text('.title-box .name-box') || text('.title-box');
              const attrs = ['data-uid', 'data-user-id', 'data-encrypt-uid', 'data-encrypt-id', 'data-id'];
              let id = '';
              for (const attr of attrs) {
                const value = el.getAttribute(attr);
                if (value) { id = attr + ':' + value; break; }
              }
              // Fallback contains recruiter + company + role, never the row index or preview text.
              const name = text('.title-box .name-box') || title;
              const job = text('.job-name') || text('.job-title');
              const key = id ? 'boss:id:' + id : 'boss:contact:' + JSON.stringify([name, job]);
              const preview = text('.last-msg-text');
              return JSON.stringify({ title, key, preview });
            }
            """;

    static final String SELECTED_SESSION_SCRIPT = """
            () => {
              const selected = document.querySelector("li[role='listitem'][aria-selected='true'], "
                + "li[role='listitem'].selected, li[role='listitem'].active, li[role='listitem'].cur, li[role='listitem'].current");
              return selected ? (
            """ + SESSION_SCRIPT + ") (selected) : '{}'; }";

    static final String MESSAGE_SCRIPT = """
            () => {
              const root = document.querySelector('.chat-message .im-list')
                || document.querySelector('.chat-record');
              if (!root) return JSON.stringify({ found: false });
              const rows = Array.from(root.querySelectorAll('li.message-item'));
              const messages = [];
              let time = '';
              let lastOwn = '';
              const occurrences = new Map();
              for (const row of rows) {
                const timestamp = row.querySelector('.message-time, .time, time');
                if (timestamp) time = (timestamp.textContent || '').trim();
                const self = row.classList.contains('item-myself');
                const friend = row.classList.contains('item-friend');
                if (!self && !friend) {
                  const label = (row.textContent || '').trim();
                  if (label) time = label;
                  continue;
                }
                const content = row.querySelector('.message-content');
                if (!content) continue;
                const copy = content.cloneNode(true);
                copy.querySelectorAll('.message-status, .message-time, .read-status').forEach(e => e.remove());
                let text = (copy.textContent || '').trim();
                if (!text) text = content.querySelector('img, [class*="image-content"], [class*="message-image"]')
                  ? '[图片]' : '[暂无法读取的消息]';
                const id = row.getAttribute('data-mid') || row.getAttribute('data-msgid')
                  || row.getAttribute('data-message-id') || row.getAttribute('data-msg-id')
                  || row.getAttribute('data-id') || row.id || '';
                const signature = JSON.stringify([self, text]);
                const occurrence = (occurrences.get(signature) || 0) + 1;
                occurrences.set(signature, occurrence);
                const identity = id ? JSON.stringify([id, self, text])
                  : JSON.stringify([time, self, text, occurrence, lastOwn]);
                if (self) lastOwn = id || JSON.stringify([time, text, occurrence]);
                const classes = [row, ...row.querySelectorAll('*')]
                  .map(e => typeof e.className === 'string' ? e.className : '').join(' ');
                const status = row.querySelector('.message-status')?.textContent || '';
                messages.push({text, self, identity,
                  failed: /send-fail|send-error|message-fail|upload-fail|upload-error/.test(classes)
                    || /发送失败|重新发送|点击重试/.test(status),
                  sending: /sending|uploading|send-progress/.test(classes) || /发送中|上传中/.test(status)});
              }
              const latest = messages.at(-1);
              let last = latest;
              // A failed outgoing bubble has not answered the HR. Pending sends must still wait.
              if (latest?.self && latest.failed) {
                last = messages.slice().reverse().find(m => !(m.self && m.failed)) || latest;
              }
              return JSON.stringify(last ? {found: true, ...last,
                previewText: latest.text,
                ownCount: messages.filter(m => m.self).length,
                context: messages.filter(m => !(m.self && m.failed)).slice(-12)
                  .map(m => (m.self ? '我：' : 'HR：') + m.text).join('\\n')
              } : {found: false});
            }
            """;

    record Session(String title, String key, String preview) {
        Session(String title, String key) { this(title, key, ""); }
    }

    static Session session(Locator item) {
        return parseSession(item.evaluate(SESSION_SCRIPT));
    }

    static Session session(ElementHandle item) {
        return parseSession(item.evaluate(SESSION_SCRIPT));
    }

    private static Session parseSession(Object raw) {
        JSONObject json = new JSONObject(String.valueOf(raw));
        return new Session(json.optString("title"), json.optString("key"), json.optString("preview"));
    }

    static JSONObject message(Page page) {
        JSONObject message = new JSONObject(String.valueOf(page.evaluate(MESSAGE_SCRIPT)));
        JSONObject selected = new JSONObject(String.valueOf(page.evaluate(SELECTED_SESSION_SCRIPT)));
        message.put("selectedKey", selected.optString("key"));
        return message;
    }

    static boolean matchesSession(Session session, JSONObject message) {
        String selected = message.optString("selectedKey");
        if (!selected.isEmpty() && !session.key().equals(selected)) return false;
        // A selected row can change before the old message pane has finished loading.
        String preview = session.preview().strip()
                .replaceFirst("^\\[(已读|未读)\\]\\s*", "")
                .replaceFirst("(?:\\.\\.\\.|…+)$", "");
        return preview.isBlank() || message.optString("previewText", message.optString("text")).startsWith(preview);
    }

    static Boss.ChatSnapshot snapshot(Session session, JSONObject message) {
        return new Boss.ChatSnapshot(session.title(), message.getString("text"), session.key(),
                message.getString("identity"), message.getBoolean("self"), message.optString("context"));
    }
}
