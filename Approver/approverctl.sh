#!/usr/bin/env bash
#
# approverctl.sh - ChatGPT 桌面版审批自动点击工具 管理脚本
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BINARY="${SCRIPT_DIR}/Approver"
SOURCE="${SCRIPT_DIR}/main.swift"
CACHE_DIR="${SCRIPT_DIR}/.cache"

# 检查日志与运行时目录写入权限
if [[ -z "${APPROVER_LOG_FILE:-}" ]]; then
    if mkdir -p "${HOME}/Library/Logs" 2>/dev/null && touch "${HOME}/Library/Logs/.test_write" 2>/dev/null; then
        rm -f "${HOME}/Library/Logs/.test_write"
        LOG_FILE="${HOME}/Library/Logs/Approver.log"
    else
        LOG_FILE="${SCRIPT_DIR}/Approver.log"
    fi
else
    LOG_FILE="${APPROVER_LOG_FILE}"
fi

if touch "/tmp/.test_write" 2>/dev/null; then
    rm -f "/tmp/.test_write"
    PID_FILE="/tmp/approver.pid"
    STOP_FILE="/tmp/approver.stop"
else
    mkdir -p "${SCRIPT_DIR}/.run"
    PID_FILE="${SCRIPT_DIR}/.run/approver.pid"
    STOP_FILE="${SCRIPT_DIR}/.run/approver.stop"
fi

AGENT_LABEL="com.user.approver"
AGENT_PLIST="${HOME}/Library/LaunchAgents/${AGENT_LABEL}.plist"

build_binary() {
    echo "🔨 正在编译 Approver..."
    mkdir -p "${CACHE_DIR}"
    swiftc -O -module-cache-path "${CACHE_DIR}" "${SOURCE}" -o "${BINARY}"
    echo "✅ 编译完成: ${BINARY}"
}

ensure_binary() {
    if [[ ! -f "${BINARY}" ]] || [[ "${SOURCE}" -nt "${BINARY}" ]]; then
        build_binary
    fi
}

get_running_pid() {
    if [[ -f "${PID_FILE}" ]]; then
        local pid
        pid=$(cat "${PID_FILE}" 2>/dev/null || echo "")
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            echo "${pid}"
            return 0
        fi
    fi
    # Fallback to pgrep
    local pids
    pids=$(pgrep -f "${BINARY} run" || pgrep -f "${BINARY} dry" || echo "")
    if [[ -n "${pids}" ]]; then
        echo "${pids}" | head -n 1
        return 0
    fi
    return 1
}

cmd_start() {
    ensure_binary
    if pid=$(get_running_pid); then
        echo "⚠️ Approver 已在运行中 (PID: ${pid})"
        return 0
    fi

    # 清理旧的急停文件
    rm -f "${STOP_FILE}"

    echo "🚀 启动 Approver 后台进程... 参数: $*"
    nohup "${BINARY}" run --log-file "${LOG_FILE}" --stop-file "${STOP_FILE}" "$@" >> "${LOG_FILE}" 2>&1 &
    local new_pid=$!
    echo "${new_pid}" > "${PID_FILE}"
    sleep 0.5

    if kill -0 "${new_pid}" 2>/dev/null; then
        echo "✅ Approver 已成功启动 (PID: ${new_pid})"
        echo "📄 实时日志查看: ./approverctl.sh log"
    else
        echo "❌ 启动失败，请检查日志: ${LOG_FILE}"
        cat "${LOG_FILE}" | tail -n 10
        exit 1
    fi
}

cmd_stop() {
    echo "🛑 正在停止 Approver..."
    touch "${STOP_FILE}"

    local waited=0
    while [[ ${waited} -lt 20 ]]; do
        if ! pid=$(get_running_pid); then
            break
        fi
        sleep 0.1
        waited=$((waited + 1))
    done

    if pid=$(get_running_pid); then
        echo "⚠️ 急停信号超时，尝试发送 SIGTERM..."
        kill -TERM "${pid}" 2>/dev/null || true
        sleep 0.5
    fi

    if pid=$(get_running_pid); then
        echo "⚠️ SIGTERM 超时，强制终止 (kill -9)..."
        kill -9 "${pid}" 2>/dev/null || true
    fi

    rm -f "${STOP_FILE}" "${PID_FILE}"
    echo "✅ Approver 已停止。"
}

cmd_status() {
    echo "========================================"
    echo "📊 Approver 运行状态"
    echo "========================================"
    if pid=$(get_running_pid); then
        echo "状态: 🟢 运行中"
        echo "PID:  ${pid}"
        if ps -p "${pid}" -o %cpu,%mem,etime,command 2>/dev/null; then
            :
        fi
    else
        echo "状态: ⚪ 已停止"
    fi

    if [[ -f "${AGENT_PLIST}" ]]; then
        echo "LaunchAgent 守护服务: 📦 已安装 (${AGENT_PLIST})"
    else
        echo "LaunchAgent 守护服务: 未安装"
    fi

    echo "----------------------------------------"
    echo "📄 最近 10 条日志 (${LOG_FILE}):"
    if [[ -f "${LOG_FILE}" ]]; then
        tail -n 10 "${LOG_FILE}"
    else
        echo "(日志文件尚不存在)"
    fi
    echo "========================================"
}

