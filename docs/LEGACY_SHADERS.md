# Shader packs on legacy Forge (1.8.9 first, then 1.12.2 and 1.7.10)

Graft's own support for OptiFine-format shader packs (BSL, Complementary, Sildur's, SEUS, Chocapic13, MakeUp...) on
the legacy coremod versions. It is a clean-room implementation: it follows OptiFine's *public* documentation
(`OptiFineDoc/doc/shaders.txt`, `shaders.properties`) and reuses ideas from Iris / Angelica / Graft Shaders (LGPL-3.0).
OptiFine's code is never decompiled or read.

Priorities: shaders must run **smoothly** (no hitches from compilation, few render-target switches, nothing allocated per
draw) and be **compatible** with real packs (measured headlessly, see *Compatibility harness*).

## Layers

```
 pack zip/folder ──► ShaderPack ─► Options / PackConfig / ProgramSet / Directives      (pure Java, any thread)
                                         │
                                         ▼
                         PackCompiler ─► MetalGlsl (GLSL 1.x → GLSL 460) ─► shaders.c (glslang → SPIR-V →
                                         SPIRV-Cross → MSL) ─► Metal library          (loader thread, disk cache)
                                         │
                                         ▼
 Hooks (version module) ──► ShaderPipeline on MGL/Programs (Graft's GL layer = Metal)  (render thread)
```

All code lives in `mc_legacy_forge/src/main/java/dev/graft/legacy/shaders/` (shared, Minecraft-free) except the
version glue in `mc<version>/src/main/java/dev/graft/legacy/shaders/` (`Hooks`, `ShaderTransformer`, `GuiShaderPacks`,
`GuiShaderOptions`), present for 1.8.9, 1.12.2 and 1.7.10, plus each version's `Terrain` (1.8.9/1.12.2 native chunk
buffers). Each version's `GraftCorePlugin` registers its `ShaderTransformer` after `GlTransformer` and `VideoTransformer`.

## Status (phase 2)

| Milestone | State | Notes |
|---|---|---|
| 1. Chunk vertex format | done (1.8.9, 1.12.2), partial (1.7.10) | 56-byte layout with normal / `mc_midTexCoord` / `at_tangent` / `mc_Entity` while a pack is on; `block.properties` ids incl. `id:meta`, `name:meta`, `name:prop=value`; 1.7.10 gets per-face normals only |
| 2. Per-program directives | done | `blend.<program>[.<buffer>]`, `alphaTest.<program>`, `scale.<program>`, `size.buffer.<buffer>`, `<buffer>MipmapEnabled`, `colortex8-15` |
| 3. Shadow pass | done (shadowcomp: n/a) | entities + block entities (`shadowEntities` / `shadowBlockEntities` / `shadowPlayer`), translucent split (`shadowtex0` vs `shadowtex1`, `shadowTranslucent`), `prepare*`; `shadowcomp` exists only as compute shaders in the tested packs (not supported) |
| 4. Per-draw uniforms | done | `entityId`, `blockEntityId`, `entityColor`, `renderStage`, hand depth, `centerDepthSmooth`, `heldItemId(2)`, `atlasSize`, `bossBattle`...; `gbuffers_hand_water` not split |
| 5. Performance | done (unmeasured on GPU) | pipeline prewarm on the loader thread, `MTLBinaryArchive`, one-pass clears, allocation-free draw path |
| 6. 1.12.2 / 1.7.10 glue | done (compiles + verified on SRG classes) | 1.7.10 terrain = display lists (see *1.7.10*) |
| 7. Anisotropic filtering | done | `GL_TEXTURE_MAX_ANISOTROPY` in MGL samplers, Video Settings option |
| 8. OptiFine guard | done | with OptiFine installed the transformers stay out and the shaders screen says why |

Nothing of this has run in a game yet (no launches allowed; the Metal compiler service was also down): see *What to
test in-game first*.

