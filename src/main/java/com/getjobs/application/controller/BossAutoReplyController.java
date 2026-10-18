package com.getjobs.application.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Boss 自动回复的配置开关。
 * <p>
 * 配置全部存在 boss_auto_reply_config 表，轮询任务每轮重新读取，
 * 所以这里改完最多一个轮询间隔就生效，不需要重启服务。
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/boss/auto-reply")
public class BossAutoReplyController {

    private final JdbcTemplate jdbcTemplate;

    public BossAutoReplyController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT enabled, dry_run, interval_minutes, max_per_run, max_per_day, " +
                            "sensitive_words, updated_at FROM boss_auto_reply_config WHERE id = 1",
                    rs -> {
                        data.put("enabled", rs.getInt("enabled") == 1);
                        data.put("dryRun", rs.getInt("dry_run") == 1);
                        data.put("intervalMinutes", rs.getInt("interval_minutes"));
                        data.put("maxPerRun", rs.getInt("max_per_run"));
                        data.put("maxPerDay", rs.getInt("max_per_day"));
                        data.put("sensitiveWords", rs.getString("sensitive_words"));
                        data.put("updatedAt", rs.getString("updated_at"));
                    });
        } catch (Exception e) {
            // 表还没建（服务刚起、自动回复服务还没跑 @PostConstruct）时给默认值
            data.put("enabled", false);
            data.put("dryRun", true);
            data.put("intervalMinutes", 3);
            data.put("maxPerRun", 2);
            data.put("maxPerDay", 3);
            data.put("sensitiveWords", null);
            data.put("updatedAt", null);
        }
        return ResponseEntity.ok(data);
    }

    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int enabled = toInt(body.get("enabled"), 0);
            int dryRun = toInt(body.get("dryRun"), 1);
            int interval = Math.max(1, toInt(body.get("intervalMinutes"), 3));
            int maxPerRun = Math.max(1, toInt(body.get("maxPerRun"), 2));
            int maxPerDay = Math.max(1, toInt(body.get("maxPerDay"), 3));

            jdbcTemplate.update(
                    "INSERT INTO boss_auto_reply_config " +
                            "(id, enabled, dry_run, interval_minutes, max_per_run, max_per_day, updated_at) " +
                            "VALUES (1, ?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT(id) DO UPDATE SET enabled = excluded.enabled, " +
                            "dry_run = excluded.dry_run, interval_minutes = excluded.interval_minutes, " +
                            "max_per_run = excluded.max_per_run, max_per_day = excluded.max_per_day, " +
                            "updated_at = excluded.updated_at",
                    enabled, dryRun, interval, maxPerRun, maxPerDay, LocalDateTime.now().toString());
            result.put("success", true);
            result.put("message", enabled == 1
                    ? (dryRun == 1 ? "已开启（dry-run：只生成、不真发）" : "已开启真实发送")
                    : "已关闭自动回复");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败：" + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    private static int toInt(Object v, int def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
