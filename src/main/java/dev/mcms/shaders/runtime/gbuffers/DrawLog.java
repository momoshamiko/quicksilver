// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.gbuffers;

import dev.mcms.glsl.msl.ProgramRequest;
import dev.mcms.metal.MetalHooks;
import dev.mcms.metal.PassDelegate;
import dev.mcms.shaders.runtime.terrain.SodiumGlobals;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The draw diagnostic (2026-11-14, E4 debugging): one line per redirected draw of the world frames the switch asks for,
 * plus the target flips, texture copies and render encoder events of those frames.
 *
 * <p>The switch is the system property {@code mcms.shaders.drawLog}, value {@code <frames>} or {@code <frames>:<scene>}.
 * It is read every frame. A changed value re-arms the log: a block of {@code frames} world frames starts at the next frame
 * in which the world is visible, labelled with the scene name ({@code SCENE}, {@code FRAME ... scene=}, {@code END}
 * lines). The first block of a game run replaces {@code <game directory>/logs/mcms-drawlog.txt}; later blocks append to
 * it. A value of 0 (or none, or blank) stops the block in progress. The same value set again does not re-arm. With the
 * switch off, nothing is read, formatted, allocated or written: every hook tests {@link #on()} first (the backend's encoder
 * hook is a null field, {@link MetalHooks#encoderTrace}, installed only while a block is open). The file is not the game
 * log. The bench harness sets the property at each scene's record phase (bench/harness, plan key drawLogFrames).
 *
 * <p>Lines: {@code FRAME} (a frame start), {@code PASS} (a redirected pass: label, kind, phase, attachments with load
 * actions, depth), {@code DRAW} (one per draw call: its kind and arguments, the pipeline, its location and variant, the
 * depth state, cull and winding, the push constants at slot 26 (sequence, region offset, raw bytes), the bound samplers,
 * the QsDraw or game matrices as rows 0 and 3 (column-major values), Sodium's u_Globals camera fields). A draw the engine
 * drops is a DRAW line marked DROPPED with {@code variant=none}. {@code FLIP} (a target's handles after a flip),
 * {@code COPY} (load, carry, depth and world/show copies: source and destination textures), {@code ENC} (a render encoder
 * begins: new, continued, or continued with a depth clear folded in; whether it ended the open encoder; each colour
 * attachment's texture and load action; the depth texture), {@code SPLIT} and {@code RESTORE} (the lazy-clear split and
 * its restore). Every line carries the frame ({@code f=}) and the pass ({@code p=}, the last redirected pass). Store actions
 * are chosen by the backend when the encoder ends (native {@code endRender}); the PASS line reports {@code store=backend}.
 */
public final class DrawLog {

	/** The system property: {@code <frames>} or {@code <frames>:<scene>}; 0 or absent is off. */
	public static final String PROPERTY = "mcms.shaders.drawLog";
	/** The log's path under the game directory. */
	public static final String FILE = "logs/mcms-drawlog.txt";
	/** The multi-draw elements one DRAW line lists; the rest are counted in drawCount only. */
	static final int MAX_ELEMENTS = 16;
	/** The push constants the backend binds at slot 26: Sodium's region offset (three floats) and the bytes after it. */
	static final int PUSH_BYTES = 20;

	private static final Logger LOG = LoggerFactory.getLogger("Quicksilver");
	/** Names of the {@link PassDelegate} draw kinds, by their constant. */
	private static final String[] KIND_NAMES = {"drawIndexed", "draw", "multiDrawIndexed", "multiDrawIndexedSeparate",
		"multiDraw", "multiDrawSeparate", "drawIndexedIndirect", "drawIndirect"};
	/** Names of the encoder begin modes ({@link MetalHooks.EncoderTrace#begin}) and of the load actions per attachment. */
	private static final String[] MODES = {"new", "merged", "mergedDepthClear"};
	private static final String[] LOADS = {"load", "clear", "dontCare"};
	/** The encoder events the backend reports while the block is open. */
	private static final MetalHooks.EncoderTrace TRACE = new EncoderEvents();

	/** The QsDraw (or game) matrices of the draw being logged, column-major: scratch, so the logging path allocates them once. */
	static final float[] MODEL_VIEW = new float[16];
	static final float[] PROJECTION = new float[16];

	/** The property's value as last seen; the same String object while it is unchanged (no allocation per frame). */
	private static @Nullable String armedValue;
	/** Frames the armed value asks for, waiting for a world-visible frame; 0 when none. */
	private static int pending;
	private static String pendingScene = "";
	/** Whether a block is open (its frames are being logged). */
	private static boolean logging;
	private static int remaining;
	private static String scene = "";
	/** Whether this run has written the file yet (the first block replaces it, later blocks append). */
	private static boolean started;
	/** Set when the file cannot be written: the log stays off for this run. */
	private static boolean broken;
	private static int frame;
	private static int pass;
	private static int draws;
	private static int encoders;
	/** The frame number of the open block's first frame: its picture dumps are taken then (-1: none). */
	private static int dumpFrame = -1;
	/** Test hook: the root of the dump folders instead of the game directory's. */
	private static @Nullable Path dumpRootOverride;
	private static boolean on;
	/** Test hook: the file to write instead of the game directory's. */
	private static @Nullable Path fileOverride;
	private static @Nullable BufferedWriter writer;
	private static final StringBuilder line = new StringBuilder(1024);

	/** The last push constants the frontend sent (slot 26): the backend keeps them bound until the next push. */
	private static int pushSeq;
	private static int pushPass;
	private static int pushTotal;
	private static int pushLength;
	private static final byte[] pushBytes = new byte[PUSH_BYTES];

	/** The draw call being logged: noted when the backend reports it, written when its line is. */
	private static int kind;
	private static boolean multi;
	private static int count, instances, first, base, firstInstance, drawCount, listed;
	private static final long[] elemA = new long[MAX_ELEMENTS];
	private static final long[] elemB = new long[MAX_ELEMENTS];
	private static final long[] elemC = new long[MAX_ELEMENTS];

	private static @Nullable List<CameraField> cameraFields;

	/** One of Sodium's u_Globals camera fields: its name, byte offset and float components. */
	private record CameraField(String name, int offset, int components) {
	}

	private DrawLog() {
	}

	/** Whether the current frame's draws are logged. Every hook tests this first; while false nothing else runs. */
	public static boolean on() {
		return on;
	}

	/**
	 * Whether this frame takes the picture dumps (the draw log's first frame of an open block, while the switch is on; false
	 * otherwise, so a dump point costs one comparison). Dump points call {@code DrawDumps} only when this is true.
	 */
	public static boolean dumping() {
		return on && frame == dumpFrame;
	}

	/** The open block's scene: the name of its dump folder. */
	public static String scene() {
		return scene;
	}

	/** The folder of the open block's picture dumps, <game>/logs/mcms-dumps/<scene>, created on demand. */
	public static Path dumpDirectory() throws IOException {
		Path root = dumpRootOverride != null ? dumpRootOverride : FabricLoader.getInstance().getGameDir().resolve("logs/mcms-dumps");
		Path dir = root.resolve(scene);
		Files.createDirectories(dir);
		return dir;
	}

	/**
	 * The engine turned off (E4 review item 13): the frame hook does not run while it is off, so an open block ends here,
	 * with its END line, the encoder hook removed and the file closed. A later frame with the engine on starts no block
	 * unless the switch is armed again.
	 */
	public static void stop() {
		if (logging) {
			closeBlock();
		} else {
			setOn(false);
		}
	}

	/**
	 * The frame-start hook: reads the switch, re-arms on a change, and decides whether this frame is logged. Called once per
	 * frame by the engine, with whether the world is visible in it.
	 */
	public static void frame(boolean worldVisible) {
		if (broken) {
			setOn(false);
			return;
		}
		String value = System.getProperty(PROPERTY);  // the stored String comes back, not a copy
		if (value != armedValue) {
			if (!Objects.equals(value, armedValue)) {
				arm(value);
			}
			armedValue = value;
		}
		if (!logging && pending > 0 && worldVisible) {
			if (!open()) {
				pending = 0;
				setOn(false);
				return;
			}
			remaining = pending;
			scene = pendingScene;
			pending = 0;
			logging = true;
			dumpFrame = frame + 1;
			StringBuilder sb = line();
			sb.append("SCENE ").append(scene).append(" frames=").append(remaining).append(" first_frame=").append(frame + 1);
			emit(sb);
		}
		if (!logging || !worldVisible) {
			setOn(false);
			return;
		}
		if (remaining == 0) {
			closeBlock();
			return;
		}
		remaining--;
		frame++;
		pass = 0;
		setOn(true);
		StringBuilder sb = line();
		sb.append("FRAME ").append(frame).append(" scene=").append(scene).append(" remaining=").append(remaining);
		emit(sb);
		flush();
	}

	/** Sets the on flag and installs (or removes) the backend's encoder hook with it. */
	private static void setOn(boolean value) {
		on = value;
		MetalHooks.encoderTrace = value ? TRACE : null;
	}

	/** Reads a changed switch value: ends the block in progress and sets what the value asks for. */
	private static void arm(@Nullable String value) {
		closeBlock();
		pending = 0;
		pendingScene = "";
		if (value == null || value.isBlank()) {
			return;
		}
		String text = value.trim();
		int colon = text.indexOf(':');
		String count = colon < 0 ? text : text.substring(0, colon).trim();
		int frames;
		try {
			frames = Integer.parseInt(count);
		} catch (NumberFormatException e) {
			frames = -1;
		}
		if (frames < 0) {
			LOG.warn("[mcms-shaders] draw log: -D{} must be <frames> or <frames>:<scene>, got '{}'; the log stays off", PROPERTY, value);
			return;
		}
		String name = colon < 0 ? "" : text.substring(colon + 1).trim();
		pending = frames;
		pendingScene = name.isEmpty() ? "unnamed" : name;
	}

	/** The frame the current or last logged frame is. */
	public static int frameNumber() {
		return frame;
	}

	/** The number of the pass being logged (the last one {@link #nextPass} numbered). */
	public static int passNumber() {
		return pass;
	}

	/** Numbers the next pass of this frame; returns the number. */
	public static int nextPass() {
		pass++;
		return pass;
	}

	/** The number of draw calls logged so far in this run (counts only while on). */
	public static int drawNumber() {
		return draws;
	}

	/** Notes a single draw call's arguments (only while on). */
	public static void noteDraw(int callKind, int callCount, int callInstances, int callFirst, int callBase, int callFirstInstance) {
		if (!on) {
			return;
		}
		draws++;
		kind = callKind;
		multi = false;
		count = callCount;
		instances = callInstances;
		first = callFirst;
		base = callBase;
		firstInstance = callFirstInstance;
		listed = 0;
	}

	/** Notes a multi-draw's arguments and copies its first {@link #MAX_ELEMENTS} elements (only while on). */
	public static void noteMulti(int callKind, @Nullable PointerBuffer offsets, @Nullable IntBuffer a, @Nullable IntBuffer b,
			int callDrawCount, int callInstances, int callFirstInstance) {
		if (!on) {
			return;
		}
		draws++;
		kind = callKind;
		multi = true;
		drawCount = callDrawCount;
		instances = callInstances;
		firstInstance = callFirstInstance;
		listed = Math.min(callDrawCount, MAX_ELEMENTS);
		for (int i = 0; i < listed; i++) {
			switch (callKind) {
				case PassDelegate.MULTI_INDEXED -> {  // params: first index, index count, base vertex
					int at = a.position() + 3 * i;
					elemA[i] = a.get(at);
					elemB[i] = a.get(at + 1);
					elemC[i] = a.get(at + 2);
				}
				case PassDelegate.MULTI_INDEXED_SEPARATE -> {  // byte offsets, index counts, base vertices
					elemA[i] = offsets.get(offsets.position() + i);
					elemB[i] = a.get(a.position() + i);
					elemC[i] = b.get(b.position() + i);
				}
				case PassDelegate.MULTI -> {  // params: first vertex, vertex count
					int at = a.position() + 2 * i;
					elemA[i] = a.get(at);
					elemB[i] = a.get(at + 1);
				}
				default -> {  // MULTI_SEPARATE: first vertices, vertex counts
					elemA[i] = a.get(a.position() + i);
					elemB[i] = b.get(b.position() + i);
				}
			}
		}
	}

	/** Notes the push constants the frontend sent (slot 26; only while on). The bytes are copied at once. */
	public static void notePush(ByteBuffer value) {
		if (!on) {
			return;
		}
		pushSeq++;
		pushPass = pass;
		pushTotal = value.remaining();
		pushLength = Math.min(PUSH_BYTES, value.remaining());
		value.get(value.position(), pushBytes, 0, pushLength);
	}

	/**
	 * A target was flipped (Targets.flip): its handles after the flip (only while on). Callers test {@link #on()} first.
	 */
	public static void flip(int target, long read, long write) {
		if (!on) {
			return;
		}
		StringBuilder sb = line();
		sb.append("FLIP f=").append(frame).append(" p=").append(pass).append(" colortex").append(target)
			.append(" read=0x").append(Long.toHexString(read)).append(" write=0x").append(Long.toHexString(write));
		emit(sb);
	}

	/**
	 * One primed load copy of the clear hook (only while on): which target, its two textures and its size and format, so a
	 * copy that reads a zero handle is named before the copy runs.
	 */
	public static void primed(int target, long read, long write, int width, int height, int format) {
		if (!on) {
			return;
		}
		StringBuilder sb = line();
		sb.append("PRIME f=").append(frame).append(" colortex").append(target).append(" read=0x")
			.append(Long.toHexString(read)).append(" write=0x").append(Long.toHexString(write)).append(" size=")
			.append(width).append('x').append(height).append(" format=").append(format);
		emit(sb);
	}

	/**
	 * A texture copy the engine makes (only while on): {@code kind} names it (load, carry, depth, world, show), {@code target}
	 * is the colour target it serves (-1 for none), {@code src} and {@code dst} are the textures' handles.
	 */
	public static void copy(String kind, int target, long src, long dst) {
		if (!on) {
			return;
		}
		StringBuilder sb = line();
		sb.append("COPY f=").append(frame).append(" p=").append(pass).append(" kind=").append(kind).append(" target=")
			.append(target < 0 ? "-" : "colortex" + target).append(" src=0x").append(Long.toHexString(src))
			.append(" dst=0x").append(Long.toHexString(dst));
		emit(sb);
	}

	/** A shared line builder, emptied. The logging path reuses it. */
	public static StringBuilder line() {
		line.setLength(0);
		return line;
	}

	/** Writes one line to the log, when a block is open. */
	public static void emit(CharSequence text) {
		BufferedWriter out = writer;
		if (out == null) {
			return;
		}
		try {
			out.append(text);
			out.newLine();
		} catch (IOException e) {
			fail(e);
		}
	}

	/** Pushes the written lines to the file (the engine's passes end with it, so a stopped game keeps its last draws). */
	public static void flush() {
		BufferedWriter out = writer;
		if (out == null) {
			return;
		}
		try {
			out.flush();
		} catch (IOException e) {
			fail(e);
		}
	}

	/** Appends the call's kind and arguments (a DRAW line's middle part). */
	static void appendCall(StringBuilder sb) {
		sb.append(" call=").append(KIND_NAMES[kind]);
		if (!multi) {
			sb.append(" count=").append(count).append(" inst=").append(instances).append(" first=").append(first)
				.append(" base=").append(base).append(" firstInst=").append(firstInstance);
			return;
		}
		boolean triple = kind == PassDelegate.MULTI_INDEXED || kind == PassDelegate.MULTI_INDEXED_SEPARATE;
		sb.append(" drawCount=").append(drawCount).append(" inst=").append(instances).append(" firstInst=").append(firstInstance)
			.append(" listed=").append(listed).append(triple ? " (first|offset,count,base)=" : " (first,count)=");
		for (int i = 0; i < listed; i++) {
			sb.append(i == 0 ? '[' : ' ').append('(').append(elemA[i]).append(',').append(elemB[i]);
			if (triple) {
				sb.append(',').append(elemC[i]);
			}
			sb.append(')');
		}
		sb.append(listed == 0 ? "[]" : "]");
	}

	/** Appends the push constants in force (slot 26): their sequence number and pass, the region offset and the raw bytes. */
	static void appendPush(StringBuilder sb) {
		if (pushSeq == 0) {
			sb.append("none");
			return;
		}
		sb.append('#').append(pushSeq).append(" pass=").append(pushPass).append(" len=").append(pushTotal)
			.append(" region=(").append(fmt(pushFloat(0))).append(',').append(fmt(pushFloat(4))).append(',')
			.append(fmt(pushFloat(8))).append(") hex=");
		for (int i = 0; i < pushLength; i++) {
			if (i > 0 && i % 4 == 0) {
				sb.append('|');
			}
			sb.append(String.format(Locale.ROOT, "%02x", pushBytes[i] & 0xff));
		}
	}

	/**
	 * Appends one matrix as its four rows {@code name.r0} .. {@code name.r3} (column-major values {@code m}). All four rows:
	 * rows 0 and 3 alone read the same for a pitch-only view rotation as for the identity (2026-11-14: the sky looked
	 * unrotated at yaw 180 for that reason).
	 */
	static void appendRows(StringBuilder sb, String name, float[] m) {
		for (int row = 0; row < 4; row++) {
			sb.append(' ').append(name).append(".r").append(row).append("=[");
			for (int column = 0; column < 4; column++) {
				if (column > 0) {
					sb.append(' ');
				}
				sb.append(fmt(m[4 * column + row]));
			}
			sb.append(']');
		}
	}

	/** Appends the camera fields of Sodium's u_Globals block, read from the mirror {@code bytes} (the camera fields only). */
	static void appendCamera(StringBuilder sb, MemorySegment bytes) {
		sb.append(" cam=[");
		for (CameraField field : cameraFields()) {
			if (field.offset() + 4L * field.components() > bytes.byteSize()) {
				continue;
			}
			sb.append(field.name()).append("=(");
			for (int c = 0; c < field.components(); c++) {
				if (c > 0) {
					sb.append(',');
				}
				sb.append(fmt(bytes.get(ValueLayout.JAVA_FLOAT_UNALIGNED, field.offset() + 4L * c)));
			}
			sb.append(')');
		}
		sb.append(']');
	}

	/** A float with four decimals, locale-independent. */
	static String fmt(float value) {
		return String.format(Locale.ROOT, "%.4f", value);
	}

	/** The float at byte {@code at} of the push constants (little-endian, as the GPU and the game write it); NaN past the bytes. */
	private static float pushFloat(int at) {
		if (at + 4 > pushLength) {
			return Float.NaN;
		}
		int bits = (pushBytes[at] & 0xff) | (pushBytes[at + 1] & 0xff) << 8 | (pushBytes[at + 2] & 0xff) << 16
			| (pushBytes[at + 3] & 0xff) << 24;
		return Float.intBitsToFloat(bits);
	}

	/** The camera fields of Sodium's u_Globals, read once from its own globals.glsl (empty when Sodium has none). */
	private static List<CameraField> cameraFields() {
		List<CameraField> fields = cameraFields;
		if (fields == null) {
			fields = new ArrayList<>();
			SodiumGlobals globals = SodiumGlobals.read();
			if (globals != null) {
				for (ProgramRequest.UniformField field : globals.block().fields()) {
					if (!field.name().contains("Camera")) {
						continue;
					}
					String type = field.type();
					int components = type.equals("float") ? 1 : type.startsWith("vec") ? type.charAt(3) - '0' : 0;
					if (components > 0) {
						fields.add(new CameraField(field.name(), field.offset(), components));
					}
				}
			}
			cameraFields = fields;
		}
		return fields;
	}

	/** Opens the file for a block: the first block of the run replaces it, later blocks append. */
	private static boolean open() {
		try {
			Path path = fileOverride != null ? fileOverride : FabricLoader.getInstance().getGameDir().resolve(FILE);
			Path parent = path.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			writer = started
				? Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
				: Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			if (!started) {
				started = true;
				emit("# mcms draw log. Switch -D" + PROPERTY + "=<frames>[:<scene>]. Store actions: chosen by the backend, not shown.");
			}
			LOG.info("[mcms-shaders] draw log block open: {} (scene {})", path, pendingScene);
			return true;
		} catch (IOException | RuntimeException e) {
			LOG.error("[mcms-shaders] draw log cannot open its file: {}", e.toString());
			return false;
		}
	}

	/** Ends the block in progress: an END line, then the file closes (the next block reopens it). */
	private static void closeBlock() {
		if (writer != null) {
			StringBuilder sb = line();
			sb.append("END scene=").append(scene);
			emit(sb);
		}
		logging = false;
		setOn(false);
		close();
	}

	private static void close() {
		BufferedWriter out = writer;
		writer = null;
		if (out != null) {
			try {
				out.close();
			} catch (IOException e) {
				LOG.error("[mcms-shaders] draw log cannot close its file: {}", e.toString());
			}
		}
	}

	/** A write failed: the log stops for this run (the game goes on). */
	private static void fail(IOException e) {
		LOG.error("[mcms-shaders] draw log stopped: {}", e.toString());
		close();
		logging = false;
		pending = 0;
		setOn(false);
		broken = true;
	}

	/** The backend's encoder events while a block is open: begins, splits and restores, one line each. */
	private static final class EncoderEvents implements MetalHooks.EncoderTrace {
		@Override
		public void begin(String why, int mode, boolean endedPrevious, long[] colors, int[] loads, long depth, boolean clearDepth) {
			encoders++;
			StringBuilder sb = line();
			sb.append("ENC f=").append(frame).append(" p=").append(pass).append(" n=#").append(encoders).append(" why=").append(why)
				.append(" mode=").append(MODES[mode]).append(" endedPrevious=").append(endedPrevious ? 1 : 0).append(" attachments=[");
			for (int i = 0; i < colors.length; i++) {
				if (i > 0) {
					sb.append(' ');
				}
				sb.append(i).append(":tex=0x").append(Long.toHexString(colors[i])).append(':').append(LOADS[loads[i]]);
			}
			sb.append("] depth=");
			if (depth == 0) {
				sb.append("none");
			} else {
				sb.append("0x").append(Long.toHexString(depth)).append(clearDepth ? ":clear" : ":load");
			}
			emit(sb);
		}

		@Override
		public void split(String why, long texture) {
			StringBuilder sb = line();
			sb.append("SPLIT f=").append(frame).append(" p=").append(pass).append(" why=").append(why).append(" tex=0x")
				.append(Long.toHexString(texture));
			emit(sb);
		}

		@Override
		public void restore(String pipeline) {
			StringBuilder sb = line();
			sb.append("RESTORE f=").append(frame).append(" p=").append(pass).append(" pipe=").append(pipeline);
			emit(sb);
		}
	}

	/** Test hook: the next log goes to {@code path} (the game directory's file otherwise). */
	static void file(@Nullable Path path) {
		fileOverride = path;
	}

	/** Test hook: stops and forgets everything (the switch, the file, the counters). */
	static void reset() {
		close();
		setOn(false);
		armedValue = null;
		pending = 0;
		pendingScene = "";
		logging = false;
		remaining = 0;
		scene = "";
		dumpFrame = -1;
		dumpRootOverride = null;
		started = false;
		broken = false;
		frame = 0;
		pass = 0;
		draws = 0;
		encoders = 0;
		fileOverride = null;
		pushSeq = 0;
		pushPass = 0;
		pushTotal = 0;
		pushLength = 0;
		kind = 0;
		multi = false;
		cameraFields = null;
	}
}
