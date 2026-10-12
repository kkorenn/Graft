package dev.graft.legacy.shaders;

import dev.graft.legacy.MetalGlsl;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Headless compatibility harness: every shader pack in a folder x Minecraft version (MC_VERSION 10710, 10809, 11202) x
 * dimension x option profile x program goes through the real pipeline (pack loader, options, preprocessor, Graft's
 * GLSL -> MSL translator, Metal compiler) and is reported as pass/fail with the first error. Run with
 * ./gradlew :mc1_8_9:shaderCompat (packs in build/shaderpacks-test). MSL is checked with the Xcode toolchain's offline
 * compiler in batches (no load on the MTLCompilerService); -Dcompat.runtime=true uses the runtime compiler instead.
 * Optional system properties: compat.pack=<substring>, compat.program=<name>, compat.dump=<dir> (writes failing sources),
 * compat.versions=10809,11202.
 */
public final class ShaderCompat {
	public static void main(String[] args) throws Exception {
		selfTest();
		File dir = new File(args.length > 0 ? args[0] : "build/shaderpacks-test");
		File[] packs = dir.listFiles();
		if (packs == null || packs.length == 0) {
			System.out.println("No shader packs in " + dir + " (run devtools/fetch-shaderpacks.sh). Self-test OK.");
			return;
		}
		Arrays.sort(packs);
		String onlyPack = System.getProperty("compat.pack"), onlyProgram = System.getProperty("compat.program"), dump = System.getProperty("compat.dump");
		String[] versions = System.getProperty("compat.versions", "10710,10809,11202").split(",");
		StringBuilder summary = new StringBuilder();
		Map<String, Map<String, int[]>> byProgram = new TreeMap<String, Map<String, int[]>>(); // program -> pack -> {pass, all}
		List<String> packNames = new ArrayList<String>();
		int totalPass = 0, totalAll = 0;
		for (File f : packs) {
			if (onlyPack != null && !f.getName().contains(onlyPack)) {
				continue;
			}
			ShaderPack pack = ShaderPack.open(f);
			if (pack == null) {
				continue;
			}
			long t0 = System.nanoTime();
			String shortName = f.getName().replaceAll("\\.zip$", "").replaceAll("[_-][vr]?[0-9][0-9.]*.*$", "").replace("Shaders", "").replace("_", "");
			packNames.add(shortName);
			int pass = 0, all = 0;
			Map<String, List<String>> reasons = new TreeMap<String, List<String>>();
			Map<String, String> matrix = new LinkedHashMap<String, String>();
			List<String[]> runs = new ArrayList<String[]>(); // {version, world, profile}
			ShaderPack.Environment env = new ShaderPack.Environment();
			PackConfig base = new PackConfig(pack, merged(env.macros(), pack.options.macros(new Options.Values())));
			for (String v : versions) {
				for (String w : new String[] {"world0", "world-1", "world1"}) {
					runs.add(new String[] {v.trim(), w, null});
				}
				for (String p : base.profiles()) {
					runs.add(new String[] {v.trim(), "world0", p});
				}
			}
			List<Object[]> pending = new ArrayList<Object[]>(); // {key, shortName, vertex MSL, fragment MSL, source}
			for (String[] run0 : runs) {
				String[] run = {run0[1], run0[2]};
				env.mcVersion = Integer.parseInt(run0[0]);
				env.world = run[0];
				Options.Values values = new Options.Values();
				if (run[1] != null) {
					pack.options.applyProfile(run[1], base.raw, values);
				}
				Map<String, String> macros = env.macros();
				PackConfig config = new PackConfig(pack, merged(macros, pack.options.macros(values)));
				ProgramSet set = new ProgramSet(pack, run[0], macros, values, config);
				for (ProgramSet.Source s : set.all()) {
					if (onlyProgram != null && !s.name.equals(onlyProgram)) {
						continue;
					}
					String key = run0[0] + ":" + run[0] + (run[1] != null ? "@" + run[1] : "") + "/" + s.name;
					String err = s.geometry != null ? "geometry shader (unsupported)" : null;
					if (err == null) {
						MetalGlsl.Linked l = PackCompiler.translate(s, null);
						err = l.error;
						if (err == null) {
							if (Boolean.getBoolean("compat.runtime")) {
								String e = MetalGlsl.checkMsl(l.vertexMsl);
								if (e == null) {
									e = MetalGlsl.checkMsl(l.fragmentMsl);
								}
								err = e == null ? null : "metal: " + e;
							} else {
								pending.add(new Object[] {key, s, l.vertexMsl, l.fragmentMsl});
								continue; // counted once the batch is checked
							}
						}
					}
					all++;
					Map<String, int[]> row = byProgram.get(s.name);
					if (row == null) {
						byProgram.put(s.name, row = new HashMap<String, int[]>());
					}
					int[] cell = row.get(shortName);
					if (cell == null) {
						row.put(shortName, cell = new int[2]);
					}
					cell[1]++;
					if (err == null) {
						cell[0]++;
						pass++;
						matrix.put(key, "ok");
					} else {
						String r = firstLine(err);
						matrix.put(key, "FAIL");
						List<String> ks = reasons.get(r);
						if (ks == null) {
							reasons.put(r, ks = new ArrayList<String>());
						}
						ks.add(key);
						if (dump != null) {
							File d = new File(dump, pack.name);
							d.mkdirs();
							write(new File(d, key.replace('/', '_') + ".vsh"), s.vertex);
							write(new File(d, key.replace('/', '_') + ".fsh"), s.fragment);
							write(new File(d, key.replace('/', '_') + ".err"), err);
						}
					}
				}
			}
			// offline Metal compiler: every program of the pack in a few batched invocations
			Map<String, String> metal = checkOffline(pending);
			for (Object[] pd : pending) {
				String key = (String)pd[0];
				ProgramSet.Source s = (ProgramSet.Source)pd[1];
				String err = metal.get(key);
				all++;
				Map<String, int[]> row = byProgram.get(s.name);
				if (row == null) {
					byProgram.put(s.name, row = new HashMap<String, int[]>());
				}
				int[] cell = row.get(shortName);
				if (cell == null) {
					row.put(shortName, cell = new int[2]);
				}
				cell[1]++;
				if (err == null) {
					cell[0]++;
					pass++;
					matrix.put(key, "ok");
				} else {
					String r = firstLine("metal: " + err);
					matrix.put(key, "FAIL");
					List<String> ks = reasons.get(r);
					if (ks == null) {
						reasons.put(r, ks = new ArrayList<String>());
					}
					ks.add(key);
					if (dump != null) {
						File d = new File(dump, pack.name);
						d.mkdirs();
						write(new File(d, key.replace('/', '_').replace(':', '_') + ".vsh"), s.vertex);
						write(new File(d, key.replace('/', '_').replace(':', '_') + ".fsh"), s.fragment);
						write(new File(d, key.replace('/', '_').replace(':', '_') + ".err"), err);
					}
				}
			}
			long ms = (System.nanoTime() - t0) / 1000000;
			System.out.printf("%n== %s: %d/%d programs pass (%d dimension/profile runs, %d options, %d ms)%n", pack.name, pass, all, runs.size(),
				pack.options.all.size(), ms);
			for (Map.Entry<String, List<String>> e : reasons.entrySet()) {
				List<String> ks = e.getValue();
				System.out.printf("  FAIL x%d: %s%n      e.g. %s%n", ks.size(), e.getKey(), ks.subList(0, Math.min(4, ks.size())));
			}
			if (Boolean.getBoolean("compat.verbose")) {
				for (Map.Entry<String, String> e : matrix.entrySet()) {
					System.out.println("  " + e.getValue() + "  " + e.getKey());
				}
			}
			summary.append(String.format("| %-45s | %4d | %4d | %5.1f%% |%n", pack.name, pass, all, all == 0 ? 0 : 100.0 * pass / all));
			totalPass += pass;
			totalAll += all;
			pack.close();
		}
		System.out.println();
		System.out.println("| pack                                          | pass |  all |  rate  |");
		System.out.print(summary);
		System.out.printf("| %-45s | %4d | %4d | %5.1f%% |%n", "TOTAL", totalPass, totalAll, totalAll == 0 ? 0 : 100.0 * totalPass / totalAll);
		// program x pack: passing / compiled (over dimensions and profiles), "-" = the pack has no such program
		System.out.println();
		StringBuilder h = new StringBuilder("| program |");
		for (String p : packNames) {
			h.append(' ').append(p).append(" |");
		}
		System.out.println(h);
		for (Map.Entry<String, Map<String, int[]>> e : byProgram.entrySet()) {
			StringBuilder r = new StringBuilder("| ").append(e.getKey()).append(" |");
			for (String p : packNames) {
				int[] c = e.getValue().get(p);
				r.append(' ').append(c == null ? "-" : c[0] == c[1] ? "ok " + c[0] : c[0] + "/" + c[1]).append(" |");
			}
			System.out.println(r);
		}
	}

