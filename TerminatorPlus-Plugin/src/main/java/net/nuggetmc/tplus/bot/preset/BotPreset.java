package net.nuggetmc.tplus.bot.preset;

import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;

/**
 * Serializable snapshot of a bot's loadout and combat behavior settings.
 * Item serialization uses {@link ItemStack#serializeAsBytes()} / deserialize,
 * so enchants, custom NBT, and 1.21+ components (mace density, trident
 * loyalty) survive save/load.
 */
public final class BotPreset {

    public String name;
    /** Name template for spawned bots ({@code %} is replaced with the bot number). */
    public String namePattern;
    /** Skin name used to fetch from Mojang; may be null to use {@link #namePattern}. */
    public String skinName;

    /** 41-slot snapshot: 0–8 hotbar, 9–35 storage, 36 boots, 37 legs, 38 chest, 39 helmet, 40 offhand. */
    public ItemStack[] slots = new ItemStack[41];

    // Behavior settings
    public String targetGoal;         // EnumTargetGoal name, or null = unchanged
    public Boolean mobTarget;         // tri-state
    public Boolean addToPlayerList;
    public Boolean shield;
    public Integer selectedHotbarSlot;
}
