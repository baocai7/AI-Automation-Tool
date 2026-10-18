#!/usr/bin/env bash
# Compile/test this checkout before replacing its running service on port 9527.
# Uses existing local dependencies; preserves the database, settings and log history.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$ROOT_DIR"
if [[ -d "$ROOT_DIR/get_jobs" ]]; then
    PROJECT_DIR="$ROOT_DIR/get_jobs"
fi
LOG_FILE=/tmp/getjobs.log

if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [[ ! -x "${JAVA_HOME:-}/bin/java" ]]; then
    JAVA_HOME=/Users/ahs/Library/Java/JavaVirtualMachines/ms-21.0.12.1/Contents/Home
fi
export JAVA_HOME
export PATH="/Users/ahs/.workbuddy/binaries/node/versions/22.22.2/bin:$JAVA_HOME/bin:$PATH"

"$JAVA_HOME/bin/java" -version
cd "$PROJECT_DIR"

# Avoid bootRun invoking dependency installation or frontend rebuilding during restart.
if [[ ! -f build/patchright-driver/.version ]] \
        || [[ "$(cat build/patchright-driver/.version)" != 1.62.1 ]] \
        || [[ ! -f build/patchright-driver/package/cli.js ]] \
        || [[ ! -f src/main/resources/dist/index.html ]]; then
    echo "现有浏览器驱动或管理页不完整，请先准备构建依赖。当前服务未停止。" >&2
    exit 1
fi

echo "正在编译并运行回归测试（使用本地依赖缓存）…"
./gradlew test bootJar -x buildFrontend --offline --no-daemon --console=plain

reader_class=build/classes/java/main/com/getjobs/worker/boss/BossChatReader.class
boss_class=build/classes/java/main/com/getjobs/worker/boss/Boss.class
service_class=build/classes/java/main/com/getjobs/application/service/BossAutoReplyService.class
for class_file in "$reader_class" "$boss_class" "$service_class"; do
    if [[ ! -f "$class_file" ]]; then
        echo "缺少新版编译产物：$class_file，当前服务未停止。" >&2
        exit 1
    fi
done
if [[ src/main/java/com/getjobs/worker/boss/Boss.java -nt "$boss_class" ]] \
        || [[ src/main/java/com/getjobs/worker/boss/BossChatReader.java -nt "$reader_class" ]] \
        || [[ src/main/java/com/getjobs/application/service/BossAutoReplyService.java -nt "$service_class" ]]; then
    echo "编译产物仍旧于源码，当前服务未停止。" >&2
    exit 1
fi
"$JAVA_HOME/bin/javap" -classpath build/classes/java/main \
    'com.getjobs.worker.boss.Boss$ChatSnapshot' | grep -F 'messageId()' >/dev/null

old_pids="$(lsof -nP -iTCP:9527 -sTCP:LISTEN -t | sort -u || true)"
for service_pid in $old_pids; do
    process_dir="$(lsof -a -p "$service_pid" -d cwd -Fn | sed -n 's/^n//p')"
    if [[ "$process_dir" != "$PROJECT_DIR" ]]; then
        echo "9527 由其他目录的进程 $service_pid 占用：$process_dir；未停止该进程。" >&2
        exit 1
    fi
done
for service_pid in $old_pids; do
    echo "停止本项目旧进程：$service_pid"
    kill -TERM "$service_pid"
done

waited=0
while lsof -nP -iTCP:9527 -sTCP:LISTEN >/dev/null 2>&1; do
    if [[ "$waited" -ge 90 ]]; then
        echo "旧进程尚未释放 9527，未启动第二个服务，请检查进程。" >&2
        exit 1
    fi
    sleep 1
    waited=$((waited + 1))
done

echo "测试通过，启动新版服务；日志追加到 $LOG_FILE"
export PLAYWRIGHT_NODEJS_PATH="$(command -v node)"
jar_hash="$(shasum -a 256 build/libs/get-jobs.jar | awk '{print $1}')"
runtime_jar="$PROJECT_DIR/build/runtime/get-jobs-$jar_hash.jar"
mkdir -p "$PROJECT_DIR/build/runtime"
if [[ ! -f "$runtime_jar" ]]; then
    cp build/libs/get-jobs.jar "$runtime_jar"
fi
nohup "$JAVA_HOME/bin/java" -Xms64m "-Xmx${GETJOBS_HEAP_MB:-512}m" \
    "-XX:ActiveProcessorCount=${GETJOBS_PROCESSORS:-2}" -Dfile.encoding=UTF-8 \
    "-Dplaywright.cli.dir=$PROJECT_DIR/build/patchright-driver" \
    -jar "$runtime_jar" --server.port=9527 >> "$LOG_FILE" 2>&1 &
launcher_pid=$!

for ((second = 0; second < 60; second++)); do
    if curl -fsS --max-time 2 http://127.0.0.1:9527/api/boss/auto-reply/config 2>/dev/null; then
        echo
        echo "新版服务已就绪，启动进程：$launcher_pid"
        echo "下一轮应出现：自动回复：扫描到 … 个未读会话（仅未读模式）"
        echo "查看实时日志：tail -f $LOG_FILE"
        exit 0
    fi
    if ! kill -0 "$launcher_pid" 2>/dev/null; then
        echo "服务启动失败，请查看 $LOG_FILE" >&2
        exit 1
    fi
    sleep 1
done
echo "新版启动尚未确认，请检查：tail -n 100 $LOG_FILE" >&2
exit 1
