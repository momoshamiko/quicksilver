// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.entities;

import dev.mcms.shaders.pack.PackModel;
import dev.mcms.shaders.runtime.Engine;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ZombieRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * The game-facing side of the per-entity ids (plan.md E4). The submit hooks ({@code EngineSubmitNodeCollectionMixin},
 * {@code EngineSubmitBlockEntityDispatcherMixin}, {@code EngineSubmitItemStackRenderStateMixin},
 * {@code EngineSubmitHumanoidArmorLayerMixin}, {@code EngineSubmitCapeLayerMixin},
 * {@code EngineSubmitEquipmentLayerRendererMixin}, {@code EngineSubmitEnderDragonRendererMixin}) resolve the ids of a
 * submit; the phase hooks attach them to the submit record the call stores; the staging buffer hooks
 * ({@code EngineSubmitStagedVertexBufferMixin}) open a {@link Ranges} range per builder request and resolve the
 * ranges against the vertex offsets upload assigns. While no set is active every hook returns at one volatile read.
 *
 * <p>Hardcoded ids (Iris {@code entity.properties}, "Hardcoded IDs"): {@code minecraft:name_tag},
 * {@code entity_shadow}, {@code entity_flame}, {@code zombie_villager_converting}, {@code current_player} and
 * {@code end_crystal_beam} are entity-file names; {@code minecraft:player_cape} and {@code elytra_with_cape} are
 * item-file names, and {@code trim_<material>} names an armour trim (Iris {@code item.properties}, "Hardcoded IDs").
 * They resolve when the maps load, like every other name.
 *
 * <p>Allocation: the id maps load into tables indexed by registry id ({@link DenseIds}) when a set loads, so a lookup
 * is one array read. Records are kept in an identity index whose entries are reused; the ranges and side buffers are
 * arrays that grow once. A trim material's id is resolved once per material holder (a key string on first sighting).
 *
 * <p>Lifetime: the record index, the item states and the item context belong to the game frame and are cleared at the
 * staging buffer's {@code endFrame} (javap: {@code StagedVertexBuffer.endFrame} at its RETURN). Records are attached
 * during the submit calls and read during the same frame's preparation, so a frame's end cannot precede the
 * preparation it serves.
 */
public final class EntityCapture {

	private static volatile @Nullable EntityIds ids;
	private static volatile @Nullable PackModel idsModel;

	/**
	 * The id maps' values for an object of the map's kind that no entry lists (65535 once the file holds entries, 0
	 * without the file): the tables' defaults, set once when the maps load. Draws that are not of a kind read
	 * {@link #UNCLAIMED}'s values or 0 instead.
	 */
	private static final int[] DEFAULTS = new int[3];
	/** The ids of the submit being made (set by the submit hooks, spent by the phase's store). */
	private static final int[] PENDING = new int[3];
	private static boolean pendingSet;
	/** The ids the bracket hands the ranges (a scratch array the caller copies at once). */
	private static final int[] PREPARED = new int[3];

	/** Per registry id: the entity, item and block-entity id tables (set when the maps load). */
	private static DenseIds entityTable = new DenseIds(0, 0);
	private static DenseIds itemTable = new DenseIds(0, 0);
	private static DenseIds blockEntityTable = new DenseIds(0, 0);

	/** The hardcoded ids (see the class comment), resolved when the maps load. */
	private static int nameTagId, shadowId, flameId, convertingZombieVillagerId, currentPlayerId, crystalBeamId;
	private static int playerCapeId, elytraWithCapeId;
	/** The zombie villager's entity type (null when the registry lacks it). */
	private static @Nullable EntityType<?> zombieVillagerType;

	/** Submit record → index into {@link #recordIds} (three ints per entry). */
	private static final IdentityIndex RECORDS = new IdentityIndex();
	private static int[] recordIds = new int[3 * 64];
	private static int recordCount;

	/** The item of each item render state, from the resolver bracket (value = item id). */
	private static final IdentityIndex ITEM_STATES = new IdentityIndex();
	/** The item ids of armour trim materials, keyed by the material's holder (resolved once per holder). */
	private static final IdentityIndex TRIM_IDS = new IdentityIndex();

