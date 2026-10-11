#include <metal_stdlib>
using namespace metal;

// OpenGL 1.x fixed function as 1.8.9 uses it: modulate/replace/interpolate texenv, per-vertex lighting with
// GL_COLOR_MATERIAL, alpha test, eye-distance fog. Must match the layouts written by MGL.

struct VIn {
	float4 pos [[attribute(0)]];
	float4 color [[attribute(1)]];
	float4 uv0 [[attribute(2)]];
	float4 uv1 [[attribute(3)]];
	float4 normal [[attribute(4)]];
};

struct VU {
	float4x4 mvp;
	float4x4 mv;
	float4x4 tm0;
	float4x4 tm1;
	float4 l0dir;
	float4 l1dir;
	float4 l0col;
	float4 l1col;
	float4 ambient;
	int lighting;
};

struct FU {
	float4 fogColor;
	float fogStart;
	float fogEnd;
	float fogDensity;
	int fogMode;
	int tex0;
	int tex1;
	int alphaFunc;
	float alphaRef;
	int env0;
	int env1;
	int comb0;
	int comb1;
	float4 envColor0;
	float4 envColor1;
	int tex2;
};

struct VOut {
	float4 pos [[position]];
	float4 color;
	float2 uv0;
	float2 uv1;
	float fogz;
};

vertex VOut vmain(VIn in [[stage_in]], constant VU& u [[buffer(0)]]) {
	VOut o;
	float4 p = u.mvp * in.pos;
	// GL clip space -> Metal: flip y so render targets keep GL's bottom-up row order, z from [-w,w] to [0,w].
	p.y = -p.y;
	p.z = (p.z + p.w) * 0.5;
	o.pos = p;
	float4 c = in.color;
	if (u.lighting != 0) {
		float3 n = (u.mv * float4(in.normal.xyz, 0.0)).xyz;
		float len = length(n);
		n = len > 0.0 ? n / len : float3(0.0, 0.0, 1.0);
		float3 l = u.ambient.rgb + max(dot(n, u.l0dir.xyz), 0.0) * u.l0col.rgb + max(dot(n, u.l1dir.xyz), 0.0) * u.l1col.rgb;
		c = float4(clamp(c.rgb * l, 0.0, 1.0), c.a);
	}
	o.color = c;
	o.uv0 = (u.tm0 * float4(in.uv0.xy, 0.0, 1.0)).xy;
	o.uv1 = (u.tm1 * float4(in.uv1.xy, 0.0, 1.0)).xy;
	o.fogz = abs((u.mv * in.pos).z);
	return o;
}

struct TIn {
	float3 pos [[attribute(0)]];
	float4 color [[attribute(1)]];
	float2 uv0 [[attribute(2)]];
	float2 light [[attribute(3)]];
};

// Native terrain: chunk-relative vertices plus a per-chunk camera-relative offset (indexed by base_instance).
vertex VOut tvert(TIn in [[stage_in]], constant VU& u [[buffer(0)]], const device float4* offsets [[buffer(2)]], uint chunk [[base_instance]]) {
	float4 p4 = float4(in.pos + offsets[chunk].xyz, 1.0);
	VOut o;
	float4 p = u.mvp * p4;
	p.y = -p.y;
	p.z = (p.z + p.w) * 0.5;
	o.pos = p;
	o.color = in.color;
	o.uv0 = (u.tm0 * float4(in.uv0, 0.0, 1.0)).xy;
	o.uv1 = (u.tm1 * float4(in.light, 0.0, 1.0)).xy;
	o.fogz = abs((u.mv * p4).z);
	return o;
}

static float4 texenv(int mode, int comb, float4 env, float4 c, float4 t) {
	switch (mode) {
		case 0x1E01: return t; // GL_REPLACE
		case 0x2101: return float4(mix(c.rgb, t.rgb, t.a), c.a); // GL_DECAL
		case 0x0104: return float4(c.rgb + t.rgb, c.a * t.a); // GL_ADD
		case 0x8570: // GL_COMBINE: 1.8.9 only uses modulate and constant-colour interpolate (hurt/ignite tint)
			if (comb == 0x8575) return float4(mix(c.rgb, env.rgb, env.a), c.a);
			return c * t;
		default: return c * t; // GL_MODULATE
	}
}

static bool alphaPass(int func, float a, float ref) {
	switch (func) {
		case 0x0200: return false;
		case 0x0201: return a < ref;
		case 0x0202: return a == ref;
		case 0x0203: return a <= ref;
		case 0x0204: return a > ref;
		case 0x0205: return a != ref;
		case 0x0206: return a >= ref;
		default: return true;
	}
}

fragment float4 fmain(VOut in [[stage_in]], constant FU& u [[buffer(0)]],
		texture2d<float> t0 [[texture(0)]], sampler s0 [[sampler(0)]],
		texture2d<float> t1 [[texture(1)]], sampler s1 [[sampler(1)]]) {
	float4 c = in.color;
	if (u.tex0 != 0) c = texenv(u.env0, u.comb0, u.envColor0, c, t0.sample(s0, in.uv0));
	if (u.tex1 != 0) c = texenv(u.env1, u.comb1, u.envColor1, c, t1.sample(s1, in.uv1));
	// Unit 2 (hurt/fire flash): unit 1 holds the tint, so 1.8.9 re-applies the lightmap (unit 1's texture) here.
	if (u.tex2 != 0) c.rgb *= t1.sample(s1, in.uv1).rgb;
	if (!alphaPass(u.alphaFunc, c.a, u.alphaRef)) discard_fragment();
	if (u.fogMode != 0) {
		float z = in.fogz;
		float f;
		if (u.fogMode == 0x2601) f = (u.fogEnd - z) / (u.fogEnd - u.fogStart);
		else if (u.fogMode == 0x0800) f = exp(-u.fogDensity * z);
		else f = exp(-(u.fogDensity * z) * (u.fogDensity * z));
		c.rgb = mix(u.fogColor.rgb, c.rgb, clamp(f, 0.0, 1.0));
	}
	return c;
}

struct BOut {
	float4 pos [[position]];
	float2 uv;
};

// Fullscreen triangle; the backing texture is in GL row order, so v=0 is the bottom of the screen.
vertex BOut bvert(uint vid [[vertex_id]]) {
	float2 p = float2((vid << 1) & 2, vid & 2);
	BOut o;
	o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0);
	o.uv = p;
	return o;
}

fragment float4 bfrag(BOut in [[stage_in]], texture2d<float> t [[texture(0)]]) {
	constexpr sampler s(filter::nearest);
	return t.sample(s, in.uv);
}
