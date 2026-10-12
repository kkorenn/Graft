package dev.graft.legacy.shaders;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * block.properties / item.properties / entity.properties of a pack (after the conditional pass): "<prefix>.<id>=<entries>"
 * where an entry is [namespace:]name[:meta,...][:property=value,...] or the legacy numeric "id[:meta,...]". The first
 * matching line wins. Names the running version does not have (1.13+ names on 1.8.9) simply never match; properties a
 * block does not have are ignored, so "leaves:waterlogged=false" still maps 1.8.9 leaves.
 */
public final class IdMap {
	private static final class Rule {
		int id, order, num = -1;
		String name;
		int[] metas;
		List<String[]> props;
	}

	private final Map<String, List<Rule>> byName = new HashMap<String, List<Rule>>();
	private final Map<Integer, List<Rule>> byNum = new HashMap<Integer, List<Rule>>();
	public int rules;

	/** The pack's file (e.g. "block.properties", prefix "block"), or null if it has none. */
	public static IdMap load(ShaderPack pack, String file, String prefix, Map<String, String> macros) {
		String text = pack.read(file);
		return text == null ? null : parse(ShaderPack.conditionals(text, new java.util.LinkedHashMap<String, String>(macros)), prefix);
	}

	public static IdMap parse(String text, String prefix) {
		IdMap m = new IdMap();
		int order = 0;
		for (String line : text.replace("\\\r\n", " ").replace("\\\n", " ").split("\n")) {
			String l = line.trim();
			int eq = l.indexOf('=');
			if (l.isEmpty() || l.charAt(0) == '#' || l.charAt(0) == '!' || eq < 0 || !l.startsWith(prefix + ".")) {
				continue;
			}
			int id;
			try {
				id = Integer.parseInt(l.substring(prefix.length() + 1, eq).trim());
			} catch (NumberFormatException e) {
				continue;
			}
			for (String tok : l.substring(eq + 1).trim().split("\\s+")) {
				Rule r = rule(tok.toLowerCase());
				if (r == null) {
					continue;
				}
				r.id = id;
				r.order = order++;
				m.rules++;
				if (r.name != null) {
					List<Rule> rs = m.byName.get(r.name);
					if (rs == null) {
						m.byName.put(r.name, rs = new ArrayList<Rule>());
					}
					rs.add(r);
				} else {
					List<Rule> rs = m.byNum.get(r.num);
					if (rs == null) {
						m.byNum.put(r.num, rs = new ArrayList<Rule>());
					}
					rs.add(r);
				}
			}
		}
		return m;
	}

	private static Rule rule(String tok) {
		if (tok.isEmpty()) {
			return null;
		}
		String[] p = tok.split(":");
		Rule r = new Rule();
		int i;
		if (isNumber(p[0])) {
			r.num = Integer.parseInt(p[0]);
			i = 1;
		} else if (p.length > 1 && !p[1].contains("=") && !isMetas(p[1])) {
			r.name = p[0] + ":" + p[1];
			i = 2;
		} else {
			r.name = "minecraft:" + p[0];
			i = 1;
		}
		for (; i < p.length; i++) {
			if (p[i].contains("=")) {
				int e = p[i].indexOf('=');
				if (r.props == null) {
					r.props = new ArrayList<String[]>();
				}
				r.props.add(new String[] {p[i].substring(0, e), "," + p[i].substring(e + 1) + ","});
			} else if (isMetas(p[i])) {
				List<Integer> ms = new ArrayList<Integer>();
				for (String s : p[i].split(",")) {
					int d = s.indexOf('-');
					int a = Integer.parseInt(d > 0 ? s.substring(0, d) : s), b = d > 0 ? Integer.parseInt(s.substring(d + 1)) : a;
					for (int k = a; k <= b && k - a < 64; k++) {
						ms.add(k);
					}
				}
				r.metas = new int[ms.size()];
				for (int k = 0; k < r.metas.length; k++) {
					r.metas[k] = ms.get(k);
				}
			} else {
				return null;
			}
		}
		return r;
	}

	private static boolean isNumber(String s) {
		if (s.isEmpty()) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			if (!Character.isDigit(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isMetas(String s) {
		return !s.isEmpty() && s.matches("[0-9]+(-[0-9]+)?(,[0-9]+(-[0-9]+)?)*");
	}

	/**
	 * The pack id of a block/item/entity: registry name ("minecraft:stone"), legacy numeric id (-1 if none), metadata or
	 * damage (-1 = any) and state properties (name -> value, may be empty); -1 if no line matches.
	 */
	public int get(String name, int num, int meta, Map<String, String> props) {
		Rule best = first(name != null ? byName.get(name.toLowerCase()) : null, null, meta, props);
		best = first(num >= 0 ? byNum.get(num) : null, best, meta, props);
		return best == null ? -1 : best.id;
	}

	private static Rule first(List<Rule> rs, Rule best, int meta, Map<String, String> props) {
		if (rs != null) {
			for (Rule r : rs) {
				if ((best == null || r.order < best.order) && matches(r, meta, props)) {
					return r;
				}
			}
		}
		return best;
	}

	private static boolean matches(Rule r, int meta, Map<String, String> props) {
		if (r.metas != null && meta >= 0) {
			boolean any = false;
			for (int m : r.metas) {
				any |= m == meta;
			}
			if (!any) {
				return false;
			}
		}
		if (r.props != null && props != null) {
			for (String[] kv : r.props) {
				String v = props.get(kv[0]);
				if (v != null && !kv[1].contains("," + v.toLowerCase() + ",")) {
					return false;
				}
			}
		}
		return true;
	}

	public static final Map<String, String> NO_PROPS = Collections.emptyMap();

	private static final Map<String, String> ENTITY_NAMES = new HashMap<String, String>();

	static {
		String[] pairs = {"XPOrb", "experience_orb", "PigZombie", "zombified_piglin", "EntityHorse", "horse", "LavaSlime", "magma_cube",
			"MushroomCow", "mooshroom", "Ozelot", "ocelot", "VillagerGolem", "iron_golem", "SnowMan", "snow_golem", "WitherBoss", "wither",
			"PrimedTnt", "tnt", "FallingSand", "falling_block", "MinecartRideable", "minecart", "ThrownPotion", "potion",
			"ThrownEnderpearl", "ender_pearl", "ThrownExpBottle", "experience_bottle", "FireworksRocketEntity", "firework_rocket",
			"EyeOfEnderSignal", "eye_of_ender", "Item", "item", "Arrow", "arrow", "Snowball", "snowball"};
		for (int i = 0; i < pairs.length; i += 2) {
			ENTITY_NAMES.put(pairs[i], pairs[i + 1]);
		}
	}

	/** Legacy EntityList name ("PigZombie", "CaveSpider") -> registry name entity.properties uses ("minecraft:zombified_piglin", "minecraft:cave_spider"). */
	public static String modernEntityName(String legacy) {
		String m = ENTITY_NAMES.get(legacy);
		if (m == null) {
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < legacy.length(); i++) {
				char c = legacy.charAt(i);
				if (Character.isUpperCase(c) && i > 0) {
					b.append('_');
				}
				b.append(Character.toLowerCase(c));
			}
			m = b.toString();
		}
		return "minecraft:" + m;
	}
}
