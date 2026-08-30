package net.nuggetmc.tplus.bot.loadout;

import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class BotInventoryRespawnTest {

    @Test
    void lastSavedInventoryWinsOverInventoryAtDeath() {
        ItemStack[] saved = new ItemStack[1];
        ItemStack[] atDeath = new ItemStack[2];

        ItemStack[] restored = BotInventory.respawnContents(saved, atDeath);

        assertEquals(1, restored.length);
        assertNotSame(saved, restored);
        assertEquals(2, BotInventory.respawnContents(null, atDeath).length);
    }
}