cmd_check() {
    ensure_binary
    "${BINARY}" check "$@"
}

cmd_dry() {
    ensure_binary
    echo "🧪 正在以「干跑模式 (dry)」在前台启动 Approver (按 Ctrl+C 退出)..."
    "${BINARY}" dry "$@"
}

cmd_run() {
    ensure_binary
    "${BINARY}" run "$@"
}

cmd_log() {
    if [[ ! -f "${LOG_FILE}" ]]; then
        touch "${LOG_FILE}"
    fi
    tail -n 30 -f "${LOG_FILE}"
}

cmd_install_agent() {
    ensure_binary
    mkdir -p "${HOME}/Library/LaunchAgents"

    # 构建启动参数
    local args_xml="        <string>${BINARY}</string>
        <string>run</string>"
    for arg in "$@"; do
        args_xml="${args_xml}
        <string>${arg}</string>"
    done

    cat <<EOF > "${AGENT_PLIST}"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${AGENT_LABEL}</string>
    <key>ProgramArguments</key>
    <array>
${args_xml}
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${LOG_FILE}</string>
    <key>StandardErrorPath</key>
    <string>${LOG_FILE}</string>
</dict>
</plist>
EOF

    echo "📄 已生成 LaunchAgent 配置: ${AGENT_PLIST}"

    local uid
    uid=$(id -u)
    if launchctl bootstrap "gui/${uid}" "${AGENT_PLIST}" 2>/dev/null; then
        echo "✅ 已通过 launchctl bootstrap 注册并启动开机守护服务"
    else
        echo "ℹ️ 回退至 launchctl load..."
        launchctl load -w "${AGENT_PLIST}" 2>/dev/null || true
        echo "✅ 已注册开机守护服务"
    fi
}

cmd_uninstall_agent() {
    local uid
    uid=$(id -u)
    if [[ -f "${AGENT_PLIST}" ]]; then
        echo "🗑️ 正在注销 LaunchAgent 守护服务..."
        launchctl bootout "gui/${uid}" "${AGENT_PLIST}" 2>/dev/null || launchctl unload "${AGENT_PLIST}" 2>/dev/null || true
        rm -f "${AGENT_PLIST}"
        echo "✅ LaunchAgent 服务已卸载。"
    else
        echo "LaunchAgent 服务未安装。"
    fi
}

cmd_help() {
    cat <<EOF
ChatGPT 桌面版审批自动点击工具 控制脚本

用法:
  ./approverctl.sh <命令> [选项]

命令列表:
  check                  自检模式：扫描并输出窗口、无障碍树和审批卡片识别情况
  dry [选项]             干跑模式：前台运行，只识别记录不实际点击 (支持 -s once|conversation|always, -a)
  start [选项]           后台启动：常驻运行，自动审批 (支持 -s once|conversation|always, -a 等参数)
  auto-switch [选项]     巡检模式：后台启动并开启侧边栏多会话自动轮转巡检 (先处理当前，完后切其他)
  stop                   急停：停止后台 Approver 进程
  restart [选项]         重启 Approver 进程
  status                 查看当前运行状态与最近日志
  log                    实时跟踪日志输出 (tail -f)
  build                  重新编译 Swift 二进制程序
  install-agent [选项]   安装为开机自启常驻服务 (LaunchAgent, 可传 -a)
  uninstall-agent        卸载开机自启常驻服务
  help                   查看本帮助信息

示例:
  ./approverctl.sh check                       # 执行环境与界面自检
  ./approverctl.sh dry -a                      # 干跑模式并开启侧边栏自动巡检
  ./approverctl.sh start                       # 后台默认启动 (纯被动监听，不切会话)
  ./approverctl.sh auto-switch                 # 巡检模式 (先批当前，处理完自动切其他会话)
  ./approverctl.sh start -a -s once            # 开启自动切会话巡检并使用「允许一次」策略
  ./approverctl.sh stop                        # 立即停止
  ./approverctl.sh install-agent -a -s once    # 开机自启常驻巡检
EOF
}

case "${1:-help}" in
    start)
        shift
        cmd_start "$@"
        ;;
    auto-switch)
        shift
        cmd_start "--auto-switch" "$@"
        ;;
    stop)
        cmd_stop
        ;;
    restart)
        shift
        cmd_stop || true
        sleep 0.5
        cmd_start "$@"
        ;;
    status)
        cmd_status
        ;;
    check)
        shift
        cmd_check "$@"
        ;;
    dry)
        shift
        cmd_dry "$@"
        ;;
    run)
        shift
        cmd_run "$@"
        ;;
    log)
        cmd_log
        ;;
    build)
        build_binary
        ;;
    install-agent)
        shift
        cmd_install_agent "$@"
        ;;
    uninstall-agent)
        cmd_uninstall_agent
        ;;
    help|--help|-h)
        cmd_help
        ;;
    *)
        echo "未知命令: $1"
        cmd_help
        exit 1
        ;;
esac
