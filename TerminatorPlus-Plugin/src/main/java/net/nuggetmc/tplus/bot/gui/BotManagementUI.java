package net.nuggetmc.tplus.bot.gui;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.navigation.MovementV2Settings;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.ChatColor;
import net.nuggetmc.tplus.compat.bukkit.Material;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.event.EventHandler;
import net.nuggetmc.tplus.compat.bukkit.event.Listener;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.InventoryClickEvent;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.InventoryCloseEvent;
import net.nuggetmc.tplus.compat.bukkit.event.inventory.InventoryDragEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.AsyncPlayerChatEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerKickEvent;
import net.nuggetmc.tplus.compat.bukkit.event.player.PlayerQuitEvent;
import net.nuggetmc.tplus.compat.bukkit.inventory.Inventory;
import net.nuggetmc.tplus.compat.bukkit.inventory.InventoryHolder;
import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import net.nuggetmc.tplus.compat.bukkit.inventory.meta.ItemMeta;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holder-based management menu for the commands already exposed by the plugin.
 *
 * <p>The UI is intentionally a command client: buttons either change pages or
 * dispatch the same command a player would type.  That keeps parsing,
 * permissions, and side effects in the existing command implementations.</p>
 */
public final class BotManagementUI implements Listener {

    public static final long REFRESH_INTERVAL_TICKS = 5L;
    public static final int INVENTORY_SIZE = 54;

    static final String MANAGE_PERMISSION = "terminatorplus.manage";
    static final String ADMIN_PERMISSION = "terminatorplus.admin";
    static final int BOT_PAGE_SIZE = 28;
    private static final int MAX_PROMPT_LENGTH = 256;
    private static final long PROMPT_TIMEOUT_TICKS = 20L * 60L;
    private static final String TITLE = ChatColor.GOLD + "TerminatorPlus Management";

    private final TerminatorPlus plugin;
    private final CommandDispatcher dispatcher;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, PendingPrompt> prompts = new ConcurrentHashMap<>();
    private final LifecycleState lifecycle = new LifecycleState();
    private BukkitTask refreshTask;
    private boolean shutdown;

    public BotManagementUI(TerminatorPlus plugin) {
        this(plugin, (playerId, command) -> {
            Player player = Bukkit.getPlayer(playerId);
            return player != null && Bukkit.dispatchCommand(player, command);
        });
    }

    BotManagementUI(TerminatorPlus plugin, CommandDispatcher dispatcher) {
        this.plugin = plugin;
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    BotManagementUI(CommandDispatcher dispatcher) {
        this(null, dispatcher);
    }

    /** Opens or reuses the management session and always starts at the main page. */
    public void open(Player player) {
        if (player == null || shutdown || plugin == null) return;
        if (!player.hasPermission(MANAGE_PERMISSION)) {
            player.sendMessage(ChatColor.RED + "You do not have permission to use bot management.");
            return;
        }
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> open(player));
            return;
        }

