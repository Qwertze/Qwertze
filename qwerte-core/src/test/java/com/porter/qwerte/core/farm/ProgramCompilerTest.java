package com.porter.qwerte.core.farm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.core.farm.ClickCompiler.Click;
import com.porter.qwerte.core.farm.ClickCompiler.ClickTarget;
import com.porter.qwerte.core.farm.ClickCompiler.TickState;
import com.porter.qwerte.core.farm.FarmRecording.Container;
import com.porter.qwerte.core.farm.FarmRecording.Mark;
import com.porter.qwerte.core.farm.FarmRecording.Rel;
import com.porter.qwerte.core.farm.FarmRecording.TrackRecord;
import com.porter.qwerte.core.farm.FarmRecording.Unsupported;
import com.porter.qwerte.core.farm.FarmRecording.Vec;
import com.porter.qwerte.core.farm.ProgramCompiler.RawAnchor;
import com.porter.qwerte.core.farm.ProgramCompiler.RawContainerAction;
import com.porter.qwerte.core.farm.ProgramCompiler.RawRecording;
import com.porter.qwerte.core.farm.ProgramCompiler.RawTick;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProgramCompilerTest {
    private static final ClickTarget PIGLIN = ClickTarget.entity("minecraft:zombified_piglin");
    private static final ClickTarget STONE = ClickTarget.block(1, 0, -2, 1, "minecraft:stone");

    private static RawRecording scenario() {
        List<RawTick> t = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int keys = i < 10 ? (FarmRecording.KEY_FORWARD | FarmRecording.KEY_SPRINT) : 0;
            double yaw = i < 10 ? 190.0 : 190.0;
            int attack = 0;
            int use = 0;
            ClickTarget target = ClickTarget.MISS;
            String item = i < 10 ? "minecraft:bone_meal" : "minecraft:diamond_sword";
            int slot = i < 10 ? 0 : 1;
            if (i == 10) {
                attack = 1;
                target = PIGLIN;
            } else if (i == 11 || i == 12) {
                attack = 2;
                target = PIGLIN;
            }
            if (i == 20) {
                use = 1;
                target = STONE;
                item = "minecraft:bone_meal";
                slot = 2;
            } else if (i > 20 && i <= 27) {
                use = 2;
                target = STONE;
                item = "minecraft:bone_meal";
                slot = 2;
            } else if (i >= 21) {
                slot = i >= 28 ? 2 : slot;
                item = i >= 28 ? "minecraft:bone_meal" : item;
            }
            if (i == 30) {
                use = 1;
                item = "minecraft:bow";
                slot = 3;
            } else if (i > 30 && i <= 40) {
                use = 2;
                item = "minecraft:bow";
                slot = 3;
            } else if (i > 40) {
                item = "minecraft:bow";
                slot = 3;
            }
            List<RawContainerAction> acts = List.of();
            if (i == 60) {
                acts = List.of(new RawContainerAction(RawContainerAction.Type.TAKE, 0, "minecraft:bone_meal", 0),
                        new RawContainerAction(RawContainerAction.Type.RAW_CLICK, 0, null, 0),
                        new RawContainerAction(RawContainerAction.Type.PUT_ALL_BUT_KIT, 0, null, 0));
            }
            t.add(new RawTick(keys, yaw, i == 0 ? 5.0 : 5.0, attack, use, false, slot, item, target,
                    new Vec(0.5 + i * 0.01, 0.0, 0.5), acts, i == 70));
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("maxLoops", 0L);
        params.put("repeat", true);
        List<RawAnchor> anchors = List.of(
                new RawAnchor("a0", new Vec(0.5, 0.0, 0.5), 10.0, 0.0, 0.3, "minecraft:stone", "minecraft:air", 0),
                new RawAnchor("a1", new Vec(4.25, 0.0, -1.75), 0.0, 0.0, 0.3, "minecraft:stone", "minecraft:air", 50));
        return new RawRecording("1.21.11", "1.21.4", "custom", "minecraft:overworld", params, 10, 90, true, anchors,
                List.of(new Mark("collect", 60, 0)), List.of(new Container(new Rel(3, 0, 0), "minecraft:chest", "collect")),
                List.of(Map.of("op", "select", "item", "minecraft:bone_meal")), List.of(), t);
    }

    @Test
    void compilesTheScenarioIntoARelativeCheckedProgram() {
        ProgramCompiler.Result r = ProgramCompiler.compile(scenario(), false);
        FarmRecording p = r.program();
        assertEquals(List.of(new Unsupported("hold_use_release", 30), new Unsupported("container_multi", 60)), r.unsupported());
        assertEquals(r.unsupported(), p.header().unsupported());
        assertEquals("unsupported_actions", assertThrows(FarmException.class, () -> ProgramChecker.check(p)).code());
        // tables in first-use order; the canonical params order
        assertEquals(List.of("minecraft:bone_meal", "minecraft:diamond_sword", "minecraft:bow"), p.header().items());
        assertEquals(List.of("minecraft:zombified_piglin"), p.header().entities());
        assertEquals(List.of("minecraft:stone"), p.header().blocks());
        assertEquals(List.of("repeat", "maxLoops"), List.copyOf(p.header().template().params().keySet()));
        // anchors are offsets: a1 splits into a whole-block offset and a stand point
        assertEquals(new Rel(0, 0, 0), p.header().anchors().get(0).rel());
        assertEquals(new Rel(4, 0, -2), p.header().anchors().get(1).rel());
        assertEquals(0.25, p.header().anchors().get(1).stand().x(), 1e-12);
        assertEquals(0.25, p.header().anchors().get(1).stand().z(), 1e-12);
        assertEquals(10.0, p.header().anchors().get(0).yaw(), 1e-12);
        // first record: keys, the normalised look, hotbar, a sample
        TrackRecord first = p.track().get(0);
        assertEquals(0, first.tick());
        assertEquals(FarmRecording.KEY_FORWARD | FarmRecording.KEY_SPRINT, first.keys());
        assertEquals(-170f, first.look().yaw(), 1e-4);
        assertEquals(new FarmRecording.Hotbar(0, 1), first.hotbar());
        assertNotNull(first.sample());
        // attack press / hold / none on 10, 11, 13 (12 carries the hold)
        assertEquals(1, record(p, 10).attack());
        assertEquals(2, record(p, 11).attack());
        assertNull(record(p, 12));
        assertEquals(0, record(p, 13).attack());
        // raw container clicks are dropped, the first closed action stays, the second is listed
        assertEquals(FarmRecording.ContainerAction.take(0, 1, 0), record(p, 60).container());
        assertTrue(record(p, 70).swing());
        // samples: every 20 ticks and at the anchor tick
        List<Integer> sampleTicks = new ArrayList<>();
        p.track().forEach(rec -> {
            if (rec.sample() != null) {
                sampleTicks.add(rec.tick());
            }
        });
        assertEquals(List.of(0, 20, 40, 50, 60, 80), sampleTicks);
        // the encoded program round trips and re-hashes the same
        byte[] bytes = p.encode();
        FarmRecording back = FarmRecording.decode(bytes);
        assertArrayEquals(bytes, back.encode());
        assertEquals(p.programHash(), ProgramCompiler.compile(scenario(), false).program().programHash());
        assertEquals(FarmRecording.sha256Hex(bytes), p.programHash());
    }

    private static TrackRecord record(FarmRecording p, int tick) {
        return p.track().stream().filter(r -> r.tick() == tick).findFirst().orElse(null);
    }

    @Test
    void clicksComeFromTheProgramWithTheTickOwnRotation() {
        FarmRecording p = ProgramCompiler.compile(scenario(), false).program();
        ClickCompiler.Plan plan = ProgramCompiler.clicks(p);
        // left on 10 (entity press), right on 20 and 24 (use hold cadence); the bow hold is unsupported, no clicks
        List<Integer> left = new ArrayList<>();
        List<Integer> right = new ArrayList<>();
        for (Click c : plan.clicks()) {
            if (c.left()) {
                left.add(c.t());
            }
            if (c.right()) {
                right.add(c.t());
            }
            assertEquals(-170.0, c.yaw(), 1e-4, "tick " + c.t() + " carries its own look");
            assertEquals(5.0, c.pitch(), 1e-4);
        }
        assertEquals(List.of(10), left);
        assertEquals(List.of(20, 24), right);
        assertEquals(List.of(new Unsupported("hold_use_release", 30)), plan.unsupported());
        Click leftClick = plan.clicks().get(0);
        assertEquals(PIGLIN, leftClick.target());
        assertEquals(STONE, plan.clicks().get(1).target());
    }

    @Test
    void expandCarriesStateForwardAndReadsAPressAsHoldLater() {
        FarmRecording p = ProgramCompiler.compile(scenario(), false).program();
        List<TickState> s = ProgramCompiler.expand(p);
        assertEquals(100, s.size());
        assertEquals(1, s.get(10).attack());
        assertEquals(2, s.get(11).attack());
        assertEquals(2, s.get(12).attack());
        assertEquals(0, s.get(13).attack());
        assertEquals(ClickTarget.MISS, s.get(14).target());
        assertEquals("minecraft:bone_meal", s.get(9).item());
        assertEquals("minecraft:diamond_sword", s.get(10).item());
        assertEquals(1, s.get(20).use());
        assertEquals(2, s.get(21).use());
        assertEquals(-170.0, s.get(99).yaw(), 1e-4);
        assertEquals(10.0, ProgramCompiler.expand(
                ProgramCompiler.compile(minimal(), false).program()).get(0).yaw(), 1e-9, "tick 0 starts at a0's look");
    }

    private static RawRecording minimal() {
        List<RawTick> t = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            t.add(new RawTick(0, 10.0, 0.0, 0, 0, false, 0, null, ClickTarget.MISS, null, List.of(), false));
        }
        return new RawRecording("1.21.11", "1.21.4", "afk_still", "minecraft:overworld", Map.of("anchor", "a0"), 0, 5, true,
                List.of(new RawAnchor("a0", new Vec(0.5, 0.0, 0.5), 10.0, 0.0, 0.3, null, null, 0)), List.of(), List.of(),
                List.of(), List.of(), t);
    }

    @Test
    void skipNeutralisesTheUnsupportedHoldAndClearsTheList() {
        ProgramCompiler.Result r = ProgramCompiler.compile(scenario(), true);
        assertEquals(List.of(), r.unsupported().stream().filter(u -> u.code().equals("hold_use_release")).toList());
        // the container_multi entry is not a hold; it is dropped by the skip as well (the owner chose to skip)
        assertTrue(r.unsupported().isEmpty());
        ProgramChecker.check(r.program());
        for (TickState s : ProgramCompiler.expand(r.program())) {
            if (s.t() >= 30 && s.t() <= 40) {
                assertEquals(0, s.use(), "tick " + s.t());
            }
        }
        assertEquals(List.of(), ProgramCompiler.clicks(r.program()).unsupported());
    }

    @Test
    void rejectsInputsTheProgramCannotHold() {
        assertEquals("bad_program", assertThrows(FarmException.class,
                () -> ProgramCompiler.compile(new RawRecording("1", "1", "custom", "minecraft:overworld", Map.of(), 0, 1,
                        true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of()), false)).code());
        RawRecording nan = new RawRecording("1", "1", "afk_still", "minecraft:overworld", Map.of("anchor", "a0"), 0, 1, true,
                List.of(new RawAnchor("a0", new Vec(0.5, 0, 0.5), 0, 0, 0.3, null, null, 0)), List.of(), List.of(), List.of(),
                List.of(), List.of(new RawTick(0, Double.NaN, 0, 0, 0, false, 0, null, ClickTarget.MISS, null, List.of(), false)));
        assertEquals("bad_track", assertThrows(FarmException.class, () -> ProgramCompiler.compile(nan, false)).code());
    }

    @Test
    void anOffHandHoldIsUnsupportedBecauseTheOffHandItemIsNotRecorded() {
        List<TickState> ticks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ticks.add(new TickState(i, 0, 0, 0, i == 0 ? 1 : (i < 5 ? 2 : 0), true, "minecraft:bone_meal", ClickTarget.MISS));
        }
        ClickCompiler.Plan p = ClickCompiler.compile(ticks);
        assertEquals(List.of(), p.clicks());
        assertEquals(List.of(new Unsupported("hold_use_release", 0)), p.unsupported());
    }

    @Test
    void aTrackThatStartsMidHoldClicksOnItsFirstTick() {
        List<TickState> ticks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ticks.add(new TickState(i, 0, 0, 0, i < 9 ? 2 : 0, "minecraft:bone_meal", ClickTarget.MISS));
        }
        List<Integer> at = ClickCompiler.compile(ticks).clicks().stream().map(Click::t).toList();
        assertEquals(List.of(0, 4, 8), at);
    }
}
