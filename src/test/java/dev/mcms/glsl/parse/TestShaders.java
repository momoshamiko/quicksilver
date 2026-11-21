// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.glsl.parse;

import java.util.List;

/**
 * Original test shaders written in the style the eight corpus packs use (docs/research/engine-pack-corpus.md §2-§3,
 * §11): legacy {@code #version 120} with {@code varying}/{@code attribute}/{@code gl_FragData}, mixed dialects at
 * 130/400 compatibility, a 430 compute shader with {@code shared} arrays, a std430 buffer block, {@code switch} with
 * fall-through, global const arrays with array constructors, out/inout parameters, structs with instance declarators.
 * Every source here is used by the round-trip test.
 */
final class TestShaders {
	record Source(String name, String text) {
	}

	static final List<Source> ALL = List.of(
			new Source("legacy120Vertex", """
					#version 120

					attribute vec4 mc_Entity;
					attribute vec2 mc_midTexCoord;

					varying vec2 texcoord;
					varying vec2 lmcoord;
					varying vec4 color;

					uniform sampler2D texture;
					uniform sampler2D lightmap;
					uniform mat4 gbufferModelView;
					uniform float frameTimeCounter;

					void main() {
						texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
						lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
						color = gl_Color;
						gl_Position = ftransform();
					}
					"""),
			new Source("legacy120Fragment", """
					#version 120

					varying vec2 texcoord;
					varying vec2 lmcoord;
					varying vec4 color;

					uniform sampler2D texture;
					uniform sampler2D lightmap;

					void main() {
						vec3 blocklight = texture2D(lightmap, lmcoord).rgb;
						vec4 albedo = texture2D(texture, texcoord) * color;
						gl_FragData[0] = vec4(albedo.rgb * blocklight, albedo.a);
					}
					"""),
			new Source("compat130TextureName", """
					#version 130

					#extension GL_ARB_shader_texture_lod : enable

					varying vec2 texcoord;
					flat varying int material;

					uniform sampler2D texture;
					uniform sampler2D colortex0;

					void main() {
						vec4 albedo = texture2D(texture, texcoord);
						vec4 scene = texture(colortex0, texcoord);
						gl_FragData[0] = material == 1 ? albedo : mix(albedo, scene, 0.5);
					}
					"""),
			new Source("compat400Mixed", """
					#version 400 compatibility

					varying vec2 texcoord;
					varying vec4 color;

					uniform sampler2D colortex0;
					uniform float viewWidth;
					uniform float viewHeight;

					noperspective out vec2 screenPos;

					void main() {
						gl_Position = ftransform();
						screenPos = gl_Position.xy / vec2(viewWidth, viewHeight);
					}
					"""),
			new Source("compute430", """
					#version 430

					#pragma optimize(off)

					layout(local_size_x = 8, local_size_y = 8) in;

					layout(rgba16f) uniform image3D noiseVolume;

					shared vec4 tile[8 * 8];
					shared uint counts[64];

					layout(std430, binding = 1) buffer Reduction {
						float weights[];
					} reduction;

					void main() {
						uvec3 id = gl_GlobalInvocationID;
						uint slot = id.x + id.y * 8u;
						counts[slot] = 0u;
						tile[slot] = vec4(0.0);
						barrier();
						for (int i = 0; i < 4; ++i) {
							counts[slot] += uint(i) & 1u;
						}
						switch (slot & 3u) {
							case 0u:
								tile[slot] += vec4(1.0);
							case 1u:
								tile[slot].y += 1.0;
								break;
							default:
								tile[slot] = tile[slot] * 0.5;
								break;
						}
						imageStore(noiseVolume, ivec3(id), tile[slot]);
						reduction.weights[slot] = float(counts[slot]) / 64.0;
					}
					"""),
			new Source("constArrays330", """
					#version 330 core

					const float kernel[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
					const vec3 palette[3] = vec3[3](vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0), vec3(0.0, 0.0, 1.0));

					out vec4 fragColor;

					float weight(const in float w[5], in int index, out float scaled) {
						scaled = w[index] * 2.0;
						return w[index];
					}

					void main() {
						float scaled;
						float total = 0.0;
						for (int i = 0; i < 5; ++i) {
							total += weight(kernel, i, scaled) * scaled;
						}
						fragColor = vec4(total, palette[2].b, 1.0, 1.0);
					}
					"""),
			new Source("structs460", """
					#version 460

					struct Light {
						vec3 position;
						float power;
					};

					struct Scene {
						Light lights[4];
						int count;
					} scene;

					uniform Light sun;

					Light stronger(Light a, inout Light b, const in float t) {
						b.power = mix(b.power, a.power, t);
						return a.power > b.power ? a : b;
					}

					void main() {
						scene.count = 1;
						scene.lights[0] = stronger(sun, scene.lights[1], 0.5);
					}
					"""),
			new Source("interfaceBlocks430", """
					#version 430

					layout(std140) uniform Matrices {
						mat4 view;
						mat4 projection;
					};

					out Vertex {
						flat vec3 normal;
						vec2 uv;
					} vertexOut;
					"""),
			new Source("noVersion110", """
					uniform float timer;

					void main() {
						gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
						gl_Position.y += sin(timer) * 0.001;
					}
					"""),
			new Source("conditionDeclarations460", """
					#version 460 core

					bool step2();

					void main() {
						int i = 0;
						do {
							++i;
						} while (i < 8);
						while (bool more = step2()) {
							++i;
						}
						if (bool done = i > 8) {
							--i;
						} else {
							i = 8;
						}
					}
					"""));

	private TestShaders() {
	}
}
