// GLSL (450, Vulkan rules) -> SPIR-V (glslang) -> MSL (SPIRV-Cross), with reflection of the shared uniform block.
// Same pipeline as Graft Shaders' MetalGlsl, done natively because Java 8 cannot load LWJGL 3's shaderc/spvc.
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <glslang/Include/glslang_c_interface.h>
#include <glslang/Public/resource_limits_c.h>
#include <spirv_cross_c.h>

// Binding scheme shared with MetalGlsl.java: set 0 binding 0 = mc_Uniforms (Metal buffer 28), binding 1 = mc_Offsets
// (buffer 29), binding 2 + i = sampler i (texture i, sampler i; from i = BOUND_SAMPLERS on a constexpr sampler instead).
#define UNIFORM_BUFFER 28
#define OFFSET_BUFFER 29
#define MAX_SAMPLERS 32
#define BOUND_SAMPLERS 15

typedef struct {
	char *data;
	size_t len, cap;
} Str;

static void put(Str *s, const char *t) {
	size_t n = strlen(t);
	if (s->len + n + 1 > s->cap) {
		s->cap = (s->len + n + 1) * 2;
		s->data = realloc(s->data, s->cap);
	}
	memcpy(s->data + s->len, t, n + 1);
	s->len += n;
}

static jstring finish(JNIEnv *env, Str *s) {
	jstring r = (*env)->NewStringUTF(env, s->data ? s->data : "");
	free(s->data);
	return r;
}

static int initialized;

// Runs only the preprocessor on a pack shader as OptiFine hands it over (#version 1xx compatibility), so the rewrite
// in MetalGlsl sees the active branch of every #if. Returns "ERR\n" + log or "OK\n" + code.
JNIEXPORT jstring JNICALL Java_dev_graft_legacy_Objc_glslPreprocess(JNIEnv *env, jclass c, jstring jsrc, jboolean fragment) {
	if (!initialized) {
		glslang_initialize_process();
		initialized = 1;
	}
	const char *src = (*env)->GetStringUTFChars(env, jsrc, NULL);
	Str out = {0};
	glslang_input_t in = {
		.language = GLSLANG_SOURCE_GLSL,
		.stage = fragment ? GLSLANG_STAGE_FRAGMENT : GLSLANG_STAGE_VERTEX,
		.client = GLSLANG_CLIENT_OPENGL,
		.client_version = GLSLANG_TARGET_OPENGL_450,
		.target_language = GLSLANG_TARGET_NONE,
		.code = src,
		.default_version = 110,
		.default_profile = GLSLANG_COMPATIBILITY_PROFILE,
		.messages = GLSLANG_MSG_ONLY_PREPROCESSOR_BIT | GLSLANG_MSG_RELAXED_ERRORS_BIT,
		.resource = glslang_default_resource(),
	};
	glslang_shader_t *shader = glslang_shader_create(&in);
	if (!glslang_shader_preprocess(shader, &in)) {
		put(&out, "ERR\n");
		put(&out, glslang_shader_get_info_log(shader));
	} else {
		put(&out, "OK\n");
		put(&out, glslang_shader_get_preprocessed_code(shader));
	}
	glslang_shader_delete(shader);
	(*env)->ReleaseStringUTFChars(env, jsrc, src);
	return finish(env, &out);
}

