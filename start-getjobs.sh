#!/usr/bin/env bash
# 启动 get_jobs（后端 9527 + 管理页 6866）
#
# 前置（只需做一次）：
#   1) db/getjobs.db 已从 release 下载的 getjobs.db.template 重命名而来
#   2) src/main/resources/dist 已放好前端构建产物（没有的话会提示"未找到可用的管理页面"）
#
# 用法：
#   ./start-getjobs.sh                       前台启动（Ctrl+C 停止）
#   BG=1 ./start-getjobs.sh                  后台启动，日志写 /tmp/getjobs.log
#   GETJOBS_NO_SANDBOX=true ./start-getjobs.sh   终端受限、Chrome 沙箱起不来时用
#   GETJOBS_NO_SANDBOX=true BG=1 ./start-getjobs.sh   仅受限终端需要关闭沙箱
#
# 环境变量：
#   GETJOBS_NO_SANDBOX=true   受限终端里 Chrome 沙箱起不来时加上（默认不加，
#                             因为 --no-sandbox 本身是自动化特征）
#   BG=1                      后台运行
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$ROOT_DIR"
if [[ -d "$ROOT_DIR/get_jobs" ]]; then
    PROJECT_DIR="$ROOT_DIR/get_jobs"
fi
LOG_FILE="${GETJOBS_LOG:-/tmp/getjobs.log}"

# 端口已被占用就先提示，避免启动到一半报 "Port already in use"
if lsof -nP -iTCP:9527 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "⚠️  9527 已被占用，服务可能已在运行： http://localhost:6866"
    echo "    要重启先执行： lsof -nP -iTCP:9527 -sTCP:LISTEN -t | xargs kill"
    exit 1
fi

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
# get_jobs 调 patchright 需要 node 在 PATH 里，用托管 node 22，避免版本不一致
export PATH="/Users/ahs/.workbuddy/binaries/node/versions/22.22.2/bin:${JAVA_HOME}/bin:${PATH}"

cd "$PROJECT_DIR"

# 日常运行直接使用 JAR，避免 Gradle 常驻。只在源码/资源变更后重新打包。
JAR_FILE="$PROJECT_DIR/build/libs/get-jobs.jar"
needs_build=0
if [[ ! -f "$JAR_FILE" ]]; then
    needs_build=1
elif [[ -n "$(find src/main -type f -newer "$JAR_FILE" -print -quit)" ]] \
        || [[ build.gradle.kts -nt "$JAR_FILE" ]] || [[ gradle.properties -nt "$JAR_FILE" ]]; then
    needs_build=1
fi
if [[ "$needs_build" = 1 ]]; then
    ./gradlew bootJar --no-daemon --console=plain
fi
if [[ ! -f build/patchright-driver/package/cli.js ]]; then
    ./gradlew installPatchrightDriver --no-daemon --console=plain
fi
export PLAYWRIGHT_NODEJS_PATH="$(command -v node)"
# 保留每个版本的独立运行副本：重新 bootJar 时不会覆盖运行中 JVM 正在读取的文件。
jar_hash="$(shasum -a 256 "$JAR_FILE" | awk '{print $1}')"
RUNTIME_JAR="$PROJECT_DIR/build/runtime/get-jobs-$jar_hash.jar"
mkdir -p "$PROJECT_DIR/build/runtime"
if [[ ! -f "$RUNTIME_JAR" ]]; then
    cp "$JAR_FILE" "$RUNTIME_JAR"
fi
JAVA_ARGS=(-Xms64m "-Xmx${GETJOBS_HEAP_MB:-512}m" "-XX:ActiveProcessorCount=${GETJOBS_PROCESSORS:-2}"
    -Dfile.encoding=UTF-8 "-Dplaywright.cli.dir=$PROJECT_DIR/build/patchright-driver"
    -jar "$RUNTIME_JAR" --server.port=9527)

echo "启动中…  后端 http://localhost:9527   管理页 http://localhost:6866"
[ "${GETJOBS_NO_SANDBOX:-}" = "true" ] && echo "（已关闭 Chrome 沙箱，代码会自动追加 --disable-gpu）"

if [ "${BG:-}" = "1" ]; then
    nohup "$JAVA_HOME/bin/java" "${JAVA_ARGS[@]}" >> "$LOG_FILE" 2>&1 &
    echo "后台已启动，PID=$!，日志： $LOG_FILE"
    echo "看日志： tail -f $LOG_FILE"
    exit 0
fi

exec "$JAVA_HOME/bin/java" "${JAVA_ARGS[@]}"
