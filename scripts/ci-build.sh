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

# --- 3b. Khoa ky co dinh + icon ung dung -------------------------------------
# Khoa ky: moi lan chay Actions lai sinh khoa ky debug ngau nhien khac nhau ->
# ban moi khong cai de len ban cu duoc (xung dot chu ky), phai go app va mat het
# du lieu + key da luu. Giai ma khoa co dinh cua repo vao NGAY TRONG REPO
# (signing/debug.keystore); build.gradle.kts tro thang toi file nay, khong phu
# thuoc bien HOME cua may build. Kiem tra luon: khoa phai mo duoc bang alias
# chuan, sai la dung build ngay chu khong im lang ky bang khoa ngau nhien.
base64 -d signing/debug.keystore.b64 > signing/debug.keystore || exit 1
"${JAVA_HOME}/bin/keytool" -list -keystore signing/debug.keystore -storepass android -alias androiddebugkey > /dev/null || exit 1
# Anh icon ung dung (anh cua nguoi dung) giu dang base64 trong repo, giai ma vao
# drawable-nodpi truoc khi build; file PNG khong nam trong repo dang nhi phan.
base64 -d signing/phongai-icon.b64 > app/src/main/res/drawable-nodpi/phongai_icon_photo.png || exit 1

# --- 4. Build ban perf (arm64) ------------------------------------------------
set -o pipefail
# gradlew day len GitHub qua API bi mat quyen chay (mode 100644), nen cap quyen o day.
chmod +x gradlew
# --- 4a. Chay test don vi cua cac lop vua sua (v3.3.1) TRUOC khi dong goi -----
# Chi chay cac lop test cham toi ban sua nay (--tests loc theo lop; loc khong
# khop o mot task se lam task do THAT BAI nen phai tach :core va :app rieng).
# Test hong -> ghi nhan BUILD_RC, BO QUA buoc dong goi; muc 5 van phat hanh
# release THAT BAI kem log nhu thuong. Luu y: --tests chi loc luc CHAY, toan bo
# ma nguon test van duoc bien dich.
./gradlew :core:test --tests "com.wangzan101.mika.core.model.ModelHttpTest" --tests "com.wangzan101.mika.core.model.GeminiModelTest" --tests "com.wangzan101.mika.core.model.ModelCatalogTest" --tests "com.wangzan101.mika.core.agent.QuickChatTest" --tests "com.wangzan101.mika.core.agent.RetryWaitNoteTest" --tests "com.wangzan101.mika.core.model.OpenAiCompatModelTest" --console=plain 2>&1 | tee build-log.txt
TEST_RC=${PIPESTATUS[0]}
if [ "$TEST_RC" = 0 ]; then
  ./gradlew :app:testPerfUnitTest --tests "com.wangzan101.mika.settings.ProviderStoreTest" --tests "com.wangzan101.mika.settings.PasteKeyTest" --tests "com.wangzan101.mika.settings.ModelOptionsTest" --tests "com.wangzan101.mika.memory.SeedPackTest" --console=plain 2>&1 | tee -a build-log.txt
  TEST_RC=${PIPESTATUS[0]}
fi
if [ "$TEST_RC" != 0 ]; then
  echo "TEST DON VI THAT BAI (rc=$TEST_RC) - bo qua buoc dong goi APK"
  BUILD_RC=$TEST_RC
else
  ./gradlew :app:assemblePerf --console=plain 2>&1 | tee -a build-log.txt
  BUILD_RC=${PIPESTATUS[0]}
fi

# --- 5. Thu thap ket qua va phat hanh Release ---------------------------------
mkdir -p out
APK=$(ls app/build/outputs/apk/perf/app-arm64-v8a-perf.apk 2>/dev/null || true)
if [ -n "$APK" ]; then
  cp "$APK" out/mika-vi-arm64.apk
  (cd out && sha256sum mika-vi-arm64.apk > mika-vi-arm64.apk.sha256)
fi
# Kiem tra chu ky: APK phai ky bang khoa co dinh cua repo (3d1e9edb...); neu
# khac thi danh dau THAT BAI, van phat hanh kem log de co the chan doan, roi
# thoat loi — khong bao gio im lang giao ban ky sai nua.
SIGN_OK=1
if [ -f out/mika-vi-arm64.apk ]; then
  if ! "${ANDROID_HOME}/build-tools/35.0.0/apksigner" verify --print-certs out/mika-vi-arm64.apk | grep -qi "3d1e9edb1c55ddea65245f9a9bce93014529bcbbf11523a25907d55ace463928"; then
    SIGN_OK=0
    echo "CHU KY KHONG KHOP KHOA CO DINH (3d1e9edb...)"
  fi
fi
tail -c 190000 build-log.txt > out/build-log.txt 2>/dev/null || true
ls -la out || true

TAG="build-${BUILD_NUMBER:-local}"
if [ -f out/mika-vi-arm64.apk ] && [ "$SIGN_OK" = 1 ]; then
  TITLE="Mika VI - ban build ${BUILD_NUMBER:-local}"
else
  TITLE="Mika VI - ban build ${BUILD_NUMBER:-local} (THAT BAI, xem build-log.txt)"
fi
gh release create "$TAG" out/* --title "$TITLE" \
  --notes "Build tu dong Mika VI (ban perf, arm64-v8a). Tai file mika-vi-arm64.apk de cai dat." || true

if [ "$SIGN_OK" = 0 ]; then exit 1; fi
exit ${BUILD_RC}
