package net.nuggetmc.tplus.bot.gui;

import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotInventoryGUITest {

    @Test
    void slotMapHasStableEditableControlAndFillerRegions() {
        assertTrue(BotInventoryGUI.isEditableSlot(0));
        assertTrue(BotInventoryGUI.isEditableSlot(40));
        assertFalse(BotInventoryGUI.isEditableSlot(41));
        assertTrue(BotInventoryGUI.isArmorOrOffhand(36));
        assertTrue(BotInventoryGUI.isArmorOrOffhand(40));
        assertFalse(BotInventoryGUI.isArmorOrOffhand(35));
        assertFalse(BotInventoryGUI.isFillerSlot(BotInventoryGUI.SAVE_SLOT));
        assertTrue(BotInventoryGUI.isFillerSlot(46));
        assertEquals(BotInventoryGUI.Control.AUTO_EQUIP,
                BotInventoryGUI.actionForSlot(BotInventoryGUI.AUTO_EQUIP_SLOT));
        assertEquals(BotInventoryGUI.Control.SAVE,
                BotInventoryGUI.actionForSlot(BotInventoryGUI.SAVE_SLOT));
        assertEquals(BotInventoryGUI.Control.DISCARD,
                BotInventoryGUI.actionForSlot(BotInventoryGUI.DISCARD_SLOT));
        assertEquals(BotInventoryGUI.Control.CLOSE,
                BotInventoryGUI.actionForSlot(BotInventoryGUI.CLOSE_SLOT));
        assertNull(BotInventoryGUI.actionForSlot(BotInventoryGUI.STATUS_SLOT));
    }

    @Test
    void equipmentValidationMatchesCanonicalSlots() {
        assertTrue(BotInventoryGUI.isValidMaterialForSlot(36, Material.DIAMOND_BOOTS));
        assertFalse(BotInventoryGUI.isValidMaterialForSlot(36, Material.DIAMOND_HELMET));
        assertTrue(BotInventoryGUI.isValidMaterialForSlot(37, Material.IRON_LEGGINGS));
        assertFalse(BotInventoryGUI.isValidMaterialForSlot(37, Material.IRON_BOOTS));
        assertTrue(BotInventoryGUI.isValidMaterialForSlot(38, Material.ELYTRA));
        assertTrue(BotInventoryGUI.isValidMaterialForSlot(39, Material.CARVED_PUMPKIN));
        assertFalse(BotInventoryGUI.isValidMaterialForSlot(39, Material.SHIELD));
        assertTrue(BotInventoryGUI.isValidMaterialForSlot(40, Material.SHIELD));

        assertTrue(BotInventoryGUI.validationError(new net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack[36])
                .contains("incomplete"));
        net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack[] contents = new net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack[BotInventoryGUI.EDITABLE_SLOTS];
        contents[36] = null;
        assertNull(BotInventoryGUI.validationError(contents));
        contents[36] = null;
        assertNull(BotInventoryGUI.validationError(contents));
    }

    @Test
    void editStateTracksTheOriginalSnapshotAndSelectedSlot() {
        net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack[] original = new net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack[BotInventoryGUI.EDITABLE_SLOTS];
        BotInventoryGUI.EditState state = new BotInventoryGUI.EditState(original, 4);
        net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack[] changed = new net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack[BotInventoryGUI.EDITABLE_SLOTS];
        assertFalse(state.changed(changed));
        assertEquals(4, state.selectedHotbarSlot());
    }

    @Test
    void prohibitedShortcutsAndTransfersAreBlocked() {
        assertFalse(BotInventoryListener.isProhibitedClick(ClickType.LEFT));
        assertFalse(BotInventoryListener.isProhibitedClick(ClickType.RIGHT));
        for (ClickType click : new ClickType[]{ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY,
                ClickType.DOUBLE_CLICK, ClickType.SWAP_OFFHAND, ClickType.MIDDLE}) {
            assertTrue(BotInventoryListener.isProhibitedClick(click), click.name());
        }
    }

    @Test
    void editorLocksAllowOneExactBotAndViewerAndReleaseCleanly() {
        BotInventoryListener.EditorLocks locks = new BotInventoryListener.EditorLocks();
        UUID bot = UUID.randomUUID();
        UUID viewer = UUID.randomUUID();
        UUID otherViewer = UUID.randomUUID();
        UUID otherBot = UUID.randomUUID();

        assertTrue(locks.tryAcquire(bot, viewer));
        assertFalse(locks.tryAcquire(bot, otherViewer));
        assertFalse(locks.tryAcquire(otherBot, viewer));
        assertTrue(locks.owns(bot, viewer));
        assertTrue(locks.locked(bot));
        assertEquals(1, locks.size());

        locks.release(bot, otherViewer);
        assertEquals(1, locks.size());
        locks.release(bot, viewer);
        assertEquals(0, locks.size());
        assertFalse(locks.locked(bot));

        assertTrue(locks.tryAcquire(bot, otherViewer));
        locks.clear();
        assertEquals(0, locks.size());
    }
}
