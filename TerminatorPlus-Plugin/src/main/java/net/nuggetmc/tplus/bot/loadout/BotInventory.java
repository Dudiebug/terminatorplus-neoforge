package net.nuggetmc.tplus.bot.loadout;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.inventory.EquipmentSlot;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.inventory.PlayerInventory;
import net.nuggetmc.tplus.compat.bukkit.inventory.meta.PotionMeta;
import net.nuggetmc.tplus.compat.bukkit.potion.PotionType;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper around a bot's underlying PlayerInventory that adds a tracked
 * "selected hotbar slot" (since bots are not real players and don't get
 * ClientboundSetCarriedItemPacket state from a client) and a handful of
 * convenience queries used by the combat / weapon-switching layer.
 *
 * All item storage lives on the bot's native Bukkit PlayerInventory, so
 * the chest-based GUI editor can read and write the same 41 slots
 * (9 hotbar + 27 storage + 4 armor + 1 offhand).
 */
public final class BotInventory {

    public static final int HOTBAR_SIZE = 9;

    // Materials placed into the hotbar in this priority order by autoEquip().
    private static final List<Material> HOTBAR_PRIORITY = List.of(
        Material.MACE,
        Material.NETHERITE_SWORD, Material.DIAMOND_SWORD, Material.IRON_SWORD,
        Material.STONE_SWORD, Material.GOLDEN_SWORD, Material.WOODEN_SWORD,
        Material.TRIDENT,
        Material.NETHERITE_AXE, Material.DIAMOND_AXE, Material.IRON_AXE,
        Material.STONE_AXE, Material.GOLDEN_AXE, Material.WOODEN_AXE,
        Material.WIND_CHARGE,
        Material.ENDER_PEARL,
        Material.BOW, Material.CROSSBOW,
        Material.END_CRYSTAL,    // OBSIDIAN paired inline
        Material.RESPAWN_ANCHOR, // GLOWSTONE paired inline
        Material.COBWEB,
        Material.FIREWORK_ROCKET,
        Material.ENCHANTED_GOLDEN_APPLE, Material.GOLDEN_APPLE,
        Material.TOTEM_OF_UNDYING
    );

    private final Bot bot;
    private int selectedHotbarSlot;
    /**
     * When true, {@link #ensureMovementKit()} is a no-op — the bot's loadout
     * is considered authoritative, including the deliberate absence of pearls /
     * wind charges. Flipped on by {@link #markLoadoutApplied()} after
     * {@code /bot loadout <name>} or a GUI save, flipped off by
     * {@link #markLoadoutCleared()} (the {@code clear} preset or a manual reset).
     *
     * <p>Without this flag a user who drags pearls out of the GUI sees them
     * re-appear two seconds later, silently overwriting their intent.
     */
    private boolean respectLoadout;
    private SavedInventory savedInventory;

    public BotInventory(Bot bot) {
        this.bot = bot;
        this.selectedHotbarSlot = 0;
        this.respectLoadout = false;
        this.savedInventory = null;
    }

    /**
     * Mark the current inventory as a deliberate loadout application — subsequent
     * {@link #ensureMovementKit()} calls will not re-stock pearls / wind charges
     * that the loadout chose to omit.
     */
    public void markLoadoutApplied() {
        saveForRespawn();
        this.respectLoadout = true;
    }

    /** Save the current deliberate inventory state for future autorespawns. */
    public void saveForRespawn() {
        PlayerInventory inventory = raw();
        this.savedInventory = new SavedInventory(
                copy(inventory.getStorageContents()),
                copy(inventory.getArmorContents()),
                copy(inventory.getExtraContents()),
                selectedHotbarSlot);
    }

    /**
     * Release the loadout lock so the baseline movement kit refill resumes.
     * Called by the {@code clear} preset and by any future {@code /bot inv reset} path.
     */
    public void markLoadoutCleared() {
        saveForRespawn();
        this.respectLoadout = false;
    }

    /**
     * @return whether {@link #ensureMovementKit()} will skip on the next tick
     *         because a loadout is considered authoritative.
     */
    public boolean isRespectingLoadout() {
        return respectLoadout;
    }

    public ItemStack[] respawnStorageContents() {
        return respawnContents(savedInventory == null ? null : savedInventory.storage(), raw().getStorageContents());
    }

    public ItemStack[] respawnArmorContents() {
        return respawnContents(savedInventory == null ? null : savedInventory.armor(), raw().getArmorContents());
    }

    public ItemStack[] respawnExtraContents() {
        return respawnContents(savedInventory == null ? null : savedInventory.extra(), raw().getExtraContents());
    }

    public int respawnSelectedHotbarSlot() {
        return savedInventory != null
                ? savedInventory.selectedHotbarSlot()
                : selectedHotbarSlot;
    }

    static ItemStack[] respawnContents(ItemStack[] saved, ItemStack[] current) {
        return copy(saved != null ? saved : current);
    }