JNIEXPORT jstring JNICALL Java_dev_graft_legacy_Objc_glslToMsl(JNIEnv *env, jclass c, jstring jsrc, jboolean fragment) {
	if (!initialized) {
		glslang_initialize_process();
		initialized = 1;
	}
	const char *src = (*env)->GetStringUTFChars(env, jsrc, NULL);
	Str out = {0};
	glslang_stage_t stage = fragment ? GLSLANG_STAGE_FRAGMENT : GLSLANG_STAGE_VERTEX;
	glslang_input_t in = {
		.language = GLSLANG_SOURCE_GLSL,
		.stage = stage,
		.client = GLSLANG_CLIENT_VULKAN,
		.client_version = GLSLANG_TARGET_VULKAN_1_1,
		.target_language = GLSLANG_TARGET_SPV,
		.target_language_version = GLSLANG_TARGET_SPV_1_3,
		.code = src,
		.default_version = 450,
		.default_profile = GLSLANG_CORE_PROFILE,
		.messages = GLSLANG_MSG_SPV_RULES_BIT | GLSLANG_MSG_VULKAN_RULES_BIT,
		.resource = glslang_default_resource(),
	};
	glslang_shader_t *shader = glslang_shader_create(&in);
	glslang_shader_set_options(shader, GLSLANG_SHADER_AUTO_MAP_LOCATIONS);
	glslang_program_t *program = NULL;
	if (!glslang_shader_preprocess(shader, &in) || !glslang_shader_parse(shader, &in)) {
		put(&out, "ERR\n");
		put(&out, glslang_shader_get_info_log(shader));
		goto done;
	}
	program = glslang_program_create();
	glslang_program_add_shader(program, shader);
	if (!glslang_program_link(program, GLSLANG_MSG_SPV_RULES_BIT | GLSLANG_MSG_VULKAN_RULES_BIT)) {
		put(&out, "ERR\n");
		put(&out, glslang_program_get_info_log(program));
		goto done;
	}
	glslang_program_SPIRV_generate(program, stage);

	spvc_context ctx;
	spvc_parsed_ir ir;
	spvc_compiler comp;
	spvc_compiler_options opts;
	spvc_context_create(&ctx);
	if (spvc_context_parse_spirv(ctx, glslang_program_SPIRV_get_ptr(program), glslang_program_SPIRV_get_size(program), &ir) != SPVC_SUCCESS
		|| spvc_context_create_compiler(ctx, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, &comp) != SPVC_SUCCESS) {
		put(&out, "ERR\n");
		put(&out, spvc_context_get_last_error_string(ctx));
		spvc_context_destroy(ctx);
		goto done;
	}
	spvc_compiler_create_compiler_options(comp, &opts);
	spvc_compiler_options_set_uint(opts, SPVC_COMPILER_OPTION_MSL_VERSION, SPVC_MAKE_MSL_VERSION(2, 1, 0));
	spvc_compiler_options_set_bool(opts, SPVC_COMPILER_OPTION_MSL_ENABLE_BASE_INDEX_ZERO, SPVC_TRUE);
	spvc_compiler_install_compiler_options(comp, opts);
	SpvExecutionModel model = fragment ? SpvExecutionModelFragment : SpvExecutionModelVertex;
	spvc_msl_resource_binding b;
	spvc_msl_resource_binding_init(&b);
	b.stage = model;
	b.desc_set = 0;
	b.binding = 0;
	b.msl_buffer = UNIFORM_BUFFER;
	spvc_compiler_msl_add_resource_binding(comp, &b);
	b.binding = 1;
	b.msl_buffer = OFFSET_BUFFER;
	spvc_compiler_msl_add_resource_binding(comp, &b);
	for (int i = 0; i < MAX_SAMPLERS; i++) {
		spvc_msl_resource_binding_init(&b);
		b.stage = model;
		b.desc_set = 0;
		b.binding = 2 + i;
		b.msl_texture = i;
		b.msl_sampler = i;
		spvc_compiler_msl_add_resource_binding(comp, &b);
		if (i >= BOUND_SAMPLERS) {
			// Metal has 16 sampler slots per stage: later samplers sample with a linear clamp-to-edge sampler built into the shader
			spvc_msl_constexpr_sampler cs;
			spvc_msl_constexpr_sampler_init(&cs);
			cs.min_filter = SPVC_MSL_SAMPLER_FILTER_LINEAR;
			cs.mag_filter = SPVC_MSL_SAMPLER_FILTER_LINEAR;
			cs.s_address = cs.t_address = cs.r_address = SPVC_MSL_SAMPLER_ADDRESS_CLAMP_TO_EDGE;
			spvc_compiler_msl_remap_constexpr_sampler_by_binding(comp, 0, 2 + i, &cs);
		}
	}

	put(&out, "OK\n");
	// Reflection of mc_Uniforms: "name offset size" per member.
	spvc_resources res;
	const spvc_reflected_resource *list;
	size_t count;
	spvc_compiler_create_shader_resources(comp, &res);
	spvc_resources_get_resource_list_for_type(res, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, &list, &count);
	for (size_t i = 0; i < count; i++) {
		spvc_type type = spvc_compiler_get_type_handle(comp, list[i].base_type_id);
		unsigned members = spvc_type_get_num_member_types(type);
		for (unsigned m = 0; m < members; m++) {
			unsigned offset = 0;
			size_t size = 0;
			spvc_compiler_type_struct_member_offset(comp, type, m, &offset);
			spvc_compiler_get_declared_struct_member_size(comp, type, m, &size);
			char line[512];
			snprintf(line, sizeof line, "U %s %u %zu\n", spvc_compiler_get_member_name(comp, list[i].base_type_id, m), offset, size);
			put(&out, line);
		}
	}
	const char *msl;
	if (spvc_compiler_compile(comp, &msl) != SPVC_SUCCESS) {
		out.len = 0;
		put(&out, "ERR\n");
		put(&out, spvc_context_get_last_error_string(ctx));
	} else {
		char line[256];
		snprintf(line, sizeof line, "E %s\n%%%%MSL%%%%\n", spvc_compiler_get_cleansed_entry_point_name(comp, "main", model));
		put(&out, line);
		put(&out, msl);
	}
	spvc_context_destroy(ctx);

done:
	if (program) glslang_program_delete(program);
	glslang_shader_delete(shader);
	(*env)->ReleaseStringUTFChars(env, jsrc, src);
	return finish(env, &out);
}
