package net.nuggetmc.tplus.bot.gui;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.bukkit.ChatColor;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.event.EventHandler;
import net.nuggetmc.tplus.compat.bukkit.event.EventPriority;
import net.nuggetmc.tplus.compat.bukkit.event.Listener;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.ClickType;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.InventoryClickEvent;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.InventoryCloseEvent;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.InventoryDragEvent;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.InventoryMoveItemEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerKickEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerQuitEvent;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.inventory.Inventory;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns transactional bot-inventory editors and their event boundaries. */
public final class BotInventoryListener implements Listener {

    static final String MANAGE_PERMISSION = "terminatorplus.manage";
    private static final long WATCH_INTERVAL_TICKS = 5L;

    private final TerminatorPlus plugin;
    private final Map<UUID, BotInventoryGUI> editorsByViewer = new HashMap<>();
    private final Map<UUID, BotInventoryGUI> editorsByBot = new HashMap<>();
    private final EditorLocks locks = new EditorLocks();
    private BukkitTask watchTask;

    public BotInventoryListener(TerminatorPlus plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (Bukkit.isPrimaryThread()) {
            watchTask = Bukkit.getScheduler().runTaskTimer(plugin, this::closeUnavailableEditors,
                    WATCH_INTERVAL_TICKS, WATCH_INTERVAL_TICKS);
        }
    }