    private static ItemStack[] copy(ItemStack[] contents) {
        if (contents == null) return new ItemStack[0];
        ItemStack[] result = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            result[i] = contents[i] == null ? null : contents[i].clone();
        }
        return result;
    }

    private record SavedInventory(
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack[] extra,
            int selectedHotbarSlot
    ) {
    }

    public Bot getBot() {
        return bot;
    }

    public PlayerInventory raw() {
        return bot.getBukkitEntity().getInventory();
    }

    public int getSelectedHotbarSlot() {
        return selectedHotbarSlot;
    }

    /**
     * Change the selected hotbar slot and send the mainhand-equipment
     * packet so viewers see the swap.
     *
     * <p>Must sync the NMS {@code Inventory.selected} field too. Our
     * own {@link #selectedHotbarSlot} tracker is a Bukkit-side mirror;
     * {@code bot.setItem(item, HAND)} underneath calls
     * {@code setItemInMainHand} which writes into whichever slot the
     * NMS inventory thinks is selected. Without the NMS sync that was
     * always slot 0 (the default), so every weapon swap silently
     * overwrote slot 0 with the newly-selected item — the mace-
     * disappearing bug.
     */
    public void setSelectedHotbarSlot(int slot) {
        if (slot < 0 || slot >= HOTBAR_SIZE) return;
        // No-op early return: CombatDirector / OpportunityScanner call this
        // every tick with the slot the bot is already holding. Without this
        // guard, repeatedly re-advertising and rewriting the held stack used
        // to create a brand-new mainhand reference every tick. Vanilla
        // Player.tick's item-change detection (getMainHandItem() !=
        // lastItemInMainHand) fires on reference inequality and calls
        // resetAttackStrengthTicker() — pinning the attack charge at ~0.08
        // forever so canSwing's 0.95 gate never passes and the bot never
        // swings or crits. With the guard, same-slot calls are no-ops, the
        // ticker climbs unmolested, and the bot attacks at vanilla cadence.
        if (this.selectedHotbarSlot == slot) return;
        this.selectedHotbarSlot = slot;
        // Sync NMS through Bukkit because the NMS selected field is private in
        // Paper 26.x, then advertise the now-selected stack to viewers.
        raw().setHeldItemSlot(slot);
        bot.refreshMainHandEquipment();
    }

    public ItemStack getSelected() {
        return getHotbar(selectedHotbarSlot);
    }

    public ItemStack getHotbar(int slot) {
        if (slot < 0 || slot >= HOTBAR_SIZE) return new ItemStack(Material.AIR);
        ItemStack item = raw().getItem(slot);
        return item == null ? new ItemStack(Material.AIR) : item;
    }

    /** Find an already-empty hotbar slot, preferring the selected slot, without changing inventory. */
    public int findEmptyHotbarSlot() {
        PlayerInventory inv = raw();
        ItemStack selected = inv.getItem(selectedHotbarSlot);
        if (selected == null || selected.getType() == Material.AIR) return selectedHotbarSlot;
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (i == selectedHotbarSlot) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType() == Material.AIR) return i;
        }
        return -1;
    }

    /** Find a hotbar slot (0-8) containing {@code type}. */
    public int findHotbarOnly(Material type) {
        if (type == null) return -1;
        PlayerInventory inv = raw();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == type) return i;
        }
        return -1;
    }

    /**
     * Find a slot anywhere in the main 36 inventory slots (hotbar + storage)
     * containing {@code type}. Prefers hotbar matches so the common case
     * (weapon already on the bar) is O(9). Returns -1 if not found.
     */
    public int findMainInventory(Material type) {
        int hotbar = findHotbarOnly(type);
        if (hotbar >= 0) return hotbar;
        if (type == null) return -1;
        PlayerInventory inv = raw();
        for (int i = HOTBAR_SIZE; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == type) return i;
        }
        return -1;
    }

    /** Backwards-compatible alias for the old "hotbar first, then storage" helper. */
    public int findHotbar(Material type) {
        return findMainInventory(type);
    }

    /** Any slot in the main inventory contains a stack of {@code type}. */
    public boolean hasMainInventory(Material type) {
        return findMainInventory(type) >= 0;
    }

    /** Backwards-compatible alias for main-inventory presence. */
    public boolean hasHotbar(Material type) {
        return hasMainInventory(type);
    }

    /**
     * If {@code slot} is in storage (9–35), swap it with a hotbar slot so it
     * can be wielded. Skips hotbar slots that already hold a primary weapon
     * (sword/axe/mace) so we don't kick a real melee weapon out for a utility
     * item. Returns the new hotbar index (0–8), the original slot if already
     * on the hotbar, or -1 if no swap target was available.
     */
    public int promoteToHotbar(int slot) {
        if (slot < 0 || slot >= 36) return -1;
        if (slot < HOTBAR_SIZE) return slot;
        PlayerInventory inv = raw();
        int target = -1;
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) { target = i; break; }
        }
        if (target < 0) {
            // Hotbar full: pick the rightmost non-weapon slot to evict.
            for (int i = HOTBAR_SIZE - 1; i >= 0; i--) {
                ItemStack it = inv.getItem(i);
                if (it == null) continue;
                if (isMeleeWeapon(it)) continue;
                target = i;
                break;
            }
        }
        if (target < 0) return -1;
        ItemStack moving = inv.getItem(slot);
        ItemStack evicted = inv.getItem(target);
        net.minecraft.world.entity.player.Inventory nms = bot.getInventory();
        nms.setItem(target, moving == null ? net.minecraft.world.item.ItemStack.EMPTY
                : net.nuggetmc.tplus.compat.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(moving));
        nms.setItem(slot, evicted == null ? net.minecraft.world.item.ItemStack.EMPTY
                : net.nuggetmc.tplus.compat.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(evicted));
        nms.setChanged();
        return target;
    }

    /**
     * Temporarily expose a storage item to a player action. Normal promotion
     * protects melee slots; a short-lived action may borrow the selected slot
     * when every hotbar slot is a weapon because the caller swaps it back on
     * completion.
     */
    public int leaseToHotbar(int slot) {
        int promoted = promoteToHotbar(slot);
        if (promoted >= 0 || slot < HOTBAR_SIZE || slot >= 36) return promoted;
        return swapMainInventorySlots(slot, selectedHotbarSlot) ? selectedHotbarSlot : -1;
    }

    /** Re-send the item currently occupying the selected slot after an in-place lease swap. */
    public void refreshSelectedItem() {
        raw().setHeldItemSlot(selectedHotbarSlot);
        bot.refreshMainHandEquipment();
    }

    /**
     * Swap two main-inventory slots through the same NMS direct-write path used
     * by utility-item promotion. Traversal actions use this to return a leased
     * item to storage instead of permanently rearranging the bot's loadout.
     */
    public boolean swapMainInventorySlots(int first, int second) {
        if (first < 0 || first >= 36 || second < 0 || second >= 36 || first == second) {
            return false;
        }
        PlayerInventory inv = raw();
        ItemStack firstItem = inv.getItem(first);
        ItemStack secondItem = inv.getItem(second);
        net.minecraft.world.entity.player.Inventory nms = bot.getInventory();
        nms.setItem(first, secondItem == null ? net.minecraft.world.item.ItemStack.EMPTY
                : net.nuggetmc.tplus.compat.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(secondItem));
        nms.setItem(second, firstItem == null ? net.minecraft.world.item.ItemStack.EMPTY
                : net.nuggetmc.tplus.compat.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(firstItem));
        nms.setChanged();
        return true;
    }

    /** Backwards-compatible alias while callers migrate to the clearer name. */
    public int bringToHotbar(int slot) {
        return promoteToHotbar(slot);
    }

    /**
     * Promote a main-inventory slot to the hotbar if needed and select it.
     *
     * @return the selected hotbar slot, or -1 if the item could not be selected
     */
    public int selectMainInventorySlot(int slot) {
        int hotbar = promoteToHotbar(slot);
        if (hotbar < 0) return -1;
        setSelectedHotbarSlot(hotbar);
        return hotbar;
    }

    /** Find {@code type}, promote it to the hotbar if needed, then select it. */
    public int selectMaterial(Material type) {
        return selectMainInventorySlot(findMainInventory(type));
    }

    /** Consume up to {@code amount} items from one main-inventory slot via NMS. */
    public boolean decrementMainInventorySlot(int slot, int amount) {
        if (slot < 0 || slot >= 36 || amount <= 0) return false;
        ItemStack current = raw().getItem(slot);
        if (current == null || current.getType() == Material.AIR) return false;
        int nextAmount = current.getAmount() - amount;
        ItemStack next = current.clone();
        if (nextAmount <= 0) {
            next = new ItemStack(Material.AIR);
        } else {
            next.setAmount(nextAmount);
        }
        return setMainInventorySlot(slot, next);
    }

    /** Consume one matching material from main inventory, hotbar first. */
    public boolean decrementMaterial(Material type) {
        return decrementMaterial(type, 1);
    }

    public boolean decrementMaterial(Material type, int amount) {
        return decrementMainInventorySlot(findMainInventory(type), amount);
    }

    /** Consume from main inventory first, then offhand. Useful for wind-charge mace kits. */
    public boolean decrementMaterialOrOffhand(Material type) {
        if (decrementMaterial(type, 1)) return true;
        ItemStack off = raw().getItemInOffHand();
        if (off == null || off.getType() != type) return false;
        int nextAmount = off.getAmount() - 1;
        if (nextAmount <= 0) {
            bot.setItemOffhand(new ItemStack(Material.AIR));
        } else {
            ItemStack next = off.clone();
            next.setAmount(nextAmount);
            bot.setItemOffhand(next);
        }
        return true;
    }

    /** Restore a prior selected slot if valid, otherwise select the best carried melee weapon. */
    public void restoreSelectedSlotOrBestWeapon(int previousSlot) {
        if (isSelectableWeaponSlot(previousSlot)) {
            setSelectedHotbarSlot(previousSlot);
            return;
        }
        selectBestMeleeWeapon();
    }

    /** Restore the exact hotbar selection held before a temporary player action. */
    public void restoreSelectedSlot(int previousSlot) {
        if (previousSlot >= 0 && previousSlot < HOTBAR_SIZE) {
            setSelectedHotbarSlot(previousSlot);
        }
    }

    public int selectBestMeleeWeapon() {
        int slot = findBestMeleeWeaponSlot();
        return selectMainInventorySlot(slot);
    }

    public int findBestMeleeWeaponSlot() {
        int mace = findMainInventory(Material.MACE);
        if (mace >= 0) return mace;
        int sword = findSword();
        if (sword >= 0) return sword;
        int axe = findAxe();
        if (axe >= 0) return axe;
        return findMainInventory(Material.TRIDENT);
    }

    public boolean isSelectedMeleeWeapon() {
        return isMeleeWeapon(getSelected());
    }

    public boolean isSelectableWeaponSlot(int slot) {
        if (slot < 0 || slot >= HOTBAR_SIZE) return false;
        return isMeleeWeapon(getHotbar(slot));
    }

    public static boolean isMeleeWeapon(ItemStack stack) {
        if (stack == null) return false;
        Material m = stack.getType();
        if (m == Material.AIR) return false;
        if (m == Material.MACE || m == Material.TRIDENT) return true;
        String name = m.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE");
    }

    public boolean hasMace() {
        return hasHotbar(Material.MACE);
    }

    public boolean hasTrident() {
        return hasHotbar(Material.TRIDENT);
    }

    public boolean hasWindCharge() {
        if (hasHotbar(Material.WIND_CHARGE)) return true;
        // Mace loadouts stash wind charges in the offhand (see autoEquip
        // offhand priority), so the gates of WIND_MACE_SMASH, interrupt
        // plays, etc. would otherwise think the bot has none.
        ItemStack off = raw().getItemInOffHand();
        return off != null && off.getType() == Material.WIND_CHARGE;
    }

    public boolean hasFirework() {
        return hasHotbar(Material.FIREWORK_ROCKET);
    }

    public boolean hasEnderPearl() {
        return hasHotbar(Material.ENDER_PEARL);
    }

    /** Any slot (not only hotbar) holds a totem of undying. */
    public boolean hasTotem() {
        if (findMainInventory(Material.TOTEM_OF_UNDYING) >= 0) return true;
        PlayerInventory inv = raw();
        ItemStack off = inv.getItemInOffHand();
        return off != null && off.getType() == Material.TOTEM_OF_UNDYING;
    }

    /** True if the bot has both an end crystal and a host block (obsidian or, in nether, glowstone). */
    public boolean hasCrystalKit() {
        return hasHotbar(Material.END_CRYSTAL)
                && (hasHotbar(Material.OBSIDIAN) || hasHotbar(Material.GLOWSTONE));
    }

    public boolean hasAnchorKit() {
        return hasHotbar(Material.RESPAWN_ANCHOR) && hasHotbar(Material.GLOWSTONE);
    }

    public boolean hasCobweb() {
        return hasHotbar(Material.COBWEB);
    }

    public boolean hasBow() {
        return hasHotbar(Material.BOW);
    }

    public boolean hasCrossbow() {
        return hasHotbar(Material.CROSSBOW);
    }

    public boolean hasElytra() {
        ItemStack chest = raw().getChestplate();
        if (chest != null && chest.getType() == Material.ELYTRA) return true;
        return findStoredChestpieceOfType(Material.ELYTRA) >= 0;
    }

    /**
     * Search the full inventory (storage + hotbar + offhand) for a
     * chestplate-style item (ELYTRA or any *_CHESTPLATE). Returns the
     * raw inventory slot index, or -1.
     */
    public int findStoredChestpieceOfType(Material type) {
        int main = findMainInventory(type);
        if (main >= 0) return main;

        PlayerInventory inv = raw();
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() == type) return 40;
        return -1;
    }

    /**
     * If the chest slot doesn't already hold {@code desired}, swap it
     * with the first instance found elsewhere in the inventory. Does
     * nothing if no such item is carried.
     *
     * @return true if a swap happened
     */
    public boolean swapChest(Material desired) {
        PlayerInventory inv = raw();
        ItemStack current = inv.getChestplate();
        if (current != null && current.getType() == desired) return false;

        int slot = findStoredChestpieceOfType(desired);
        if (slot < 0) return false;

        ItemStack stored = inv.getItem(slot);
        ItemStack chestPrev = current == null ? new ItemStack(Material.AIR) : current.clone();

        bot.setItem(stored.clone(), EquipmentSlot.CHEST);
        if (slot == 40) {
            inv.setItemInOffHand(chestPrev);
        } else {
            setMainInventorySlot(slot, chestPrev);
        }
        return true;
    }

    /** First slot (hotbar first, then storage) whose item is a sword, or -1. */
    public int findSword() {
        return findByNameSuffix("_SWORD");
    }

    public int findAxe() {
        return findByNameSuffix("_AXE");
    }

    private int findByNameSuffix(String suffix) {
        PlayerInventory inv = raw();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType().name().endsWith(suffix)) return i;
        }
        for (int i = HOTBAR_SIZE; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType().name().endsWith(suffix)) return i;
        }
        return -1;
    }

    /** Heuristic: first hotbar slot that heals (golden apple / potion of healing / totem / splash healing). */
    public int findHealing() {
        PlayerInventory inv = raw();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            Material m = it.getType();
            if (m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE
                    || m == Material.TOTEM_OF_UNDYING) {
                return i;
            }
            if (m == Material.POTION || m == Material.SPLASH_POTION) {
                if (isHealingPotion(it)) return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Consumable / potion finders
    //
    // These scan the main inventory, hotbar first. Callers that need to use
    // the item must pass the returned slot through selectMainInventorySlot().
    // -------------------------------------------------------------------------

    /** First main-inventory slot matching {@code type} + (optionally) {@code potionTypes}. Returns -1 if none. */
    public int findHotbarPotion(Material container, PotionType... potionTypes) {
        if (container == null) return -1;
        PlayerInventory inv = raw();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != container) continue;
            if (!(it.getItemMeta() instanceof PotionMeta pm)) continue;
            PotionType pt = pm.getBasePotionType();
            if (pt == null) continue;
            if (potionTypes == null || potionTypes.length == 0) return i;
            for (PotionType wanted : potionTypes) {
                if (pt == wanted) return i;
            }
        }
        return -1;
    }

    /** First main-inventory drinkable potion with a healing base. */
    public int findHealingPotion() {
        return findHotbarPotion(Material.POTION, PotionType.HEALING, PotionType.STRONG_HEALING);
    }

    /** First main-inventory splash potion of healing (for on-self self-heal). */
    public int findSplashHealing() {
        return findHotbarPotion(Material.SPLASH_POTION, PotionType.HEALING, PotionType.STRONG_HEALING);
    }

    /** First main-inventory drinkable strength potion (for pre-combat buff-up). */
    public int findStrengthPotion() {
        return findHotbarPotion(Material.POTION, PotionType.STRENGTH, PotionType.STRONG_STRENGTH);
    }

    /** First main-inventory drinkable fire-resistance potion. */
    public int findFireResPotion() {
        return findHotbarPotion(Material.POTION, PotionType.FIRE_RESISTANCE, PotionType.LONG_FIRE_RESISTANCE);
    }

    /** First main-inventory offensive splash (harming / poison / slowness). Used against enemies. */
    public int findSplashHarming() {
        return findHotbarPotion(Material.SPLASH_POTION,
                PotionType.HARMING, PotionType.STRONG_HARMING,
                PotionType.POISON, PotionType.STRONG_POISON, PotionType.LONG_POISON,
                PotionType.SLOWNESS, PotionType.STRONG_SLOWNESS, PotionType.LONG_SLOWNESS);
    }

    /** Cheap pre-check: does the bot have ANY consumable (apple / potion / splash) in main inventory? */
    public boolean hasAnyConsumable() {
        PlayerInventory inv = raw();
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            Material m = it.getType();
            if (m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE) return true;
            if (m == Material.POTION || m == Material.SPLASH_POTION || m == Material.LINGERING_POTION) return true;
        }
        return false;
    }

    /** True if this stack is a drinkable or splash potion whose base type heals. */
    private static boolean isHealingPotion(ItemStack it) {
        if (!(it.getItemMeta() instanceof PotionMeta pm)) return false;
        PotionType pt = pm.getBasePotionType();
        return pt == PotionType.HEALING || pt == PotionType.STRONG_HEALING;
    }

    /**
     * Move the given material onto the offhand slot from any other slot.
     * If something else was in the offhand, park it into the source slot so
     * the swap is a true exchange. Returns true on swap.
     */
    public boolean equipOffhand(Material desired) {
        PlayerInventory inv = raw();
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() == desired) return false;

        int source = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && it.getType() == desired) {
                source = i;
                break;
            }
        }
        if (source < 0) return false;

        ItemStack pickup = inv.getItem(source).clone();
        ItemStack previous = off == null ? new ItemStack(Material.AIR) : off.clone();
        bot.setItemOffhand(pickup);
        setMainInventorySlot(source, previous);
        return true;
    }

    // -------------------------------------------------------------------------
    // Explicit inventory copy utility for callers that intentionally clone a kit.
    // -------------------------------------------------------------------------

    /**
     * Copy this bot's current full inventory (post-autoEquip state) to
     * {@code target}, overwriting its hotbar, storage, armor, offhand, and
     * selected hotbar slot.
     */
    public void copyInventoryTo(Bot target) {
        PlayerInventory src = raw();
        net.minecraft.world.entity.player.Inventory nmsTarget = target.getInventory();
        for (int i = 0; i < 36; i++) {
            setMainSlotIfChanged(nmsTarget, i, src.getItem(i));
        }
        nmsTarget.setChanged();

        ItemStack h   = src.getHelmet(),      ch  = src.getChestplate(),
                  l   = src.getLeggings(),    b2  = src.getBoots(),
                  off = src.getItemInOffHand();
        target.setItem(h   != null ? h   : new ItemStack(Material.AIR), EquipmentSlot.HEAD);
        target.setItem(ch  != null ? ch  : new ItemStack(Material.AIR), EquipmentSlot.CHEST);
        target.setItem(l   != null ? l   : new ItemStack(Material.AIR), EquipmentSlot.LEGS);
        target.setItem(b2  != null ? b2  : new ItemStack(Material.AIR), EquipmentSlot.FEET);
        target.setItemOffhand(off != null ? off : new ItemStack(Material.AIR));
        target.getBotInventory().setSelectedHotbarSlot(selectedHotbarSlot);
    }

    // -------------------------------------------------------------------------
    // Auto-equip
    // -------------------------------------------------------------------------

    /**
     * Examines every item in the bot's full inventory (hotbar, storage, current
     * armor, offhand) and reorganises them into the best combat configuration:
     * best armor → armor slots, weapons/tools → hotbar (by priority), leftover → storage.
     *
     * Called explicitly when a user opts into auto-equip while saving an
     * inventory edit, or by command/loadout code that needs the deterministic
     * combat layout.
     */
    public void autoEquip() {
        PlayerInventory inv = raw();

        // Collect the entire inventory into a mutable pool (cloned items).
        List<ItemStack> pool = new ArrayList<>();
        for (int i = 0; i < 36; i++) addToPool(pool, inv.getItem(i));
        addToPool(pool, inv.getHelmet());
        addToPool(pool, inv.getChestplate());
        addToPool(pool, inv.getLeggings());
        addToPool(pool, inv.getBoots());
        addToPool(pool, inv.getItemInOffHand());

        // Clear everything.
        clearAll();

        // Armor slots.
        pickAndEquip(pool, EquipmentSlot.HEAD,  "_HELMET");
        pickAndEquipChest(pool);
        pickAndEquip(pool, EquipmentSlot.LEGS,  "_LEGGINGS");
        pickAndEquip(pool, EquipmentSlot.FEET,  "_BOOTS");

        // Offhand priority:
        //  1. Wind charges, IF the bot has a mace too — wiki Mace-PvP kit
        //     pairs the two so the bot can throw a wind charge from offhand
        //     for a launch without swapping main-hand off the mace (which
        //     would reset the attack-strength ticker and downgrade the
        //     smash from a crit to a normal swing).
        //  2. Totem of undying (clutch hit savior).
        //  3. Shield.
        boolean hasMaceInPool = false;
        for (ItemStack p : pool) {
            if (p != null && p.getType() == Material.MACE) { hasMaceInPool = true; break; }
        }
        boolean offhandSet = false;
        if (hasMaceInPool) {
            ItemStack wind = removeFirst(pool, Material.WIND_CHARGE);
            if (wind != null) {
                if (wind.getAmount() < 16) wind.setAmount(16);
                bot.setItemOffhand(wind);
                offhandSet = true;
            }
        }
        if (!offhandSet) {
            for (Material m : new Material[]{Material.TOTEM_OF_UNDYING, Material.SHIELD}) {
                ItemStack found = removeFirst(pool, m);
                if (found != null) { bot.setItemOffhand(found); break; }
            }
        }

        // Hotbar: fill by priority order.
        int hotbarSlot = 0;
        for (Material wanted : HOTBAR_PRIORITY) {
            if (hotbarSlot >= HOTBAR_SIZE) break;
            ItemStack found = removeFirst(pool, wanted);
            if (found == null) continue;
            nmsSet(hotbarSlot++, found);
            // Crystal / anchor kits need their support block adjacent in hotbar.
            if (wanted == Material.END_CRYSTAL && hotbarSlot < HOTBAR_SIZE) {
                ItemStack obs = removeFirst(pool, Material.OBSIDIAN);
                if (obs != null) nmsSet(hotbarSlot++, obs);
            } else if (wanted == Material.RESPAWN_ANCHOR && hotbarSlot < HOTBAR_SIZE) {
                ItemStack glow = removeFirst(pool, Material.GLOWSTONE);
                if (glow != null) nmsSet(hotbarSlot++, glow);
            }
        }

        // Dump remainder into storage (slots 9–35).
        int storage = 9;
        for (ItemStack leftover : pool) {
            if (storage >= 36) break;
            nmsSet(storage++, leftover);
        }

        // Select the first weapon/melee slot, or slot 0.
        setSelectedHotbarSlot(findPrimaryWeaponSlot());
    }

    // ---- private helpers ----

    /**
     * Apply a 36-slot main inventory snapshot (hotbar + storage) through NMS with
     * content-equality checks to avoid needless stack-reference churn.
     *
     * <p>Passing a 41-slot preset snapshot is supported; only indices 0-35 are read.
     * Missing entries are treated as AIR.
     */
    public void applyMainInventorySnapshot(ItemStack[] snapshot) {
        net.minecraft.world.entity.player.Inventory nmsInv = bot.getInventory();
        boolean changed = false;
        for (int i = 0; i < 36; i++) {
            ItemStack next = (snapshot != null && i < snapshot.length) ? snapshot[i] : null;
            if (setMainSlotIfChanged(nmsInv, i, next)) {
                changed = true;
            }
        }
        if (changed) {
            nmsInv.setChanged();
        }
    }

    /**
     * Write one main-inventory slot via NMS, skipping writes when the incoming
     * item is content-equal to the current stack.
     *
     * @return true if the underlying slot changed
     */
    public boolean setMainInventorySlot(int slot, ItemStack item) {
        if (slot < 0 || slot >= 36) return false;
        net.minecraft.world.entity.player.Inventory nmsInv = bot.getInventory();
        boolean changed = setMainSlotIfChanged(nmsInv, slot, item);
        if (changed) {
            nmsInv.setChanged();
        }
        return changed;
    }

    /** Write directly to the NMS Inventory, bypassing the Bukkit container transaction system. */
    private void nmsSet(int slot, ItemStack bukkit_item) {
        net.minecraft.world.entity.player.Inventory nmsInv = bot.getInventory();
        if (setMainSlotIfChanged(nmsInv, slot, bukkit_item)) {
            nmsInv.setChanged();
        }
    }

    /** Clear all 36 main slots via NMS, then clear armor/offhand via bot.setItem (sends packets). */
    private void clearAll() {
        net.minecraft.world.entity.player.Inventory nmsInv = bot.getInventory();
        for (int i = 0; i < 36; i++) nmsInv.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
        nmsInv.setChanged();
        bot.setItem(new ItemStack(Material.AIR), EquipmentSlot.HEAD);
        bot.setItem(new ItemStack(Material.AIR), EquipmentSlot.CHEST);
        bot.setItem(new ItemStack(Material.AIR), EquipmentSlot.LEGS);
        bot.setItem(new ItemStack(Material.AIR), EquipmentSlot.FEET);
        bot.setItemOffhand(new ItemStack(Material.AIR));
    }

    private void pickAndEquip(List<ItemStack> pool, EquipmentSlot slot, String suffix) {
        ItemStack best = null;
        int bestTier = -1;
        for (ItemStack it : pool) {
            String n = it.getType().name();
            boolean match = n.endsWith(suffix)
                    || (suffix.equals("_HELMET") && n.equals("TURTLE_HELMET"));
            if (match && armorTier(it.getType()) > bestTier) {
                best = it;
                bestTier = armorTier(it.getType());
            }
        }
        if (best != null) {
            pool.remove(best);
            bot.setItem(best, slot);
        }
    }

    /**
     * Chest slot: elytra if fireworks are in the pool (skydiver mode),
     * otherwise best chestplate, falling back to elytra if no chestplate.
     */
    private void pickAndEquipChest(List<ItemStack> pool) {
        boolean hasFireworks = pool.stream().anyMatch(it -> it.getType() == Material.FIREWORK_ROCKET);
        ItemStack elytra = findFirst(pool, Material.ELYTRA);
        ItemStack bestChest = null;
        int bestTier = -1;
        for (ItemStack it : pool) {
            if (it.getType().name().endsWith("_CHESTPLATE") && armorTier(it.getType()) > bestTier) {
                bestChest = it;
                bestTier = armorTier(it.getType());
            }
        }
        ItemStack toEquip = (hasFireworks && elytra != null) ? elytra
                : bestChest != null ? bestChest
                : elytra;
        if (toEquip != null) {
            pool.remove(toEquip);
            bot.setItem(toEquip, EquipmentSlot.CHEST);
        }
    }

    private int findPrimaryWeaponSlot() {
        PlayerInventory inv = raw();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            if (isMeleeWeapon(inv.getItem(i))) return i;
        }
        return 0;
    }

    /** Numeric armor/tool tier from a material name. 5=NETHERITE, 4=DIAMOND, 3=IRON, 2=CHAINMAIL, 1=GOLD/LEATHER, 0=none. */
    public static int armorTier(Material m) {
        if (m == null) return 0;
        String n = m.name();
        if (n.startsWith("NETHERITE")) return 5;
        if (n.startsWith("DIAMOND"))   return 4;
        if (n.startsWith("IRON"))      return 3;
        if (n.startsWith("CHAINMAIL")) return 2;
        if (n.startsWith("GOLD"))      return 1;
        if (n.startsWith("LEATHER"))   return 1;
        return 0;
    }

    /** Highest tier across the four equipped armor pieces. 0 if no armor is worn. */
    public int getEquippedArmorTier() {
        PlayerInventory inv = raw();
        int best = 0;
        for (ItemStack p : inv.getArmorContents()) {
            if (p == null || p.getType() == Material.AIR) continue;
            int t = armorTier(p.getType());
            if (t > best) best = t;
        }
        return best;
    }

    /**
     * Guarantee the bot has at least one ender pearl and one wind charge in its hotbar,
     * with the stack topped up to {@link #MOVEMENT_KIT_STACK}. If there are no free hotbar
     * slots, packs them into storage instead so they can be auto-equipped on the next pass.
     *
     * <p>Bots get these as part of their baseline kit — but if a loadout has been applied
     * (see {@link #markLoadoutApplied()}) we honor its intent and skip the refill entirely.
     * Otherwise a user who drags pearls out of the GUI would see them re-appear every 40
     * ticks, silently overwriting the edit.
     */
    public void ensureMovementKit() {
        if (respectLoadout) return;
        ensureStocked(Material.ENDER_PEARL, MOVEMENT_KIT_STACK);
        ensureStocked(Material.WIND_CHARGE, MOVEMENT_KIT_STACK);
        ensurePrimaryWeaponOnHotbar();
    }

    /**
     * Safety net — if the hotbar has no primary weapon (sword / axe / mace /
     * trident) but storage still holds one, promote it onto the hotbar so
     * the director isn't stuck firing {@code MELEE(empty)} for the rest of
     * the fight. The selected slot is only overwritten if it was empty, so
     * this does not reset the attack-strength ticker on a live weapon.
     */
    private void ensurePrimaryWeaponOnHotbar() {
        PlayerInventory inv = raw();
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && isMeleeWeapon(it)) return;
        }
        for (int slot = HOTBAR_SIZE; slot < 36; slot++) {
            ItemStack it = inv.getItem(slot);
            if (it != null && isMeleeWeapon(it)) {
                promoteToHotbar(slot);
                return;
            }
        }
    }

    public static final int MOVEMENT_KIT_STACK = 16;

    private void ensureStocked(Material type, int target) {
        PlayerInventory inv = raw();
        // Check offhand first — wind charges paired with a mace live there
        // (see autoEquip's offhand priority). If we skipped it we'd duplicate
        // the stack onto the hotbar every 40 ticks.
        ItemStack off = inv.getItemInOffHand();
        if (off != null && off.getType() == type) {
            if (off.getAmount() < target) {
                off.setAmount(target);
                bot.setItemOffhand(off);
            }
            return;
        }
        int slot = findMainInventory(type);
        if (slot >= 0) {
            ItemStack held = inv.getItem(slot);
            if (held != null && held.getAmount() < target) {
                held.setAmount(target);
                setMainInventorySlot(slot, held);
            }
            return;
        }
        // No hotbar copy — find an empty hotbar slot first.
        int free = -1;
        for (int i = 0; i < HOTBAR_SIZE; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == Material.AIR) { free = i; break; }
        }
        if (free < 0) {
            // Hotbar full: park it in storage so a later autoEquip can promote it.
            for (int i = 9; i < 36; i++) {
                ItemStack it = inv.getItem(i);
                if (it == null || it.getType() == Material.AIR) { free = i; break; }
            }
        }
        if (free < 0) return;
        ItemStack stack = new ItemStack(type, target);
        setMainInventorySlot(free, stack);
    }

    private static ItemStack removeFirst(List<ItemStack> pool, Material type) {
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).getType() == type) return pool.remove(i);
        }
        return null;
    }

    private static ItemStack findFirst(List<ItemStack> pool, Material type) {
        for (ItemStack it : pool) if (it.getType() == type) return it;
        return null;
    }

    private static void addToPool(List<ItemStack> pool, ItemStack it) {
        if (it != null && it.getType() != Material.AIR) pool.add(it.clone());
    }

    private static boolean setMainSlotIfChanged(net.minecraft.world.entity.player.Inventory nmsInv, int slot, ItemStack bukkitItem) {
        net.minecraft.world.item.ItemStack incoming = (bukkitItem == null || bukkitItem.getType() == Material.AIR)
                ? net.minecraft.world.item.ItemStack.EMPTY
                : net.nuggetmc.tplus.compat.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(bukkitItem);
        net.minecraft.world.item.ItemStack existing = nmsInv.getItem(slot);
        if (net.minecraft.world.item.ItemStack.matches(existing, incoming)) {
            return false;
        }
        nmsInv.setItem(slot, incoming);
        return true;
    }
}
