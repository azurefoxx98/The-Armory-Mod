package me.ladypaladra.thearmorymod.scribing;

import me.ladypaladra.thearmorymod.alteration.AlterationConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScribingSealPolicyTest {

    /**
     * Alterations carry every metadata entry while this set is empty, including the
     * private seal key owned by ScribingSeal. Any future exclusion needs this test to
     * change deliberately because exclusions can silently remove permanent data.
     */
    @Test
    void alterationTransferExcludesNoMetadataKeys() {
        assertTrue(AlterationConfig.EXCLUDED_METADATA_KEYS.isEmpty());
    }
}
