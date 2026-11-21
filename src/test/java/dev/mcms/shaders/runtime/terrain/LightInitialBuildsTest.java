// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.runtime.terrain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.CoordinateSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

/**
 * The light's first builds (E6-B2; {@link LightInitialBuilds}). The candidates are real Sodium {@link RenderSection}s over
 * real {@link RenderRegion}s (the constructor takes no arena until it draws, as in {@code ShadowTerrainListTest}); the storage
 * and the submission are fakes, because {@code RenderSectionManager} needs a GPU and a level. A build is driven as
 * {@code ShadowTerrain.build} drives it: {@code begin}, the traversal's visits, {@code end}. The traversal itself and the
 * Sodium-side submission are checked by the javap evidence in the class Javadoc and by the mixin audit, not here.
 */
class LightInitialBuildsTest {

	private static RenderSection section(int x, int y, int z) {
		return new RenderSection(new RenderRegion(0, 0, 0, null), x, y, z);
	}

	/** A section at block x of the row, waiting for its first build. */
	private static RenderSection waitingSection(int x) {
		RenderSection section = section(x, 0, 0);
		section.setPendingUpdate(ChunkUpdateTypes.INITIAL_BUILD, 0L);
		return section;
	}

	@Test
	void aSectionWaitingForItsFirstBuildIsACandidate() {
		RenderSection section = section(1, 2, 3);
		section.setPendingUpdate(ChunkUpdateTypes.INITIAL_BUILD, 0L);

		assertTrue(LightInitialBuilds.wantsInitialBuild(section));
	}

	@Test
	void aSectionWithNoPendingUpdateIsNotACandidate() {
		assertFalse(LightInitialBuilds.wantsInitialBuild(section(1, 2, 3)));
	}

	@Test
	void aSectionWhoseBuildFinishedIsNotACandidateEvenWithAPendingUpdate() {
		RenderSection section = section(1, 2, 3);
		section.setInfo(BuiltSectionInfo.EMPTY);
		section.setPendingUpdate(ChunkUpdateTypes.INITIAL_BUILD, 0L);

		assertFalse(LightInitialBuilds.wantsInitialBuild(section), "a built section is the camera's to rebuild");
	}

	@Test
	void aDisposedSectionIsNotACandidate() {
		RenderSection section = section(1, 2, 3);
		section.setPendingUpdate(ChunkUpdateTypes.INITIAL_BUILD, 0L);
		section.delete();

		assertFalse(LightInitialBuilds.wantsInitialBuild(section));
	}

	@Test
	void aBuildForwardsEveryVisitAndSubmitsOnlyTheSectionsWaitingForTheirFirstBuild() {
		RenderSection waiting = section(1, 2, 3);
		waiting.setPendingUpdate(ChunkUpdateTypes.INITIAL_BUILD, 0L);
		RenderSection built = section(4, 5, 6);
		built.setInfo(BuiltSectionInfo.EMPTY);
		built.setPendingUpdate(ChunkUpdateTypes.INITIAL_BUILD, 0L);
		RenderSection idle = section(10, 11, 12);
		FakeStorage storage = new FakeStorage()
			.put(1, 2, 3, waiting).put(4, 5, 6, built).put(10, 11, 12, idle);
		FakePort port = new FakePort(true, 10);
		List<String> forwarded = new ArrayList<>();
		LightInitialBuilds light = new LightInitialBuilds();

		CoordinateSectionVisitor visitor = light.begin((x, y, z) -> forwarded.add(x + "," + y + "," + z), storage, port);
		visitor.visit(1, 2, 3);
		visitor.visit(4, 5, 6);
		visitor.visit(7, 8, 9);
		visitor.visit(10, 11, 12);
		light.end(port);

		assertEquals(List.of("1,2,3", "4,5,6", "7,8,9", "10,11,12"), forwarded, "every visit reaches the light's collector");
		assertEquals(List.of(SectionPos.asLong(1, 2, 3), SectionPos.asLong(4, 5, 6), SectionPos.asLong(7, 8, 9),
			SectionPos.asLong(10, 11, 12)), storage.lookups, "each visit looks its section up by its key");
		assertEquals(List.of(waiting), port.submitted, "only the section waiting for its first build is submitted");
	}

