// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.compute;

import dev.mcms.glsl.msl.ProgramTranslation.Reflection.ResourceUse;
import dev.mcms.shaders.pack.ConstValue;
import dev.mcms.shaders.pack.ProgramDirectives;
import dev.mcms.shaders.pack.StageFile;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One compute kernel of a pack program (plan.md E6): its {@code <program>.csh} or {@code <program>_a}..{@code _z}.csh file,
 * compiled to a pipeline, with the work groups its file asks for. The kernel holds facts only; the frame resolves each
 * texture and image to its source per dispatch (EngineFrame), because a target's textures move when it flips.
 *
 * <p>Work groups (engine-pack-format.md section 14; OptiFine shaders.txt Compute Shaders): {@code const ivec3 workGroups}
 * is a fixed count; {@code const vec2 workGroupsRender} is a count relative to the render size, rounded up in local-size
 * units. A kernel with neither constant dispatches {@code workGroupsRender = vec2(1.0, 1.0)}: one group per local size
 * of the render. Both constants are read through the pack's constant evaluator, so {@code f} and {@code u} suffixes and
 * constructor arithmetic work as they do in the documented examples.
 *
 * <p>Image sources are encoded as ints: {@link #IMAGE_TARGET} + the colour target for {@code colorimg<N>},
 * {@link #IMAGE_SHADOW} + the shadow colour index for {@code shadowcolorimg<N>}, {@link #IMAGE_CUSTOM} + the custom
 * image's index for a {@code image.<name>} (CustomImages).
 */
public final class ComputeKernel {

	/** The image source base of {@code colorimg<N>}: the write texture of colour target N. */
	public static final int IMAGE_TARGET = 100;
	/** The image source base of {@code shadowcolorimg<N>}: the shadow colour write texture N. */
	public static final int IMAGE_SHADOW = 200;
	/** The image source base of a custom image: its texture (CustomImages). */
	public static final int IMAGE_CUSTOM = 300;
	/**
	 * Metal's threadgroup memory per group, in bytes. A kernel whose shared arrays need more moves them to a device buffer
	 * (the translator's StageEmitter.THREADGROUP_LIMIT: keep the two equal).
	 */
	public static final int THREADGROUP_LIMIT = 32768;

	/**
	 * The work-group rule of a kernel: fixed counts, or counts relative to the render size (exactly one of the two is set).
	 *
	 * @param fixed    the work groups per axis, or null
	 * @param relative the render-size factors per axis (the render width and height are scaled by them), or null
	 */
	public record WorkGroups(int @Nullable [] fixed, double @Nullable [] relative) {

		/** The documented default: {@code workGroupsRender = vec2(1.0, 1.0)}. */
		public static final WorkGroups DEFAULT = new WorkGroups(null, new double[] {1.0, 1.0});

		/** The rule a kernel's file names: {@code workGroups} first, then {@code workGroupsRender}, else {@link #DEFAULT}. */
		public static WorkGroups of(StageFile file) {
			ProgramDirectives directives = ProgramDirectives.of(List.of(file));
			ConstValue fixed = directives.constants().get("workGroups");
			if (fixed != null && fixed.values().size() >= 3) {
				return new WorkGroups(new int[] {(int) fixed.values().get(0).doubleValue(),
					(int) fixed.values().get(1).doubleValue(), (int) fixed.values().get(2).doubleValue()}, null);
			}
			ConstValue relative = directives.constants().get("workGroupsRender");
			if (relative != null && relative.values().size() >= 2) {
				return new WorkGroups(null, new double[] {relative.values().get(0), relative.values().get(1)});
			}
			return DEFAULT;
		}
	}

	private final String name;
	private final long pipeline;
	private final int[] local;
	private final WorkGroups groups;
	private final List<ResourceUse> textures;
	private final List<ResourceUse> images;
	/** The block uses of the kernel (storage buffers among them: {@link ResourceUse#storage()}). */
	private final List<ResourceUse> buffers;
	private final int spillBytesPerGroup;

	/**
	 * @param pipeline           the compiled compute pipeline (owned by the set)
	 * @param local              the local size (x, y, z), from the kernel's layout qualifier
	 * @param groups             the work-group rule of the kernel's file
	 * @param textures           the sampled textures of the reflection, in its order
	 * @param images             the images of the reflection, in its order
	 * @param buffers            the block uses of the reflection (storage buffers among them)
	 * @param spillBytesPerGroup the bytes of shared memory each group needs when its arrays move to device memory (0: none;
	 *                           the spill buffer binds at {@link dev.mcms.glsl.msl.ProgramRequest#SHARED_SPILL_SLOT})
	 */
	public ComputeKernel(String name, long pipeline, int[] local, WorkGroups groups, List<ResourceUse> textures,
			List<ResourceUse> images, List<ResourceUse> buffers, int spillBytesPerGroup) {
		if (local.length != 3) {
			throw new IllegalArgumentException("a local size has three axes: " + name);
		}
		this.name = name;
		this.pipeline = pipeline;
		this.local = local.clone();
		this.groups = groups;
		this.textures = List.copyOf(textures);
		this.images = List.copyOf(images);
		this.buffers = List.copyOf(buffers);
		this.spillBytesPerGroup = spillBytesPerGroup;
	}

	/**
	 * The colour target a {@code colorimg<N>} image name stands for (N = 0 to 5), or -1 when the name is no colour image.
	 * Kernels and passes both name colour images; the target set counts them (DimensionSet.usedTargets). The gbuffers binds
	 * call this per draw, so it allocates nothing (RIL-567).
	 */
	public static int colourTargetOf(String imageName) {
		return switch (imageName) {
			case "colorimg0" -> 0;
			case "colorimg1" -> 1;
			case "colorimg2" -> 2;
			case "colorimg3" -> 3;
			case "colorimg4" -> 4;
			case "colorimg5" -> 5;
			default -> -1;
		};
	}

	/** The shadow colour buffer a {@code shadowcolorimg<N>} image name stands for (N = 0 or 1), or -1 (allocates nothing). */
	public static int shadowColourImageOf(String imageName) {
		return switch (imageName) {
			case "shadowcolorimg0" -> 0;
			case "shadowcolorimg1" -> 1;
			default -> -1;
		};
	}

	public String name() {
		return this.name;
	}

	public long pipeline() {
		return this.pipeline;
	}

	public int localX() {
		return this.local[0];
	}

	public int localY() {
		return this.local[1];
	}

	public int localZ() {
		return this.local[2];
	}

	public List<ResourceUse> textures() {
		return this.textures;
	}

	public List<ResourceUse> images() {
		return this.images;
	}

	/** The block uses of the kernel; the storage ones bind their bufferObject at their slots. */
	public List<ResourceUse> buffers() {
		return this.buffers;
	}

	public int spillBytesPerGroup() {
		return this.spillBytesPerGroup;
	}

	/**
	 * Writes the work groups of one dispatch into {@code out} ({@code x, y, z}): the fixed count, or the render size scaled
	 * by the relative factors and rounded up in local-size units, at least one group per axis (OptiFine shaders.txt). No
	 * allocation: the frame path calls this per dispatch.
	 */
	public void groupsInto(int renderWidth, int renderHeight, int[] out) {
		if (this.groups.fixed() != null) {
			System.arraycopy(this.groups.fixed(), 0, out, 0, 3);
			return;
		}
		double[] rel = this.groups.relative();
		out[0] = Math.max(1, (int) Math.ceil(renderWidth * rel[0] / this.local[0]));
		out[1] = Math.max(1, (int) Math.ceil(renderHeight * rel[1] / this.local[1]));
		out[2] = 1;
	}
}
