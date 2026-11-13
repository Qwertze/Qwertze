package com.porter.stashmanager;

import com.porter.qwerte.core.enchant.McData;
import com.porter.qwerte.core.enchant.Plan;
import com.porter.qwerte.core.enchant.PlanRequest;
import com.porter.qwerte.core.enchant.Planner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Proves the qwerte-core composite-build dependency (classes and enchant-*.json resources) is on the server classpath. */
class QwerteCoreWiringTest {
    @Test
    void loadsTablesAndPlans() {
        McData d = McData.load("1.21.11");
        assertNotNull(d);
        assertNotNull(d.get("sharpness"));
        assertNull(Planner.validate(d, "diamond_sword", List.of(new PlanRequest.Wanted("sharpness", 5))));
        Plan bad = Planner.validate(d, "diamond_sword", List.of(new PlanRequest.Wanted("sharpness", 9)));
        assertNotNull(bad);
        assertFalse(bad.ok());
        assertEquals(Planner.INVALID_LEVEL, bad.code());
        Plan none = Planner.plan(d, new PlanRequest("diamond_sword", List.of(new PlanRequest.Wanted("sharpness", 5)), List.of(), List.of()));
        assertFalse(none.ok());
    }
}
