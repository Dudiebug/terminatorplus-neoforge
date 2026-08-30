package net.nuggetmc.tplus;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.Mod;
import net.nuggetmc.tplus.api.TerminatorPlusAPI;
import net.nuggetmc.tplus.api.utils.MojangAPI;
import net.nuggetmc.tplus.api.utils.PlayerUtils;
import net.nuggetmc.tplus.bot.BotManagerImpl;
import net.nuggetmc.tplus.bot.combat.CombatDebugger;
import net.nuggetmc.tplus.bot.combat.CombatDirector;
import net.nuggetmc.tplus.bot.gui.BotInventoryListener;
import net.nuggetmc.tplus.bot.gui.BotManagementUI;
import net.nuggetmc.tplus.bot.navigation.MovementV2Settings;
import net.nuggetmc.tplus.bot.movement.MovementOutputApplier;
import net.nuggetmc.tplus.bot.preset.PresetManager;
import net.nuggetmc.tplus.bridge.InternalBridgeImpl;
import net.nuggetmc.tplus.command.CommandHandler;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.command.PluginCommand;
import net.nuggetmc.tplus.compat.bukkit.configuration.file.FileConfiguration;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;
import net.nuggetmc.tplus.compat.bukkit.plugin.PluginDescriptionFile;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitScheduler;
import net.nuggetmc.tplus.utils.Debugger;
import net.nuggetmc.tplus.migration.PaperImporter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge entry point for TerminatorPlus.
 *
 * <p>The gameplay implementation intentionally remains server-only: no
 * client-only classes, custom registries, or custom payloads are registered.
 * Vanilla player packets are enough for clients to see and interact with the
 * packet-backed bots.</p>
 */
@Mod(TerminatorPlus.MOD_ID)
public final class TerminatorPlus implements Plugin {
    public static final String MOD_ID = "terminatorplus";
    public static final String REQUIRED_VERSION = "1.21.1";
    private static final String VERSION = "6.2.7-neoforge-1.21.1";

    private static volatile TerminatorPlus instance;
    private static volatile String version = VERSION;
    private static volatile String mcVersion;
    private static volatile boolean correctVersion;

    private final Map<String, PluginCommand> commands = new ConcurrentHashMap<>();
    private final Path configDirectory = Path.of("config", MOD_ID);
    private final Path serverConfigPath = Path.of("config", MOD_ID + "-server.toml");
    private volatile FileConfiguration config;
    private volatile boolean started;

    private BotManagerImpl manager;
    private CommandHandler handler;
    private CombatDirector combatDirector;
    private PresetManager presetManager;
    private BotManagementUI managementUI;
    private BotInventoryListener inventoryListener;

