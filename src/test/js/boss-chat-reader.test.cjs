// Runs the exact Java text-block probes against small DOM API fixtures. No network/browser required.
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { runInNewContext } = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');

const source = readFileSync(resolve(__dirname, '../../main/java/com/getjobs/worker/boss/BossChatReader.java'), 'utf8');
function script(name) {
  const block = source.match(new RegExp(name + ' = """\\n([\\s\\S]*?)\\n\\s*""";'));
  assert.ok(block, name);
  // Decode the escaped backslash in Java text blocks (the probes have no other Java escapes).
  return block[1].replaceAll('\\\\', '\\');
}

function row({ text = '', self = false, id = '', time = '', status = '', classes = '', image = false, system = false } = {}) {
  const content = {
    textContent: text,
    querySelector: selector => selector.startsWith('img,') && image ? {} : null,
    cloneNode: () => ({ textContent: text, querySelectorAll: () => [] }),
  };
  return {
    id,
    className: classes,
    textContent: text,
    classList: { contains: name => !system && name === (self ? 'item-myself' : 'item-friend') },
    getAttribute: () => null,
    querySelector: selector => {
      if (selector === '.message-content') return content;
      if (selector === '.message-time, .time, time') return time ? { textContent: time } : null;
      if (selector === '.message-status') return { textContent: status };
      return null;
    },
    querySelectorAll: () => [],
  };
}

function read(rows, rootFound = true) {
  const root = { querySelectorAll: selector => {
    assert.equal(selector, 'li.message-item');
    return rows;
  } };
  const document = { querySelector: () => rootFound ? root : null };
  return JSON.parse(runInNewContext('(' + script('MESSAGE_SCRIPT') + ')()', { document }));
}

test('read/unread badges do not suppress the latest HR message', () => {
  const last = read([row({ self: true, text: '您好', id: '1' }), row({ text: '你好', id: '2' })]);
  assert.equal(last.self, false);
  assert.equal(last.text, '你好');
  assert.equal(last.context, '我：您好\nHR：你好');
});

test('same text in distinct HR messages has distinct identity', () => {
  assert.notEqual(read([row({ text: '你好', id: '1' })]).identity,
    read([row({ text: '你好', id: '2' })]).identity);
});

test('missing ids still distinguish consecutive identical incoming bubbles', () => {
  const first = row({ text: '你好' });
  assert.notEqual(read([first]).identity, read([first, row({ text: '你好' })]).identity);
});

test('identity is stable when status changes to read', () => {
  assert.equal(read([row({ text: '你好', id: '1', status: '未读' })]).identity,
    read([row({ text: '你好', id: '1', status: '已读' })]).identity);
});

test('outgoing message and delivery failures are recognized', () => {
  const last = read([row({ self: true, text: '您好', classes: 'message-item item-myself send-fail', status: '发送失败' })]);
  assert.equal(last.self, true);
  assert.equal(last.failed, true);
  assert.equal(last.ownCount, 1);
});

test('pending outgoing bubble is not treated as confirmed', () => {
  assert.equal(read([row({ self: true, text: '您好', classes: 'sending' })]).sending, true);
});

test('failed outgoing bubble leaves the HR message pending for retry', () => {
  const last = read([row({ text: '你好', id: 'hr-1' }), row({ self: true, text: '您好', status: '发送失败' })]);
  assert.equal(last.self, false);
  assert.equal(last.text, '你好');
  assert.equal(last.previewText, '您好');
  assert.equal(last.ownCount, 1);
  assert.equal(last.context, 'HR：你好');
});

test('outgoing message still sending must not trigger another reply', () => {
  const last = read([row({ text: '你好', id: 'hr-1' }), row({ self: true, text: '您好', classes: 'sending' })]);
  assert.equal(last.self, true);
  assert.equal(last.sending, true);
});

test('image messages are explicit rather than disappearing', () => {
  assert.equal(read([row({ image: true, id: 'image-1' })]).text, '[图片]');
});

test('unreadable multimedia bubble does not expose an older message as latest', () => {
  assert.equal(read([row({ text: '旧问题' }), row({ id: 'voice-1' })]).text, '[暂无法读取的消息]');
});

test('system notices are excluded but timestamp is retained for fallback identity', () => {
  const last = read([row({ system: true, text: '今天 15:07' }), row({ text: '你好' })]);
  assert.equal(last.context, 'HR：你好');
  assert.ok(last.identity.includes('今天 15:07'));
});

test('missing or empty chat pane does not yield a reply target', () => {
  assert.equal(read([], false).found, false);
  assert.equal(read([]).found, false);
});

function session({ company = '同公司', name = '张女士 同公司', job = 'Java工程师', id = '' } = {}) {
  const texts = {
    '.title-box .name-box span:nth-child(2)': company,
    '.title-box .name-box': name,
    '.job-name': job,
    '.last-msg-text': '你好',
  };
  const el = {
    querySelector: selector => texts[selector] ? { textContent: texts[selector] } : null,
    getAttribute: attr => attr === 'data-uid' ? id : null,
  };
  return JSON.parse(runInNewContext('(' + script('SESSION_SCRIPT') + ')(el)', { el }));
}

test('same company with different recruiters has different fallback identities', () => {
  assert.notEqual(session({ name: '张女士 同公司' }).key, session({ name: '李先生 同公司' }).key);
});

test('stable HR identity survives company/title changes and row reordering', () => {
  assert.equal(session({ id: 'hr-1' }).key, session({ id: 'hr-1', company: '公司新名' }).key);
  assert.notEqual(session({ id: 'hr-1' }).key, session({ id: 'hr-2' }).key);
});

test('selected-session probe returns the same stable key as the list probe', () => {
  const selectedBlock = source.match(/SELECTED_SESSION_SCRIPT = """\n([\s\S]*?)\n\s*""" \+ SESSION_SCRIPT \+ "(.*?)";/);
  assert.ok(selectedBlock);
  const probe = selectedBlock[1] + script('SESSION_SCRIPT') + JSON.parse('"' + selectedBlock[2] + '"');
  const el = { querySelector: () => ({ textContent: '同公司' }), getAttribute: attr => attr === 'data-uid' ? 'hr-1' : null };
  assert.equal(JSON.parse(runInNewContext('(' + probe + ')()', { document: { querySelector: () => el } })).key, 'boss:id:data-uid:hr-1');
  assert.deepEqual(JSON.parse(runInNewContext('(' + probe + ')()', { document: { querySelector: () => null } })), {});
});