	@Test
	void aBusyCameraGivesThePlainCollectorLooksUpNothingAndSubmitsNothing() {
		FakeStorage storage = new FakeStorage().put(1, 0, 0, waitingSection(1));
		FakePort port = new FakePort(false, 10);
		CoordinateSectionVisitor plain = (x, y, z) -> { };
		LightInitialBuilds light = new LightInitialBuilds();

		CoordinateSectionVisitor visitor = light.begin(plain, storage, port);
		visitor.visit(1, 0, 0);
		light.end(port);

		assertSame(plain, visitor, "the camera is busy: the traversal gets the plain collector");
		assertTrue(storage.lookups.isEmpty(), "no candidate is looked up while the camera is busy");
		assertEquals(1, port.cameraIdleAsked, "the camera is asked once, before the traversal");
		assertEquals(0, port.collectors, "no collector is made");
		assertEquals(0, port.budgets, "no budget is made");
		assertTrue(port.submitted.isEmpty(), "the camera's deferred builds come first");
	}

	@Test
	void theCandidatesAreSubmittedInTraversalOrderWhileTheBudgetHasRoom() {
		FakeStorage storage = new FakeStorage();
		List<RenderSection> sections = new ArrayList<>();
		for (int x = 1; x <= 5; x++) {
			RenderSection section = waitingSection(x);
			storage.put(x, 0, 0, section);
			sections.add(section);
		}
		FakePort port = new FakePort(true, 2);
		LightInitialBuilds light = new LightInitialBuilds();

		CoordinateSectionVisitor visitor = light.begin((x, y, z) -> { }, storage, port);
		for (int x = 1; x <= 5; x++) {
			visitor.visit(x, 0, 0);
		}
		light.end(port);

		assertEquals(sections.subList(0, 2), port.submitted, "the first two in order; the budget had room for two");
	}

	@Test
	void aCollectorWithoutTimeLeftSubmitsNothing() {
		FakeStorage storage = new FakeStorage().put(1, 0, 0, waitingSection(1));
		FakePort port = new FakePort(true, 10);
		port.collectorDuration = 0L;
		LightInitialBuilds light = new LightInitialBuilds();

		light.begin((x, y, z) -> { }, storage, port).visit(1, 0, 0);
		light.end(port);

		assertTrue(port.submitted.isEmpty());
	}

	@Test
	void aBuildWithNoCandidatesDoesNotAskThePortForACollectorOrABudget() {
		FakeStorage storage = new FakeStorage().put(1, 0, 0, section(1, 0, 0));
		FakePort port = new FakePort(true, 10);
		LightInitialBuilds light = new LightInitialBuilds();

		light.begin((x, y, z) -> { }, storage, port).visit(1, 0, 0);
		light.end(port);

		assertEquals(0, port.collectors, "an idle frame makes no collector");
		assertEquals(0, port.budgets, "an idle frame makes no budget");
	}

	@Test
	void aCandidateTheBudgetDoesNotReachIsFoundAgainByTheNextBuildAndNotKeptTwice() {
		RenderSection section = waitingSection(1);
		FakeStorage storage = new FakeStorage().put(1, 0, 0, section);
		LightInitialBuilds light = new LightInitialBuilds();

		FakePort starved = new FakePort(true, 0);
		light.begin((x, y, z) -> { }, storage, starved).visit(1, 0, 0);
		light.end(starved);
		assertTrue(starved.submitted.isEmpty(), "no budget: nothing is submitted");

		FakePort next = new FakePort(true, 10);
		light.begin((x, y, z) -> { }, storage, next).visit(1, 0, 0);
		light.end(next);
		light.begin((x, y, z) -> { }, storage, next);
		light.end(next);

		assertEquals(List.of(section), next.submitted, "the next build finds the section again, and an empty build adds nothing");
	}

	@Test
	void aThrowingSubmissionLeavesTheFirstBuildsOffForTheSetAndIsNotRetried() {
		FakeStorage storage = new FakeStorage().put(1, 0, 0, waitingSection(1)).put(2, 0, 0, waitingSection(2));
		FakePort port = new FakePort(true, 10);
		port.throwOnSubmit = true;
		LightInitialBuilds light = new LightInitialBuilds();

		CoordinateSectionVisitor failing = light.begin((x, y, z) -> { }, storage, port);
		failing.visit(1, 0, 0);
		failing.visit(2, 0, 0);
		assertDoesNotThrow(() -> light.end(port), "a throw from the submission ends in the light, not in the frame");
		assertEquals(1, port.submitCalls, "the submission that threw is the only one made");

		CoordinateSectionVisitor plain = (x, y, z) -> { };
		assertSame(plain, light.begin(plain, storage, port), "the next build of the set gets the plain collector");
		light.end(port);
		assertEquals(1, port.cameraIdleAsked, "the camera is not asked again once the first builds are off");
		assertEquals(1, port.submitCalls, "the first builds are not submitted again in the set");
	}

