package net.nuggetmc.tplus.bot.gui;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.ChatColor;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.inventory.EquipmentSlot;
import net.nuggetmc.tplus.compat.bukkit.inventory.Inventory;
import net.nuggetmc.tplus.compat.bukkit.inventory.InventoryHolder;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.inventory.PlayerInventory;
import net.nuggetmc.tplus.compat.bukkit.inventory.meta.ItemMeta;

import java.util.Objects;
import java.util.UUID;

/**
 * Transactional editor for one exact bot inventory.
 *
 * <p>The chest is a working copy. The bot is changed only by {@link #save()}.
 * Closing the chest without saving discards the working copy.</p>
 */
public final class BotInventoryGUI implements InventoryHolder {

    public static final int SIZE = 54;
    public static final int EDITABLE_SLOTS = 41;
    public static final int BOOTS_SLOT = 36;
    public static final int LEGGINGS_SLOT = 37;
    public static final int CHEST_SLOT = 38;
    public static final int HELMET_SLOT = 39;
    public static final int OFFHAND_SLOT = 40;

    public static final int AUTO_EQUIP_SLOT = 45;
    public static final int SAVE_SLOT = 48;
    public static final int STATUS_SLOT = 49;
    public static final int DISCARD_SLOT = 51;
    public static final int CLOSE_SLOT = 53;

    private final Bot bot;
    private final UUID viewerId;
    private final ItemStack originalCursor;
    private final Inventory inventory;
    private final EditState edits;
    private boolean autoEquip;
    private boolean closed;

    BotInventoryGUI(Bot bot, Player viewer) {
        this.bot = Objects.requireNonNull(bot, "bot");
        Objects.requireNonNull(viewer, "viewer");
        this.viewerId = viewer.getUniqueId();
        this.originalCursor = cloneOrNull(viewer.getItemOnCursor());
        this.inventory = Bukkit.createInventory(this, SIZE,
                ChatColor.GOLD + "Inventory: " + ChatColor.YELLOW + bot.getBotName());
        this.edits = EditState.capture(bot);
        render();
    }

    public Bot getBot() {
        return bot;
    }

    public UUID getBotId() {
        return bot.getUUID();
    }

    UUID getViewerId() {
        return viewerId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player viewer) {
        viewer.openInventory(inventory);
    }

    public boolean isAutoEquipEnabled() {
        return autoEquip;
    }

    void toggleAutoEquip() {
        autoEquip = !autoEquip;
        renderControls();
    }

    public boolean hasChanges() {
        return edits.changed(editableContents());
    }

    /**
     * Validate and apply the working copy. Returns false without changing the
     * bot when the copy is invalid or this editor has already finished.
     */
    public boolean save() {
        if (closed || !isBotUsable()) return false;
        ItemStack[] snapshot = editableContents();
        if (validationError(snapshot) != null) return false;

        bot.getBotInventory().applyMainInventorySnapshot(snapshot);
        bot.setItem(safe(snapshot[BOOTS_SLOT]), EquipmentSlot.FEET);
        bot.setItem(safe(snapshot[LEGGINGS_SLOT]), EquipmentSlot.LEGS);
        bot.setItem(safe(snapshot[CHEST_SLOT]), EquipmentSlot.CHEST);
        bot.setItem(safe(snapshot[HELMET_SLOT]), EquipmentSlot.HEAD);
        bot.setItemOffhand(safe(snapshot[OFFHAND_SLOT]));

        if (autoEquip) {
            bot.getBotInventory().autoEquip();
        } else {
            bot.getBotInventory().setSelectedHotbarSlot(edits.selectedHotbarSlot());
            bot.getBotInventory().refreshSelectedItem();
        }
        bot.getBotInventory().markLoadoutApplied();
        closed = true;
        return true;
    }

    /** Discard the working copy and restore the cursor captured on open. */
    void discard(Player viewer) {
        if (closed) return;
        if (viewer != null && viewerId.equals(viewer.getUniqueId())) {
            viewer.setItemOnCursor(cloneOrNull(originalCursor));
        }
        closed = true;
    }

    boolean isClosed() {
        return closed;
    }

    boolean isBotUsable() {
        try {
            return bot.isBotAlive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    ItemStack[] editableContents() {
        ItemStack[] contents = new ItemStack[EDITABLE_SLOTS];
        for (int i = 0; i < EDITABLE_SLOTS; i++) {
            ItemStack item = inventory.getItem(i);
            contents[i] = item == null ? null : item.clone();
        }
        return contents;
    }

    private void render() {
        inventory.clear();
        PlayerInventory playerInventory = bot.getBukkitEntity().getInventory();
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, cloneOrNull(playerInventory.getItem(i)));
        }
        inventory.setItem(BOOTS_SLOT, cloneOrNull(playerInventory.getBoots()));
        inventory.setItem(LEGGINGS_SLOT, cloneOrNull(playerInventory.getLeggings()));
        inventory.setItem(CHEST_SLOT, cloneOrNull(playerInventory.getChestplate()));
        inventory.setItem(HELMET_SLOT, cloneOrNull(playerInventory.getHelmet()));
        inventory.setItem(OFFHAND_SLOT, cloneOrNull(playerInventory.getItemInOffHand()));

        ItemStack filler = filler();
        for (int slot = EDITABLE_SLOTS; slot < SIZE; slot++) {
            if (!isControlSlot(slot)) inventory.setItem(slot, filler.clone());
        }
        renderControls();
    }

