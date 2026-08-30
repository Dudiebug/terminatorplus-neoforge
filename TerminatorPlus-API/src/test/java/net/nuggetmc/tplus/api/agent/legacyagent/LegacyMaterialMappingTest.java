package net.nuggetmc.tplus.api.agent.legacyagent;

import net.nuggetmc.tplus.compat.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMaterialMappingTest {
    @Test
    void maps26ChainTaxonomyToThe21ChainMaterial() {
        assertSame(Material.CHAIN, Material.matchMaterial("IRON_CHAIN"));
        assertSame(Material.CHAIN, Material.matchMaterial("CHAIN"));
        assertTrue(LegacyMats.OBSTACLES.contains(Material.CHAIN));
    }

    @Test
    void unavailableCopperChainIsNotExposedAsNativeEnum() {
        assertThrows(IllegalArgumentException.class, () -> Material.valueOf("COPPER_CHAIN"));
        assertFalse(Material.matchMaterial("COPPER_CHAIN") != null);
    }
}
