# Boss 自动回复排查与验证

自动回复先点击并确认聊天页的「未读」筛选，只从该列表捕获新目标。
你提前手动读过的会话不会再作为新目标。已捕获的未读会话打开后即使红点消失，仍继续完成本次回复；最后一条是本人已发送或发送中的消息时不会回复自己。
未找到未读入口或无法确认筛选生效时，本轮跳过，不回退扫描「全部」。

## 发送与去重

- 会话优先按页面上的 HR 标识定位；缺失时使用联系人、公司和职位组合。同一标识重复且无法区分时记录警告并跳过。
- 消息优先按消息 ID 去重，缺失 ID 时结合时间、文本、出现次数和前一条本人消息区分。浏览器历史裁剪或站点 DOM 改动仍需要实际页面验证。
- AI 生成后复核当前消息、选中会话及会话预览。打开后已从未读列表消失的行无需再次点击；消息发生变化时取消本次发送。
- 输入框已有草稿时不覆盖。失败时仅清理本次生成、且未被用户修改的草稿。
- 页面出现新增本人消息、未显示发送中或失败状态并连续稳定两次后，才更新 `boss_chat_reply_state` 和当日回复次数。
- 从未读列表捕获的 HR 消息保存到 `boss_auto_reply_pending`。AI 失败或发送未确认时保留当前会话，下轮在消息身份仍一致时重试；其他已读会话不会被纳入扫描。
- 待处理记录可以跨服务重启保留；若页面已切换到其他会话，只有重新确认到同一条消息才重试，不通过扫描「全部」强行定位。
- 页面明确显示发送失败时，之前的 HR 消息继续保持待回复；发送状态不明时不立即重发，避免重复。
- dry-run 的预览只在内存去重；切换真实发送后仍能回复同一条消息。

原有配置、历史记录和敏感词规则保留。每日上限和敏感词拦截不会消耗待回复消息；调整配置或额度恢复后仍可重新判断。
`last_seen_msg` 新记录以 `v2:` 开头保存消息标识，用于区分相同文字的不同消息。

## 回归检查

在项目目录，使用 Java 21 执行：

```sh
./gradlew test -x buildFrontend --no-daemon
```

无需 Java 的 DOM API fixture 测试：

```sh
node --test src/test/js/boss-chat-reader.test.cjs
```

Java 测试覆盖首次待回复消息、旧基线记录、同文新消息、本人消息、dry-run 切换、AI 失败、每日上限恢复、敏感词调整、不同 HR、发送确认、生成期间消息变化、用户草稿及会话匹配。
DOM fixture 测试验证实际注入脚本的解析行为，不代替对 Boss 当前页面的集成测试。

## 生效与观察

源码修改需要编译并重启后端，正在运行的 JVM 不会自动加载这些变更。保持现有自动回复开关和 dry-run 设置。

工作区根目录的 `restart-getjobs-verified.sh` 会先执行离线编译和 Java 回归测试、确认新版 class 及消息标识方法存在，再停止当前项目在 9527 的旧进程并启动新版。测试失败不会停止旧服务；日志追加到 `/tmp/getjobs.log`。

```sh
bash ../restart-getjobs-verified.sh
```

生效后每轮均有摘要，包括真实发送条数、AI 调用次数和跳过原因；发送日志为：

```text
自动回复已确认发送 | 会话：… | 内容：…
自动回复本轮完成，确认发送 … 条，AI 调用 … 次，模式：真实发送，跳过原因：…
```

实际验收应看到「扫描到 N 个未读会话（仅未读模式）」；有待回复消息时确认发送成功，下一轮不会重复回复。未读列表为空时发送 0 条是正常结果。


## 回复流水与统计

每次已确认发送、预览、AI 失败、发送未确认及规则拦截，会输出单行结构化日志：

```text
BOSS_AUTO_REPLY_EVENT {"eventId":"…","runId":"…","time":"…","sessionKey":"…","sessionTitle":"…","messageId":"…","hrMessage":"…","reply":"…","status":"SENT","reason":"页面确认发送成功"}
```

日志同时出现在 `/tmp/getjobs.log`（按当前启动方式）和 `target/logs/get-jobs.log`。
完整内容也逐条写入项目数据库 `db/getjobs.db` 的 `boss_auto_reply_event` 表，日志轮转后仍可按日期统计。

状态：`SENT` 为已确认发送；`PREVIEW` 为仅预览；`AI_FAILED` 为生成失败；`SEND_UNCONFIRMED` 为未确认发送；`DEFERRED` 为限额/敏感词等暂缓；`SKIPPED` 为系统消息/结束语等不回复。
成功回复统计只计算 `SENT`，避免将预览和失败算进去。

```sql
SELECT substr(occurred_at, 1, 10) AS day, COUNT(*) AS sent_count,
       COUNT(DISTINCT session_key) AS session_count
FROM boss_auto_reply_event
WHERE status = 'SENT'
GROUP BY substr(occurred_at, 1, 10)
ORDER BY day DESC;

SELECT occurred_at, session_title, hr_message, reply, status, reason
FROM boss_auto_reply_event
ORDER BY occurred_at DESC
LIMIT 50;
```

新增表由 `db/migration/V20261018_01__boss_auto_reply_events.sql` 幂等创建，不改写既有记录。
回退代码时保留新增流水表，无需删除历史数据。统计流水从本版本启用后开始记录，不回填旧日志。