    private void renderControls() {
        inventory.setItem(AUTO_EQUIP_SLOT, item(
                autoEquip ? Material.LIME_DYE : Material.GRAY_DYE,
                "Auto-equip on save: " + (autoEquip ? "ON" : "OFF"),
                autoEquip ? "Save will use the deterministic combat layout."
                        : "Save keeps every item in its edited slot."));
        inventory.setItem(SAVE_SLOT, item(Material.LIME_WOOL, "Save changes",
                "Apply the edited 41-slot inventory to " + bot.getBotName()));
        inventory.setItem(STATUS_SLOT, item(Material.PAPER, "Bot: " + bot.getBotName(),
                "UUID: " + bot.getUUID(),
                "Changes stay local until Save.",
                "Auto-equip: " + (autoEquip ? "on" : "off")));
        inventory.setItem(DISCARD_SLOT, item(Material.RED_WOOL, "Discard changes",
                "Close without changing " + bot.getBotName()));
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, "Close (discard)",
                "Unsaved changes will be discarded."));
    }

    static boolean isEditableSlot(int slot) {
        return slot >= 0 && slot < EDITABLE_SLOTS;
    }

    static boolean isControlSlot(int slot) {
        return slot == AUTO_EQUIP_SLOT || slot == SAVE_SLOT || slot == STATUS_SLOT
                || slot == DISCARD_SLOT || slot == CLOSE_SLOT;
    }

    /** Slots that are decorative and cannot receive or provide items. */
    public static boolean isFillerSlot(int slot) {
        return slot >= EDITABLE_SLOTS && slot < SIZE && !isControlSlot(slot);
    }

    public static boolean isArmorOrOffhand(int slot) {
        return slot >= BOOTS_SLOT && slot <= OFFHAND_SLOT;
    }

    static Control actionForSlot(int slot) {
        if (slot == AUTO_EQUIP_SLOT) return Control.AUTO_EQUIP;
        if (slot == SAVE_SLOT) return Control.SAVE;
        if (slot == DISCARD_SLOT) return Control.DISCARD;
        if (slot == CLOSE_SLOT) return Control.CLOSE;
        return null;
    }

    /** Check the only slots that a chest inventory cannot validate itself. */
    static String validationError(ItemStack[] contents) {
        if (contents == null || contents.length < EDITABLE_SLOTS) return "The editor contents are incomplete.";
        for (int slot = BOOTS_SLOT; slot <= HELMET_SLOT; slot++) {
            if (!isValidItemForSlot(slot, contents[slot])) {
                return "That item cannot be placed in equipment slot " + slot + ".";
            }
        }
        return null;
    }

    static boolean isValidItemForSlot(int slot, ItemStack item) {
        return item == null || isValidMaterialForSlot(slot, item.getType());
    }

    static boolean isValidMaterialForSlot(int slot, Material material) {
        if (material == null || material == Material.AIR || material == Material.CAVE_AIR
                || material == Material.VOID_AIR || !isEditableSlot(slot)) return true;
        String name = material.name();
        return switch (slot) {
            case BOOTS_SLOT -> name.endsWith("_BOOTS");
            case LEGGINGS_SLOT -> name.endsWith("_LEGGINGS");
            case CHEST_SLOT -> name.endsWith("_CHESTPLATE") || name.equals("ELYTRA");
            case HELMET_SLOT -> name.endsWith("_HELMET") || name.equals("CARVED_PUMPKIN");
            default -> true;
        };
    }

    static final class EditState {
        private final ItemStack[] original;
        private final int selectedHotbarSlot;

        EditState(ItemStack[] original, int selectedHotbarSlot) {
            this.original = copy(original, EDITABLE_SLOTS);
            this.selectedHotbarSlot = selectedHotbarSlot;
        }

        static EditState capture(Bot bot) {
            PlayerInventory inventory = bot.getBukkitEntity().getInventory();
            ItemStack[] snapshot = new ItemStack[EDITABLE_SLOTS];
            for (int i = 0; i < 36; i++) snapshot[i] = inventory.getItem(i);
            snapshot[BOOTS_SLOT] = inventory.getBoots();
            snapshot[LEGGINGS_SLOT] = inventory.getLeggings();
            snapshot[CHEST_SLOT] = inventory.getChestplate();
            snapshot[HELMET_SLOT] = inventory.getHelmet();
            snapshot[OFFHAND_SLOT] = inventory.getItemInOffHand();
            return new EditState(snapshot, bot.getBotInventory().getSelectedHotbarSlot());
        }

        boolean changed(ItemStack[] current) {
            ItemStack[] normalized = copy(current, EDITABLE_SLOTS);
            for (int i = 0; i < EDITABLE_SLOTS; i++) {
                if (!Objects.equals(original[i], normalized[i])) return true;
            }
            return false;
        }

        int selectedHotbarSlot() {
            return selectedHotbarSlot;
        }
    }

    enum Control {
        AUTO_EQUIP,
        SAVE,
        DISCARD,
        CLOSE
    }

    private static ItemStack[] copy(ItemStack[] source, int length) {
        ItemStack[] result = new ItemStack[length];
        if (source == null) return result;
        for (int i = 0; i < length && i < source.length; i++) {
            result[i] = cloneOrNull(source[i]);
        }
        return result;
    }

    private static ItemStack cloneOrNull(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private static ItemStack safe(ItemStack item) {
        return item == null || item.getType().isAir() ? new ItemStack(Material.AIR) : item.clone();
    }

    private static ItemStack filler() {
        return item(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RESET + name);
            if (lore.length > 0) {
                java.util.List<String> lines = new java.util.ArrayList<>(lore.length);
                for (String line : lore) lines.add(ChatColor.GRAY + line);
                meta.setLore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}
