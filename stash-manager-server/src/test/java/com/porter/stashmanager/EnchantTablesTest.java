package com.porter.stashmanager;

import com.porter.qwerte.core.enchant.McData;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The page's /enchant-&lt;mc&gt;.json files are qwerte-core's own tables (copied by the build): never missing, never different. */
class EnchantTablesTest {
    @Test
    void theWebCopiesAreTheLibrarysTables() throws Exception {
        assertTrue(McData.SUPPORTED.size() >= 2);
        for (String mc : McData.SUPPORTED) {
            try (InputStream web = StashManagerServer.class.getResourceAsStream("/web/enchant-" + mc + ".json");
                 InputStream core = McData.class.getResourceAsStream("enchant-" + mc + ".json")) {
                assertNotNull(web, "web/enchant-" + mc + ".json is missing from the build");
                assertNotNull(core);
                assertArrayEquals(core.readAllBytes(), web.readAllBytes(), mc);
            }
        }
    }
}