| Class | Role |
|---|---|
| `ShaderPack` | Folder or zip (any nesting depth of `shaders/`), file cache, `#include` expansion (relative and `/absolute`, cycle-safe), the conditional pass used for configuration, standard macros (`MC_VERSION 10809`, `MC_GL_VERSION`, `MC_OS_MAC`, render stages, extension macros...) injected right after `#version`. |
| `Options` | Option discovery by the documented rules: `#define X` / `//#define X` switches (only when the same file has `#ifdef/#ifndef X`), `#define X v // [a b c]` choices, the recognized `const` settings; ambiguous defaults disable an option; values are written into source lines during expansion; profiles (`profile.X`, `!OPT`, `OPT:v`, `profile.Y`, `!program.Z`). Values persist in `shaderpacks/<pack>.txt`. |
| `PackConfig` | `shaders.properties` after the conditional pass (standard + option macros); unconditioned copy for profiles/screens; custom `uniform.*`/`variable.*` in file order. |
| `ProgramSet` | Programs of a dimension: `worldN/` is used exclusively when present (as documented), `program.X.enabled` conditions, profile-disabled programs, OptiFine's fallback table (`gbuffers_water → gbuffers_terrain → gbuffers_textured_lit → ...`). |
| `Directives` | `/* DRAWBUFFERS:… */`, `/* RENDERTARGETS: … */`, `const` buffer formats/clears/clear colors, shadow settings, old `/* SHADOWRES:… */` style, samplers used, vertex attributes used. Scanned on the active `#if` branches with comments kept. |
| `Expr` | One expression engine for `#if`, `program.X.enabled` and custom uniforms (C precedence, `defined`, functions `if/clamp/smooth/frac/in/between/vec3…`, `name.x` element access). |
| `PackCompiler` | Translation with OptiFine's attribute slots (`mc_Entity` 10, `mc_midTexCoord` 11, `at_tangent` 12) and a compatibility retry: a standard uniform used without a declaration (packs written for newer OptiFine) gets declared and the program is translated again. |
| `ShaderPipeline` | Runtime: async loading, render targets, stage → program binding, uniforms, shadow pass, deferred/composite/final chain. |
| `Uniforms` | Standard uniforms (per frame), sun/moon/shadow-light positions, matrices + inverses + previous, smoothed eye brightness / wetness, custom uniforms. |
| `OptionScreens` | Version-independent options UI model (screens, sub-screens, `<profile>`, `*`, `<empty>`, columns, lang labels, tooltips, prefix/suffix). |

## Render stages → gbuffers programs (1.8.9)

Stages are tagged without touching the renderer's logic: `ShaderTransformer` redirects the **Profiler section calls**
in `EntityRenderer.renderWorldPass` (1.7.10: `renderWorld`), `renderCloudsCheck` and `RenderGlobal.renderEntities` to
`Hooks`, which forwards the section name to `ShaderPipeline.section`. Section names were checked in each version's
decompiled `EntityRenderer`/`RenderGlobal`: 1.12.2 has the 1.8.9 names (plus a second `entities` after `translucent`,
pass-1 entities); 1.7.10 calls translucent terrain `water` and Forge's last pass `FRenderLast` (both mapped).