    /** Open an exact bot instance, acquiring the one-editor-per-bot lock. */
    public boolean open(Player viewer, Bot bot) {
        if (viewer == null || bot == null) return false;
        if (!viewer.hasPermission(MANAGE_PERMISSION)) {
            viewer.sendMessage(ChatColor.RED + "You do not have permission to edit bot inventories.");
            return false;
        }
        if (!isCurrentBot(bot) || !isAlive(bot)) {
            viewer.sendMessage(ChatColor.RED + "That bot is no longer available.");
            return false;
        }

        UUID viewerId = viewer.getUniqueId();
        UUID botId = bot.getUUID();
        BotInventoryGUI existingBotEditor = editorsByBot.get(botId);
        if (existingBotEditor != null && !viewerId.equals(existingBotEditor.getViewerId())) {
            viewer.sendMessage(ChatColor.YELLOW + "That bot is already being edited by another player.");
            return false;
        }

        BotInventoryGUI existingViewerEditor = editorsByViewer.get(viewerId);
        if (existingViewerEditor != null) {
            closeEditor(existingViewerEditor, "Previous inventory changes were discarded.", true);
        }
        if (!locks.tryAcquire(botId, viewerId)) {
            viewer.sendMessage(ChatColor.YELLOW + "That bot is already being edited.");
            return false;
        }

        try {
            BotInventoryGUI gui = new BotInventoryGUI(bot, viewer);
            editorsByViewer.put(viewerId, gui);
            editorsByBot.put(botId, gui);
            gui.open(viewer);
            return true;
        } catch (RuntimeException openFailure) {
            BotInventoryGUI registered = editorsByViewer.remove(viewerId);
            if (registered != null) editorsByBot.remove(botId, registered);
            locks.release(botId, viewerId);
            viewer.sendMessage(ChatColor.RED + "The bot inventory editor could not be opened.");
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BotInventoryGUI gui)) return;

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !owns(gui, player)) return;
        if (!checkPermission(gui, player)) return;
        if (!isCurrentBot(gui.getBot())) {
            closeEditor(gui, "The bot disappeared; inventory changes were discarded.", true);
            return;
        }

        if (event.getClickedInventory() != top || event.getRawSlot() < 0
                || event.getRawSlot() >= top.getSize()) return;
        if (isProhibitedClick(event.getClick())) {
            player.sendMessage(ChatColor.YELLOW + "Use a normal left or right click in the editor.");
            return;
        }

        BotInventoryGUI.Control control = BotInventoryGUI.actionForSlot(event.getRawSlot());
        if (control != null) {
            handleControl(gui, player, control);
            return;
        }
        if (!BotInventoryGUI.isEditableSlot(event.getRawSlot())) return;
        if (!BotInventoryGUI.isValidItemForSlot(event.getRawSlot(), event.getCursor())) {
            player.sendMessage(ChatColor.RED + "That item does not fit in this equipment slot.");
            return;
        }

        // Only ordinary left/right clicks reach Bukkit's chest transaction.
        event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof BotInventoryGUI gui)) return;

        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player && owns(gui, player)) {
            if (checkPermission(gui, player) && !isCurrentBot(gui.getBot())) {
                closeEditor(gui, "The bot disappeared; inventory changes were discarded.", true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMoveItem(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof BotInventoryGUI
                || event.getDestination().getHolder() instanceof BotInventoryGUI) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof BotInventoryGUI gui)) return;
        if (!(event.getPlayer() instanceof Player player) || !owns(gui, player)) return;
        closeEditor(gui, gui.hasChanges()
                ? "Inventory changes discarded. Use Save before closing to keep edits."
                : "Inventory editor closed.", false);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        closeForViewer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        closeForViewer(event.getPlayer().getUniqueId());
    }

    /** Close every editor for a bot that was removed or replaced. */
    public void closeForBot(Bot bot) {
        if (bot == null) return;
        BotInventoryGUI gui = editorsByBot.get(bot.getUUID());
        if (gui != null && gui.getBot() == bot) {
            closeEditor(gui, "The bot was removed; inventory changes were discarded.", true);
        }
    }

    /** Release all locks and stop the watcher during plugin shutdown. */
    public void shutdown() {
        if (watchTask != null && !watchTask.isCancelled()) watchTask.cancel();
        watchTask = null;
        for (BotInventoryGUI gui : new ArrayList<>(editorsByViewer.values())) {
            closeEditor(gui, null, true);
        }
        editorsByViewer.clear();
        editorsByBot.clear();
        locks.clear();
    }

    static boolean isProhibitedClick(ClickType click) {
        return click != ClickType.LEFT && click != ClickType.RIGHT;
    }

    static final class EditorLocks {
        private final Map<UUID, UUID> botOwners = new HashMap<>();
        private final Map<UUID, UUID> viewerBots = new HashMap<>();

        boolean tryAcquire(UUID botId, UUID viewerId) {
            if (botId == null || viewerId == null || botOwners.containsKey(botId)
                    || viewerBots.containsKey(viewerId)) return false;
            botOwners.put(botId, viewerId);
            viewerBots.put(viewerId, botId);
            return true;
        }

        void release(UUID botId, UUID viewerId) {
            if (botId == null || viewerId == null) return;
            if (viewerId.equals(botOwners.get(botId))) botOwners.remove(botId);
            if (botId.equals(viewerBots.get(viewerId))) viewerBots.remove(viewerId);
        }

        boolean owns(UUID botId, UUID viewerId) {
            return botId != null && viewerId != null && viewerId.equals(botOwners.get(botId));
        }

        boolean locked(UUID botId) {
            return botOwners.containsKey(botId);
        }

        int size() {
            return botOwners.size();
        }

        void clear() {
            botOwners.clear();
            viewerBots.clear();
        }
    }

    private void handleControl(BotInventoryGUI gui, Player player, BotInventoryGUI.Control control) {
        switch (control) {
            case AUTO_EQUIP -> {
                gui.toggleAutoEquip();
                player.sendMessage(ChatColor.YELLOW + "Auto-equip on save is now "
                        + (gui.isAutoEquipEnabled() ? "on" : "off") + ".");
            }
            case SAVE -> save(gui, player);
            case DISCARD, CLOSE -> discard(gui, player);
        }
    }

    private void save(BotInventoryGUI gui, Player player) {
        if (!checkPermission(gui, player)) return;
        if (!isCurrentBot(gui.getBot())) {
            closeEditor(gui, "The bot disappeared; inventory changes were discarded.", true);
            return;
        }
        String error = BotInventoryGUI.validationError(gui.editableContents());
        if (error != null) {
            player.sendMessage(ChatColor.RED + "Save rejected: " + error);
            return;
        }
        try {
            if (!gui.save()) {
                player.sendMessage(ChatColor.RED + "Save rejected because the bot is no longer available.");
                closeEditor(gui, "Inventory changes were discarded.", true);
                return;
            }
        } catch (RuntimeException errorDuringSave) {
            player.sendMessage(ChatColor.RED + "Save failed while applying the inventory; the bot may be partially updated.");
            return;
        }
        unregister(gui);
        player.sendMessage(ChatColor.GREEN + "Inventory saved for " + ChatColor.YELLOW
                + gui.getBot().getBotName() + ChatColor.GREEN + ".");
        if (player.getOpenInventory().getTopInventory().getHolder() == gui) player.closeInventory();
    }

    private void discard(BotInventoryGUI gui, Player player) {
        unregister(gui);
        gui.discard(player);
        player.sendMessage(ChatColor.YELLOW + "Inventory changes discarded for "
                + ChatColor.GOLD + gui.getBot().getBotName() + ChatColor.YELLOW + ".");
        if (player.getOpenInventory().getTopInventory().getHolder() == gui) player.closeInventory();
    }

    private boolean checkPermission(BotInventoryGUI gui, Player player) {
        if (player.hasPermission(MANAGE_PERMISSION)) return true;
        closeEditor(gui, "You no longer have permission to edit bot inventories.", true);
        return false;
    }

    private boolean owns(BotInventoryGUI gui, Player player) {
        return gui != null && player != null && gui.getViewerId() != null
                && gui.getViewerId().equals(player.getUniqueId())
                && locks.owns(gui.getBotId(), player.getUniqueId())
                && editorsByViewer.get(player.getUniqueId()) == gui;
    }

    private boolean isCurrentBot(Bot bot) {
        if (bot == null || plugin.getManager() == null) return false;
        try {
            for (Terminator candidate : plugin.getManager().fetch()) {
                if (candidate instanceof Bot current && current.getUUID().equals(bot.getUUID())) {
                    return current == bot;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static boolean isAlive(Bot bot) {
        try {
            return bot != null && bot.isBotAlive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void closeUnavailableEditors() {
        for (BotInventoryGUI gui : new ArrayList<>(editorsByViewer.values())) {
            if (!isCurrentBot(gui.getBot()) || !gui.isBotUsable()) {
                closeEditor(gui, "The bot disappeared; inventory changes were discarded.", true);
            }
        }
    }

    private void closeForViewer(UUID viewerId) {
        BotInventoryGUI gui = editorsByViewer.get(viewerId);
        if (gui != null) closeEditor(gui, null, false);
    }

    private void closeEditor(BotInventoryGUI gui, String message, boolean closeInventory) {
        if (!unregister(gui)) return;
        Player player = Bukkit.getPlayer(gui.getViewerId());
        gui.discard(player);
        if (player != null && message != null) player.sendMessage(ChatColor.YELLOW + message);
        if (closeInventory && player != null
                && player.getOpenInventory().getTopInventory().getHolder() == gui) {
            player.closeInventory();
        }
    }

    private boolean unregister(BotInventoryGUI gui) {
        if (gui == null) return false;
        boolean registered = editorsByViewer.get(gui.getViewerId()) == gui
                || editorsByBot.get(gui.getBotId()) == gui;
        if (!registered) return false;
        editorsByViewer.remove(gui.getViewerId(), gui);
        editorsByBot.remove(gui.getBotId(), gui);
        locks.release(gui.getBotId(), gui.getViewerId());
        return true;
    }
}