    public TerminatorPlus(IEventBus modBus) {
        instance = this;
        NeoForgePermissions.install();
        // NeoForge's mod bus is for construction-time events. Gameplay events
        // are all delivered on the global server bus.
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    public static TerminatorPlus getInstance() {
        return instance;
    }

    public static String getVersion() {
        return version;
    }

    public static boolean isCorrectVersion() {
        return correctVersion;
    }

    public static String getMcVersion() {
        return mcVersion;
    }

    public BotManagerImpl getManager() {
        return manager;
    }

    public CommandHandler getHandler() {
        return handler;
    }

    public CombatDirector getCombatDirector() {
        return combatDirector;
    }

    public PresetManager getPresetManager() {
        return presetManager;
    }

    public BotManagementUI getManagementUI() {
        return managementUI;
    }

    public BotInventoryListener getInventoryListener() {
        return inventoryListener;
    }

    private void onServerStarting(ServerStartingEvent event) {
        Bukkit.bind(event.getServer());
        BukkitScheduler.instance().restart();
        initialize(event.getServer());
    }

    private void initialize(MinecraftServer server) {
        if (started) return;
        started = true;
        mcVersion = Bukkit.getServer().getMinecraftVersion();
        correctVersion = REQUIRED_VERSION.equals(mcVersion);
        loadNativeConfig();
        MovementV2Settings.applyDefaultEnabledMigration(this);
        PaperImporter.run(Path.of("."), configDirectory);

        manager = new BotManagerImpl();
        combatDirector = new CombatDirector();
        presetManager = new PresetManager(this);
        inventoryListener = new BotInventoryListener(this);
        managementUI = new BotManagementUI(this);
        handler = new CommandHandler(this);

        TerminatorPlusAPI.setBotManager(manager);
        TerminatorPlusAPI.setInternalBridge(new InternalBridgeImpl());
        NeoForgeEventBridge.register(this);
        getLogger().info("TerminatorPlus " + version + " started on " + mcVersion);
    }

    private void onServerTick(ServerTickEvent.Post event) {
        if (!started || event.getServer() == null) return;
        // The scheduler is deliberately tick-based; run it before any queued
        // AI action can observe the next bot tick.
        BukkitScheduler.instance().tick();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        if (handler != null) handler.registerBrigadier(event);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (started) onDisable();
        BukkitScheduler.instance().shutdown();
    }

    public synchronized void onDisable() {
        if (!started && manager == null) return;
        if (managementUI != null) managementUI.shutdown();
        if (inventoryListener != null) inventoryListener.shutdown();
        if (manager != null) manager.reset();
        handler = null;
        combatDirector = null;
        presetManager = null;
        managementUI = null;
        inventoryListener = null;
        manager = null;
        TerminatorPlusAPI.setBotManager(null);
        TerminatorPlusAPI.setInternalBridge(null);
        PlayerUtils.clearUsernameCache();
        MojangAPI.shutdown();
        Debugger.shutdown();
        CombatDebugger.shutdown();
        MovementOutputApplier.clearAll();
        started = false;
        correctVersion = false;
        instance = this;
    }

    // ---------------------------------------------------------------------
    // Internal Plugin facade used by the preserved Paper implementation.
    // ---------------------------------------------------------------------

    @Override
    public java.io.File getDataFolder() {
        try {
            Files.createDirectories(configDirectory);
        } catch (IOException ignored) {
        }
        return configDirectory.toFile();
    }

    @Override
    public FileConfiguration getConfig() {
        FileConfiguration loaded = config;
        if (loaded == null) {
            loadNativeConfig();
            loaded = config;
        }
        return loaded;
    }

    @Override
    public void saveConfig() {
        FileConfiguration current = config;
        if (current == null) return;
        try {
            Files.createDirectories(serverConfigPath.getParent());
            Files.writeString(serverConfigPath, toToml(current.getValues(false)), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException error) {
            getLogger().warning("Could not save " + serverConfigPath + ": " + error.getMessage());
        }
    }

    @Override
    public void reloadConfig() {
        loadNativeConfig();
    }

    private synchronized void loadNativeConfig() {
        config = new FileConfiguration();
        config.set("ai.movement.v2.enabled", true);
        config.set("migrations.movement-v2-default-enabled-6-2-4", false);
        if (Files.isRegularFile(serverConfigPath)) {
            try {
                parseToml(Files.readAllLines(serverConfigPath, StandardCharsets.UTF_8), config);
            } catch (IOException error) {
                getLogger().warning("Could not read " + serverConfigPath + ": " + error.getMessage());
            }
        } else {
            saveConfig();
        }
    }

    private static void parseToml(Iterable<String> lines, FileConfiguration target) {
        String section = "";
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            String path = section.isEmpty() ? key : section + "." + key;
            target.set(path, parseTomlValue(value));
        }
    }

    private static Object parseTomlValue(String value) {
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        if (value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
        try {
            return value.contains(".") ? Double.parseDouble(value) : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    @SuppressWarnings("unchecked")
    private static String toToml(Map<String, Object> values) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", values, flat);
        StringBuilder output = new StringBuilder("# TerminatorPlus NeoForge server configuration\n");
        String currentSection = null;
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            String path = entry.getKey();
            int split = path.lastIndexOf('.');
            String section = split < 0 ? "" : path.substring(0, split);
            String key = split < 0 ? path : path.substring(split + 1);
            if (!section.equals(currentSection)) {
                if (!section.isEmpty()) output.append('\n').append('[').append(section).append("]\n");
                currentSection = section;
            }
            Object value = entry.getValue();
            output.append(key).append(" = ").append(formatToml(value)).append('\n');
        }
        return output.toString();
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> source, Map<String, Object> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) flatten(key, (Map<String, Object>) nested, target);
            else target.put(key, value);
        }
    }

    private static String formatToml(Object value) {
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if (value instanceof Iterable<?> iterable) {
            ArrayList<String> items = new ArrayList<>();
            for (Object item : iterable) items.add(formatToml(item));
            return "[" + String.join(", ", items) + "]";
        }
        String string = String.valueOf(value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + string + "\"";
    }

    @Override
    public PluginCommand getCommand(String name) {
        return commands.computeIfAbsent(name, PluginCommand::new);
    }

    @Override
    public String getName() {
        return MOD_ID;
    }

    @Override
    public PluginDescriptionFile getDescription() {
        return new PluginDescriptionFile(MOD_ID, VERSION);
    }
}