| Profiler section | Program (then fallback chain) |
|---|---|
| `sky` | `gbuffers_skybasic`, `gbuffers_skytextured` while `GL_TEXTURE_2D` is on (sun, moon) |
| `clouds` | `gbuffers_clouds` |
| `terrain` (solid, cutout_mipped, cutout) | `gbuffers_terrain` |
| `entities`, `global` | `gbuffers_entities` (`gbuffers_basic` with texturing off: leashes, debug boxes) |
| `blockentities` | `gbuffers_block` |
| `outline` | `gbuffers_basic` |
| `destroyProgress` | `gbuffers_damagedblock` |
| `litParticles`, `forge_render_last` | `gbuffers_textured_lit` |
| `particles` | `gbuffers_textured` |
| `weather` (rain, snow, world border) | `gbuffers_weather` |
| `translucent` | copies depth → `depthtex1`/`depthtex2`, runs `deferred*`, then `gbuffers_water` |
| `hand` | `gbuffers_hand`: its depth clear is skipped (composites keep the world's depth) and the hand's clip-space z is scaled by `MC_HAND_DEPTH` (0.125, `MGL.handDepth`) so it stays in front of the world, as OptiFine documents |

`GL_TEXTURE_2D` on unit 0 is the only draw-time state that changes the program (MGL calls
`ShaderPipeline.textureToggled`), so program switches happen at state changes, never per draw.

Other hooks: the first `GlStateManager.clear` of the pass becomes the buffer clears (`colortex0` = fog color,
`colortex1` = white, others black, or the pack's `colortexNClear`/`ClearColor`); `glBindFramebuffer` of Minecraft's
framebuffer is redirected to the gbuffers while the world renders (`MGL.glBindFramebuffer`), so code that rebinds the
main framebuffer mid-frame (entity outlines, mods) still lands in the gbuffers.

## GL state mapping

Graft's GL layer already turns fixed-function state into what pack GLSL expects (`MetalGlsl`): `gl_ModelViewMatrix`,
`gl_ProjectionMatrix`, `gl_NormalMatrix`, `gl_TextureMatrix[]`, `gl_Fog`, `gl_Color`, `gl_MultiTexCoord0/1`, `ftransform()`
become members of one std140 block filled from MGL's current state per draw; the fixed alpha test is appended to every
fragment shader (`mc_AlphaFunc/Ref`). Blend, depth, cull and color mask stay GL state → Metal pipeline/depth state.
Pack overrides live on the program object (`Programs.setOverrides`), so they apply to every draw of that program without
touching GL state: `blend.<program>` replaces the blend of all attachments, `blend.<program>.<buffer>` that attachment's
(per-attachment blend in the Metal pipeline descriptor), `alphaTest.<program>` replaces `mc_AlphaFunc/Ref`.

## Render targets

* `colortex0-15` (`gcolor…gaux4` = 0-7), each two Metal textures (A/B, "main"/"alt"); only buffers a program writes or
  samples are allocated. MGL framebuffers take 16 color attachments and `glDrawBuffers` up to `COLOR_ATTACHMENT15`;
  a pass still has at most 8 (Metal's limit), which is what `DRAWBUFFERS`/`RENDERTARGETS` select. `colortex8-15`
  sample from units 16-23 (MGL now tracks 24 binding units).
* `size.buffer.<buffer>=<w> <h>` (pixels, or fractions when not whole numbers; option macros substituted) for buffers
  only deferred/composite programs draw; passes writing them use that viewport. `scale.<program>=<s> [<x> <y>]` scales
  and offsets a composite's viewport.
* `const bool <buffer>MipmapEnabled = true` allocates the buffer with a full mip chain (trilinear sampler) and
  regenerates its mipmaps right before each deferred/composite program that asked for them. Formats from `const int colortexNFormat` / `/* GAUX4FORMAT */` mapped to Metal formats
  (`RGBA16F`, `RGB10_A2`, `R11F_G11F_B10F`, `R32F`...; integer/snorm formats fall back to the nearest float/unorm format
  MGL supports); `gdepth` becomes `RGBA32F` when sampled with no format given, as documented.
* gbuffers write **main** through one framebuffer for the whole frame (draw buffers switch per program, no pass change
  unless the draw-buffer set changes). Deferred/composite read main, write alt, then flip what they wrote
  (`flip.<program>.<buffer>=false` honoured).
* `depthtex0` is the gbuffers' depth attachment; `depthtex1`/`depthtex2` are copies (blit) taken before translucent.
* Shadow: `shadowtex0` (depth), `shadowtex1` (copy), `shadowcolor0/1`; `shadowHardwareFiltering[0/1]` → compare
  samplers. Size `shadowMapResolution`.
* Samplers bind by name to OptiFine's documented units (gbuffers: 0 atlas, 1 lightmap, 2 normals, 3 specular, 4/5 shadow,
  7-10 gaux, 13/14 shadowcolor, 15 noisetex; composite: 0-3, 7-10 colortex, 6/11/12 depth...). Units 2-15 are free in
  vanilla, so they are bound once per frame (and after deferred), not per draw. `texture.noise` and
  `texture.<stage>.<unit>` 2D images from the pack are loaded (with `.mcmeta` blur/clamp).
* Metal allows 16 sampler states per stage; packs can declare more samplers (Complementary: up to 20). The translator
  puts shadow samplers first, and from binding 15 on SPIRV-Cross gives the texture a `constexpr` linear clamp-to-edge
  sampler inside the shader (`BOUND_SAMPLERS` in `shaders.c`), so no sampler slot is needed and MGL skips binding one.
  (A first attempt with separate textures + a `#define name sampler2D(...)` broke packs whose locals shadow sampler
  names, e.g. `float specular`.)

## Shadow pass

Runs at the start of the `sky` section (camera matrices captured there), before any gbuffers:

* `shadowModelView` = translate(0,0,-100) · rotX(90°) · rotZ(−360°·shadowAngle) · rotX(sunPathRotation), snapped to
  `shadowIntervalSize`; `shadowProjection` = ortho(±`shadowDistance`, 0.05…256) (perspective with `shadowMapFov`).
* Terrain comes straight from Graft's per-chunk Metal buffers (`Terrain.drawShadow`): no re-meshing, no display lists,
  one indexed draw per chunk layer, chunks culled in light space against the ortho box (`inShadowView`).
* Then entities and block entities: the version module runs `RenderGlobal.renderEntities` again with an `ICamera` that
  culls against the shadow box (render pass 0, the first-person player added). `Hooks.renderEntity/renderTileEntity`
  (redirected calls inside `renderEntities`) drop what `shadowEntities`, `shadowBlockEntities`, `shadowPlayer` exclude.
  The entities set their state through `GlStateManager` (1.8.9/1.12.2), so its cache stays in step with MGL; the
  terrain parts use MGL state pushed and popped around them.
* `shadowtex1` is copied after the opaque part; the translucent layer is drawn after it (into `shadowtex0` and
  `shadowcolor*`), as documented (`shadowTranslucent=false` skips it).
* `prepare*` programs run right after the shadow pass (full-screen, before the gbuffers).
* `shadowcomp*`: in the tested packs these are compute shaders (`.csh`), which the Metal path does not translate.

## Composite chain

`deferred*` (at `translucent`), then after the hand `composite*` and `final` as full-screen quads into
Minecraft's framebuffer (a built-in copy shader when the pack has no `final`). State is saved and restored with
`glPushAttrib` / matrix stacks, texture units 0-1 and the active unit are restored for `GlStateManager`'s cache.

## Uniforms

Per frame: `worldTime`, `worldDay`, `moonPhase`, `frameCounter`, `frameTime`, `frameTimeCounter`, `sunAngle`,
`shadowAngle`, `rainStrength`, `wetness` (half-lives), `aspectRatio`, `viewWidth/Height`, `near`, `far`,
`sunPosition`, `moonPosition`, `shadowLightPosition`, `upPosition`, `cameraPosition`, `previousCameraPosition`,
`gbufferModelView(+Inverse)`, `gbufferProjection(+Inverse)`, `gbufferPrevious*`, `shadowModelView/Projection(+Inverse)`,
`eyeAltitude`, `eyeBrightness(+Smooth)`, `isEyeInWater`, `nightVision`, `blindness`, `screenBrightness`, `hideGUI`,
`heldItemId`, `heldBlockLightValue`, `fogColor`, `skyColor`, custom uniforms (`biome`, `temperature`, `rainfall`).
Also `atlasSize` / `terrainTextureSize` (blocks atlas from MGL), `terrainIconSize`, `centerDepthSmooth` (depth at the
screen centre read back asynchronously at the end of each frame, `centerDepthHalflife` smoothing), `heldItemId2` /
`heldBlockLightValue2` (1.12.2 off-hand), `bossBattle` (1.7.10/1.8.9 boss bar name; 1.12.2: dragon only),
`cameraPositionInt/Fract`, `nightVision` (fades over the last 10 s like the game), `blindness` (fades over 1 s).
`heldItemId` uses `item.properties`, entity ids `entity.properties` (1.8-era names like `PigZombie` are mapped to registry
names), block entity ids `block.properties`.

Per draw (`ShaderPipeline.setEntity/setBlockEntity`, written into the bound program's block only when the value
changes): `entityId`, `entityColor` (hurt/death red, creeper flash white), `blockEntityId`, `renderStage`
(`MC_RENDER_STAGE_*` of the section).

Not provided: `playerMood` (0), `BIOME_*`/`is_*` names in custom expressions (0), `fogMode`/`fogStart/End` uniforms
(packs read `gl_Fog`), `blendFunc`, Iris-only uniforms.

A program copies the frame's values into its block the first time it is bound in a frame (type-correct int/float/mat
writes), so a stage switch costs one `glUseProgram` and, once per frame, a few hundred bytes of writes.

## Pack selection and options UI

`.minecraft/shaderpacks/` (zips and folders), choice in `config/graft-shaders.properties`. Video Settings → Shaders...
(`VideoScreens.shadersScreenFactory`, registered by `ShaderTransformer`) opens `GuiShaderPacks` (OFF / packs / Shader
Options... / Open Folder). `GuiShaderOptions` follows the pack's `screen=` layout with sub-screens, profile cycling,
lang labels and tooltips; right-click = previous value, shift-click = default. Done saves `<pack>.txt` and reloads.

## Performance design

* **Nothing compiles on the render thread.** Pack loading, option application, GLSL → MSL and Metal library compilation
  run on one low-priority loader thread; the game renders vanilla until the pack is ready, then the render thread only
  wraps the compiled functions into program objects (`Programs.createProgram`).
* **Disk cache** of translations: `graft/shadercache/<sha1(vs, fs, attributes, translator version)>.msl` (MSL +
  reflection). A pack that was loaded once skips glslang/SPIRV-Cross completely; Metal's own source cache then makes
  the library compile cheap too.
* **Pipeline states prewarmed on the loader thread** (`MGL.prewarm`): chunk terrain for the terrain, water (the game's
  translucent blend or the pack's override) and shadow programs, full-screen quads for prepare/deferred/composite/final,
  with the pack's target formats; installed into the program's pipeline cache with the program. Entity/particle
  pipelines (vertex layouts vary) still compile on first use.
* **`MTLBinaryArchive`** at `graft/shadercache/pipelines.metallib` (`MGL.openPipelineArchive`): every pipeline descriptor
  is created with the archive attached and added to it afterwards; the archive is written (temp file + rename) when
  new pipelines were added and none for 5 s. A second launch finds its pipelines there instead of compiling. An archive
  from another OS/GPU driver is replaced. `-Dgraft.pipelineArchive=false` turns it off.
* **Few render-target switches:** one gbuffers framebuffer per frame (draw buffers change only when the program's
  `DRAWBUFFERS` differ), one render pass per composite program, and the gbuffer clears as **one render pass per 8
  buffers** with per-attachment clear colors (`MGL.clearBuffers`; was one pass per buffer), depth in the same pass.
* **No per-draw allocation:** program choice happens at stage/state changes, frame uniforms once per program per frame,
  texture units 2-23 once per frame. Fixed in phase 2 on MGL's hot path (also vanilla): the program block and client
  vertex data were copied with a `ByteBuffer.duplicate()`/JNI `wrap` per draw (now `memcpy` into the ring buffer), every
  draw built a `String` pipeline key (now a fast path when the state equals the previous draw's), `glUniform*` went
  through varargs arrays (now direct). `Objc`'s selector cache is a `ConcurrentHashMap` now (the loader thread and chunk
  workers call into Metal too).
* **Chunk vertex expansion off the render thread:** the 28→56-byte conversion and its Metal buffer happen on the chunk
  worker that uploads (`TerrainFormat.extend`, one reusable scratch buffer per worker, `MGL.newBufferAnyThread`):
  ~28 ns per vertex (≈0.9 ms for a 32k-vertex chunk layer, `./gradlew :mc1_8_9:shaderBench`), 0 bytes allocated.
* **Shadow pass** honours `shadowMapResolution`/`shadowDistance` (and profiles that lower them), culls chunks in light
  space and reuses the chunk buffers already on the GPU. Next: `shadowDistanceRenderMul`, an option to cap shadow
  resolution/distance for low-end Macs, and rendering the shadow map every other frame when the sun barely moves.
* **Async pack loading** also covers dimension changes (`world-1/` programs load while the old set keeps rendering).

## Changes to Graft's legacy GL emulation

* `MetalGlsl`: public `link`/`linkCached`/`checkMsl`; no static state during linking (thread-safe with one loader);
  shadow samplers first; `gl_VertexID`/`gl_InstanceID` → `gl_VertexIndex`/`gl_InstanceIndex`; `texture(...)` calls in
  `#version 120` code are kept as calls (only the identifier `texture` is renamed).
* `shaders.c`: constexpr samplers for sampler bindings ≥ 15.
* `Programs`: `compileFunctions` (any thread, own autorelease pool), `createProgram`, `uniformType`.
* `MGL`: queries `GL_ACTIVE_TEXTURE`, `GL_DRAW/READ_FRAMEBUFFER_BINDING`, `GL_CURRENT_PROGRAM`,
  `GL_COLOR_CLEAR_VALUE`; sampler slots above 15 are not bound; `GL_TEXTURE_2D` toggles on unit 0 notify the pipeline;
  Minecraft's framebuffer is redirected to the gbuffers while a pack renders.
* Phase 2: 16 color attachments per framebuffer and 24 texture units; `TEXTURE_MAX_ANISOTROPY` (sampler key + descriptor);
  `clearBuffers` (per-attachment clear colors); program blend/alpha overrides and per-attachment blend in pipelines;
  `handDepth`; `prewarm` + binary archive; `textureSize`; `newBufferAnyThread`; allocation-free copies (see above).

## Chunk vertices for packs (1.8.9 / 1.12.2)

While a pack is installed, `ShaderPipeline.extendedTerrain` is on and the version module rebuilds all chunks
(`loadRenderers`) whenever that or the block table changes (`terrainGeneration`); with no pack the 28-byte vanilla
path is untouched.

* `RenderChunk.rebuildChunk`'s `BlockRendererDispatcher.renderBlock` call goes through `Hooks.renderBlock`, which tags
  the vertices the block added with its pack id in the (unused) high bytes of the lightmap shorts (`TerrainFormat.tag`)
  — the id travels with the quad through translucent sorting.
* `Terrain.upload` (chunk worker) expands the buffer to the 56-byte layout MGL reads for programs: normal (per quad, from
  the diagonals), `mc_midTexCoord` (quad's uv centre), `at_tangent` (from position/uv deltas, w = handedness against
  `cross(tangent, normal)`, shorts ×32767 like OptiFine's), `mc_Entity` (x = id, y/z = 0); the lightmap tag is removed.
* Ids: `block.properties` after the conditional pass (`IdMap`), first matching line wins; entries
  `[ns:]name[:meta,…][:prop=value,…]` or legacy `id[:meta]`. The table (id << 4 | meta → pack id) is built on the
  loader thread from the block registry and every metadata's state properties; unmapped blocks keep their numeric id
  (what 1.8-era packs test). Names the version lacks (1.13+ names) never match; a property the block lacks is ignored
  (so `leaves:waterlogged=false` still maps 1.8 leaves).

## 1.7.10

1.7.10 chunks are display lists of Tessellator draws (Graft records them with MGL's generic arrays; there is no
`Terrain` class). With a pack on: `Tessellator.draw` fills per-face normals for quads that have none (`Hooks.tessellatorDraw`),
the shadow pass replays the chunk display lists (`WorldRenderer.glRenderList` + pass, translated by `posXMinus - camera`,
culled against the shadow box), and `beginWorld`/`endWorld` sit just inside Graft's render-scale calls in
`EntityRenderer.renderWorld`. `mc_Entity`, `mc_midTexCoord` and `at_tangent` are **not** produced on 1.7.10 (constants
in the shader): waving plants, water detection by id and normal/parallax maps do not work there yet. Doing it needs a
Tessellator-level attribute stream inside display lists.

## OptiFine

With OptiFine installed (`VideoTransformer.optifine()`), every version's `ShaderTransformer` leaves the game classes
alone, `ShaderPipeline.disabledReason` is set (the pipeline never starts) and the shaders screen shows it. (Graft's
Video Settings, where the Shaders... button lives, already step aside for OptiFine's.)

## Compatibility harness

`./gradlew :mc1_8_9:shaderCompat` (packs from `devtools/fetch-shaderpacks.sh` in `build/shaderpacks-test/`, gitignored,
never committed). For every pack × Minecraft version (`MC_VERSION` 10710, 10809, 11202) × dimension (`world0`,
`world-1`, `world1`) × profile (default + every `profile.*` in world0) × program, the real path runs: options →
includes → macros → `PackCompiler` → glslang → SPIRV-Cross → Metal compiler. MSL is checked with the Xcode toolchain's
offline compiler (`metal -fsyntax-only`, 64 files per invocation, 4 at a time), so the harness puts no load on the
`MTLCompilerService` (thousands of runtime compiles made it stop responding in phase 1); `-Pcompat.runtime=true` uses
the runtime compiler. Options: `-Pcompat.pack=BSL -Pcompat.program=final -Pcompat.versions=10809 -Pcompat.verbose=true
-Pcompat.dump=/tmp/dump`. It starts with a self-test of the preprocessor, expressions, directives, `block.properties`
matching and the chunk vertex expansion.

Also headless: `./gradlew :mc<version>:shaderVerify` runs that version's `ShaderTransformer` on the SRG-named
Minecraft classes the game runs (loom's SRG jar; 1.7.10: forgeBin remapped with SpecialSource), checks that every hook
landed and runs ASM's `CheckClassAdapter` on the result; `./gradlew :mc1_8_9:shaderBench` times the chunk vertex
expansion and checks it allocates nothing.

See the end of this file for the latest matrix.

## What to test in-game first

1. 1.8.9, Sildur's Basic (no shadows), then BSL: stage tags, one-pass clears, composite/final, GUI. Watch the log for
   `pipeline states prewarmed` and `pipeline failed`.
2. Terrain normals/tangents/ids: BSL waving plants and water (ids 10008...), shading of block faces in all six
   directions (normal sign), normal-mapped resource packs (tangent handedness).
3. Shadows: entities and the player in the shadow map, water shadow tint (`shadowcolor0`), `shadowtex0` vs `shadowtex1`
   in Complementary.
4. Hand: not clipping into walls, composites that test hand depth (`< 0.56`).
5. Second launch with the same pack: no pipeline hitches (archive found), `graft/shadercache/pipelines.metallib` present.
6. Video Settings → Anisotropic Filtering 16x on terrain at grazing angles.
7. 1.12.2 and 1.7.10 the same way (1.7.10: no block ids / tangents, flat per-face normals).

## Implemented vs stubbed

Implemented (compiles, headless-tested where possible): everything in the status table, plus phase 1 (pack loading,
includes, options + profiles + persistence, conditionals, program conditions, fallbacks, world folders, directives,
translation with cache, async loading, render targets, stage tagging, uniforms incl. custom uniforms, terrain shadows,
deferred/composite/final, noise and custom 2D textures, `sunPathRotation` on the vanilla sun/moon, selection + options
screens).

Not yet: geometry and compute shaders (`shadowcomp*.csh`), `gbuffers_hand_water` (the hand renders once, with
`gbuffers_hand`), entities outside the camera's visible chunks in the shadow map (the game's entity lists per visible
chunk are reused), raw (`.dat`) and resource-pack textures, 1.7.10 chunk attributes beyond normals, `playerMood`,
biome/entity-state constants in custom uniform expressions, a GPU timing of a BSL frame.

## Next milestones

1. In-game runs (see *What to test in-game first*), then fix what they show.
2. GPU timing of a BSL frame (Metal capture) and a shadow-distance / resolution cap for low-end Macs; shadow map every
   other frame when the sun barely moves.
3. 1.7.10 chunk attributes (`mc_Entity`, `mc_midTexCoord`, `at_tangent`) through the Tessellator.
4. `gbuffers_hand_water`, compute shaders (`shadowcomp`), raw/resource-pack textures.

## Latest compatibility matrix

2026-10-11 (phase 2), `./gradlew :mc1_8_9:shaderCompat`: every pack × `MC_VERSION` 10710/10809/11202 × world0/world-1/
world1 × every profile in world0, MSL checked with the toolchain's offline `metal -fsyntax-only` (the runtime compiler
service was down). Cells: programs that compiled to Metal / programs present. This proves translation, not rendering:
nothing has run in-game yet. Phase 1 (1.8.9 macros only) was 1285/1285.

| pack                                          | pass |  all |  rate  |
|---|---|---|---|
| BSLClassic_v10.0.zip                          |  516 |  516 | 100.0% |
| BSL_v10.1.8.zip                               |  516 |  516 | 100.0% |
| ComplementaryReimagined_r5.9.3.zip            |  846 |  846 | 100.0% |
| ComplementaryUnbound_r5.9.3.zip               |  846 |  846 | 100.0% |
| MakeUp-UltraFast-9.5g.zip                     |  777 |  777 | 100.0% |
| Sildurs_Basic_Shaders_v2.7_Fancy.zip          |  156 |  156 | 100.0% |
| Sildurs_Enhanced_Default_v1.20_Fancy.zip      |  108 |  108 | 100.0% |
| Sildurs_Vibrant_Shaders_v2.02_Extreme.zip     |   90 |   90 | 100.0% |
| TOTAL                                         | 3855 | 3855 | 100.0% |

| program | BSLClassic | BSL | ComplementaryReimagined | ComplementaryUnbound | MakeUp-UltraFast | SildursBasic | SildursEnhancedDefault | SildursVibrant |
|---|---|---|---|---|---|---|---|---|
| composite | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | ok 24 | ok 15 | - |
| composite1 | - | - | ok 33 | ok 33 | ok 33 | - | ok 15 | - |
| composite2 | - | - | - | - | ok 33 | - | - | - |
| composite3 | - | - | ok 33 | ok 33 | - | - | - | - |
| composite4 | ok 24 | ok 24 | ok 33 | ok 33 | - | - | - | - |
| composite5 | ok 24 | ok 24 | ok 33 | ok 33 | - | - | - | - |
| composite6 | - | - | ok 33 | ok 33 | - | - | - | - |
| composite7 | - | - | ok 24 | ok 24 | - | - | - | - |
| deferred | - | - | - | - | ok 33 | - | - | ok 9 |
| deferred1 | ok 24 | ok 24 | ok 33 | ok 33 | - | - | - | - |
| final | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | ok 24 | ok 15 | ok 9 |
| gbuffers_armor_glint | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | ok 9 |
| gbuffers_basic | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | ok 24 | ok 15 | ok 9 |
| gbuffers_beaconbeam | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | - |
| gbuffers_block | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | - |
| gbuffers_clouds | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | ok 18 | ok 9 | ok 9 |
| gbuffers_damagedblock | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | - |
| gbuffers_entities | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | - |
| gbuffers_entities_glowing | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | - |
| gbuffers_hand | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | - |
| gbuffers_hand_water | - | - | - | - | ok 33 | - | - | - |
| gbuffers_skybasic | ok 21 | ok 21 | ok 33 | ok 33 | ok 33 | ok 18 | ok 9 | ok 9 |
| gbuffers_skytextured | ok 21 | ok 21 | ok 33 | ok 33 | ok 33 | ok 24 | ok 15 | ok 9 |
| gbuffers_spidereyes | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | - |
| gbuffers_terrain | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | - |
| gbuffers_textured | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | ok 24 | ok 15 | ok 9 |
| gbuffers_water | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | ok 9 |
| gbuffers_weather | ok 24 | ok 24 | ok 33 | ok 33 | ok 33 | - | - | ok 9 |
| prepare | - | - | - | - | ok 33 | - | - | - |
| shadow | ok 18 | ok 18 | ok 30 | ok 30 | ok 18 | - | - | - |

How it got there: the first run passed 63% (834/1318). Fixed since: Metal warnings were reported as errors by the
harness; Complementary declares up to 20 samplers (Metal: 16 sampler slots) → constexpr samplers; MakeUp calls
`texture()` from `#version 120` code and uses `gl_VertexID`; `gbuffers_line` (1.16+, needs `projectionMatrix`) is not a
1.8.9 program; Sildur's Vibrant's End `deferred` uses `fogColor` without declaring it → implicit standard-uniform
declarations in `PackCompiler`. Not covered yet: Chocapic13 and SEUS Renewed (no Modrinth release; add their zips to
`build/shaderpacks-test/` by hand), `shadowcomp*.csh` compute programs (not part of 1.8.9's pipeline).
