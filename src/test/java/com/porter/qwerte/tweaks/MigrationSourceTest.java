package com.porter.qwerte.tweaks;

import com.porter.qwerte.tweaks.MigrationSource.Decision;
import com.porter.qwerte.tweaks.MigrationSource.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MigrationSourceTest {
    private static Entry ours(String name) {
        return new Entry(name, Set.of("Shulker Preview", "Smart Shulker"), Set.of("icon-scale", "smart-shulker"));
    }

    private static Entry empty(String name) {
        return new Entry(name, Set.of(), Set.of());
    }

    @Test
    void takesOverOurOldEntry() {
        assertEquals("container-tweaks", MigrationSource.choose(List.of(ours("container-tweaks"))).source());
    }

    @Test
    void takesOverTheStrayEntryWhenItIsOurs() {
        assertEquals("container-tweaks-porter", MigrationSource.choose(List.of(ours("container-tweaks-porter"), empty("container-tweaks"))).source());
    }

    @Test
    void neverTakesAnotherAddonsEntry() {
        Entry musheor = new Entry("container-tweaks", Set.of("General"), Set.of("only-shulkers", "move-all", "rotate"));
        Decision d = MigrationSource.choose(List.of(musheor));
        assertNull(d.source());
        assertTrue(d.reason().contains("defaults"));
    }

    @Test
    void anEntryWithNoSettingsCannotBeIdentified() {
        assertNull(MigrationSource.choose(List.of(empty("container-tweaks"))).source());
    }

    @Test
    void genericSettingNamesAloneAreNotEnough() {
        Entry other = new Entry("container-tweaks", Set.of("General"), Set.of("rotate", "swing", "action-delay", "icon"));
        assertNull(MigrationSource.choose(List.of(other)).source());
    }

    @Test
    void aDistinctiveSettingWithAnotherMarkerIdentifiesIt() {
        Entry e = new Entry("container-tweaks", Set.of(), Set.of("freeze-key", "icon-scale"));
        assertEquals("container-tweaks", MigrationSource.choose(List.of(e)).source());
    }

    @Test
    void oneMarkerIsNotEnough() {
        assertNull(MigrationSource.choose(List.of(new Entry("container-tweaks", Set.of(), Set.of("freeze-key")))).source());
        assertNull(MigrationSource.choose(List.of(new Entry("container-tweaks", Set.of("Smart Shulker"), Set.of()))).source());
    }

    @Test
    void onlyGroupNamesAreNotEnough() {
        Entry e = new Entry("container-tweaks", Set.of("Shulker Preview", "Smart Shulker", "Smart Echest"), Set.of());
        assertNull(MigrationSource.choose(List.of(e)).source());
    }

    @Test
    void onlyGenericSettingsAreNotEnough() {
        Entry e = new Entry("container-tweaks", Set.of(), Set.of("icon-scale", "icon-corner", "hide-text-list", "hotbar-icon"));
        assertNull(MigrationSource.choose(List.of(e)).source());
        assertNull(MigrationSource.choose(List.of(new Entry("container-tweaks", Set.of("General"), Set.of("icon-scale")))).source());
    }

    @Test
    void namesAreMatchedIgnoringCase() {
        assertEquals("container-tweaks", MigrationSource.choose(List.of(ours("Container-Tweaks"))).source());
        assertNull(MigrationSource.choose(List.of(ours("container-tweaks"), empty("Qwerte-Container-Tweaks"))).source());
    }

    @Test
    void theOldNameWinsWhenBothAreOurs() {
        assertEquals("container-tweaks", MigrationSource.choose(List.of(ours("container-tweaks-porter"), ours("container-tweaks"))).source());
    }

    @Test
    void skipsMusheorsAndFallsBackToTheStray() {
        Entry musheor = new Entry("container-tweaks", Set.of("General"), Set.of("only-shulkers"));
        assertEquals("container-tweaks-porter", MigrationSource.choose(List.of(musheor, ours("container-tweaks-porter"))).source());
    }

    @Test
    void nothingIsTakenOnceTheNewEntryExists() {
        Decision d = MigrationSource.choose(List.of(ours("container-tweaks"), empty("qwerte-container-tweaks")));
        assertNull(d.source());
        assertTrue(d.reason().contains("already"));
    }

    @Test
    void noEntriesStartsFromDefaults() {
        assertNull(MigrationSource.choose(List.of()).source());
    }
}