	/**
	 * Checks pending programs ({key, source, vertex MSL, fragment MSL}) with the Xcode toolchain's offline Metal compiler
	 * in syntax-only mode, 64 files per invocation on a few threads. Returns key -> first errors (absent = passed).
	 */
	private static Map<String, String> checkOffline(List<Object[]> pending) throws Exception {
		final Map<String, String> out = java.util.Collections.synchronizedMap(new HashMap<String, String>());
		if (pending.isEmpty()) {
			return out;
		}
		Process px = new ProcessBuilder("xcrun", "-f", "metal").redirectErrorStream(true).start();
		final String metal = new String(readAll(px.getInputStream()), "UTF-8").trim();
		px.waitFor();
		final File dir = java.nio.file.Files.createTempDirectory("graft-compat").toFile();
		final Map<String, String> fileToKey = new HashMap<String, String>();
		List<File> files = new ArrayList<File>();
		for (int i = 0; i < pending.size(); i++) {
			Object[] pd = pending.get(i);
			for (int st = 0; st < 2; st++) {
				File f = new File(dir, i + (st == 0 ? "v" : "f") + ".metal");
				java.nio.file.Files.write(f.toPath(), ((String)pd[2 + st]).getBytes("UTF-8"));
				fileToKey.put(f.getName(), (String)pd[0]);
				files.add(f);
			}
		}
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
		List<java.util.concurrent.Future<?>> jobs = new ArrayList<java.util.concurrent.Future<?>>();
		for (int i = 0; i < files.size(); i += 64) {
			final List<File> batch = files.subList(i, Math.min(files.size(), i + 64));
			jobs.add(pool.submit(new java.util.concurrent.Callable<Void>() {
				@Override
				public Void call() throws Exception {
					List<String> cmd = new ArrayList<String>(Arrays.asList(metal, "-std=macos-metal2.1", "-fsyntax-only", "-w"));
					for (File f : batch) {
						cmd.add(f.getName());
					}
					Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
					String text = new String(readAll(p.getInputStream()), "UTF-8");
					p.waitFor();
					for (String line : text.split("\n")) {
						int c = line.indexOf(".metal:");
						if (c > 0 && line.contains(" error: ")) {
							String key = fileToKey.get(line.substring(0, c + 6));
							if (key != null && !out.containsKey(key)) {
								out.put(key, line.substring(c + 7).replaceFirst("^\\d+:\\d+: ", "program_source: "));
							}
						}
					}
					return null;
				}
			}));
		}
		for (java.util.concurrent.Future<?> j : jobs) {
			j.get();
		}
		pool.shutdown();
		for (File f : files) {
			f.delete();
		}
		dir.delete();
		return out;
	}

