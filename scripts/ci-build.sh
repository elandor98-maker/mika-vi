#!/usr/bin/env bash
# Kich ban build Mika VI tren GitHub Actions.
# File .github/workflows/build.yml chi goi script nay; moi buoc build nam o day.
# Bien moi truong nhan vao: GH_TOKEN (token cua Actions), BUILD_NUMBER (so lan chay).
set -x

UPSTREAM_SHA=c393f6455bc88a40b4dc55ebb3719985de4b94d0
LLAMA_SHA=ac931b5bcb12a9be71a0a26b0f6edb8b583fda90

# --- 1. Ghep cay nguon -------------------------------------------------------
# Repo nay chi chua ma nguon dang text; cac file nhi phan (rootfs.tar.xz,
# thu vien .so, hinh anh, gradle-wrapper.jar...) lay tu ban Mika goc, roi de
# ma nguon cua repo nay len tren (cac file da sua se thang).
mkdir -p /tmp/ours
cp -a . /tmp/ours/
curl -sL "https://github.com/wangzan101/Mika/archive/${UPSTREAM_SHA}.tar.gz" -o /tmp/mika-upstream.tar.gz
tar -xzf /tmp/mika-upstream.tar.gz --strip-components=1 -C .
cp -a /tmp/ours/. .
rm -rf /tmp/ours

# --- 2. llama.cpp (commit ghim co dinh) --------------------------------------
curl -sL "https://github.com/ggml-org/llama.cpp/archive/${LLAMA_SHA}.tar.gz" -o /tmp/llama.tar.gz
mkdir -p app/src/main/cpp/llama.cpp
tar -xzf /tmp/llama.tar.gz -C app/src/main/cpp/llama.cpp --strip-components=1

# --- 3. JDK 17 + Android SDK --------------------------------------------------
if [ -n "${JAVA_HOME_17_X64:-}" ] && [ -x "${JAVA_HOME_17_X64}/bin/java" ]; then
  export JAVA_HOME="${JAVA_HOME_17_X64}"
else
  for d in /usr/lib/jvm/*17*; do
    if [ -x "$d/bin/java" ]; then export JAVA_HOME="$d"; break; fi
  done
fi
"${JAVA_HOME}/bin/java" -version

export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
export PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"
yes | sdkmanager --licenses > /dev/null 2>&1 || true
sdkmanager "platforms;android-36" "platforms;android-35" "build-tools;36.0.0" "build-tools;35.0.0" "cmake;3.22.1" "ndk;27.2.12479018"

# --- 4. Build ban perf (arm64) ------------------------------------------------
set -o pipefail
./gradlew :app:assemblePerf --console=plain 2>&1 | tee build-log.txt
BUILD_RC=${PIPESTATUS[0]}

# --- 5. Thu thap ket qua va phat hanh Release ---------------------------------
mkdir -p out
APK=$(ls app/build/outputs/apk/perf/app-arm64-v8a-perf.apk 2>/dev/null || true)
if [ -n "$APK" ]; then
  cp "$APK" out/mika-vi-arm64.apk
  (cd out && sha256sum mika-vi-arm64.apk > mika-vi-arm64.apk.sha256)
fi
tail -c 190000 build-log.txt > out/build-log.txt 2>/dev/null || true
ls -la out || true

TAG="build-${BUILD_NUMBER:-local}"
if [ -f out/mika-vi-arm64.apk ]; then
  TITLE="Mika VI - ban build ${BUILD_NUMBER:-local}"
else
  TITLE="Mika VI - ban build ${BUILD_NUMBER:-local} (THAT BAI, xem build-log.txt)"
fi
gh release create "$TAG" out/* --title "$TITLE" \
  --notes "Build tu dong Mika VI (ban perf, arm64-v8a). Tai file mika-vi-arm64.apk de cai dat." || true

exit ${BUILD_RC}
