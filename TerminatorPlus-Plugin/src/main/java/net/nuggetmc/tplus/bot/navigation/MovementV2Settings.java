package net.nuggetmc.tplus.bot.navigation;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.configuration.Configuration;

/** Runtime feature gate shared by commands, UI, and bots. */
public final class MovementV2Settings {

    public static final String CONFIG_PATH = "ai.movement.v2.enabled";
    static final String DEFAULT_ENABLED_MIGRATION = "migrations.movement-v2-default-enabled-6-2-4";

    private MovementV2Settings() {
    }

    public static boolean isEnabled(TerminatorPlus plugin) {
        return plugin.getConfig().getBoolean(CONFIG_PATH, true);
    }

    public static void applyDefaultEnabledMigration(TerminatorPlus plugin) {
        if (migrateDefaultEnabled(plugin.getConfig())) {
            plugin.saveConfig();
        }
    }

    static boolean migrateDefaultEnabled(Configuration config) {
        if (config.getBoolean(DEFAULT_ENABLED_MIGRATION, false)) {
            return false;
        }
        config.set(CONFIG_PATH, true);
        config.set(DEFAULT_ENABLED_MIGRATION, true);
        return true;
    }

    public static void setEnabled(TerminatorPlus plugin, boolean enabled) {
        plugin.getConfig().set(CONFIG_PATH, enabled);
        plugin.saveConfig();
        if (!enabled) {
            plugin.getManager().fetch().stream()
                    .filter(Bot.class::isInstance)
                    .map(Bot.class::cast)
                    .forEach(bot -> bot.cancelMovementV2Action("movement-v2-disabled"));
        }
    }
}
