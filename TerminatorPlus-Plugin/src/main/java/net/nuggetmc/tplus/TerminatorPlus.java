package net.nuggetmc.tplus;

import net.nuggetmc.tplus.api.TerminatorPlusAPI;
import net.nuggetmc.tplus.api.utils.MojangAPI;
import net.nuggetmc.tplus.api.utils.PlayerUtils;
import net.nuggetmc.tplus.bot.BotManagerImpl;
import net.nuggetmc.tplus.bot.combat.CombatDebugger;
import net.nuggetmc.tplus.bot.combat.CombatDirector;
import net.nuggetmc.tplus.bot.gui.BotInventoryListener;
import net.nuggetmc.tplus.bot.gui.BotManagementUI;
import net.nuggetmc.tplus.bot.movement.MovementOutputApplier;
import net.nuggetmc.tplus.bot.navigation.MovementV2Settings;
import net.nuggetmc.tplus.bot.preset.PresetManager;
import net.nuggetmc.tplus.bridge.InternalBridgeImpl;
import net.nuggetmc.tplus.command.CommandHandler;
import net.nuggetmc.tplus.utils.Debugger;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.event.Listener;
import net.nuggetmc.tplus.compat.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;

public class TerminatorPlus extends JavaPlugin {

    public static final String REQUIRED_VERSION = "26.2";

    private static TerminatorPlus instance;
    private static String version;
    private static String mcVersion;

    private static boolean correctVersion;

    private BotManagerImpl manager;
    private CommandHandler handler;
    private CombatDirector combatDirector;
    private PresetManager presetManager;
    private BotManagementUI managementUI;
    private BotInventoryListener inventoryListener;

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

    @Override
    public void onEnable() {
        instance = this;
        version = getDescription().getVersion();
        saveDefaultConfig();
        MovementV2Settings.applyDefaultEnabledMigration(this);

        mcVersion = Bukkit.getServer().getMinecraftVersion();
        correctVersion = mcVersion.equals(REQUIRED_VERSION);
        getLogger().info("Running on version: " + mcVersion + ", required version: " + REQUIRED_VERSION + ", correct version: " + correctVersion);

        // Create Instances
        this.manager = new BotManagerImpl();
        this.combatDirector = new CombatDirector();
        this.presetManager = new PresetManager(this);
        this.inventoryListener = new BotInventoryListener(this);
        this.managementUI = new BotManagementUI(this);
        this.handler = new CommandHandler(this);

        TerminatorPlusAPI.setBotManager(manager);
        TerminatorPlusAPI.setInternalBridge(new InternalBridgeImpl());

        // Register event listeners
        this.registerEvents(manager, inventoryListener, managementUI);

        if (!correctVersion) {
            for (int i = 0; i < 20; i++) { // Kids are stupid so we need to make sure they see this
                getLogger().severe("----------------------------------------");
                getLogger().severe("TerminatorPlus is not compatible with your server version!");
                getLogger().severe("You are running on version: " + mcVersion + ", required version: " + REQUIRED_VERSION);
                getLogger().severe("Either download the correct version of TerminatorPlus or update your server. (https://papermc.io/downloads)");
                getLogger().severe("----------------------------------------");
            }
        }
    }

    @Override
    public void onDisable() {
        if (managementUI != null) {
            managementUI.shutdown();
        }
        if (inventoryListener != null) {
            inventoryListener.shutdown();
        }
        if (manager != null) {
            manager.reset();
        }
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
        instance = null;
        version = null;
        mcVersion = null;
        correctVersion = false;
    }

    private void registerEvents(Listener... listeners) {
        Arrays.stream(listeners).forEach(li -> this.getServer().getPluginManager().registerEvents(li, this));
    }
}