	@Test
	void aNewSetTurnsTheFirstBuildsOnAgain() {
		FakeStorage storage = new FakeStorage().put(1, 0, 0, waitingSection(1));
		FakePort port = new FakePort(true, 10);
		port.throwOnSubmit = true;
		LightInitialBuilds light = new LightInitialBuilds();
		light.begin((x, y, z) -> { }, storage, port).visit(1, 0, 0);
		light.end(port);

		light.reset();
		port.throwOnSubmit = false;
		CoordinateSectionVisitor visitor = light.begin((x, y, z) -> { }, storage, port);
		visitor.visit(1, 0, 0);
		light.end(port);

		assertEquals(2, port.cameraIdleAsked, "a new set asks the camera again");
		assertEquals(1, port.submitted.size(), "the new set submits the section");
	}

	@Test
	void aStorageKeepsOneVisitorAcrossBuildsAndAnotherStorageGetsAnotherOne() {
		FakeStorage first = new FakeStorage();
		FakeStorage second = new FakeStorage();
		FakePort port = new FakePort(true, 10);
		LightInitialBuilds light = new LightInitialBuilds();
		List<String> firstLists = new ArrayList<>();
		List<String> secondLists = new ArrayList<>();

		CoordinateSectionVisitor one = light.begin((x, y, z) -> firstLists.add("1"), first, port);
		one.visit(0, 0, 0);
		light.end(port);
		CoordinateSectionVisitor two = light.begin((x, y, z) -> secondLists.add("2"), first, port);
		two.visit(0, 0, 0);
		light.end(port);
		CoordinateSectionVisitor other = light.begin((x, y, z) -> secondLists.add("3"), second, port);
		light.end(port);

		assertSame(one, two, "two builds on one storage share one visitor (no allocation per frame)");
		assertNotSame(one, other, "another storage has its own visitor");
		assertEquals(List.of("1"), firstLists, "the first build's collector gets only the first build's visit");
		assertEquals(List.of("2"), secondLists, "the second build's collector gets only the second build's visit");
	}


	/** A port that records the submissions; its budget takes one unit per submission, as Sodium's consume does. */
	private static final class FakePort implements LightInitialBuilds.Port {
		private final boolean idle;
		private final int units;
		private long collectorDuration = Long.MAX_VALUE;
		private final List<RenderSection> submitted = new ArrayList<>();
		private int collectors;
		private int budgets;
		private int cameraIdleAsked;
		private int submitCalls;
		private boolean throwOnSubmit;

		FakePort(boolean idle, int units) {
			this.idle = idle;
			this.units = units;
		}

		@Override
		public boolean cameraIdle() {
			this.cameraIdleAsked++;
			return this.idle;
		}

		@Override
		public ChunkJobCollector newCollector() {
			this.collectors++;
			return new ChunkJobCollector(this.collectorDuration, result -> {
			});
		}

		@Override
		public UploadResourceBudget newBudget() {
			this.budgets++;
			return new UploadResourceBudget() {
				private int left = FakePort.this.units;

				@Override
				public boolean isAvailable() {
					return this.left > 0;
				}

				@Override
				public void consume(long duration, long size) {
					this.left--;
				}
			};
		}

		@Override
		public void submit(ChunkJobCollector collector, RenderSection section, UploadResourceBudget budget) {
			this.submitCalls++;
			if (this.throwOnSubmit) {
				throw new IllegalStateException("the submission refused");
			}
			this.submitted.add(section);
			budget.consume(0L, 0L);
		}
	}

	/** Storage over a map: the lookup is the one the visitor makes; the rest of the interface is not used here. */
	private static final class FakeStorage implements SectionStorage {
		private final Map<Long, RenderSection> sections = new HashMap<>();
		private final List<Long> lookups = new ArrayList<>();

		FakeStorage put(int x, int y, int z, RenderSection section) {
			this.sections.put(SectionPos.asLong(x, y, z), section);
			return this;
		}

		@Override
		public RenderSection getConsistent(long key) {
			this.lookups.add(key);
			return this.sections.get(key);
		}

		@Override
		public RenderSection getCurrent(long key) {
			return this.sections.get(key);
		}

		@Override
		public boolean hasSectionConsistent(long key) {
			return this.sections.containsKey(key);
		}

		@Override
		public void queuePut(long key, RenderSection section) {
			throw new UnsupportedOperationException();
		}

		@Override
		public RenderSection queueRemove(long key) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void deleteAll() {
			throw new UnsupportedOperationException();
		}

		@Override
		public int size() {
			return this.sections.size();
		}

		@Override
		public void startSafeReadPhase() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void endSafeReadPhase() {
			throw new UnsupportedOperationException();
		}

		@Override
		public String getDebugInfo() {
			return "fake";
		}
	}
}