        UUID playerId = player.getUniqueId();
        Session session = sessions.get(playerId);
        if (session == null) {
            session = new Session(playerId);
            sessions.put(playerId, session);
            lifecycle.sessionOpened();
        }
        clearPrompt(playerId);
        session.resetForOpen();
        render(session);
        player.openInventory(session.inventory);
        ensureRefreshTask();
    }

    /** Lifecycle-friendly alias for callers that name the root page explicitly. */
    public void openMain(Player player) {
        open(player);
    }

    /** Cancels refresh, closes this UI's inventories, and is safe to call repeatedly. */
    public void shutdown() {
        if (shutdown) return;
        shutdown = true;
        if (plugin != null && !Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::finishShutdown);
            return;
        }
        finishShutdown();
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Session session)) return;

        // Cancel before inspecting the slot: this includes bottom inventory,
        // shift-click, number-key, double-click, creative, and outside clicks.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !session.playerId.equals(player.getUniqueId())) return;
        if (!player.hasPermission(MANAGE_PERMISSION)) {
            player.sendMessage(ChatColor.RED + "You no longer have permission to use bot management.");
            removeSession(session.playerId, true);
            return;
        }

        Button button = session.buttons.get(event.getRawSlot());
        if (button != null) handle(session, button);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session session) {
            // A drag can contain both top and bottom raw slots; cancel the
            // entire operation rather than trying to filter individual slots.
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player
                    && session.playerId.equals(player.getUniqueId())
                    && !player.hasPermission(MANAGE_PERMISSION)) {
                player.sendMessage(ChatColor.RED + "You no longer have permission to use bot management.");
                removeSession(session.playerId, true);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session session)) return;
        if (!(event.getPlayer() instanceof Player player)
                || !session.playerId.equals(player.getUniqueId())) return;
        if (!session.awaitingPrompt) removeSession(session.playerId, false);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeSession(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        removeSession(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        PendingPrompt pending = prompts.get(playerId);
        if (pending == null) return;

        event.setCancelled(true);
        if (plugin == null) return;
        String message = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> finishPrompt(playerId, pending, message));
    }

    private void handle(Session session, Button button) {
        UiAction action = button.action();
        switch (action) {
            case OPEN_BOTS -> page(session, Page.BOTS, "Bot list");
            case OPEN_CREATE -> page(session, Page.CREATE, "Create bots");
            case OPEN_MOVEMENT -> page(session, Page.MOVEMENT, "Movement");
            case OPEN_AI -> page(session, Page.AI, "AI");
            case OPEN_COMBAT -> page(session, Page.COMBAT, "Combat and loadouts");
            case OPEN_ADMIN -> page(session, Page.ADMIN, "Admin");
            case OPEN_ENVIRONMENT -> page(session, Page.ENVIRONMENT, "Environment");
            case OPEN_HELP -> page(session, Page.HELP, "Help and plugin info");
            case BACK -> back(session);
            case PREVIOUS_PAGE -> previousBotPage(session);
            case NEXT_PAGE -> nextBotPage(session);
            case CLOSE -> closeSession(session.playerId, true);
            case SELECT_BOT -> selectBot(session, button.payload());
            case BOT_INVENTORY -> openSelectedInventory(session);
            case CONFIRM -> confirm(session);
            case CANCEL -> cancelConfirmation(session);
            default -> runAction(session, button);
        }
    }

    private void runAction(Session session, Button button) {
        UiAction action = button.action();
        if (action.command() == null) return;

        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null) {
            removeSession(session.playerId, false);
            return;
        }
        if (!player.hasPermission(MANAGE_PERMISSION)) {
            player.sendMessage(ChatColor.RED + "You no longer have permission to use bot management.");
            removeSession(session.playerId, true);
            return;
        }
        if (action.requiresAdmin() && !player.hasPermission(ADMIN_PERMISSION)) {
            setStatus(session, "Admin permission required.");
            return;
        }
        if (action.prompt()) {
            beginPrompt(session, action, promptHint(action));
            return;
        }

        String command = commandFor(action, button.payload());
        if (action.destructive()) {
            requestConfirmation(session, action, command);
        } else {
            dispatch(session, command);
        }
    }

    private void selectBot(Session session, String payload) {
        UUID botId;
        try {
            botId = UUID.fromString(payload);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            setStatus(session, "That bot selection is no longer valid.");
            return;
        }

        Terminator bot = findBot(botId);
        if (bot == null) {
            setStatus(session, "That bot is no longer active.");
            return;
        }
        session.selectedBot = botId;
        session.state.navigate(Page.BOT_DETAIL);
        session.status = "Selected " + bot.getBotName();
        render(session);
    }

    private void page(Session session, Page page, String status) {
        session.state.navigate(page);
        session.status = status;
        render(session);
    }

    private void back(Session session) {
        session.confirmation = null;
        session.state.back();
        session.status = "Back";
        render(session);
    }

    private void nextBotPage(Session session) {
        if (session.state.page != Page.BOTS) return;
        int pageCount = pageCount(currentBots().size());
        if (session.state.pageIndex() + 1 >= pageCount) {
            session.status = "Already on the last bot page (" + pageCount + ").";
            render(session);
            return;
        }
        session.state.setPageIndex(session.state.pageIndex() + 1, pageCount);
        session.status = "Bot page " + (session.state.pageIndex + 1) + "/" + pageCount;
        render(session);
    }

    private void previousBotPage(Session session) {
        if (session.state.page != Page.BOTS) return;
        int pageCount = pageCount(currentBots().size());
        if (session.state.pageIndex() <= 0) {
            session.status = "Already on the first bot page (" + pageCount + ").";
            render(session);
            return;
        }
        session.state.setPageIndex(session.state.pageIndex() - 1, pageCount);
        session.status = "Bot page " + (session.state.pageIndex() + 1) + "/" + pageCount;
        render(session);
    }

    private void openSelectedInventory(Session session) {
        Player player = Bukkit.getPlayer(session.playerId);
        Terminator selected = findBot(session.selectedBot);
        if (!(selected instanceof Bot bot)) {
            setStatus(session, "The selected bot is no longer active.");
            return;
        }
        if (plugin == null || plugin.getInventoryListener() == null) {
            setStatus(session, "The bot inventory editor is unavailable.");
            return;
        }
        if (player == null || !player.hasPermission(MANAGE_PERMISSION)) {
            removeSession(session.playerId, true);
            return;
        }
        if (plugin.getInventoryListener().open(player, bot)) {
            removeSession(session.playerId, false);
        }
    }

    private void beginPrompt(Session session, UiAction action, String hint) {
        PendingPrompt previous = prompts.remove(session.playerId);
        if (previous != null) {
            previous.cancel();
            Player player = Bukkit.getPlayer(session.playerId);
            if (player != null) player.sendMessage(ChatColor.YELLOW + "Previous prompt cancelled.");
        }
        PendingPrompt pending = new PendingPrompt(action, hint);
        prompts.put(session.playerId, pending);
        session.awaitingPrompt = true;
        session.status = "Waiting for chat input";
        Player player = Bukkit.getPlayer(session.playerId);
        if (player != null) {
            player.sendMessage(ChatColor.YELLOW + "Enter " + hint + ChatColor.GRAY + ", or type cancel.");
        }
        if (plugin != null) {
            pending.timeoutTask = Bukkit.getScheduler().runTaskLater(plugin,
                    () -> expirePrompt(session.playerId, pending), PROMPT_TIMEOUT_TICKS);
        }
        render(session);
        if (player != null) player.closeInventory();
        stopRefreshIfNoOpenSessions();
    }

    private void finishPrompt(UUID playerId, PendingPrompt pending, String message) {
        Session session = sessions.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (!isCurrentPrompt(prompts.get(playerId), pending)) return;
        if (session == null || player == null) {
            prompts.remove(playerId, pending);
            pending.cancel();
            return;
        }

        if (message != null && message.trim().equalsIgnoreCase("cancel")) {
            prompts.remove(playerId, pending);
            pending.cancel();
            session.awaitingPrompt = false;
            session.status = "Prompt cancelled";
            render(session);
            reopen(session, player);
            return;
        }
        if (!isSafePromptInput(message)) {
            player.sendMessage(ChatColor.RED + "Input is too long or contains control characters.");
            return;
        }

        if (!player.hasPermission(requiredPermission(pending.action()))) {
            prompts.remove(playerId, pending);
            pending.cancel();
            session.awaitingPrompt = false;
            if (!player.hasPermission(MANAGE_PERMISSION)) {
                player.sendMessage(ChatColor.RED + "You no longer have permission to use bot management.");
                removeSession(playerId, false);
                return;
            }
            session.status = "Permission recheck failed; nothing was dispatched.";
            render(session);
            reopen(session, player);
            return;
        }

        if (!prompts.remove(playerId, pending)) return;
        pending.cancel();
        session.awaitingPrompt = false;
        String command = commandFor(pending.action(), message);
        if (pending.action().destructive()) {
            requestConfirmation(session, pending.action(), command);
            reopen(session, player);
        } else {
            // The existing CommandInstance parser validates numbers, materials,
            // locations, and all other command-specific input.
            dispatch(session, command);
            if (pending.action() == UiAction.BOT_INVENTORY_INPUT) {
                removeSession(playerId, false);
            } else {
                reopen(session, player);
            }
        }
    }

    private void expirePrompt(UUID playerId, PendingPrompt pending) {
        if (!prompts.remove(playerId, pending)) return;
        pending.cancel();
        Session session = sessions.get(playerId);
        if (session == null) return;
        session.awaitingPrompt = false;
        session.status = "Prompt expired";
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            removeSession(playerId, false);
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "The input prompt expired. No action was run.");
        render(session);
        reopen(session, player);
    }

    private void requestConfirmation(Session session, UiAction action, String command) {
        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null) {
            removeSession(session.playerId, false);
            return;
        }
        if (!player.hasPermission(requiredPermission(action))) {
            setStatus(session, "Permission recheck failed.");
            return;
        }

        session.confirmation = new Confirmation(action, command, session.state.page);
        session.state.page = Page.CONFIRM;
        session.status = "Confirm: " + action.label();
        render(session);
    }

    private void confirm(Session session) {
        Confirmation confirmation = session.confirmation;
        if (confirmation == null) return;

        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null) {
            removeSession(session.playerId, false);
            return;
        }
        if (!player.hasPermission(requiredPermission(confirmation.action()))) {
            session.confirmation = null;
            session.state.page = confirmation.returnPage();
            session.status = "Permission recheck failed; nothing changed.";
            render(session);
            return;
        }

        session.confirmation = null;
        session.state.page = confirmation.returnPage();
        dispatch(session, confirmation.command());
    }

    private void cancelConfirmation(Session session) {
        if (session.confirmation == null) return;
        session.state.page = session.confirmation.returnPage();
        session.confirmation = null;
        session.status = "Action cancelled";
        render(session);
    }

    private void dispatch(Session session, String command) {
        boolean accepted;
        try {
            accepted = dispatcher.dispatch(session.playerId, command);
        } catch (RuntimeException error) {
            accepted = false;
        }
        session.status = dispatchStatus(accepted, command);
        render(session);
    }

    private void setStatus(Session session, String status) {
        session.status = status;
        render(session);
    }

    private void refreshAll() {
        if (shutdown || !hasOpenSessions()) {
            stopRefreshTask();
            return;
        }

        for (Session session : new ArrayList<>(sessions.values())) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null) {
                removeSession(session.playerId, false);
                continue;
            }
            if (!player.hasPermission(MANAGE_PERMISSION)) {
                player.sendMessage(ChatColor.RED + "You no longer have permission to use bot management.");
                removeSession(session.playerId, true);
                continue;
            }
            if (session.awaitingPrompt) continue;
            if (player.getOpenInventory().getTopInventory().getHolder() != session) {
                removeSession(session.playerId, false);
                continue;
            }
            refreshSession(session);
        }
    }

    private void refreshSession(Session session) {
        if (session.selectedBot != null && findBot(session.selectedBot) == null) {
            // End this bot-bound refresh session so no stale UUID can act on a
            // replacement with the same name.
            removeSession(session.playerId, true);
            return;
        }
        render(session);
    }

    private void ensureRefreshTask() {
        if (plugin == null || shutdown || !hasOpenSessions() || refreshTask != null) return;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll,
                REFRESH_INTERVAL_TICKS, REFRESH_INTERVAL_TICKS);
    }

    private void stopRefreshTask() {
        BukkitTask task = refreshTask;
        refreshTask = null;
        if (task != null && !task.isCancelled()) task.cancel();
    }

    private void removeSession(UUID playerId, boolean closeInventory) {
        Session removed = sessions.remove(playerId);
        clearPrompt(playerId);
        if (removed == null) return;

        removed.awaitingPrompt = false;
        lifecycle.sessionClosed();
        if (closeInventory) closeInventoryIfOwned(removed);
        if (!lifecycle.shouldRefresh()) stopRefreshTask();
    }

    private void clearPrompt(UUID playerId) {
        PendingPrompt pending = prompts.remove(playerId);
        if (pending != null) pending.cancel();
        Session session = sessions.get(playerId);
        if (session != null) session.awaitingPrompt = false;
    }

    private void closeSession(UUID playerId, boolean closeInventory) {
        removeSession(playerId, closeInventory);
    }

    private void reopen(Session session, Player player) {
        if (shutdown || session == null || player == null || !player.isOnline()) return;
        if (!player.hasPermission(MANAGE_PERMISSION)) {
            removeSession(session.playerId, false);
            return;
        }
        player.openInventory(session.inventory);
        ensureRefreshTask();
    }

    private boolean hasOpenSessions() {
        if (!lifecycle.shouldRefresh()) return false;
        for (Session session : sessions.values()) {
            Player player = Bukkit.getPlayer(session.playerId);
            if (!session.awaitingPrompt && player != null
                    && player.getOpenInventory().getTopInventory().getHolder() == session) return true;
        }
        return false;
    }

    private void stopRefreshIfNoOpenSessions() {
        if (!hasOpenSessions()) stopRefreshTask();
    }

    private void finishShutdown() {
        Collection<Session> active = new ArrayList<>(sessions.values());
        sessions.clear();
        prompts.values().forEach(PendingPrompt::cancel);
        prompts.clear();
        active.forEach(session -> session.awaitingPrompt = false);
        lifecycle.shutdown();
        stopRefreshTask();
        for (Session session : active) closeInventoryIfOwned(session);
    }

    private void closeInventoryIfOwned(Session session) {
        Player player = Bukkit.getPlayer(session.playerId);
        if (player != null && player.getOpenInventory().getTopInventory().getHolder() == session) {
            player.closeInventory();
        }
    }

    private void render(Session session) {
        Set<Integer> previousSlots = new HashSet<>(session.renderedSlots);
        session.renderedSlots.clear();
        session.buttons.clear();

        switch (session.state.page) {
            case MAIN -> renderMain(session);
            case BOTS -> renderBots(session);
            case BOT_DETAIL -> renderBotDetail(session);
            case CREATE -> renderCreate(session);
            case MOVEMENT -> renderMovement(session);
            case AI -> renderAi(session);
            case COMBAT -> renderCombat(session);
            case ADMIN -> renderAdmin(session);
            case ENVIRONMENT -> renderEnvironment(session);
            case HELP -> renderHelp(session);
            case CONFIRM -> renderConfirmation(session);
        }
        renderFooter(session);
        previousSlots.removeAll(session.renderedSlots);
        previousSlots.forEach(slot -> updateIfChanged(session.inventory, slot, null));
    }

    private void renderMain(Session session) {
        put(session, 10, Material.PLAYER_HEAD, "Bots and status", UiAction.OPEN_BOTS, null,
                "Browse active bots and live status");
        put(session, 11, Material.CHEST, "Create / multi", UiAction.OPEN_CREATE, null,
                "Create normal, random, or training bots");
        put(session, 12, Material.FEATHER, "Movement", UiAction.OPEN_MOVEMENT, null,
                "Gather, circular scatter, respawn, Movement V2");
        put(session, 13, Material.REDSTONE, "AI", UiAction.OPEN_AI, null,
                "Brains, training, evaluation, and info");
        put(session, 14, Material.DIAMOND_SWORD, "Combat / loadouts", UiAction.OPEN_COMBAT, null,
                "Weapons, presets, and inventory commands");
        put(session, 15, Material.COMPARATOR, "Debug / admin", UiAction.OPEN_ADMIN, null,
                "Administrative and debug commands");
        put(session, 16, Material.GRASS_BLOCK, "Environment", UiAction.OPEN_ENVIRONMENT, null,
                "Materials, mobs, and environment lists");
        put(session, 17, Material.BOOK, "Help / plugin info", UiAction.OPEN_HELP, null,
                "Show existing help and plugin information");
        put(session, 19, Material.ENDER_PEARL, "Gather all", UiAction.BOT_GATHER, null,
                "/bot move gather");
        put(session, 20, Material.COMPASS, "Circular scatter", UiAction.BOT_SCATTER, null,
                "/bot move scatter (default radius)");
        put(session, 21, Material.PAPER, "Count bots", UiAction.BOT_COUNT, null,
                "/bot inspect list");
    }

    private void renderBots(Session session) {
        List<Terminator> bots = currentBots();
        int pages = pageCount(bots.size());
        int pageIndex = clampPageIndex(session.state.pageIndex(), pages);
        session.state.setPageIndex(pageIndex, pages);
        int start = pageIndex * BOT_PAGE_SIZE;
        int end = Math.min(start + BOT_PAGE_SIZE, bots.size());
        int slot = 10;
        if (bots.isEmpty()) {
            put(session, slot, Material.BARRIER, "No active bots", null, null,
                    "Spawn a bot to make it appear here.");
        }
        for (int index = start; index < end; index++) {
            Terminator bot = bots.get(index);
            UUID id = botId(bot);
            if (id == null) continue;
            String location = bot.getLocation() == null || bot.getLocation().getWorld() == null
                    ? "unknown world"
                    : bot.getLocation().getWorld().getName();
            put(session, slot++, Material.PLAYER_HEAD, bot.getBotName(), UiAction.SELECT_BOT,
                    id.toString(), (bot.isBotAlive() ? ChatColor.GREEN + "alive" : ChatColor.RED + "dead")
                            + ChatColor.GRAY + " | " + location,
                    "Click for status and actions");
        }
        session.status = "Bots " + bots.size() + " | page " + (pageIndex + 1) + "/" + pages;
    }

    private void renderBotDetail(Session session) {
        Terminator bot = findBot(session.selectedBot);
        if (bot == null) {
            session.state.resetTo(Page.BOTS);
            session.selectedBot = null;
            session.status = "Selected bot disappeared; detail closed";
            renderBots(session);
            return;
        }

        String world = bot.getLocation() == null || bot.getLocation().getWorld() == null
                ? "unknown" : bot.getLocation().getWorld().getName();
        put(session, 10, Material.PLAYER_HEAD, "Status: " + bot.getBotName(), null, null,
                "UUID: " + botId(bot),
                "Health: " + bot.getBotHealth() + "/" + bot.getBotMaxHealth(),
                "Alive: " + bot.isBotAlive(),
                "World: " + world);
        boolean uniqueName = isUniqueBotName(bot.getBotName(), currentBots().stream()
                .map(Terminator::getBotName).toList());
        if (!uniqueName) {
            put(session, 12, Material.BARRIER, "Duplicate bot name", null, null,
                    "Name-based commands are disabled for this selection.",
                    "The inventory editor still targets this exact bot UUID.");
        }
        if (uniqueName) {
            put(session, 11, Material.PAPER, "Bot info", UiAction.BOT_INFO, bot.getBotName(),
                    "/bot inspect info " + bot.getBotName());
            put(session, 12, Material.REDSTONE, "AI info", UiAction.AI_INFO, bot.getBotName(),
                    "/ai inspect info " + bot.getBotName());
            put(session, 13, Material.DIAMOND_SWORD, "Weapon status", UiAction.BOT_WEAPONS, bot.getBotName(),
                    "/bot inspect weapons " + bot.getBotName());
        }
        UUID exactBotId = botId(bot);
        if (exactBotId != null) {
            put(session, 14, Material.CHEST, "Edit inventory: " + bot.getBotName(), UiAction.BOT_INVENTORY,
                    exactBotId.toString(), "/bot equipment inventory <exact selected bot>",
                    "UUID: " + exactBotId);
        }
        if (uniqueName) {
            put(session, 15, Material.OBSERVER, "Combat debug on", UiAction.BOT_COMBAT_DEBUG,
                    bot.getBotName() + " on", "/bot debug combat " + bot.getBotName() + " on");
            put(session, 16, Material.BARRIER, "Combat debug off", UiAction.BOT_COMBAT_DEBUG,
                    bot.getBotName() + " off", "/bot debug combat " + bot.getBotName() + " off");
            put(session, 17, Material.SHULKER_BOX, "Apply loadout...", UiAction.BOT_LOADOUT, null,
                    "Enter loadout [bot-name]");
        }
    }

    private void renderCreate(Session session) {
        put(session, 10, Material.PLAYER_HEAD, "Create one", UiAction.BOT_CREATE, null,
                "name [skin] [x y z world]");
        put(session, 11, Material.CHEST, "Create multiple", UiAction.BOT_MULTI, null,
                "amount name [skin] [x y z world]");
        put(session, 12, Material.REDSTONE, "Random AI bots", UiAction.AI_RANDOM, null,
                "amount name [skin] [x y z world]");
        put(session, 13, Material.FEATHER, "Movement V2 bots", UiAction.AI_MOVEMENT, null,
                "amount name [skin] [x y z world]");
        put(session, 14, Material.EXPERIENCE_BOTTLE, "Training session", UiAction.AI_REINFORCEMENT, null,
                "population name [skin] [mode] [round-minutes]");
    }

    private void renderMovement(Session session) {
        boolean respawnEnabled = plugin != null && plugin.getManager() != null
                && plugin.getManager().isRespawnEnabled();
        boolean movementV2Enabled = plugin != null && MovementV2Settings.isEnabled(plugin);
        boolean admin = hasAdminPermission(session);
        put(session, 10, Material.ENDER_PEARL, "Gather all bots", UiAction.BOT_GATHER, null,
                "Teleport living bots to you");
        put(session, 11, Material.COMPASS, "Circular scatter", UiAction.BOT_SCATTER, null,
                "Use the default safe circular radius");
        put(session, 12, Material.SPYGLASS, "Scatter radius...", UiAction.BOT_SCATTER_RADIUS, null,
                "Enter a radius; command validates it");
        if (admin) {
            put(session, 13, Material.TOTEM_OF_UNDYING,
                    "Respawn: " + (respawnEnabled ? "enabled" : "disabled"), UiAction.BOT_RESPAWN, null,
                    "/bot settings auto-respawn");
            put(session, 14, Material.LIME_DYE, "Enable respawn", UiAction.BOT_RESPAWN, "true",
                    "/bot settings auto-respawn true");
            put(session, 15, Material.RED_DYE, "Disable respawn", UiAction.BOT_RESPAWN, "false",
                    "/bot settings auto-respawn false");
            put(session, 16, Material.FEATHER,
                    "Movement V2: " + (movementV2Enabled ? "enabled" : "disabled"), UiAction.BOT_MOVEMENT_V2, "status",
                    "/bot settings movement-v2 status");
            put(session, 17, Material.LIME_WOOL, "Enable Movement V2", UiAction.BOT_MOVEMENT_V2, "on",
                    "/bot settings movement-v2 on");
            put(session, 18, Material.RED_WOOL, "Disable Movement V2", UiAction.BOT_MOVEMENT_V2, "off",
                    "/bot settings movement-v2 off");
        }
    }

    private void renderAi(Session session) {
        put(session, 10, Material.REDSTONE, "Random AI bots...", UiAction.AI_RANDOM, null,
                "Create random-network bots");
        put(session, 11, Material.EXPERIENCE_BOTTLE, "Start training...", UiAction.AI_REINFORCEMENT, null,
                "Begin an AI training session");
        put(session, 12, Material.BARRIER, "Stop training", UiAction.AI_STOP, null,
                "Confirmation required");
        put(session, 13, Material.BOOK, "Brain status", UiAction.AI_BRAIN_STATUS, null,
                "/ai brain status");
        put(session, 14, Material.HOPPER, "Load brain", UiAction.AI_BRAIN_LOAD, null,
                "/ai brain load");
        if (session.selectedBot != null) {
            Terminator bot = findBot(session.selectedBot);
            String name = bot == null ? null : bot.getBotName();
            if (isUniqueBotName(name, currentBots().stream().map(Terminator::getBotName).toList())) {
                put(session, 15, Material.WRITABLE_BOOK, "Save selected brain", UiAction.AI_BRAIN_SAVE,
                        name, "/ai brain save <selected bot>");
                put(session, 19, Material.REDSTONE, "AI info (selected)", UiAction.AI_INFO,
                        name, "/ai inspect info <selected bot>");
            } else {
                put(session, 15, Material.GRAY_DYE, "Selected brain unavailable", null, null,
                        "The selected bot name is ambiguous.");
                put(session, 19, Material.GRAY_DYE, "Selected info unavailable", null, null,
                        "The selected bot name is ambiguous.");
            }
        } else {
            put(session, 15, Material.WRITABLE_BOOK, "Save brain...", UiAction.AI_BRAIN_SAVE_INPUT, null,
                    "Enter optional bot name");
            put(session, 19, Material.REDSTONE, "AI info...", UiAction.AI_INFO_INPUT, null,
                    "Enter bot name");
        }
        put(session, 16, Material.TNT, "Reset brain", UiAction.AI_BRAIN_RESET, null,
                "Confirmation required");
        put(session, 17, Material.FEATHER, "Movement bots...", UiAction.AI_MOVEMENT, null,
                "Create movement-controller bots");
        put(session, 18, Material.MAP, "List evaluations", UiAction.AI_EVALUATE, "list",
                "/ai evaluate list");
        put(session, 20, Material.PAPER, "Run evaluation...", UiAction.AI_EVALUATE_INPUT, null,
                "Enter optional variant scenario seeds");
    }

    private void renderCombat(Session session) {
        put(session, 10, Material.DIAMOND_SWORD, "Weapons (all)", UiAction.BOT_WEAPONS, null,
                "/bot inspect weapons");
        String selectedName = selectedName(session);
        boolean uniqueSelected = isUniqueBotName(selectedName,
                currentBots().stream().map(Terminator::getBotName).toList());
        put(session, 11, uniqueSelected ? Material.IRON_SWORD : Material.GRAY_DYE,
                uniqueSelected ? "Weapons (selected)" : "Selected weapons unavailable",
                uniqueSelected ? UiAction.BOT_WEAPONS : null,
                uniqueSelected ? selectedName : null,
                uniqueSelected ? "/bot inspect weapons <selected bot>"
                        : "The selected bot name is ambiguous.");
        put(session, 12, Material.CHEST, "Give item...", UiAction.BOT_GIVE, null,
                "item [bot-name] [slot]");
        put(session, 13, Material.BRICKS, "Placement material...", UiAction.BOT_PLACE, null,
                "material");
        put(session, 14, Material.IRON_CHESTPLATE, "Armor tier...", UiAction.BOT_ARMOR, null,
                "none, leather, chain, gold, iron, diamond, netherite");
        put(session, 15, Material.SHULKER_BOX, "Loadout...", UiAction.BOT_LOADOUT, null,
                "name [bot-name]");
        put(session, 16, Material.ENDER_CHEST, "Loadout mix...", UiAction.BOT_LOADOUT_MIX, null,
                "alltypes, core, or problem [bot-prefix]");
        put(session, 17, Material.BOOK, "List presets", UiAction.BOT_PRESET_LIST, null,
                "/bot preset list");
        put(session, 18, Material.WRITABLE_BOOK, "Save preset...", UiAction.BOT_PRESET_SAVE, null,
                "preset-name bot-name");
        put(session, 19, Material.ENCHANTED_BOOK, "Apply preset...", UiAction.BOT_PRESET_APPLY, null,
                "preset-name [bot-name]");
        if (hasAdminPermission(session)) {
            put(session, 20, Material.TNT, "Delete preset...", UiAction.BOT_PRESET_DELETE, null,
                    "Confirmation required");
        }
        if (session.selectedBot != null && selectedName == null) {
            put(session, 21, Material.GRAY_DYE, "Selected inventory unavailable", null, null,
                    "The selected bot is no longer active.");
        } else {
            put(session, 21, Material.CHEST, selectedName == null ? "Open inventory..." : "Open selected inventory",
                    selectedName == null ? UiAction.BOT_INVENTORY_INPUT : UiAction.BOT_INVENTORY,
                    selectedName, selectedName == null ? "Enter bot-name" : "/bot equipment inventory <selected bot>");
        }
        put(session, 22, Material.PAPER, "Count bots", UiAction.BOT_COUNT, null,
                "/bot inspect list");
    }

    private void renderAdmin(Session session) {
        boolean admin = hasAdminPermission(session);
        if (admin) {
            put(session, 10, Material.TNT, "Reset all bots", UiAction.BOT_RESET, null,
                    "Confirmation and permission recheck required");
        }
        put(session, 11, Material.LIME_DYE, "Mob targeting on", UiAction.BOT_SETTINGS, "mobtarget true",
                "/bot settings target-mobs true");
        put(session, 12, Material.RED_DYE, "Mob targeting off", UiAction.BOT_SETTINGS, "mobtarget false",
                "/bot settings target-mobs false");
        put(session, 13, Material.LIME_DYE, "Player-list on", UiAction.BOT_SETTINGS, "addplayerlist true",
                "/bot settings show-in-player-list true");
        put(session, 14, Material.RED_DYE, "Player-list off", UiAction.BOT_SETTINGS, "addplayerlist false",
                "/bot settings show-in-player-list false");
        put(session, 15, Material.COMPASS, "Set target goal...", UiAction.BOT_SETTINGS_INPUT, null,
                "combat-goal value");
        put(session, 16, Material.BEACON, "Set region...", UiAction.BOT_SETTINGS_REGION_INPUT, null,
                "target-region bounds and weights");
        put(session, 17, Material.BARRIER, "Clear region", UiAction.BOT_SETTINGS_REGION_CLEAR, null,
                "Confirmation required");
        if (admin) {
            put(session, 18, Material.COMPARATOR, "Debug expression...", UiAction.BOT_DEBUG, null,
                    "Enter a behavior expression");
            put(session, 19, Material.OBSERVER, "Combat debug all on", UiAction.BOT_COMBAT_DEBUG, "all on",
                    "/bot debug combat all on");
            put(session, 20, Material.REDSTONE_TORCH, "Combat debug all off", UiAction.BOT_COMBAT_DEBUG, "all off",
                    "/bot debug combat all off");
            put(session, 21, Material.PAPER, "Combat debug args...", UiAction.BOT_COMBAT_DEBUG_INPUT, null,
                    "bot-name|all on|off");
        }
    }

    private void renderEnvironment(Session session) {
        put(session, 10, Material.BOOK, "Environment help", UiAction.ENV_HELP, null,
                "/bot environment");
        put(session, 11, Material.BOOK, "Block help", UiAction.ENV_HELP, "blocks",
                "/bot environment solid-block");
        put(session, 12, Material.BOOK, "Mob help", UiAction.ENV_HELP, "mobs",
                "/bot environment custom-mob");
        put(session, 13, Material.COMPASS, "Get material...", UiAction.ENV_GET_MATERIAL, null,
                "x y z (relative values supported)");
        put(session, 14, Material.BRICKS, "Add solid...", UiAction.ENV_ADD_SOLID, null,
                "material or x y z");
        put(session, 15, Material.BARRIER, "Remove solid...", UiAction.ENV_REMOVE_SOLID, null,
                "material or x y z");
        put(session, 16, Material.PAPER, "List solids", UiAction.ENV_LIST_SOLIDS, null,
                "/bot environment solid-block list");
        put(session, 17, Material.TNT, "Clear solids", UiAction.ENV_CLEAR_SOLIDS, null,
                "Confirmation required");
        put(session, 18, Material.ZOMBIE_HEAD, "Add custom mob...", UiAction.ENV_ADD_CUSTOM_MOB, null,
                "entity type");
        put(session, 19, Material.BARRIER, "Remove custom mob...", UiAction.ENV_REMOVE_CUSTOM_MOB, null,
                "entity type");
        put(session, 20, Material.PAPER, "List custom mobs", UiAction.ENV_LIST_CUSTOM_MOBS, null,
                "/bot environment custom-mob list");
        put(session, 21, Material.TNT, "Clear custom mobs", UiAction.ENV_CLEAR_CUSTOM_MOBS, null,
                "Confirmation required");
        put(session, 22, Material.COMPARATOR, "Mob list type...", UiAction.ENV_MOB_LIST_TYPE, null,
                "custom list mode");
        if (hasAdminPermission(session)) {
            put(session, 23, Material.FEATHER, "Movement V2 status", UiAction.ENV_MOVEMENT_V2_STATUS, null,
                    "/bot debug movement [bot-name]");
        }
    }

    private void renderHelp(Session session) {
        put(session, 10, Material.BOOK, "Plugin information", UiAction.MAIN_INFO, null,
                "/terminatorplus");
        put(session, 11, Material.WRITABLE_BOOK, "Upload debug info", UiAction.MAIN_DEBUG_INFO, null,
                "/terminatorplus debuginfo");
        put(session, 12, Material.PLAYER_HEAD, "Bot command help", UiAction.BOT_HELP, null,
                "/bot");
        put(session, 13, Material.REDSTONE, "AI command help", UiAction.AI_HELP, null,
                "/ai");
        put(session, 14, Material.GRASS_BLOCK, "Environment help", UiAction.ENV_HELP, null,
                "/bot environment");
    }

    private void renderConfirmation(Session session) {
        Confirmation confirmation = session.confirmation;
        String command = confirmation == null ? "" : confirmation.command();
        put(session, 22, Material.TNT, "Confirm " + (confirmation == null ? "action" : confirmation.action().label()),
                null, null, "This dispatches:", "/" + command, "Permission is checked again.");
        put(session, 29, Material.LIME_WOOL, "Confirm", UiAction.CONFIRM, null,
                "Run the existing command");
        put(session, 31, Material.RED_WOOL, "Cancel", UiAction.CANCEL, null,
                "Return without changing anything");
    }

    private void renderFooter(Session session) {
        if (session.state.page != Page.MAIN && session.state.page != Page.CONFIRM) {
            put(session, 45, Material.ARROW, "Back", UiAction.BACK, null);
        }
        if (session.state.page == Page.BOTS) {
            int pages = pageCount(currentBots().size());
            int pageIndex = clampPageIndex(session.state.pageIndex(), pages);
            put(session, 47, pageIndex > 0 ? Material.ARROW : Material.GRAY_DYE,
                    "Previous page", UiAction.PREVIOUS_PAGE, null,
                    pageIndex > 0 ? "Page " + pageIndex + "/" + pages : "Already on the first page");
            put(session, 50, Material.PAPER, "Page " + (pageIndex + 1) + "/" + pages, null, null,
                    currentBots().isEmpty() ? "No active bots" : "Select a bot above");
            put(session, 51, pageIndex + 1 < pages ? Material.SPECTRAL_ARROW : Material.GRAY_DYE,
                    "Next page", UiAction.NEXT_PAGE, null,
                    pageIndex + 1 < pages ? "Page " + (pageIndex + 2) + "/" + pages : "Already on the last page");
        }
        put(session, 49, Material.PAPER, "Status", null, null, session.status);
        put(session, 53, Material.BARRIER, "Close", UiAction.CLOSE, null);
    }

    private void put(Session session, int slot, Material material, String name,
                     UiAction action, String payload, String... lore) {
        session.renderedSlots.add(slot);
        ItemStack item = item(material, name, lore);
        updateIfChanged(session.inventory, slot, item);
        if (action != null) session.buttons.put(slot, new Button(action, payload));
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.RESET + name);
            if (lore != null && lore.length > 0) {
                List<String> lines = new ArrayList<>(lore.length);
                for (String line : lore) lines.add(ChatColor.GRAY + String.valueOf(line));
                meta.setLore(lines);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    static boolean updateIfChanged(Inventory inventory, int slot, ItemStack next) {
        ItemStack current = inventory.getItem(slot);
        if (Objects.equals(current, next)) return false;
        inventory.setItem(slot, next == null ? null : next.clone());
        return true;
    }

    static boolean shouldUpdate(ItemStack current, ItemStack next) {
        return changedOnly(current, next);
    }

    static boolean changedOnly(Object current, Object next) {
        return !Objects.equals(current, next);
    }

    static boolean isSafePromptInput(String input) {
        if (input == null || input.length() > MAX_PROMPT_LENGTH) return false;
        for (int i = 0; i < input.length(); i++) {
            if (Character.isISOControl(input.charAt(i))) return false;
        }
        return true;
    }

    static String commandFor(UiAction action) {
        return action == null ? null : action.command();
    }

    static String commandFor(UiAction action, String arguments) {
        String command = commandFor(action);
        if (command == null) return null;
        if (arguments == null || arguments.isBlank()) return command;
        return command + " " + arguments.trim();
    }

    static boolean requiresConfirmation(UiAction action) {
        return action != null && action.destructive();
    }

    static boolean visibleForPermission(UiAction action, boolean admin) {
        return action != null && (!action.requiresAdmin() || admin);
    }

    static String dispatchStatus(boolean accepted, String command) {
        String value = command == null ? "" : command;
        String shown = value.length() > 48 ? value.substring(0, 45) + "..." : value;
        return (accepted ? "Dispatched /" : "Rejected /") + shown;
    }

    static long refreshIntervalTicks() {
        return REFRESH_INTERVAL_TICKS;
    }

    static long promptTimeoutTicks() {
        return PROMPT_TIMEOUT_TICKS;
    }

    static boolean isCurrentPrompt(PendingPrompt active, PendingPrompt candidate) {
        return active != null && active == candidate && !candidate.cancelled();
    }

    static Page initialPage() {
        return Page.MAIN;
    }

    static boolean detailStillValid(UUID selectedBot, Collection<UUID> activeBotIds) {
        return selectedBot != null && activeBotIds != null && activeBotIds.contains(selectedBot);
    }

    static boolean isUniqueBotName(String selected, Collection<String> names) {
        if (selected == null || names == null) return false;
        return names.stream().filter(name -> selected.equalsIgnoreCase(name)).limit(2).count() == 1;
    }

    private String promptHint(UiAction action) {
        return switch (action) {
            case BOT_CREATE -> "name [skin] [x y z world]";
            case BOT_MULTI, AI_RANDOM, AI_MOVEMENT -> "amount name [skin] [x y z world]";
            case AI_REINFORCEMENT -> "population-size name [skin] [mode] [round-minutes]";
            case BOT_GIVE -> "item [bot-name] [slot]";
            case BOT_PLACE -> "material";
            case BOT_ARMOR -> "armor tier";
            case BOT_SCATTER_RADIUS -> "radius";
            case AI_BRAIN_SAVE_INPUT -> "optional bot-name";
            case AI_EVALUATE_INPUT -> "variant [scenario] [seed[,seed...]]";
            case AI_INFO_INPUT -> "bot-name";
            case BOT_LOADOUT -> "loadout [bot-name]";
            case BOT_LOADOUT_MIX -> "alltypes|core|problem [bot-prefix]";
            case BOT_PRESET_SAVE -> "preset-name bot-name";
            case BOT_PRESET_APPLY -> "preset-name [bot-name]";
            case BOT_PRESET_DELETE -> "preset-name";
            case BOT_SETTINGS_INPUT -> "settings arguments";
            case BOT_SETTINGS_REGION_INPUT -> "region bounds and weights";
            case BOT_DEBUG -> "debug expression";
            case BOT_COMBAT_DEBUG_INPUT -> "bot-name|all on|off";
            case ENV_GET_MATERIAL -> "x y z";
            case ENV_ADD_SOLID, ENV_REMOVE_SOLID -> "material or x y z";
            case ENV_ADD_CUSTOM_MOB, ENV_REMOVE_CUSTOM_MOB -> "entity type";
            case ENV_MOB_LIST_TYPE -> "custom list mode";
            default -> "command arguments";
        };
    }

    private String requiredPermission(UiAction action) {
        return action.requiresAdmin() ? ADMIN_PERMISSION : MANAGE_PERMISSION;
    }

    private List<Terminator> currentBots() {
        if (plugin == null || plugin.getManager() == null) return List.of();
        return plugin.getManager().fetch().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Terminator::getBotName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private Terminator findBot(UUID id) {
        if (id == null) return null;
        for (Terminator bot : currentBots()) {
            if (id.equals(botId(bot))) return bot;
        }
        return null;
    }

    private static UUID botId(Terminator bot) {
        return bot == null || bot.getBukkitEntity() == null ? null : bot.getBukkitEntity().getUniqueId();
    }

    static int pageCount(int count) {
        return Math.max(1, (count + BOT_PAGE_SIZE - 1) / BOT_PAGE_SIZE);
    }

    static int clampPageIndex(int index, int pages) {
        int last = Math.max(0, pages - 1);
        return Math.max(0, Math.min(index, last));
    }

    private boolean hasAdminPermission(Session session) {
        Player player = Bukkit.getPlayer(session.playerId);
        return player != null && player.hasPermission(ADMIN_PERMISSION);
    }

    private String selectedName(Session session) {
        Terminator bot = findBot(session.selectedBot);
        return bot == null ? null : bot.getBotName();
    }

    enum Page {
        MAIN,
        BOTS,
        BOT_DETAIL,
        CREATE,
        MOVEMENT,
        AI,
        COMBAT,
        ADMIN,
        ENVIRONMENT,
        HELP,
        CONFIRM
    }

    enum UiAction {
        OPEN_BOTS(null, false, false, false, "Bots and status"),
        OPEN_CREATE(null, false, false, false, "Create bots"),
        OPEN_MOVEMENT(null, false, false, false, "Movement"),
        OPEN_AI(null, false, false, false, "AI"),
        OPEN_COMBAT(null, false, false, false, "Combat and loadouts"),
        OPEN_ADMIN(null, false, false, false, "Debug and admin"),
        OPEN_ENVIRONMENT(null, false, false, false, "Environment"),
        OPEN_HELP(null, false, false, false, "Help and plugin info"),
        BACK(null, false, false, false, "Back"),
        PREVIOUS_PAGE(null, false, false, false, "Previous page"),
        NEXT_PAGE(null, false, false, false, "Next page"),
        CLOSE(null, false, false, false, "Close"),
        SELECT_BOT(null, false, false, false, "Select bot"),
        CONFIRM(null, false, false, false, "Confirm"),
        CANCEL(null, false, false, false, "Cancel"),

        BOT_HELP("bot info", false, false, false, "Bot help"),
        BOT_CREATE("bot spawn single", true, false, false, "Create bot"),
        BOT_MULTI("bot spawn multiple", true, false, false, "Create multiple bots"),
        BOT_RESPAWN("bot settings auto-respawn", false, false, true, "Respawn"),
        BOT_MOVEMENT_V2("bot settings movement-v2", false, false, true, "Movement V2"),
        BOT_GIVE("bot equipment give", true, false, false, "Give item"),
        BOT_PLACE("bot settings placement-material", true, false, false, "Placement material"),
        BOT_ARMOR("bot equipment armor", true, false, false, "Armor"),
        BOT_INFO("bot inspect info", false, false, false, "Bot info"),
        BOT_COUNT("bot inspect list", false, false, false, "Count bots"),
        BOT_RESET("bot admin reset", false, true, true, "Reset all bots"),
        BOT_SETTINGS("bot settings", false, false, false, "Bot settings"),
        BOT_SETTINGS_INPUT("bot settings", true, false, false, "Bot settings"),
        BOT_SETTINGS_REGION_INPUT("bot settings target-region", true, false, false, "Set region"),
        BOT_SETTINGS_REGION_CLEAR("bot settings target-region clear", false, true, false, "Clear region"),
        BOT_DEBUG("bot debug behavior", true, false, true, "Debug expression"),
        BOT_WEAPONS("bot inspect weapons", false, false, false, "Weapon status"),
        BOT_COMBAT_DEBUG("bot debug combat", false, false, true, "Combat debug"),
        BOT_COMBAT_DEBUG_INPUT("bot debug combat", true, false, true, "Combat debug"),
        BOT_GATHER("bot move gather", false, false, false, "Gather"),
        BOT_SCATTER("bot move scatter", false, false, false, "Circular scatter"),
        BOT_SCATTER_RADIUS("bot move scatter", true, false, false, "Scatter radius"),
        BOT_INVENTORY("bot equipment inventory", false, false, false, "Inventory"),
        BOT_INVENTORY_INPUT("bot equipment inventory", true, false, false, "Inventory"),
        BOT_PRESET_SAVE("bot preset save", true, false, false, "Save preset"),
        BOT_PRESET_APPLY("bot preset apply", true, false, false, "Apply preset"),
        BOT_PRESET_LIST("bot preset list", false, false, false, "List presets"),
        BOT_PRESET_DELETE("bot preset delete", true, true, true, "Delete preset"),
        BOT_LOADOUT("bot equipment loadout", true, false, false, "Loadout"),
        BOT_LOADOUT_MIX("bot equipment mixed-loadout", true, false, false, "Loadout mix"),

        AI_HELP("ai", false, false, false, "AI help"),
        AI_RANDOM("ai spawn random", true, false, false, "Random AI bots"),
        AI_REINFORCEMENT("ai train reinforcement", true, false, false, "Training session"),
        AI_STOP("ai train stop", false, true, false, "Stop training"),
        AI_BRAIN_STATUS("ai brain status", false, false, false, "Brain status"),
        AI_BRAIN_LOAD("ai brain load", false, false, false, "Load brain"),
        AI_BRAIN_SAVE("ai brain save", false, false, false, "Save brain"),
        AI_BRAIN_SAVE_INPUT("ai brain save", true, false, false, "Save brain"),
        AI_BRAIN_RESET("ai brain reset", false, true, false, "Reset brain"),
        AI_MOVEMENT("ai spawn movement", true, false, false, "Movement bots"),
        AI_EVALUATE("ai evaluate", false, false, false, "Evaluate"),
        AI_EVALUATE_INPUT("ai evaluate", true, false, false, "Evaluate"),
        AI_INFO("ai inspect info", false, false, false, "AI info"),
        AI_INFO_INPUT("ai inspect info", true, false, false, "AI info"),

        MAIN_INFO("terminatorplus", false, false, false, "Plugin information"),
        MAIN_DEBUG_INFO("terminatorplus debuginfo", false, false, false, "Debug upload"),

        ENV_HELP("bot environment", false, false, false, "Environment help"),
        ENV_GET_MATERIAL("bot environment inspect material", true, false, false, "Get material"),
        ENV_ADD_SOLID("bot environment solid-block add", true, false, false, "Add solid"),
        ENV_REMOVE_SOLID("bot environment solid-block remove", true, true, false, "Remove solid"),
        ENV_LIST_SOLIDS("bot environment solid-block list", false, false, false, "List solids"),
        ENV_CLEAR_SOLIDS("bot environment solid-block clear", false, true, false, "Clear solids"),
        ENV_ADD_CUSTOM_MOB("bot environment custom-mob add", true, false, false, "Add custom mob"),
        ENV_REMOVE_CUSTOM_MOB("bot environment custom-mob remove", true, true, false, "Remove custom mob"),
        ENV_LIST_CUSTOM_MOBS("bot environment custom-mob list", false, false, false, "List custom mobs"),
        ENV_CLEAR_CUSTOM_MOBS("bot environment custom-mob clear", false, true, false, "Clear custom mobs"),
        ENV_MOB_LIST_TYPE("bot environment mob-list-mode set", true, false, false, "Mob list type"),
        ENV_MOVEMENT_V2_STATUS("bot debug movement", false, false, true, "Movement V2 status");

        private final String command;
        private final boolean prompt;
        private final boolean destructive;
        private final boolean requiresAdmin;
        private final String label;

        UiAction(String command, boolean prompt, boolean destructive, boolean requiresAdmin, String label) {
            this.command = command;
            this.prompt = prompt;
            this.destructive = destructive;
            this.requiresAdmin = requiresAdmin;
            this.label = label;
        }

        String command() {
            return command;
        }

        boolean prompt() {
            return prompt;
        }

        boolean destructive() {
            return destructive;
        }

        boolean requiresAdmin() {
            return requiresAdmin;
        }

        String label() {
            return label;
        }
    }

    static final class PageState {
        private Page page = initialPage();
        private int pageIndex;
        private final Deque<Page> history = new ArrayDeque<>();

        void navigate(Page next) {
            if (next == null || next == page || next == Page.CONFIRM) return;
            history.push(page);
            page = next;
            if (next != Page.BOTS) pageIndex = 0;
        }

        void back() {
            page = history.isEmpty() ? initialPage() : history.pop();
            if (page != Page.BOTS) pageIndex = 0;
        }

        void reset() {
            history.clear();
            page = initialPage();
            pageIndex = 0;
        }

        void resetTo(Page next) {
            history.clear();
            page = next == null ? initialPage() : next;
            pageIndex = 0;
        }

        void setPageIndex(int index, int pages) {
            pageIndex = clampPageIndex(index, pages);
        }

        Page page() {
            return page;
        }

        int pageIndex() {
            return pageIndex;
        }
    }

    static final class LifecycleState {
        private int sessions;
        private boolean refreshDemand;

        void sessionOpened() {
            sessions++;
            refreshDemand = true;
        }

        void sessionClosed() {
            if (sessions > 0) sessions--;
            refreshDemand = sessions > 0;
        }

        void shutdown() {
            sessions = 0;
            refreshDemand = false;
        }

        int sessionCount() {
            return sessions;
        }

        boolean shouldRefresh() {
            return refreshDemand && sessions > 0;
        }
    }

    @FunctionalInterface
    interface CommandDispatcher {
        boolean dispatch(UUID playerId, String command);
    }

    private record Button(UiAction action, String payload) {
    }

    static final class PendingPrompt {
        private final UiAction action;
        private final String hint;
        private BukkitTask timeoutTask;
        private boolean cancelled;

        PendingPrompt(UiAction action, String hint) {
            this.action = action;
            this.hint = hint;
        }

        UiAction action() {
            return action;
        }

        String hint() {
            return hint;
        }

        void cancel() {
            if (timeoutTask != null && !timeoutTask.isCancelled()) timeoutTask.cancel();
            timeoutTask = null;
            cancelled = true;
        }

        boolean cancelled() {
            return cancelled;
        }
    }

    private record Confirmation(UiAction action, String command, Page returnPage) {
    }

    private final class Session implements InventoryHolder {
        private final UUID playerId;
        private final Inventory inventory;
        private final PageState state = new PageState();
        private final Map<Integer, Button> buttons = new HashMap<>();
        private final Set<Integer> renderedSlots = new HashSet<>();
        private UUID selectedBot;
        private String status = "Ready";
        private Confirmation confirmation;
        private boolean awaitingPrompt;

        private Session(UUID playerId) {
            this.playerId = playerId;
            this.inventory = Bukkit.createInventory(this, INVENTORY_SIZE, TITLE);
        }

        private void resetForOpen() {
            state.reset();
            selectedBot = null;
            confirmation = null;
            awaitingPrompt = false;
            status = "Ready";
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
