# Graft

A native Apple Metal renderer for Minecraft (Fabric). It adds **Metal** to *Options → Video Settings → Graphics API*, next to Default, OpenGL and Vulkan, and selects it the first time the game starts with the mod installed. Switch back to OpenGL or Vulkan at any time and that choice sticks. If Metal fails to start, the game falls back to OpenGL, then Vulkan.

## Requirements
- macOS (Apple Silicon recommended; MSL 3.1 on macOS 14+, MSL 2.3 fallback on older systems)
- Minecraft **26.2** or **26.3**, Fabric Loader 0.19.5+, Java 25

Grab `graft-26.2-*.jar` or `graft-26.3-*.jar` to match your game version.

### Minecraft 1.21.5-1.21.11 (Fabric)
`graft-1.21.x-*.jar` replaces Blaze3D's GpuDevice like the 26.x builds (Java 25). 1.21.6-1.21.11 share
`mc1_21_legacy`; 1.21.5 has its own backend (`mc1_21_5`): its Blaze3D is an earlier draft (textures without views,
loose GLSL uniforms set by name, GL fences), so uniforms go into a std140 block filled from the pass and from
RenderSystem's global state, as GL's GlProgram.setDefaultUniforms does. Sodium is not supported on 1.21.5.

### Minecraft 1.7.10, 1.8.9 and 1.12.2 (Forge)
`graft-1.7.10-*.jar` (Forge 10.13.4.1614), `graft-1.8.9-*.jar` (Forge 11.15.1.2318+) and
`graft-1.12.2-*.jar` (Forge 14.23.5.2847+) are coremods for Java 8 (Intel or Apple Silicon). All share `mc_legacy_forge` (Metal core, window/input, shader translation, native
library); `mc1_7_10` / `mc1_8_9` / `mc1_12_2` only hold the version-specific hooks. 1.7.10 renders through the
generic paths (its display lists and Tessellator client arrays become Metal buffers) rather than native chunk hooks.
1.8.9 has no GPU device layer, so the renderer's shared paths are replaced natively:
- Terrain: chunk meshes go from the chunk builder straight into Metal buffers; one Metal draw per chunk layer.
- Tessellator: `WorldVertexBufferUploader.draw` submits WorldRenderer vertices to Metal as they are (entities, tile
  entities, particles, sky, weather, GUI, items). Consecutive quads with the same state are merged into one draw.
- Models and sky: display lists are compiled into Metal buffers, with each model part merged into a single mesh.
- Text: glyphs are batched (about one draw per string instead of one per character).
- Framebuffers and textures are Metal textures; one fixed-function Metal shader stands in for GL 1.x.

No OpenGL context is ever created: Graft opens its own Metal window (Display, Keyboard and Mouse are reimplemented
on it), and any GL call it does not implement becomes a no-op with a one-time log warning. A bundled universal
(arm64 + x86_64) native library replaces JNA, so it also runs on Apple Silicon Java 8.

**OptiFine** (HD U M5) works, including **shader packs**: as in Graft Shaders, pack GLSL is rewritten to GLSL 4.60
(compatibility built-ins become inputs/varyings/members of one std140 block, explicit sampler bindings, name-matched
varyings) and compiled to MSL with glslang + SPIRV-Cross, built into the native library. Multiple render targets,
float/depth formats and shadow-compare samplers are Metal-backed. Tested with BSL v10.1.8 (about 4x the FPS of Apple's
OpenGL). Geometry shaders are not supported. `-Dgraft.disabled=true` runs plain OpenGL.

**MetalFX render scale**: set `renderScale` (50-100) in `config/graft.properties`; below 100 the world renders at
that percentage and the MetalFX spatial scaler upscales it, with the GUI at full resolution (as in the 26.2 build).
With an OptiFine shader pack, the pack's own buffers (gbuffers, shadows, composites) follow the same scale and MetalFX
upscales its final image.

Building 1.8.9 needs the Xcode command line tools, git and cmake (`mc_legacy_forge/shaderdeps.sh` builds glslang and
SPIRV-Cross once).

## Features
- Vertex, fragment, compute and tessellation shaders (GLSL → SPIR-V → MSL via SPIRV-Cross)
- Direct, multi-draw (interleaved and separate) and indirect draws
- Triangle fans and geometry emulation
- Fabric 26.x: no native libraries of its own, Objective-C/Metal is called through Java's FFM API (Forge uses the bundled `libgraft` JNI bridge; 1.21.x bundles LWJGL shaderc/spvc)

## Mod compatibility
| Mod | 26.2 | 26.3 | Notes |
|---|---|---|---|
| Sodium | ✅ | ✅ | Adds a Graft page to Sodium's video settings |
| Distant Horizons | ✅ | ✅ | Uses DH's Blaze3D renderer |
| Shine | ✅ | — | No 26.3 build of Shine exists yet |
| Metallum | ❌ | ❌ | Also replaces the renderer; install one or the other |

## Options
`config/graft.properties` (also on Sodium's settings page if Sodium is installed):

| Key | Default | |
|---|---|---|
| `metalSelected` | `false` | Set once the mod has switched the Graphics API to Metal. Set it back to `false` to have it switch again on the next launch. |
| `displayP3` | `false` | `true` shows saturated Display P3 colours (the pre-26.3 Mac look); `false` shows accurate sRGB. Needs a restart. |

## Building
```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew build
```
Jars land in `mc26_2/build/libs` and `mc26_3/build/libs` (Forge: `mc1_8_9/build/libs`, `mc1_12_2/build/libs`).

Dev helpers:
- `./gradlew :core:smokeTest`: headless Metal test (draw, mid-pass blit, compute)
- `./gradlew :mc26_2:runClient -Pquickplay=<world>`: launch straight into a singleplayer world
- `./gradlew :mc26_2:runClient -Pquickplay=<world> -Pbench=metal|vanilla`: 30-second FPS benchmark, logged as `GRAFT_BENCH`
- `./gradlew :mc1_8_9:runClient -Pquickplay=<save> -Pbench=metal|vanilla`: same for 1.8.9 (FPS every 5 s)
- `-Dgraft.dumpFrame=N` (1.8.9): saves every render pass of frame N+1 to `/tmp/graft-dump` and logs program uniforms

## License

LGPL-3.0. See [LICENSE](LICENSE).
