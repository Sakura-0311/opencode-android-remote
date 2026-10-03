#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-v1.5.0}"
DIST_DIR="dist_${VERSION}"

echo "🚀 开始构建 OpenCode Android Remote ${VERSION} 发行资产..."
python3 scripts/smoke_test_contract.py
echo "✅ 契约测试通过！"

rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}"

tar --exclude="*.pyc" --exclude="__pycache__" --exclude=".env" \
    -czvf "${DIST_DIR}/opencode-desktop-agent-${VERSION}.tar.gz" desktop_agent/
tar --exclude="*.pyc" --exclude="__pycache__" --exclude=".env" \
    -czvf "${DIST_DIR}/opencode-relay-server-${VERSION}.tar.gz" relay_server/
tar --exclude="caddy_data" --exclude="caddy_config" --exclude=".env" \
    -czvf "${DIST_DIR}/opencode-cloud-docker-${VERSION}.tar.gz" cloud_server/

if [ -d "android_app" ]; then
    cd android_app
    chmod +x gradlew || true
    if ./gradlew assembleDebug 2>/dev/null; then
        cd ..
        cp android_app/app/build/outputs/apk/debug/*.apk "${DIST_DIR}/OpenCode-Remote-${VERSION}-debug.apk" 2>/dev/null || true
    else
        cd ..
    fi
fi

cd "${DIST_DIR}"
sha256sum * > SHA256SUMS.txt 2>/dev/null || true
cd ..

echo "🎉 发布资产打包完成: ${DIST_DIR}"
