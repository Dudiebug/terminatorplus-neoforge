package net.nuggetmc.tplus.bot.preset;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.configuration.file.YamlConfiguration;
import net.nuggetmc.tplus.compat.bukkit.inventory.EquipmentSlot;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;

/**
 * Saves and loads {@link BotPreset}s as YAML files under
 * {@code plugins/TerminatorPlus/presets/}.
 *
 * <p>ItemStacks are encoded as Base64 of {@link ItemStack#serializeAsBytes()}
 * so modern item components (mace density, trident loyalty, ...) round-trip
 * cleanly.
 */
public final class PresetManager {

    private final TerminatorPlus plugin;
    private final File folder;

    public PresetManager(TerminatorPlus plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "presets");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create presets directory at " + folder.getAbsolutePath());
        }
    }

    public File getFolder() {
        return folder;
    }

    public List<String> list() {
        List<String> out = new ArrayList<>();
        File[] files = folder.listFiles((f, n) -> n.endsWith(".yml"));
        if (files == null) return out;
        for (File f : files) {
            out.add(f.getName().substring(0, f.getName().length() - 4));
        }
        return out;
    }

    public boolean exists(String name) {
        return fileFor(name).exists();
    }

    public boolean delete(String name) {
        return fileFor(name).delete();
    }

    public BotPreset load(String name) {
        File f = fileFor(name);
        if (!f.exists()) return null;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        BotPreset preset = new BotPreset();
        preset.name = name;
        preset.namePattern = cfg.getString("namePattern", name);
        preset.skinName = cfg.getString("skinName");

        preset.targetGoal = cfg.getString("targetGoal");
        if (cfg.isSet("mobTarget")) preset.mobTarget = cfg.getBoolean("mobTarget");
        if (cfg.isSet("addToPlayerList")) preset.addToPlayerList = cfg.getBoolean("addToPlayerList");
        if (cfg.isSet("shield")) preset.shield = cfg.getBoolean("shield");
        if (cfg.isSet("selectedHotbarSlot")) preset.selectedHotbarSlot = cfg.getInt("selectedHotbarSlot");

        for (int i = 0; i < 41; i++) {
            String encoded = cfg.getString("slots." + i);
            if (encoded == null || encoded.isEmpty()) continue;
            try {
                byte[] bytes = Base64.getDecoder().decode(encoded);
                preset.slots[i] = ItemStack.deserializeBytes(bytes);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Corrupt slot " + i + " in preset " + name, e);
            }
        }
        return preset;
    }

    public void save(BotPreset preset) throws IOException {
        if (preset.name == null || preset.name.isEmpty()) {
            throw new IOException("Preset name is required");
        }
        File f = fileFor(preset.name);
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("namePattern", preset.namePattern);
        cfg.set("skinName", preset.skinName);
        if (preset.targetGoal != null) cfg.set("targetGoal", preset.targetGoal);
        if (preset.mobTarget != null) cfg.set("mobTarget", preset.mobTarget);
        if (preset.addToPlayerList != null) cfg.set("addToPlayerList", preset.addToPlayerList);
        if (preset.shield != null) cfg.set("shield", preset.shield);
        if (preset.selectedHotbarSlot != null) cfg.set("selectedHotbarSlot", preset.selectedHotbarSlot);

        for (int i = 0; i < preset.slots.length; i++) {
            ItemStack it = preset.slots[i];
            if (it == null || it.getType() == Material.AIR) continue;
            byte[] bytes = it.serializeAsBytes();
            cfg.set("slots." + i, Base64.getEncoder().encodeToString(bytes));
        }

        cfg.save(f);
    }

    /** Capture the current state of a live bot into a new preset POJO. */
    public BotPreset capture(Bot bot, String presetName) {
        BotPreset p = new BotPreset();
        p.name = presetName;
        p.namePattern = bot.getBotName();
        // Skin name isn't directly recoverable — store the bot name as a reasonable default.
        p.skinName = bot.getBotName();
        p.selectedHotbarSlot = bot.getBotInventory().getSelectedHotbarSlot();
        p.addToPlayerList = bot.isInPlayerList();

        PlayerInventory inv = bot.getBukkitEntity().getInventory();
        for (int i = 0; i < 36; i++) {
            p.slots[i] = inv.getItem(i) == null ? null : inv.getItem(i).clone();
        }
        p.slots[36] = copy(inv.getBoots());
        p.slots[37] = copy(inv.getLeggings());
        p.slots[38] = copy(inv.getChestplate());
        p.slots[39] = copy(inv.getHelmet());
        p.slots[40] = copy(inv.getItemInOffHand());

        if (plugin.getManager().getAgent() instanceof LegacyAgent la) {
            EnumTargetGoal goal = la.getTargetType();
            if (goal != null) p.targetGoal = goal.name();
        }
        p.mobTarget = plugin.getManager().isMobTarget();
        return p;
    }

    /** Apply a preset to an existing bot in-place. */
    public void apply(BotPreset p, Bot bot) {
        // Main inventory through NMS-backed writes (0-35) to avoid Paper 26.x
        // container rollback on MockConnection-backed bots.
        bot.getBotInventory().applyMainInventorySnapshot(p.slots);

        // Always write equipment/offhand, including AIR clears, so stale gear
        // from a previous preset cannot leak through.
        bot.setItem(cloneOrAir(p.slots[36]), EquipmentSlot.FEET);
        bot.setItem(cloneOrAir(p.slots[37]), EquipmentSlot.LEGS);
        bot.setItem(cloneOrAir(p.slots[38]), EquipmentSlot.CHEST);
        bot.setItem(cloneOrAir(p.slots[39]), EquipmentSlot.HEAD);
        bot.setItem(cloneOrAir(p.slots[40]), EquipmentSlot.OFF_HAND);

        int sel = p.selectedHotbarSlot != null ? p.selectedHotbarSlot : 0;
        bot.selectHotbarSlot(sel);

        if (p.shield != null) bot.setShield(p.shield);
        bot.getBotInventory().markLoadoutApplied();

        // Global settings (applies to all bots, not just this one).
        if (p.targetGoal != null && plugin.getManager().getAgent() instanceof LegacyAgent la) {
            EnumTargetGoal goal = EnumTargetGoal.from(p.targetGoal);
            if (goal != null) la.setTargetType(goal);
        }
        if (p.mobTarget != null) plugin.getManager().setMobTarget(p.mobTarget);
        if (p.addToPlayerList != null) plugin.getManager().setAddToPlayerList(p.addToPlayerList);
    }

    /** Apply a preset to every currently spawned bot. */
    public int applyToAll(BotPreset preset) {
        int n = 0;
        for (Terminator t : plugin.getManager().fetch()) {
            if (t instanceof Bot b) {
                apply(preset, b);
                n++;
            }
        }
        return n;
    }

    private File fileFor(String name) {
        return new File(folder, sanitize(name) + ".yml");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static ItemStack copy(ItemStack it) {
        return (it == null || it.getType() == Material.AIR) ? null : it.clone();
    }

    private static ItemStack cloneOrAir(ItemStack it) {
        ItemStack copy = copy(it);
        return copy == null ? new ItemStack(Material.AIR) : copy;
    }
}