	/**
	 * The item ids of the armour, cape and trim being drawn, innermost last ({@link #pushItem} and {@link #popItem});
	 * a model submit made inside them carries the top id as its {@code currentRenderedItemId}.
	 */
	private static int[] itemContext = new int[8];
	private static int itemDepth;

	/** The preparation bracket: the record whose vertices the next builder request writes (null outside it). */
	private static @Nullable Object preparing;
	/** The block entity whose renderer is submitting (set by the dispatcher bracket). */
	private static @Nullable BlockEntityRenderState blockEntityContext;

	private EntityCapture() {
	}

	/** True while a set is active; every hook checks this first and stays inert otherwise. */
	public static boolean active() {
		return Engine.get().state() == Engine.State.ACTIVE && Engine.get().model() != null;
	}

	/**
	 * The active set's id maps. When the pack model changes, the maps load and the tables fill from the registries:
	 * every entity type, item and block state that holds a block entity gets its id here, once per set.
	 */
	public static EntityIds ids() {
		PackModel model = Engine.get().model();
		EntityIds known = ids;
		if (known == null || idsModel != model) {
			known = EntityIds.of(model.entities(), model.blocks(), model.items());
			loadTables(known);
			ids = known;
			idsModel = model;
		}
		return known;
	}

	private static void loadTables(EntityIds maps) {
		DEFAULTS[0] = maps.defaultEntity();
		DEFAULTS[1] = maps.defaultBlockEntity();
		DEFAULTS[2] = maps.defaultItem();
		entityTable = new DenseIds(BuiltInRegistries.ENTITY_TYPE.size(), DEFAULTS[0]);
		zombieVillagerType = null;
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			var key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
			entityTable.set(BuiltInRegistries.ENTITY_TYPE.getId(type), maps.entity(key.getNamespace(), key.getPath()));
			if (key.getNamespace().equals("minecraft") && key.getPath().equals("zombie_villager")) {
				zombieVillagerType = type;
			}
		}
		itemTable = new DenseIds(BuiltInRegistries.ITEM.size(), DEFAULTS[2]);
		for (Item item : BuiltInRegistries.ITEM) {
			var key = BuiltInRegistries.ITEM.getKey(item);
			itemTable.set(BuiltInRegistries.ITEM.getId(item), maps.item(key.getNamespace(), key.getPath()));
		}
		blockEntityTable = new DenseIds(Block.BLOCK_STATE_REGISTRY.size(), DEFAULTS[1]);
		for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
			if (state.getBlock() instanceof EntityBlock) {
				var key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
				blockEntityTable.set(Block.BLOCK_STATE_REGISTRY.getId(state),
					maps.blockEntity(key.getNamespace(), key.getPath(), blockValues(state)));
			}
		}
		nameTagId = maps.entity("minecraft", "name_tag");
		shadowId = maps.entity("minecraft", "entity_shadow");
		flameId = maps.entity("minecraft", "entity_flame");
		convertingZombieVillagerId = maps.entity("minecraft", "zombie_villager_converting");
		currentPlayerId = maps.entity("minecraft", "current_player");
		crystalBeamId = maps.entity("minecraft", "end_crystal_beam");
		playerCapeId = maps.item("minecraft", "player_cape");
		elytraWithCapeId = maps.item("minecraft", "elytra_with_cape");
		TRIM_IDS.clear();
	}

	/**
	 * The ids of vertices no submit claimed (terrain, the sky, particles: every draw that is not an entity, a block
	 * entity or an item): entityId 0, blockEntityId -1, currentRenderedItemId -1, whatever the id maps hold. Iris 1.11.7
	 * gives terrain these values in the main and the shadow pass (measured with the mcms-ids pack, plan.md E7); before,
	 * the engine gave the maps' "present but not listed" 65535, which a pack reads as an entity (Complementary's shadow
	 * program faded terrain casters out past its entity shadow distance).
	 */
	private static final int[] UNCLAIMED = {0, -1, -1};

	/** The ids for vertices no submit claimed ({@link #UNCLAIMED}); callers copy what they keep. */
	public static int[] defaultIds() {
		return UNCLAIMED;
	}

	/**
	 * The ids of the record being prepared, or the defaults when the record carries none. Returns a static array the
	 * caller copies at once (the next call overwrites it).
	 */
	public static int[] preparingIds() {
		int[] defaults = defaultIds();
		Object record = preparing;
		int entry = record == null ? -1 : RECORDS.get(record, -1);
		if (entry < 0) {
			PREPARED[0] = defaults[0];
			PREPARED[1] = defaults[1];
			PREPARED[2] = defaults[2];
		} else {
			PREPARED[0] = recordIds[entry * 3];
			PREPARED[1] = recordIds[entry * 3 + 1];
			PREPARED[2] = recordIds[entry * 3 + 2];
		}
		return PREPARED;
	}

	// ----- submit hooks -----------------------------------------------------------------------------------------------

	/**
	 * The ids of the model submit being made ({@code SubmitNodeCollection.submitModel}: entities, block entities,
	 * armour). The state is the argument; renderers that pass something else than the state inside a block entity's
	 * submit read the dispatcher's context instead. A zombie villager that is converting reads the converting id, and
	 * the local player reads the current player id (both named in the docs' hardcoded ids). The ids a submit is not of
	 * read 0 (Iris's values for an entity's blockEntityId and a block entity's currentRenderedItemId, measured with the
	 * mcms-ids pack, plan.md E7).
	 */
	public static void modelSubmit(Object state) {
		ids();
		if (state instanceof EntityRenderState entityState && entityState.entityType != null) {
			int entity = entityIdOf(entityState.entityType);
			if (entityState.entityType == zombieVillagerType && state instanceof ZombieRenderState zombie
				&& zombie.isConverting) {
				entity = convertingZombieVillagerId;
			} else if (state instanceof AvatarRenderState avatar && isLocalPlayer(avatar)) {
				entity = currentPlayerId;
			}
			setPending(entity, 0, itemContextOr(0));
			return;
		}
		BlockEntityRenderState blockEntity = state instanceof BlockEntityRenderState cast ? cast
			: blockEntityContext;
		if (blockEntity != null) {
			BlockState block = ((dev.mcms.metal.mixin.EngineSubmitBlockEntityStateAccessor) blockEntity)
				.mcms$blockState();
			if (block != null) {
				setPending(0, blockEntityIdOf(block), itemContextOr(0));
				return;
			}
		}
		setPending(0, 0, itemContextOr(0));
	}

	private static boolean isLocalPlayer(AvatarRenderState state) {
		var player = Minecraft.getInstance().player;
		return player != null && state.id == player.getId();
	}

	/**
	 * A block entity's renderer submits ({@code BlockEntityRenderDispatcher.submit}): some renderers pass something
	 * else than the render state to their model submits (the chest, shulker-box and banner renderers pass
	 * {@code Float.valueOf(...)}), so the state names the identity for everything inside.
	 */
	public static void blockEntitySubmit(BlockEntityRenderState state) {
		blockEntityContext = state;
	}

	/** The block entity's renderer finished its submits. */
	public static void blockEntitySubmitDone() {
		blockEntityContext = null;
	}

	/** The name tag's submit ({@code SubmitNodeCollection.submitNameTag}): its record carries {@code minecraft:name_tag}. */
	public static void nameTagSubmit() {
		kindSubmit(nameTagId);
	}

	/** The entity shadow's submit ({@code SubmitNodeCollection.submitShadow}): {@code minecraft:entity_shadow}. */
	public static void shadowSubmit() {
		kindSubmit(shadowId);
	}

	/** The fire overlay's submit ({@code SubmitNodeCollection.submitFlame}): {@code minecraft:entity_flame}. */
	public static void flameSubmit() {
		kindSubmit(flameId);
	}

	/** The end crystal healing beams ({@code EnderDragonRenderer.submitCrystalBeams}): {@code minecraft:end_crystal_beam}. */
	public static void crystalBeamSubmit() {
		kindSubmit(crystalBeamId);
	}

	private static void kindSubmit(int entity) {
		ids();
		setPending(entity, 0, itemContextOr(0));
	}

	/**
	 * An item render state was filled from a stack ({@code ItemModelResolver.updateFor*} / {@code appendItemLayers}):
	 * every path that renders an item goes through them with the stack (javap against the 26.3 jar), so the resolved
	 * item id waits on the state until its submit.
	 */
	public static void itemState(Object state, ItemStack stack) {
		ids();
		if (stack.isEmpty()) {
			// Clear any stale item id on a recycled ItemStackRenderState instance.
			ITEM_STATES.put(state, Integer.MIN_VALUE);
			return;
		}
		ITEM_STATES.put(state, itemOf(stack));
	}

	/**
	 * The item render state's layers are being submitted ({@code ItemStackRenderState.submit}): its records carry the
	 * state's item id as {@code currentRenderedItemId} (engine-pack-format.md section 10: the item being rendered,
	 * the held item for the hand included). A state nobody filled reads the item map's default (an item the engine could
	 * not name); the entity and block-entity ids of an item read 0.
	 */
	public static void itemSubmit(Object state) {
		ids();
		int item = ITEM_STATES.get(state, Integer.MIN_VALUE);
		setPending(0, 0, item == Integer.MIN_VALUE ? DEFAULTS[2] : item);
	}

	/**
	 * A piece of armour is drawn ({@code HumanoidArmorLayer.renderArmorPiece}): its model submits carry the armour's
	 * item id until {@link #itemEnd}.
	 */
	public static void armorBegin(ItemStack stack) {
		ids();
		pushItem(stack.isEmpty() ? itemContextOr(0) : itemOf(stack));
	}

	/**
	 * A player's cape is drawn ({@code CapeLayer.submit}): {@code minecraft:elytra_with_cape} when the chest slot holds
	 * an elytra, {@code minecraft:player_cape} otherwise (the Iris docs' two cape ids) until {@link #itemEnd}.
	 */
	public static void capeBegin(AvatarRenderState state) {
		ids();
		pushItem(state.chestEquipment.is(Items.ELYTRA) ? elytraWithCapeId : playerCapeId);
	}

	/**
	 * An armour trim's layers are drawn ({@code EquipmentLayerRenderer.renderLayers}, its trim submits): the trim
	 * material names the id ({@code trim_<material>}); a stack without a trim keeps the current id. Pairs with
	 * {@link #itemEnd} on every path.
	 */
	public static void trimBegin(ItemStack stack) {
		ids();
		ArmorTrim trim = stack.get(DataComponents.TRIM);
		pushItem(trim == null ? itemContextOr(0) : trimId(trim));
	}

	/** The armour, cape or trim whose submits began has finished: its item id leaves the context. */
	public static void itemEnd() {
		popItem();
	}

	private static int trimId(ArmorTrim trim) {
		var material = trim.material();
		int id = TRIM_IDS.get(material, Integer.MIN_VALUE);
		if (id == Integer.MIN_VALUE) {
			String name = material.getRegisteredNameIfPresent().orElse("");
			id = ids().item("minecraft", "trim_" + name.substring(name.indexOf(':') + 1));
			TRIM_IDS.put(material, id);
		}
		return id;
	}

	/** Pushes an item id on the item context (the model submits inside it carry it). */
	static void pushItem(int id) {
		if (itemDepth == itemContext.length) {
			itemContext = Arrays.copyOf(itemContext, itemDepth * 2);
		}
		itemContext[itemDepth++] = id;
	}

	/** Pops the innermost item id; an empty context stays empty. */
	static void popItem() {
		if (itemDepth > 0) {
			itemDepth--;
		}
	}

	/** The innermost item id of the context, or {@code fallback} when the context is empty. */
	static int itemContextOr(int fallback) {
		return itemDepth == 0 ? fallback : itemContext[itemDepth - 1];
	}

	/** A phase stored a submit record: it carries the pending ids (one submit can store several records). */
	public static void attach(Object record) {
		if (!pendingSet) {
			return;
		}
		if (3 * (recordCount + 1) > recordIds.length) {
			recordIds = Arrays.copyOf(recordIds, recordIds.length * 2);
		}
		int at = recordCount * 3;
		recordIds[at] = PENDING[0];
		recordIds[at + 1] = PENDING[1];
		recordIds[at + 2] = PENDING[2];
		RECORDS.put(record, recordCount);
		recordCount++;
	}

	/** The pending ids are spent ({@code SimpleFeatureRenderPhase.submit} stored the record). */
	public static void clearPending() {
		pendingSet = false;
	}

	private static void setPending(int entity, int blockEntity, int item) {
		PENDING[0] = entity;
		PENDING[1] = blockEntity;
		PENDING[2] = item;
		pendingSet = true;
	}

	/**
	 * {@code heldItemId} / {@code heldItemId2} for one hand's stack (Iris "ID Uniforms"): 0 while no set has item maps,
	 * -1 for an empty hand under an item file, and the item's id, or -1 for an item the file does not list.
	 */
	public static int heldItemId(ItemStack stack) {
		if (Engine.get().model() == null) {
			return 0;
		}
		EntityIds maps = ids();
		if (stack.isEmpty()) {
			return maps.heldEmpty();
		}
		return EntityIds.held(itemOf(stack));
	}

	private static int entityIdOf(EntityType<?> type) {
		return entityTable.get(BuiltInRegistries.ENTITY_TYPE.getId(type));
	}

	private static int blockEntityIdOf(BlockState block) {
		return blockEntityTable.get(Block.BLOCK_STATE_REGISTRY.getId(block));
	}

	private static int itemOf(ItemStack stack) {
		return itemTable.get(BuiltInRegistries.ITEM.getId(stack.getItem()));
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Map<String, String> blockValues(BlockState block) {
		if (block.getProperties().isEmpty()) {
			return Map.of();
		}
		Map<String, String> values = new LinkedHashMap<>();
		for (Property<?> property : block.getProperties()) {
			values.put(property.getName(), ((Property) property).getName(block.getValue(property)));
		}
		return values;
	}

	// ----- the preparation bracket ------------------------------------------------------------------------------------

	/** One submit's model preparation begins: the next vertex builder request carries its record's ids. */
	public static void prepareRecord(Object record) {
		preparing = record;
	}

	/** The submit's preparation ended; a later builder request without a bracket claims the defaults. */
	public static void prepareDone() {
		preparing = null;
	}

	/**
	 * The staging buffer's frame ended ({@code StagedVertexBuffer.endFrame}): the records, item states and item context
	 * of the frame reset, and the brackets close.
	 */
	public static void frameEnded() {
		pendingSet = false;
		preparing = null;
		blockEntityContext = null;
		itemDepth = 0;
		RECORDS.clear();
		recordCount = 0;
		ITEM_STATES.clear();
	}

	/**
	 * One staging buffer's range bookkeeping (plan.md E4), held by {@code EngineSubmitStagedVertexBufferMixin} per
	 * buffer. A builder request opens a range for the draw it belongs to with the prepared record's ids; the next
	 * request, or the upload's last flush, closes it. {@link #addUploadedDraw} then records the vertex offsets upload
	 * assigned, and {@link #resolve} writes each range into its stride's side buffer.
	 *
	 * <p>A draw's slices are packed in the order its builder requests appended them (javap of {@code upload}: each
	 * draw's {@code vertexBufferSlices} are copied in turn at its {@code vertexOffset}), so a range's first vertex
	 * sits at the draw's base vertex plus the bytes of the draw's earlier ranges, and the range's vertices follow.
	 */
	public static final class Ranges {

		private int count;
		private Object[] draws = new Object[64];
		private long[] froms = new long[64];
		private long[] tos = new long[64];
		private int[] entityIds = new int[64];
		private int[] blockEntityIds = new int[64];
		private int[] itemIds = new int[64];

		private long openFrom = -1;
		private @Nullable Object openDraw;
		private final int[] openIds = new int[3];

		private final IdentityIndex uploadSlots = new IdentityIndex();
		private int uploadCount;
		private Object[] uploadDraws = new Object[16];
		private int[] uploadStrides = new int[16];
		private int[] uploadBases = new int[16];
		private long[] packedBytes = new long[16];

		private EntityVertexIds[] pool = new EntityVertexIds[4];
		private int poolUsed;

		/**
		 * A vertex builder was requested for {@code draw} ({@code StagedVertexBuffer.getVertexBuilder}): the bytes
		 * since the last request belong to the draw that held the previous builder; the builder now handed out
		 * belongs to the record being prepared, whose ids are {@code ids}.
		 */
		public void builderRequested(long writeOffset, Object draw, int[] ids) {
			close(writeOffset);
			openFrom = writeOffset;
			openDraw = draw;
			openIds[0] = ids[0];
			openIds[1] = ids[1];
			openIds[2] = ids[2];
		}

		/**
		 * The staging buffer's last builder flushed ({@code StagedVertexBuffer.upload}, at the invoke of
		 * {@code finishLastVertexBuilder}, bytecode 30): the open range closes at the write offset the flush reads.
		 */
		public void lastBuilderFlushed(long writeOffset) {
			close(writeOffset);
		}

		private void close(long writeOffset) {
			if (openFrom >= 0 && writeOffset > openFrom && openDraw != null) {
				if (count == draws.length) {
					int grown = count * 2;
					draws = Arrays.copyOf(draws, grown);
					froms = Arrays.copyOf(froms, grown);
					tos = Arrays.copyOf(tos, grown);
					entityIds = Arrays.copyOf(entityIds, grown);
					blockEntityIds = Arrays.copyOf(blockEntityIds, grown);
					itemIds = Arrays.copyOf(itemIds, grown);
				}
				draws[count] = openDraw;
				froms[count] = openFrom;
				tos[count] = writeOffset;
				entityIds[count] = openIds[0];
				blockEntityIds[count] = openIds[1];
				itemIds[count] = openIds[2];
				count++;
			}
			openFrom = -1;
			openDraw = null;
		}

		/**
		 * One non-empty draw the upload packed: its stride and its {@code vertexOffset / stride} (javap:
		 * {@code upload()} bytecode 79-93 assigns {@code vertexOffset = Mth.roundToward(running, stride)}). Called in
		 * the draws' order.
		 */
		public void addUploadedDraw(Object draw, int stride, int baseVertex) {
			int slot = uploadCount;
			if (slot == uploadDraws.length) {
				uploadDraws = Arrays.copyOf(uploadDraws, slot * 2);
				uploadStrides = Arrays.copyOf(uploadStrides, slot * 2);
				uploadBases = Arrays.copyOf(uploadBases, slot * 2);
				packedBytes = Arrays.copyOf(packedBytes, slot * 2);
			}
			uploadDraws[slot] = draw;
			uploadStrides[slot] = stride;
			uploadBases[slot] = baseVertex;
			packedBytes[slot] = 0;
			uploadSlots.put(draw, slot);
			uploadCount++;
		}

		/**
		 * Writes every range into its stride's side buffer, once the uploaded draws are known. {@code defaults} are the
		 * ids of vertices no range claims; {@code totalBytes} is the staging size the upload read.
		 */
		public void resolve(long totalBytes, int[] defaults) {
			poolUsed = 0;
			Arrays.fill(packedBytes, 0, uploadCount, 0L);
			for (int r = 0; r < count; r++) {
				int at = uploadSlots.get(draws[r], -1);
				if (at < 0) {
					continue; // the draw was empty at upload: its bytes are not in the packed buffer
				}
				int stride = uploadStrides[at];
				long length = tos[r] - froms[r];
				if (length % stride != 0) {
					continue; // a range the upload cannot address keeps the defaults
				}
				EntityVertexIds buffer = bufferFor(stride, defaults);
				int base = uploadBases[at] + (int) (packedBytes[at] / stride);
				int vertices = (int) (length / stride);
				for (int v = 0; v < vertices; v++) {
					buffer.vertex(base + v, entityIds[r], blockEntityIds[r], itemIds[r]);
				}
				packedBytes[at] += length;
			}
			for (int b = 0; b < poolUsed; b++) {
				pool[b].build(totalBytes);
			}
		}

		private EntityVertexIds bufferFor(int stride, int[] defaults) {
			for (int b = 0; b < poolUsed; b++) {
				if (pool[b].stride() == stride) {
					return pool[b];
				}
			}
			if (poolUsed == pool.length) {
				pool = Arrays.copyOf(pool, poolUsed * 2);
			}
			EntityVertexIds buffer = pool[poolUsed];
			if (buffer == null) {
				buffer = new EntityVertexIds(stride, defaults[0], defaults[1], defaults[2]);
				pool[poolUsed] = buffer;
			} else {
				buffer.reset(stride, defaults[0], defaults[1], defaults[2]);
			}
			poolUsed++;
			return buffer;
		}

		/**
		 * The side buffer of one stride after {@link #resolve} (null when no range of this stride was resolved). Its
		 * first {@code EntityVertexIds#byteCount} bytes are the records; the upload's RETURN publishes them to the
		 * delegate ({@code GbufferDelegate.publishVertexIds}), which binds them per draw.
		 */
		public @Nullable EntityVertexIds sideBuffer(int stride) {
			for (int b = 0; b < poolUsed; b++) {
				if (pool[b].stride() == stride) {
					return pool[b];
				}
			}
			return null;
		}

		/** The number of side buffers the last {@link #resolve} produced (one per stride the upload packed). */
		public int sideBufferCount() {
			return poolUsed;
		}

		/** The side buffer at {@code index} (0 up to {@link #sideBufferCount}) after {@link #resolve}. */
		public EntityVertexIds sideBufferAt(int index) {
			return pool[index];
		}

		/**
		 * The staging buffer's frame ended ({@code StagedVertexBuffer.endFrame}): the ranges and the upload registry
		 * reset, so a reused buffer starts empty.
		 */
		public void reset() {
			count = 0;
			openFrom = -1;
			openDraw = null;
			uploadSlots.clear();
			uploadCount = 0;
			poolUsed = 0;
		}

		/** The number of recorded ranges (for tests). */
		public int rangeCount() {
			return count;
		}
	}

	/**
	 * An identity-keyed table of ints with open addressing: the caches and the record index. Keys are held until
	 * {@link #clear}; no node objects are allocated, so lookups and inserts stay allocation-free once the table has
	 * grown.
	 */
	static final class IdentityIndex {

		private Object[] keys = new Object[64];
		private int[] values = new int[64];
		private int size;

		/** The value of {@code key}, or {@code absent} when it has none. */
		int get(Object key, int absent) {
			int mask = keys.length - 1;
			for (int i = System.identityHashCode(key) & mask;; i = (i + 1) & mask) {
				Object k = keys[i];
				if (k == null) {
					return absent;
				}
				if (k == key) {
					return values[i];
				}
			}
		}

		void put(Object key, int value) {
			if ((size + 1) * 2 > keys.length) {
				grow();
			}
			int mask = keys.length - 1;
			for (int i = System.identityHashCode(key) & mask;; i = (i + 1) & mask) {
				Object k = keys[i];
				if (k == null) {
					keys[i] = key;
					values[i] = value;
					size++;
					return;
				}
				if (k == key) {
					values[i] = value;
					return;
				}
			}
		}

		void clear() {
			if (size > 0) {
				Arrays.fill(keys, null);
				size = 0;
			}
		}

		private void grow() {
			Object[] oldKeys = keys;
			int[] oldValues = values;
			keys = new Object[oldKeys.length * 2];
			values = new int[oldValues.length * 2];
			size = 0;
			for (int i = 0; i < oldKeys.length; i++) {
				if (oldKeys[i] != null) {
					put(oldKeys[i], oldValues[i]);
				}
			}
		}
	}
}
