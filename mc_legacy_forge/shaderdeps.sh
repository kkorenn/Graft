#!/bin/sh
# Builds glslang + SPIRV-Cross as static universal (arm64 + x86_64) libraries into build/shaderdeps (once).
set -e
D="$(cd "$(dirname "$0")" && pwd)/build/shaderdeps"
[ -f "$D/spvc-build/libspirv-cross-msl.a" ] && [ -f "$D/glslang-build/glslang/libglslang.a" ] && exit 0
mkdir -p "$D" && cd "$D"
[ -d glslang ] || git clone -q --depth 1 --branch 15.1.0 https://github.com/KhronosGroup/glslang.git
[ -d SPIRV-Cross ] || git clone -q --depth 1 --branch vulkan-sdk-1.4.304.0 https://github.com/KhronosGroup/SPIRV-Cross.git
COMMON="-DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_ARCHITECTURES=arm64;x86_64 -DCMAKE_OSX_DEPLOYMENT_TARGET=10.13 -DCMAKE_POSITION_INDEPENDENT_CODE=ON"
cmake -S glslang -B glslang-build $COMMON -DBUILD_SHARED_LIBS=OFF -DENABLE_OPT=OFF -DENABLE_HLSL=OFF -DGLSLANG_TESTS=OFF \
	-DENABLE_GLSLANG_BINARIES=OFF -DGLSLANG_ENABLE_INSTALL=OFF >/dev/null
cmake --build glslang-build -j 12 >/dev/null
cmake -S SPIRV-Cross -B spvc-build $COMMON -DSPIRV_CROSS_SHARED=OFF -DSPIRV_CROSS_STATIC=ON -DSPIRV_CROSS_CLI=OFF \
	-DSPIRV_CROSS_ENABLE_TESTS=OFF -DSPIRV_CROSS_ENABLE_HLSL=OFF -DSPIRV_CROSS_ENABLE_CPP=OFF >/dev/null
cmake --build spvc-build -j 12 >/dev/null
