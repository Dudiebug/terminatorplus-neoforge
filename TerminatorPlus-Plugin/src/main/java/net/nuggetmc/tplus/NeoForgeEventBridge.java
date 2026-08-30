package net.nuggetmc.tplus;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.entity.EntityBridge;
import net.nuggetmc.tplus.compat.bukkit.event.entity.EntityDeathEvent;
import net.nuggetmc.tplus.compat.bukkit.event.entity.EntityTargetLivingEntityEvent;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.InventoryCloseEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.AsyncPlayerChatEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerJoinEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;

/** Bridges native NeoForge lifecycle events to the preserved internal hooks. */
final class NeoForgeEventBridge {
    private static volatile TerminatorPlus active;
    private static volatile boolean installed;

    private NeoForgeEventBridge() {
    }

    static synchronized void register(TerminatorPlus plugin) {
        active = plugin;
        Bukkit.getServer().getPluginManager().unregisterAll();
        Bukkit.getServer().getPluginManager().registerEvents(plugin.getManager(), plugin);
        Bukkit.getServer().getPluginManager().registerEvents(plugin.getInventoryListener(), plugin);
        Bukkit.getServer().getPluginManager().registerEvents(plugin.getManagementUI(), plugin);
        if (installed) return;
        installed = true;
        NeoForge.EVENT_BUS.addListener(NeoForgeEventBridge::loggedIn);
        NeoForge.EVENT_BUS.addListener(NeoForgeEventBridge::loggedOut);
        NeoForge.EVENT_BUS.addListener(NeoForgeEventBridge::livingDeath);
        NeoForge.EVENT_BUS.addListener(NeoForgeEventBridge::changeTarget);
        NeoForge.EVENT_BUS.addListener(NeoForgeEventBridge::serverChat);
        NeoForge.EVENT_BUS.addListener(NeoForgeEventBridge::containerClose);
    }

    private static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        TerminatorPlus plugin = active;
        if (plugin == null || plugin.getManager() == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        Bukkit.getServer().getPluginManager().callEvent(new PlayerJoinEvent(EntityBridge.player(player)));
    }

    private static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        TerminatorPlus plugin = active;
        if (plugin == null || plugin.getInventoryListener() == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        Bukkit.getServer().getPluginManager().callEvent(new PlayerQuitEvent(EntityBridge.player(player)));
    }

    private static void livingDeath(LivingDeathEvent event) {
        TerminatorPlus plugin = active;
        if (plugin == null || plugin.getManager() == null || !(event.getEntity() instanceof Bot bot)) return;
        EntityDeathEvent compat = new EntityDeathEvent(bot.getBukkitEntity(), event.getSource(), new ArrayList<>(), 0);
        Bukkit.getServer().getPluginManager().callEvent(compat);
    }

    private static void changeTarget(LivingChangeTargetEvent event) {
        TerminatorPlus plugin = active;
        if (plugin == null || plugin.getManager() == null) return;
        LivingEntity target = event.getNewAboutToBeSetTarget();
        if (!(target instanceof ServerPlayer player)) return;
        Bot bot = plugin.getManager().getBot(player.getUUID()) instanceof Bot found ? found : null;
        if (bot == null || plugin.getManager().isMobTarget()) return;
        EntityTargetLivingEntityEvent compat = new EntityTargetLivingEntityEvent(EntityBridge.living(target));
        Bukkit.getServer().getPluginManager().callEvent(compat);
        if (compat.isCancelled()) event.setCanceled(true);
    }

    private static void serverChat(ServerChatEvent event) {
        TerminatorPlus plugin = active;
        if (plugin == null || plugin.getManagementUI() == null) return;
        AsyncPlayerChatEvent compat = new AsyncPlayerChatEvent(EntityBridge.player(event.getPlayer()), event.getRawText());
        Bukkit.getServer().getPluginManager().callEvent(compat);
        if (compat.isCancelled()) event.setCanceled(true);
    }

    private static void containerClose(PlayerContainerEvent.Close event) {
        TerminatorPlus plugin = active;
        if (plugin == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        Bukkit.getServer().getPluginManager().callEvent(new InventoryCloseEvent(
                EntityBridge.player(player), EntityBridge.player(player).getOpenInventory()));
    }
}
