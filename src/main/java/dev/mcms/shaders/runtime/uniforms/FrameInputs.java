// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.uniforms;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EntityTypes;
import dev.mcms.shaders.runtime.entities.EntityCapture;
import dev.mcms.shaders.pack.BiomeIds;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;

/**
 * Everything the engine reads from the game for one frame, as plain values (no game types): {@link #capture} fills it
 * once per frame at the frame-start hook and {@link FrameUniforms#update} turns it into the frame block, so every
 * computation below the capture is testable without the game. The members keep the game's own units; the conversion to
 * each uniform's documented unit happens in {@link FrameUniforms}, next to the uniform it feeds.
 *
 * <p>Every game member this reads was checked with javap against the 26.3 client jar (2026-11-13): the render state is
 * filled by {@code Minecraft.renderFrame} before {@code GameRenderer.render}, so at the frame-start hook (right after
 * {@code render}'s {@code resize(II)V} call) it holds the frame being drawn. Camera and fog from
 * {@code gameRenderer.gameRenderState().levelRenderState.cameraRenderState} (fields {@code pos}, {@code blockPos},
 * {@code yRot}, {@code xRot}, {@code isFirstPerson}, {@code fogType}, {@code fogData}, {@code depthFar}); sky from its
 * {@code skyRenderState} (fields {@code skyColor}, {@code moonPhase}, {@code cloudHeight}, {@code endFlashIntensity},
 * {@code endFlashXAngle}, {@code endFlashYAngle}); the lightmap factors from
 * {@code gameRenderState().lightmapRenderState} (fields {@code nightVisionEffectIntensity}, {@code darknessEffectScale});
 * the camera look from {@code gameRenderer.mainCamera().forwardVector()} and the FOV from
 * {@code mainCamera().getFov()} (degrees; the game builds its projection from it with near 0.05 and
 * {@code cameraRenderState.depthFar}); time from {@code ClientLevel.getOverworldClockTime()} and the sun angle from
 * {@code mainCamera().attributeProbe()} with {@code net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE}
 * at partialTick; weather from
 * {@code ClientLevel.getRainLevel(float)} and {@code getThunderLevel(float)}; light from
 * {@code ClientLevel.getBrightness(LightLayer, BlockPos)}; the biome from
 * {@code ClientLevel.getBiome(BlockPos)} (climate through {@code Biome.getBaseTemperature()} and precipitation
 * through {@code Biome.getPrecipitationAt(BlockPos, int)} with the level's sea level; the registry key through
 * {@code registryAccess().lookupOrThrow(Registries.BIOME).getKey(Biome)}); the dimension through
 * {@code ClientLevel.dimensionType()} (methods {@code ambientLight}, {@code minY}, {@code height}, {@code logicalHeight},
 * {@code hasCeiling}, {@code hasSkyLight}); the player from {@code Minecraft.player} (methods
 * {@code isSpectator}, {@code isCreative}, {@code isShiftKeyDown}, {@code isSprinting}, {@code onGround},
 * {@code isSwimming}, {@code isInWater}, {@code isInWaterOrRain}, {@code isInLava}, {@code isOnFire},
 * {@code isInvisible}, {@code isCurrentlyGlowing}, {@code isAlive}, {@code isBaby}, {@code isPassenger},
 * {@code getPassengers}, {@code isFallFlying}, {@code getVehicle}, {@code getHealth}, {@code getMaxHealth},
 * {@code getArmorValue}, {@code getAirSupply}, {@code getMaxAirSupply}, {@code getEyePosition}, {@code hurtTime},
 * {@code getCurrentMood}, {@code getEffect}, {@code MobEffectInstance.getBlendFactor(LivingEntity, float)},
 * {@code getMainHandItem}, {@code getOffhandItem}, {@code getFoodData().getFoodLevel()}); options through
 * {@code Minecraft.options} (methods {@code gamma().get()}, {@code mainHand().get()},
 * {@code getEffectiveRenderDistance()}) and the HUD state through {@code Minecraft.gui.hud.isHidden()}; lightning from
 * {@code ClientLevel.entitiesForRendering()} with {@code Entity.getType()} and {@code Entity.position()}.
 *
 * @param width height              the main render target's size in pixels
 * @param partialTick              the fraction of the current game tick
 * @param cameraX/Y/Z              the camera's exact position in world space (block units)
 * @param eyeX/Y/Z                 the player model's eye position in world space (equals the camera in first person)
 * @param yaw pitch                the camera's rotation in degrees (yaw 0 faces +Z, pitch +90 looks down)
 * @param viewRotation            the camera's view rotation as a matrix, player space to view space, no translation
 * @param fovY                     the camera's vertical field of view in degrees
 * @param farPlane                 the game projection's far plane in blocks (the {@code far} uniform is not this)
 * @param renderDistanceChunks     the effective render distance in chunks
 * @param dayTime                  the overworld clock ticks ({@code /time query daytime} without the modulo)
 * @param sunAngleDegrees          the game's sun angle in degrees: EnvironmentAttributes.SUN_ANGLE through the main
 *                                   camera's attribute probe at partialTick, the value the sky renderer draws the sun
 *                                   with; 0 at noon; the attribute default 0 in dimensions without a day timeline
 * @param moonPhaseOrdinal         {@code SkyRenderState.moonPhase.ordinal()} (0 = full moon)
 * @param rainLevel thunderLevel   the level's rain and thunder strength, 0 to 1, tick-interpolated
 * @param skyR/G/B                 the level's sky colour
 * @param fogR/G/B                 the fog colour (the game's {@code FogData.color})
 * @param fogEnvironmentalStart/End, fogRenderDistanceStart/End the game's two linear fog ranges (its fog.glsl)
 * @param eyeFluid                 0 none, 1 water, 2 lava, 3 powder snow (the camera's fluid)
 * @param eyeBlockLight eyeSkyLight the light at the camera's block position, 0 to 15 (the sky light is the raw
 *                         lightmap value at the eye, not darkness-adjusted)
 * @param screenBrightness         the brightness video setting, 0 to 1
 * @param hideGui firstPerson spectator rightHanded creative the named option, camera and mode facts
 * @param nightVision blindness darknessFactor darknessLightFactor the named effect strengths, 0 to 1
 * @param playerMood               the player's mood value, 0 to 1 (vanilla's mood system)
 * @param heldBlockLightValue(2)   the light emission of the held items, main and off hand
 * @param heldItemId(2)            the item.properties ids of the held items (0 without an item file, -1 when unlisted)
 * @param sneaking sprinting onGround swimming inWaterFeet inLava wet burning hurt invisible glowing alive child
 *                                  ridden riding elytraFlying vehicleInWater the named player facts
 * @param health maxHealth armor maxArmor air maxAir food maxFood the player's absolute stats (-1 where documented)
 * @param biomeId biomeCategory biomePrecipitation the camera's biome as the custom-uniform constants number it
 * @param temperature rainfall     the biome's climate values
 * @param ambientLight cloudHeight bedrockLevel heightLimit logicalHeightLimit hasCeiling hasSkylight the dimension's
 *                                  values
 * @param endFlashIntensity endFlashXAngle endFlashYAngle the End flash strength and angles (0 when none)
 * @param lightning                true when the level holds a lightning bolt
 * @param lightningX/Y/Z           one lightning bolt's position in world space
 * @param dateYear dateMonth dateDay timeHour timeMinute timeSecond yearSecondIn yearSecondLeft the system date and time
 */
