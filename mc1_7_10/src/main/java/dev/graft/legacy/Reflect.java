package dev.graft.legacy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Minecraft 1.7.10 members by MCP name (dev) or SRG name (production). */
final class Reflect {
	static Method method(String cls, String mcp, String srg, Class<?>... args) throws ReflectiveOperationException {
		Class<?> c = Class.forName(cls);
		for (String n : new String[] {mcp, srg}) {
			try {
				Method m = c.getDeclaredMethod(n, args);
				m.setAccessible(true);
				return m;
			} catch (NoSuchMethodException ignored) {
			}
		}
		throw new NoSuchMethodException(cls + "." + mcp);
	}

	static Field field(String cls, String mcp, String srg) throws ReflectiveOperationException {
		Class<?> c = Class.forName(cls);
		for (String n : new String[] {mcp, srg}) {
			try {
				Field f = c.getDeclaredField(n);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new NoSuchFieldException(cls + "." + mcp);
	}

	private Reflect() {
	}
}