	private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] b = new byte[8192];
		for (int n; (n = in.read(b)) > 0; ) {
			out.write(b, 0, n);
		}
		return out.toByteArray();
	}

	private static Map<String, String> merged(Map<String, String> a, Map<String, String> b) {
		Map<String, String> m = new LinkedHashMap<String, String>(a);
		m.putAll(b);
		return m;
	}

	private static String firstLine(String err) {
		if (err.startsWith("metal:")) { // Metal lists warnings too
			for (String line : err.split("\n")) {
				if (line.contains("error:")) {
					return "metal: " + line.replaceAll("program_source:\\d+:\\d+:", "").trim();
				}
			}
		}
		for (String line : err.split("\n")) {
			String l = line.trim();
			if (l.startsWith("ERROR:") || l.contains("error:") || l.startsWith("vertex") || l.startsWith("fragment") || l.startsWith("metal") || l.startsWith("geometry")) {
				if (l.equals("vertex:") || l.equals("fragment:")) {
					continue;
				}
				return l.replaceAll("\\d+:\\d+:", "").replaceAll("program_source:\\d+:\\d+:", "").trim();
			}
		}
		return err.split("\n")[0];
	}

	private static void write(File f, String s) {
		try {
			java.nio.file.Files.write(f.toPath(), s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (java.io.IOException ignored) {
		}
	}

	/** Pack-independent checks of the preprocessor, expressions and option rewriting. */
	static void selfTest() {
		Map<String, String> m = new HashMap<String, String>();
		m.put("A", "2");
		m.put("B", "");
		check(Expr.condition("A == 2 && defined B", m), "cond 1");
		check(!Expr.condition("defined(C) || A > 3", m), "cond 2");
		check(Expr.condition("MC_VERSION >= 0 && !defined C", m), "cond 3 (unknown = 0)");
		String out = ShaderPack.conditionals("#define X 1\n#if X == 1\nyes\n#else\nno\n#endif\n#ifdef Y\nno2\n#elif X\nyes2\n#endif\n", new HashMap<String, String>());
		check(out.contains("yes\n") && out.contains("yes2") && !out.contains("no"), "conditionals: " + out);
		Map<String, double[]> vars = new HashMap<String, double[]>();
		vars.put("sunAngle", new double[] {0.25});
		vars.put("cameraPosition", new double[] {1, 70, 3});
		check(Math.abs(Expr.parse("frac(sunAngle - 0.033333333) * 2").scalar(Expr.env(vars)) - 0.4333) < 1e-3, "expr frac");
		check(Expr.parse("if(cameraPosition.y >= 63.0, 1, 0)").scalar(Expr.env(vars)) == 1, "expr if/.y");
		check(Expr.parse("clamp(1.0 - (abs(abs(sunAngle - 0.5) - 0.25) - 0.23) * 100.0, 0.0, 1.0)").scalar(Expr.env(vars)) == 1, "expr clamp");
		check(Expr.parse("in(2, 1, 2, 3) && !between(5, 0, 4)").bool(Expr.env(vars)), "expr in/between");
		check(Expr.parse("vec3(1, 2, 3)").eval(Expr.env(vars)).length == 3, "expr vec3");
		check(Directives.parse("/* DRAWBUFFERS:024 */\nconst int colortex2Format = RGBA16F;\n/* const int gaux1Format = RGB10_A2; */", null).drawBuffers.length == 3, "drawbuffers");
		Directives d = Directives.parse("/* RENDERTARGETS: 0,7 */\nconst int colortex2Format = RGBA16F;\n/* const int gaux1Format = RGB10_A2; */", null);
		check(d.drawBuffers[1] == 7 && "RGBA16F".equals(d.formats.get(2)) && "RGB10_A2".equals(d.formats.get(4)), "rendertargets/formats");
		IdMap ids = IdMap.parse("block.10008=water flowing_water\nblock.31=38:6 38:7 83\nblock.10005=tallgrass:1 double_plant:half=lower\n"
			+ "block.10009=leaves:waterlogged=false\nblock.10011=leaves:waterlogged=true\nblock.20=short_grass minecraft:stone:1,2\n", "block");
		check(ids.get("minecraft:water", 9, 0, IdMap.NO_PROPS) == 10008, "idmap name");
		check(ids.get("minecraft:red_flower", 38, 6, IdMap.NO_PROPS) == 31 && ids.get("minecraft:red_flower", 38, 2, IdMap.NO_PROPS) == -1, "idmap id:meta");
		check(ids.get("minecraft:reeds", 83, 0, IdMap.NO_PROPS) == 31, "idmap id");
		Map<String, String> lower = new HashMap<String, String>();
		lower.put("half", "lower");
		Map<String, String> upper = new HashMap<String, String>();
		upper.put("half", "upper");
		check(ids.get("minecraft:double_plant", 175, 0, lower) == 10005 && ids.get("minecraft:double_plant", 175, 8, upper) == -1, "idmap props");
		check(ids.get("minecraft:leaves", 18, 0, upper) == 10009, "idmap absent property ignored, first line wins");
		check(ids.get("minecraft:tallgrass", 31, 1, IdMap.NO_PROPS) == 10005 && ids.get("minecraft:stone", 1, 2, IdMap.NO_PROPS) == 20
			&& ids.get("minecraft:stone", 1, 0, IdMap.NO_PROPS) == -1, "idmap name:meta");
		check(IdMap.modernEntityName("CaveSpider").equals("minecraft:cave_spider") && IdMap.modernEntityName("PigZombie").equals("minecraft:zombified_piglin"),
			"entity names");
		terrainSelfTest();
		check(ShaderPipeline.numbers("REFLECTION_RES 0.25", java.util.Collections.singletonMap("REFLECTION_RES", "0.5"))[0] == 0.5f, "directive macros");
		System.out.println("Self-test OK");
	}

	/** One upward quad (y = 1) with uv along +x/+z: normal +y, tangent +x, mid uv, block id from the lightmap tag. */
	private static void terrainSelfTest() {
		java.nio.ByteBuffer src = java.nio.ByteBuffer.allocateDirect(4 * TerrainFormat.VANILLA).order(java.nio.ByteOrder.nativeOrder());
		float[][] v = {{0, 1, 0, 0, 0}, {0, 1, 1, 0, 1}, {1, 1, 1, 1, 1}, {1, 1, 0, 1, 0}}; // x y z u v (vanilla UP face order)
		for (int i = 0; i < 4; i++) {
			int o = i * TerrainFormat.VANILLA;
			src.putFloat(o, v[i][0]).putFloat(o + 4, v[i][1]).putFloat(o + 8, v[i][2]).putInt(o + 12, -1).putFloat(o + 16, v[i][3] / 16)
				.putFloat(o + 20, v[i][4] / 16).putShort(o + 24, (short)240).putShort(o + 26, (short)176);
		}
		TerrainFormat.tag(src, 0, 4, 10008);
		java.nio.ByteBuffer dst = java.nio.ByteBuffer.allocateDirect(4 * TerrainFormat.SHADER).order(java.nio.ByteOrder.nativeOrder());
		TerrainFormat.extend(src, dst, 4);
		int o = 2 * TerrainFormat.SHADER;
		check(dst.getFloat(o) == 1 && dst.getFloat(o + 8) == 1 && dst.getInt(o + 12) == -1, "terrain: position/color kept");
		check(dst.getShort(o + 24) == 240 && dst.getShort(o + 26) == 176, "terrain: lightmap without the tag");
		check(dst.get(o + 28) == 0 && dst.get(o + 29) == 127 && dst.get(o + 30) == 0, "terrain: normal up, got " + dst.get(o + 28) + "," + dst.get(o + 29) + "," + dst.get(o + 30));
		check(Math.abs(dst.getFloat(o + 32) - 0.5f / 16) < 1e-6 && Math.abs(dst.getFloat(o + 36) - 0.5f / 16) < 1e-6, "terrain: mid texcoord");
		check(dst.getShort(o + 40) == 32767 && dst.getShort(o + 42) == 0 && dst.getShort(o + 44) == 0, "terrain: tangent +x, got "
			+ dst.getShort(o + 40) + "," + dst.getShort(o + 42) + "," + dst.getShort(o + 44));
		check(Math.abs(dst.getShort(o + 46)) == 32767 && dst.getShort(o + 48) == 10008, "terrain: handedness and mc_Entity");
	}

	private static void check(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError("self-test failed: " + what);
		}
	}
}