public record FrameInputs(
	int width, int height, float partialTick,
	double cameraX, double cameraY, double cameraZ,
	double eyeX, double eyeY, double eyeZ,
	float yaw, float pitch,
	float lookX, float lookY, float lookZ,
	org.joml.Matrix4f viewRotation,
	float fovY,
	float farPlane,
	int renderDistanceChunks,
	long dayTime, float sunAngleDegrees,
	int moonPhaseOrdinal,
	float rainLevel, float thunderLevel,
	float skyR, float skyG, float skyB,
	float fogR, float fogG, float fogB,
	float fogEnvironmentalStart, float fogEnvironmentalEnd,
	float fogRenderDistanceStart, float fogRenderDistanceEnd,
	int eyeFluid,
	int eyeBlockLight, int eyeSkyLight,
	float screenBrightness,
	boolean hideGui, boolean firstPerson, boolean spectator, boolean rightHanded, boolean creative,
	float nightVision, float blindness, float darknessFactor, float darknessLightFactor,
	float playerMood,
	int heldBlockLightValue, int heldBlockLightValue2, int heldItemId, int heldItemId2,
	boolean sneaking, boolean sprinting, boolean onGround, boolean swimming, boolean inWaterFeet, boolean inLava,
	boolean wet, boolean burning, boolean hurt, boolean invisible, boolean glowing, boolean alive, boolean child,
	boolean ridden, boolean riding, boolean elytraFlying, boolean vehicleInWater,
	float health, float maxHealth, float armor, float maxArmor, float air, float maxAir, float food, float maxFood,
	int biomeId, int biomeCategory, int biomePrecipitation,
	float temperature, float rainfall,
	float ambientLight, float cloudHeight,
	int bedrockLevel, int heightLimit, int logicalHeightLimit,
	boolean hasCeiling, boolean hasSkylight,
	float endFlashIntensity, float endFlashXAngle, float endFlashYAngle,
	boolean lightning,
	double lightningX, double lightningY, double lightningZ,
	int dateYear, int dateMonth, int dateDay, int timeHour, int timeMinute, int timeSecond,
	int yearSecondIn, int yearSecondLeft) {

	/** The Iris-documented default maximum armor (50) and food (20); the game exposes no per-player maximum for either. */
	private static final float ARMOR_MAX = 50f, FOOD_MAX = 20f;

	/**
	 * Reads one frame's values from the game. The only method of this package that touches game classes; E3-A calls it
	 * at the frame-start hook, right after {@code GameRenderer.render()}'s {@code resize(II)V} call.
	 *
	 * @param mc          the game instance
	 * @param partialTick the fraction of the current game tick
	 * @param width       the main render target's width in pixels
	 * @param height      the main render target's height in pixels
	 * @return the frame's values, or {@code null} when no level or player is loaded (menu and loading frames): the
	 *         engine skips the frame's uniform update then, keeping the previous frame's block
	 */
	@org.jetbrains.annotations.Nullable
	public static FrameInputs capture(Minecraft mc, float partialTick, int width, int height) {
		LocalPlayer player = mc.player;
		var level = mc.level;
		if (player == null || level == null) {
			return null;
		}
		var levelState = mc.gameRenderer.gameRenderState().levelRenderState;
		var cam = levelState.cameraRenderState;
		var sky = levelState.skyRenderState;
		var lightmap = mc.gameRenderer.gameRenderState().lightmapRenderState;
		// Camera (CameraRenderState, filled for this frame).
		var pos = cam.pos;
		var eye = player.getEyePosition(partialTick);
		var look = mc.gameRenderer.mainCamera().forwardVector();
		var viewRotation = new org.joml.Matrix4f(cam.viewRotationMatrix);
		float fovY = mc.gameRenderer.mainCamera().getFov();
		// Fog and fluid (FogData; FogType: the camera's fluid decides isEyeInWater's code).
		var fog = cam.fogData;
		int fluid = switch (cam.fogType) {
			case WATER -> 1;
			case LAVA -> 2;
			case POWDER_SNOW -> 3;
			default -> 0;
		};
		// Light at the camera's block position (block light 0-15; the sky light is the raw lightmap value at the
		// eye, not darkness-adjusted: eyeBrightness.y is the lightmap sky light scaled to 0-240, Iris "Camera").
		var eyeBlock = cam.blockPos;
		int blockLight = level.getBrightness(LightLayer.BLOCK, eyeBlock);
		int skyLight = level.getBrightness(LightLayer.SKY, eyeBlock);
		// Biome and dimension: the biome at the player's block (its feet), the "biome currently occupied by the player" of the
		// Iris page; the camera's block is one block higher and, with the game's fuzzy biome lookup, can name the next cell.
		var feetBlock = player.blockPosition();
		var biomeHolder = level.getBiome(feetBlock);
		Biome biome = biomeHolder.value();
		var biomes = mc.level.registryAccess().lookupOrThrow(Registries.BIOME);
		var biomeKey = biomes.getKey(biome);
		// The biome's number: its place in the game's registration order (BiomeIds) for the game's own biomes; any other (a
		// mod's, even one named like a vanilla biome) takes its registry id. Its category comes from its tags (BiomeIds.category).
		String biomePath = biomeKey != null && biomeKey.getNamespace().equals("minecraft") ? biomeKey.getPath() : "";
		int biomeId = BiomeIds.idOf(biomePath);
		if (biomeId < 0) {
			biomeId = biomes.getId(biome);
		}
		int biomeCategory = BiomeIds.category(biomePath, biomeTags(biomeHolder));
		float rainfall = BiomeClimate.downfall(biome);
		DimensionType dimension = level.dimensionType();
		// Lightning: the entity list is walked only while a thunderstorm runs.
		boolean lightning = false;
		double lightningX = 0, lightningY = 0, lightningZ = 0;
		float thunder = level.getThunderLevel(partialTick);
		if (thunder > 0f) {
			for (var entity : level.entitiesForRendering()) {
				if (entity.getType() == EntityTypes.LIGHTNING_BOLT) {
					lightning = true;
					var bolt = entity.position();
					lightningX = bolt.x;
					lightningY = bolt.y;
					lightningZ = bolt.z;
					break;
				}
			}
		}
		// Effect strengths: night vision and the darkness dimming from the lightmap state the game computed;
		// blindness and the darkness presence from the effects' blend factors (0 without the effect).
		var blindnessEffect = player.getEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
		var darknessEffect = player.getEffect(net.minecraft.world.effect.MobEffects.DARKNESS);
		float blindness = blindnessEffect == null ? 0f : blindnessEffect.getBlendFactor(player, partialTick);
		float darkness = darknessEffect == null ? 0f : darknessEffect.getBlendFactor(player, partialTick);
		// System date and time (Iris "Screen and System").
		LocalDate date = LocalDate.now();
		LocalDateTime now = LocalDateTime.now();
		int secondOfYear = (date.getDayOfYear() - 1) * 86400 + now.getHour() * 3600 + now.getMinute() * 60
			+ now.getSecond();
		int yearLength = Year.isLeap(date.getYear()) ? 366 : 365;
		boolean noStats = player.isCreative() || player.isSpectator();
		return new FrameInputs(width, height, partialTick,
			pos.x, pos.y, pos.z,
			eye.x, eye.y, eye.z,
			cam.yRot, cam.xRot,
			look.x(), look.y(), look.z(),
			viewRotation,
			fovY,
			cam.depthFar,
			mc.options.getEffectiveRenderDistance(),
			level.getOverworldClockTime(),
			// The game's sun angle in degrees from the main camera's attribute probe: the value the sky renderer uses.
			mc.gameRenderer.mainCamera().attributeProbe()
				.getValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE, partialTick),
			// SkyRenderState's colour and phase are filled only for an OVERWORLD skybox (SkyRenderer.extractRenderState
			// returns before them for NONE and END, so they would NPE or read stale after a dimension change): the
			// moon phase falls back to the day-time rule, and a dimension without a sky reports the fog colour as its
			// sky colour (the Nether's visual sky is its fog).
			sky.skybox == DimensionType.Skybox.OVERWORLD && sky.skyColor != null
				? sky.moonPhase.ordinal()
				: moonPhaseFromDayTime(level.getOverworldClockTime()),
			level.getRainLevel(partialTick), thunder,
			sky.skybox == DimensionType.Skybox.OVERWORLD && sky.skyColor != null ? sky.skyColor.x() : fog.color.x,
			sky.skybox == DimensionType.Skybox.OVERWORLD && sky.skyColor != null ? sky.skyColor.y() : fog.color.y,
			sky.skybox == DimensionType.Skybox.OVERWORLD && sky.skyColor != null ? sky.skyColor.z() : fog.color.z,
			fog.color.x, fog.color.y, fog.color.z,
			fog.environmentalStart, fog.environmentalEnd, fog.renderDistanceStart, fog.renderDistanceEnd,
			fluid, blockLight, skyLight,
			mc.options.gamma().get().floatValue(),
			mc.gui.hud.isHidden(), cam.isFirstPerson, player.isSpectator(),
			mc.options.mainHand().get() != net.minecraft.world.entity.HumanoidArm.LEFT, player.isCreative(),
			lightmap.nightVisionEffectIntensity, blindness, darkness, lightmap.darknessEffectScale,
			player.getCurrentMood(),
			lightEmission(player.getMainHandItem()), lightEmission(player.getOffhandItem()),
			EntityCapture.heldItemId(player.getMainHandItem()), EntityCapture.heldItemId(player.getOffhandItem()),
			player.isShiftKeyDown(), player.isSprinting(), player.onGround(), player.isSwimming(),
			player.isInWater(), player.isInLava(), player.isInWaterOrRain(), player.isOnFire(), player.hurtTime > 0,
			player.isInvisible(), player.isCurrentlyGlowing(), player.isAlive(), player.isBaby(),
			!player.getPassengers().isEmpty(), player.isPassenger(), player.isFallFlying(),
			player.getVehicle() != null && player.getVehicle().isInWater(),
			noStats ? -1f : player.getHealth(), noStats ? -1f : player.getMaxHealth(),
			noStats ? -1f : player.getArmorValue(), ARMOR_MAX,
			noStats ? -1f : player.getAirSupply(), noStats ? -1f : player.getMaxAirSupply(),
			noStats ? -1f : player.getFoodData().getFoodLevel(), FOOD_MAX,
			biomeId, biomeCategory,
			biome.getPrecipitationAt(feetBlock, level.getSeaLevel()).ordinal(),
			biome.getBaseTemperature(), rainfall,
			dimension.ambientLight(), levelState.cloudHeight,
			dimension.minY(), dimension.height(), dimension.logicalHeight(),
			dimension.hasCeiling(), dimension.hasSkyLight(),
			sky.endFlashIntensity, sky.endFlashXAngle, sky.endFlashYAngle,
			lightning, lightningX, lightningY, lightningZ,
			date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
			now.getHour(), now.getMinute(), now.getSecond(),
			secondOfYear, yearLength * 86400 - secondOfYear);
	}

	private static int lightEmission(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem block)) {
			return 0;
		}
		return block.getBlock().defaultBlockState().getLightEmission();
	}

	/** The {@code BiomeIds.IS_*} bits of the game's biome tags the holder is in (the category rule's input; no allocation). */
	private static int biomeTags(net.minecraft.core.Holder<Biome> biome) {
		int bits = 0;
		if (biome.is(BiomeTags.IS_OCEAN)) bits |= BiomeIds.IS_OCEAN;
		if (biome.is(BiomeTags.IS_DEEP_OCEAN)) bits |= BiomeIds.IS_DEEP_OCEAN;
		if (biome.is(BiomeTags.IS_RIVER)) bits |= BiomeIds.IS_RIVER;
		if (biome.is(BiomeTags.IS_BEACH)) bits |= BiomeIds.IS_BEACH;
		if (biome.is(BiomeTags.IS_MOUNTAIN)) bits |= BiomeIds.IS_MOUNTAIN;
		if (biome.is(BiomeTags.IS_HILL)) bits |= BiomeIds.IS_HILL;
		if (biome.is(BiomeTags.IS_FOREST)) bits |= BiomeIds.IS_FOREST;
		if (biome.is(BiomeTags.IS_TAIGA)) bits |= BiomeIds.IS_TAIGA;
		if (biome.is(BiomeTags.IS_JUNGLE)) bits |= BiomeIds.IS_JUNGLE;
		if (biome.is(BiomeTags.IS_SAVANNA)) bits |= BiomeIds.IS_SAVANNA;
		if (biome.is(BiomeTags.IS_BADLANDS)) bits |= BiomeIds.IS_BADLANDS;
		if (biome.is(BiomeTags.IS_NETHER)) bits |= BiomeIds.IS_NETHER;
		if (biome.is(BiomeTags.IS_END)) bits |= BiomeIds.IS_END;
		if (biome.is(BiomeTags.IS_OVERWORLD)) bits |= BiomeIds.IS_OVERWORLD;
		return bits;
	}

	/**
	 * The moon phase from the level's day time, the vanilla rule ({@code MoonPhase} ordinal: 0 full moon, 4 new
	 * moon). Used where the sky render state holds no phase (a skybox of NONE or END leaves it stale).
	 *
	 * @param dayTime the level's overworld clock ticks
	 * @return the moon phase ordinal, 0 to 7
	 */
	static int moonPhaseFromDayTime(long dayTime) {
		return (int) ((dayTime / 24000L % 8L + 8L) % 8L);
	}
}
