package dev.graft.legacy.shaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Expressions of shader packs: preprocessor conditions (#if in GLSL and .properties files), program.X.enabled conditions
 * and custom uniforms / variables (functions, vectors, smooth()). Parsed once into a tree; values are double[] (a scalar
 * is length 1, booleans are 0/1). Unknown identifiers evaluate to 0 like the C preprocessor does.
 */
public final class Expr {
	/** Identifier lookup: a value, or null if unknown. */
	public interface Env {
		double[] get(String name);

		/** Seconds since the last frame (for smooth()); 0 outside of custom uniforms. */
		float frameTime();
	}

	private static final double[] ZERO = {0};
	private final Node root;

	private Expr(Node root) {
		this.root = root;
	}

	public static Expr parse(String text) {
		Parser p = new Parser(tokenize(text));
		Node n = p.ternary();
		if (p.i != p.t.size()) {
			throw new IllegalArgumentException("unexpected '" + p.t.get(p.i) + "' in: " + text);
		}
		return new Expr(n);
	}

	public double[] eval(Env env) {
		return root.eval(env);
	}

	public double scalar(Env env) {
		double[] v = root.eval(env);
		return v.length > 0 ? v[0] : 0;
	}

	public boolean bool(Env env) {
		return scalar(env) != 0;
	}

	// ---------------------------------------------------------------- preprocessor conditions

	/** Evaluates a #if / #elif condition against the macros defined so far (name -> replacement text, "" if empty). */
	public static boolean condition(String text, final Map<String, String> macros) {
		text = stripComments(text);
		// defined X / defined(X) first, so the names are not expanded as values
		StringBuffer b = new StringBuffer();
		java.util.regex.Matcher m = DEFINED.matcher(text);
		while (m.find()) {
			String name = m.group(1) != null ? m.group(1) : m.group(2);
			m.appendReplacement(b, macros.containsKey(name) ? " 1 " : " 0 ");
		}
		m.appendTail(b);
		final int[] depth = {0};
		try {
			return parse(b.toString()).bool(new Env() {
				@Override
				public double[] get(String name) {
					String v = macros.get(name);
					if (v == null || v.trim().isEmpty() || depth[0] > 16) {
						return v != null && v.trim().isEmpty() ? new double[] {1} : ZERO;
					}
					depth[0]++;
					try {
						return parse(v).eval(this);
					} catch (RuntimeException e) {
						return ZERO;
					} finally {
						depth[0]--;
					}
				}

				@Override
				public float frameTime() {
					return 0;
				}
			});
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static final java.util.regex.Pattern DEFINED = java.util.regex.Pattern.compile("\\bdefined\\s*(?:\\(\\s*(\\w+)\\s*\\)|(\\w+))");

	static String stripComments(String s) {
		int c = s.indexOf("//");
		if (c >= 0) {
			s = s.substring(0, c);
		}
		return s.replaceAll("/\\*.*?\\*/", " ");
	}

	// ---------------------------------------------------------------- tokens

	private static List<String> tokenize(String s) {
		List<String> out = new ArrayList<String>();
		int i = 0, n = s.length();
		while (i < n) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c) || c == '\\') {
				i++;
			} else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
				int j = i;
				if (c == '0' && i + 1 < n && (s.charAt(i + 1) == 'x' || s.charAt(i + 1) == 'X')) {
					j += 2;
					while (j < n && Character.digit(s.charAt(j), 16) >= 0) {
						j++;
					}
				} else {
					while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
						j++;
					}
					if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
						j++;
						if (j < n && (s.charAt(j) == '+' || s.charAt(j) == '-')) {
							j++;
						}
						while (j < n && Character.isDigit(s.charAt(j))) {
							j++;
						}
					}
				}
				int end = j;
				while (j < n && "fFuUlL".indexOf(s.charAt(j)) >= 0) {
					j++;
				}
				out.add(s.substring(i, end));
				i = j;
			} else if (Character.isJavaIdentifierStart(c)) {
				int j = i + 1;
				while (j < n && (Character.isJavaIdentifierPart(s.charAt(j)) || s.charAt(j) == '.' && j + 1 < n && Character.isJavaIdentifierPart(s.charAt(j + 1)))) {
					j++;
				}
				out.add(s.substring(i, j));
				i = j;
			} else {
				String two = i + 1 < n ? s.substring(i, i + 2) : "";
				if (two.equals("&&") || two.equals("||") || two.equals("==") || two.equals("!=") || two.equals("<=") || two.equals(">=")
					|| two.equals("<<") || two.equals(">>")) {
					out.add(two);
					i += 2;
				} else {
					out.add(String.valueOf(c));
					i++;
				}
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- tree

	private abstract static class Node {
		abstract double[] eval(Env env);
	}

	private static final class Num extends Node {
		final double[] v;

		Num(double v) {
			this.v = new double[] {v};
		}

		@Override
		double[] eval(Env env) {
			return v;
		}
	}

	private static final class Var extends Node {
		final String name;

		Var(String name) {
			this.name = name;
		}

		@Override
		double[] eval(Env env) {
			double[] v = env.get(name);
			if (v != null) {
				return v;
			}
			// vector element: name.x / name.r / matrix name.row.col
			int dot = name.indexOf('.');
			if (dot > 0) {
				double[] base = env.get(name.substring(0, dot));
				String rest = name.substring(dot + 1);
				if (base != null) {
					int idx = "xyzw".indexOf(rest) >= 0 && rest.length() == 1 ? "xyzw".indexOf(rest)
						: "rgba".indexOf(rest) >= 0 && rest.length() == 1 ? "rgba".indexOf(rest) : -1;
					if (idx < 0 && rest.matches("\\d\\.\\d")) {
						idx = (rest.charAt(2) - '0') * 4 + rest.charAt(0) - '0';
					}
					return idx >= 0 && idx < base.length ? new double[] {base[idx]} : ZERO;
				}
			}
			return ZERO;
		}
	}

	private static final class Unary extends Node {
		final char op;
		final Node a;

		Unary(char op, Node a) {
			this.op = op;
			this.a = a;
		}

		@Override
		double[] eval(Env env) {
			double[] v = a.eval(env);
			double[] r = new double[v.length];
			for (int i = 0; i < v.length; i++) {
				r[i] = op == '!' ? v[i] == 0 ? 1 : 0 : op == '-' ? -v[i] : op == '~' ? ~(long)v[i] : v[i];
			}
			return r;
		}
	}

	private static final class Binary extends Node {
		final String op;
		final Node a, b;

		Binary(String op, Node a, Node b) {
			this.op = op;
			this.a = a;
			this.b = b;
		}

		@Override
		double[] eval(Env env) {
			if (op.equals("&&")) {
				return a.eval(env)[0] != 0 && b.eval(env)[0] != 0 ? ONE : ZERO;
			}
			if (op.equals("||")) {
				return a.eval(env)[0] != 0 || b.eval(env)[0] != 0 ? ONE : ZERO;
			}
			double[] x = a.eval(env), y = b.eval(env);
			int n = Math.max(x.length, y.length);
			double[] r = new double[n];
			for (int i = 0; i < n; i++) {
				double p = x[Math.min(i, x.length - 1)], q = y[Math.min(i, y.length - 1)];
				r[i] = apply(op, p, q);
			}
			return r;
		}

		private static double apply(String op, double p, double q) {
			switch (op) {
				case "+": return p + q;
				case "-": return p - q;
				case "*": return p * q;
				case "/": return q != 0 ? p / q : 0;
				case "%": return q != 0 ? p % q : 0;
				case "<": return p < q ? 1 : 0;
				case ">": return p > q ? 1 : 0;
				case "<=": return p <= q ? 1 : 0;
				case ">=": return p >= q ? 1 : 0;
				case "==": return p == q ? 1 : 0;
				case "!=": return p != q ? 1 : 0;
				case "&": return (long)p & (long)q;
				case "|": return (long)p | (long)q;
				case "^": return (long)p ^ (long)q;
				case "<<": return (long)p << (long)q;
				case ">>": return (long)p >> (long)q;
				default: return 0;
			}
		}
	}

	private static final double[] ONE = {1};

	private static final class Ternary extends Node {
		final Node c, a, b;

		Ternary(Node c, Node a, Node b) {
			this.c = c;
			this.a = a;
			this.b = b;
		}

		@Override
		double[] eval(Env env) {
			return c.eval(env)[0] != 0 ? a.eval(env) : b.eval(env);
		}
	}

	private static final class Call extends Node {
		final String fn;
		final Node[] args;
		// smooth(): state per call site
		double smoothed = Double.NaN;

		Call(String fn, Node[] args) {
			this.fn = fn;
			this.args = args;
		}

		private double s(Env env, int i) {
			return i < args.length ? args[i].eval(env)[0] : 0;
		}

		@Override
		double[] eval(Env env) {
			switch (fn) {
				case "sin": return v(Math.sin(s(env, 0)));
				case "cos": return v(Math.cos(s(env, 0)));
				case "asin": return v(Math.asin(s(env, 0)));
				case "acos": return v(Math.acos(s(env, 0)));
				case "tan": return v(Math.tan(s(env, 0)));
				case "atan": return v(Math.atan(s(env, 0)));
				case "atan2": return v(Math.atan2(s(env, 0), s(env, 1)));
				case "torad": return v(Math.toRadians(s(env, 0)));
				case "todeg": return v(Math.toDegrees(s(env, 0)));
				case "abs": return v(Math.abs(s(env, 0)));
				case "floor": return v(Math.floor(s(env, 0)));
				case "ceil": return v(Math.ceil(s(env, 0)));
				case "exp": return v(Math.exp(s(env, 0)));
				case "log": return v(Math.log(s(env, 0)));
				case "sqrt": return v(Math.sqrt(s(env, 0)));
				case "round": return v(Math.round(s(env, 0)));
				case "signum": return v(Math.signum(s(env, 0)));
				case "frac": {
					double x = s(env, 0);
					return v(x - Math.floor(x));
				}
				case "pow": return v(Math.pow(s(env, 0), s(env, 1)));
				case "fmod": {
					double x = s(env, 0), y = s(env, 1);
					return v(y != 0 ? x - Math.floor(x / y) * y : 0);
				}
				case "min": case "max": {
					double r = s(env, 0);
					for (int i = 1; i < args.length; i++) {
						r = fn.equals("min") ? Math.min(r, s(env, i)) : Math.max(r, s(env, i));
					}
					return v(r);
				}
				case "clamp": return v(Math.max(s(env, 1), Math.min(s(env, 2), s(env, 0))));
				case "lerp": {
					double k = s(env, 0);
					return v(s(env, 1) + (s(env, 2) - s(env, 1)) * k);
				}
				case "random": return v(Math.random());
				case "if": case "ifb": {
					int i = 0;
					for (; i + 1 < args.length; i += 2) {
						if (s(env, i) != 0) {
							return args[i + 1].eval(env);
						}
					}
					return i < args.length ? args[i].eval(env) : ZERO;
				}
				case "between": {
					double x = s(env, 0);
					return x >= s(env, 1) && x <= s(env, 2) ? ONE : ZERO;
				}
				case "equals": return Math.abs(s(env, 0) - s(env, 1)) <= s(env, 2) ? ONE : ZERO;
				case "in": {
					double x = s(env, 0);
					for (int i = 1; i < args.length; i++) {
						if (x == s(env, i)) {
							return ONE;
						}
					}
					return ZERO;
				}
				case "vec2": case "vec3": case "vec4": {
					double[] r = new double[fn.charAt(3) - '0'];
					for (int i = 0; i < r.length; i++) {
						r[i] = s(env, Math.min(i, args.length - 1));
					}
					return r;
				}
				case "smooth": {
					// smooth([id,] value [, fadeIn [, fadeOut]]): only the 4-argument form carries an id (state is per call site anyway).
					// Exponential approach that covers 99% of the distance in the fade time (seconds).
					int o = args.length >= 4 ? 1 : 0;
					double target = s(env, o), in = args.length > o + 1 ? s(env, o + 1) : 1, out = args.length > o + 2 ? s(env, o + 2) : in;
					if (Double.isNaN(smoothed)) {
						smoothed = target;
					} else {
						double t = target > smoothed ? in : out;
						smoothed += (target - smoothed) * (t <= 0 ? 1 : 1 - Math.pow(0.01, env.frameTime() / t));
					}
					return v(smoothed);
				}
				default: return ZERO;
			}
		}

		private static double[] v(double x) {
			return new double[] {x};
		}
	}

	// ---------------------------------------------------------------- parser (C precedence)

	private static final class Parser {
		final List<String> t;
		int i;

		Parser(List<String> t) {
			this.t = t;
		}

		private String peek() {
			return i < t.size() ? t.get(i) : "";
		}

		private boolean eat(String s) {
			if (peek().equals(s)) {
				i++;
				return true;
			}
			return false;
		}

		Node ternary() {
			Node c = binary(0);
			if (eat("?")) {
				Node a = ternary();
				if (!eat(":")) {
					throw new IllegalArgumentException("expected ':'");
				}
				return new Ternary(c, a, ternary());
			}
			return c;
		}

		private static final String[][] LEVELS = {{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="}, {"<", ">", "<=", ">="}, {"<<", ">>"},
			{"+", "-"}, {"*", "/", "%"}};

		Node binary(int level) {
			if (level == LEVELS.length) {
				return unary();
			}
			Node a = binary(level + 1);
			outer:
			while (true) {
				for (String op : LEVELS[level]) {
					if (peek().equals(op)) {
						i++;
						a = new Binary(op, a, binary(level + 1));
						continue outer;
					}
				}
				return a;
			}
		}

		Node unary() {
			String p = peek();
			if (p.equals("!") || p.equals("-") || p.equals("+") || p.equals("~")) {
				i++;
				return new Unary(p.charAt(0), unary());
			}
			return primary();
		}

		Node primary() {
			if (i >= t.size()) {
				throw new IllegalArgumentException("unexpected end");
			}
			String s = t.get(i++);
			if (s.equals("(")) {
				Node n = ternary();
				if (!eat(")")) {
					throw new IllegalArgumentException("expected ')'");
				}
				return n;
			}
			char c = s.charAt(0);
			if (Character.isDigit(c) || c == '.') {
				return new Num(s.startsWith("0x") || s.startsWith("0X") ? Long.parseLong(s.substring(2), 16) : Double.parseDouble(s));
			}
			if (!Character.isJavaIdentifierStart(c)) {
				throw new IllegalArgumentException("unexpected '" + s + "'");
			}
			if (s.equals("true")) {
				return new Num(1);
			}
			if (s.equals("false")) {
				return new Num(0);
			}
			if (s.equals("pi")) {
				return new Num(Math.PI);
			}
			if (eat("(")) {
				List<Node> args = new ArrayList<Node>();
				if (!eat(")")) {
					do {
						args.add(ternary());
					} while (eat(","));
					if (!eat(")")) {
						throw new IllegalArgumentException("expected ')' after arguments of " + s);
					}
				}
				return new Call(s, args.toArray(new Node[0]));
			}
			return new Var(s);
		}
	}

	/** Env over a fixed map (tests, program conditions). */
	public static Env env(final Map<String, double[]> values) {
		return new Env() {
			@Override
			public double[] get(String name) {
				return values.get(name);
			}

			@Override
			public float frameTime() {
				return 0;
			}
		};
	}

	static Map<String, double[]> map() {
		return new HashMap<String, double[]>();
	}
}
