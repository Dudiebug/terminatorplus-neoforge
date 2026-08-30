package net.nuggetmc.tplus.command.commands;

import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.AIManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.IntelligenceAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementBrainBank;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementBrainPersistence;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementNetwork;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementNetworkGenetics;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementTrainingConfig;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.MathUtils;
import net.nuggetmc.tplus.bot.BotManagerImpl;
import net.nuggetmc.tplus.bot.movement.eval.MovementEvaluationHarness;
import net.nuggetmc.tplus.command.CommandHandler;
import net.nuggetmc.tplus.command.CommandInstance;
import net.nuggetmc.tplus.command.annotation.*;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.ChatColor;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.World;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitScheduler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class AICommand extends CommandInstance implements AIManager {

    private final TerminatorPlus plugin;
    private final BotManagerImpl manager;
    private final BukkitScheduler scheduler;
    private final Random brainRandom;

    private IntelligenceAgent agent;
    private MovementBrainBank movementBank;
    private MovementNetwork movementBrain;
    private MovementBrainPersistence.TrainingMetadata movementBrainMetadata;

    public AICommand(CommandHandler handler, String name, String description, String... aliases) {
        super(handler, name, description, aliases);

        this.plugin = TerminatorPlus.getInstance();
        this.manager = plugin.getManager();
        this.scheduler = Bukkit.getScheduler();
        this.brainRandom = new Random();
        loadMovementBrain(null, false);
    }

    @Command
    public void root(CommandSender sender, List<String> args) {
        commandHandler.sendRootInfo(this, sender);
    }

    @Command(
            name = "spawn",
            desc = "Spawn AI-controlled bots.",
            autofill = "spawnAutofill"
    )
    public void spawn(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sendGroupHelp(sender, "/ai spawn", "random <amount> <name> [skin] [location]", "movement <amount> <name> [skin] [location]");
            return;
        }

        if (args.get(0).equalsIgnoreCase("movement")) {
            movement(sender, args.subList(1, args.size()));
            return;
        }
        if (args.get(0).equalsIgnoreCase("random")) {
            if (args.size() < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ai spawn random <amount> <name> [skin] [location]");
                return;
            }
            int amount;
            try {
                amount = Integer.parseInt(args.get(1));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Amount must be a number.");
                return;
            }
            String skin = args.size() >= 4 ? args.get(3) : null;
            String location = args.size() >= 5 ? String.join(" ", args.subList(4, args.size())) : "";
            random(sender, args.subList(1, args.size()), amount, args.get(2), skin, location);
            return;
        }
        sendGroupHelp(sender, "/ai spawn", "random <amount> <name> [skin] [location]", "movement <amount> <name> [skin] [location]");
    }

    public List<String> spawnAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? List.of("random", "movement") : List.of();
    }

    @Command(
            name = "train",
            desc = "Start or stop AI training.",
            autofill = "trainAutofill"
    )
    public void train(CommandSender sender, List<String> args) {
        if (args.isEmpty()) {
            sendGroupHelp(sender, "/ai train", "reinforcement <population-size> <name> [skin] [mode] [round-minutes]", "stop");
            return;
        }
        if (args.get(0).equalsIgnoreCase("stop")) {
            stop(sender);
            return;
        }
        if (!args.get(0).equalsIgnoreCase("reinforcement")) {
            sendGroupHelp(sender, "/ai train", "reinforcement <population-size> <name> [skin] [mode] [round-minutes]", "stop");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This is a player-only command.");
            return;
        }
        if (args.size() < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /ai train reinforcement <population-size> <name> [skin] [mode] [round-minutes]");
            return;
        }
        int populationSize;
        try {
            populationSize = Integer.parseInt(args.get(1));
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Population size must be a number.");
            return;
        }
        String skin = args.size() >= 4 ? args.get(3) : null;
        String mode = args.size() >= 5 ? args.get(4) : null;
        String roundMinutes = args.size() >= 6 ? args.get(5) : null;
        reinforcement(player, populationSize, args.get(2), skin, mode, roundMinutes);
    }

    public List<String> trainAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? List.of("reinforcement", "stop") : List.of();
    }

    @Command(
            name = "inspect",
            desc = "Inspect AI state.",
            autofill = "inspectAutofill"
    )
    public void inspect(CommandSender sender, List<String> args) {
        if (args.size() == 2 && args.get(0).equalsIgnoreCase("info")) {
            info(sender, args.get(1));
            return;
        }
        sendGroupHelp(sender, "/ai inspect", "info <bot-name>");
    }

    public List<String> inspectAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) return List.of("info");
        return args.length == 3 && args[1].equalsIgnoreCase("info") ? manager.fetchNames() : List.of();
    }

    @Command(
            name = "random",
            desc = "Create bots with random neural networks, collecting feed data.",
            visible = false
    )
    public void random(CommandSender sender, List<String> args, @Arg("amount") int amount, @Arg("name") String name, @OptArg("skin") String skin, @OptArg("loc") @TextArg String loc) {
        if (sender instanceof Player && args.size() < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /ai random <amount> <name> [skin] [spawnLoc: [player Player]/[x,y,z]]");
            return;
        }
        Location location = parseSpawnLocation(sender, loc);
        if (location == null) return;
        manager.createBots(sender, name, skin, amount, NeuralNetwork.RANDOM, location);
    }

    @Command(
            name = "reinforcement",
            desc = "Begin an AI training session.",
            visible = false
    )
    public void reinforcement(Player sender, @Arg("population-size") int populationSize, @Arg("name") String name, @OptArg("skin") String skin, @OptArg("mode") String mode, @OptArg("round-minutes") String roundMinutesStr) {
        //FIXME: Sometimes, bots will become invisible, or just stop working if they're the last one alive, this has been partially fixed (invis part) see Terminator#removeBot, which removes the bot.
        //This seems to fix it for the most part, but its still buggy, as the bot will sometimes still freeze
        //see https://cdn.carbonhost.cloud/6201479d7b237373ab269385/screenshots/javaw_DluMN4m0FR.png
        //Blocks are also not placeable where bots have died
        if (agent != null) {
            sender.sendMessage("A session is already active.");
            return;
        }

        double defaultRoundMinutes = plugin.getConfig().getDouble("ai.training.max-round-minutes", 1.0);
        int maxRoundTicks = roundMinutesToTicks(defaultRoundMinutes);
        if (roundMinutesStr != null && !roundMinutesStr.isBlank()) {
            try {
                double minutes = Double.parseDouble(roundMinutesStr);
                maxRoundTicks = roundMinutesToTicks(minutes);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Round time must be a number (minutes).");
                return;
            }
        }

        sender.sendMessage("Starting a new session...");

        MovementTrainingRequest trainingRequest = parseTrainingRequest(mode);
        IntelligenceAgent.TrainingMode trainingMode = trainingRequest.mode();
        MovementTrainingConfig config = movementConfig()
                .withOverrides(trainingRequest.loadoutMixOverride(), trainingRequest.curriculumFamilyOverride());
        MovementBrainBank seedBank = null;
        if (trainingMode == IntelligenceAgent.TrainingMode.MOVEMENT_CONTROLLER) {
            seedBank = ensureMovementBank(sender);
            if (seedBank == null) return;
            sender.sendMessage("Training mode: " + ChatColor.YELLOW + "movement-controller"
                    + ChatColor.RESET + ", loadout mix: " + ChatColor.YELLOW + config.effectiveLoadoutMix()
                    + ChatColor.RESET + ", curriculum family: " + ChatColor.YELLOW
                    + config.curriculumFamily() + ChatColor.RESET + ".");
            sender.sendMessage("Round limit: " + ChatColor.YELLOW + formatRoundLimit(maxRoundTicks) + ChatColor.RESET + ".");
            sender.sendMessage(ChatColor.GRAY + "Mixed default training records per-family telemetry and updates "
                    + "eligible specialist brains; set ai.training.curriculum-family to train one specialist.");
        }
        agent = new IntelligenceAgent(this, populationSize, name, skin, plugin, plugin.getManager(),
                trainingMode, seedBank, config, this::saveTrainingBrain, maxRoundTicks);
        agent.addUser(sender);
    }

    public IntelligenceAgent getSession() {
        return agent;
    }

    @Command(
            name = "stop",
            desc = "End a currently running AI training session.",
            visible = false
    )
    public void stop(CommandSender sender) {
        if (agent == null) {
            sender.sendMessage("No session is currently active.");
            return;
        }

        sender.sendMessage("Stopping the current session...");
        String name = agent.getName();
        clearSession();

        scheduler.runTaskLater(plugin, () -> sender.sendMessage("The session " + ChatColor.YELLOW + name + ChatColor.RESET + " has been closed."), 10);
    }

    @Override
    public void clearSession() {
        if (agent != null) {
            agent.stop();
            agent = null;
        }
    }

    public boolean hasActiveSession() {
        return agent != null;
    }

    @Command(
            name = "brain",
            desc = "Save, load, reset, or show the movement-controller brain.",
            autofill = "brainAutofill"
    )
    public void brain(CommandSender sender, List<String> args) {
        if (args.isEmpty() || args.get(0).equalsIgnoreCase("status")) {
            sendBrainStatus(sender);
            return;
        }

        String action = args.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "load" -> loadMovementBrain(sender, true);
            case "save" -> saveBrainCommand(sender, args.size() >= 2 ? args.get(1) : null);
            case "reset" -> resetMovementBrain(sender);
            default -> sender.sendMessage(ChatColor.RED + "Usage: /ai brain <status|load|save|reset> [bot-name]");
        }
    }

    @Command(
            name = "movement",
            desc = "Create movement-controller bot(s) from the persisted movement brain.",
            visible = false
    )
    public void movement(CommandSender sender, List<String> args) {
        if (args.size() < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /ai movement <amount> <name> [skin] [spawnLoc: player|x y z [world]]");
            return;
        }

        int amount;
        try {
            amount = Math.max(1, Integer.parseInt(args.get(0)));
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Amount must be a number.");
            return;
        }

        String name = args.get(1);
        String skin = args.size() >= 3 ? args.get(2) : null;
        String locText = args.size() >= 4 ? String.join(" ", args.subList(3, args.size())) : "";
        Location location = parseLocation(sender, locText);
        if (location == null) return;

        MovementBrainBank bank = ensureMovementBank(sender);
        if (bank == null) return;
        MovementNetwork brain = bank.fallbackNetwork();

        NeuralNetwork network = NeuralNetwork.createMovementControllerNetwork(bank);
        manager.createBots(sender, name, skin, amount, network, location);
        sender.sendMessage("Created movement-controller bot(s) with movement bank "
                + ChatColor.YELLOW + bank.availableBrainNames().size() + ChatColor.RESET
                + " brain(s); fallback shape " + ChatColor.YELLOW
                + Arrays.toString(brain.layerSizes()) + ChatColor.RESET + ".");
    }

    @Command(
            name = "evaluate",
            desc = "Export a movement-brain evaluation report.",
            autofill = "evaluateAutofill"
    )
    public void evaluate(CommandSender sender, List<String> args) {
        if (!args.isEmpty() && args.get(0).equalsIgnoreCase("list")) {
            sender.sendMessage(ChatUtils.LINE);
            sender.sendMessage(ChatColor.DARK_GREEN + "Movement Evaluation");
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "variants: " + ChatColor.YELLOW
                    + String.join(", ", MovementEvaluationHarness.variantIds()));
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "scenarios: " + ChatColor.YELLOW
                    + String.join(", ", MovementEvaluationHarness.scenarioIds()));
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "usage: " + ChatColor.YELLOW
                    + "/ai evaluate [variant] [scenario] [seed[,seed...]]");
            sender.sendMessage(ChatUtils.LINE);
            return;
        }

        String variant = args.size() >= 1 ? args.get(0) : plugin.getConfig().getString("ai.evaluation.default-variant", "branch_family_latched");
        String scenario = args.size() >= 2 ? args.get(1) : plugin.getConfig().getString("ai.evaluation.default-scenario", "all");
        if (!MovementEvaluationHarness.isKnownVariant(variant)) {
            sender.sendMessage(ChatColor.RED + "Unknown evaluation variant: " + ChatColor.YELLOW + variant);
            sender.sendMessage("Available: " + ChatColor.YELLOW + String.join(", ", MovementEvaluationHarness.variantIds()));
            return;
        }
        if (!MovementEvaluationHarness.isKnownScenario(scenario)) {
            sender.sendMessage(ChatColor.RED + "Unknown evaluation scenario: " + ChatColor.YELLOW + scenario);
            sender.sendMessage("Available: " + ChatColor.YELLOW + String.join(", ", MovementEvaluationHarness.scenarioIds()));
            return;
        }

        List<Long> seeds;
        try {
            seeds = MovementEvaluationHarness.parseSeeds(args.size() >= 3 ? args.subList(2, args.size()) : List.of());
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Seeds must be comma-separated whole numbers.");
            return;
        }

        if (movementBank == null) {
            loadMovementBrain(sender, false);
        }

        MovementEvaluationHarness harness = new MovementEvaluationHarness(plugin);
        MovementEvaluationHarness.ExportResult result;
        try {
            result = harness.evaluate(movementBank, movementConfig(), variant, scenario, seeds);
        } catch (IOException | RuntimeException e) {
            sender.sendMessage(ChatColor.RED + "Failed to export movement evaluation report: " + e.getMessage());
            return;
        }

        MovementEvaluationHarness.EvaluationReport report = result.report();
        MovementEvaluationHarness.AggregateSummary aggregate = report.aggregate();
        sender.sendMessage(ChatUtils.LINE);
        sender.sendMessage(ChatColor.DARK_GREEN + "Movement Evaluation" + ChatUtils.BULLET_FORMATTED
                + ChatColor.YELLOW + report.variant().id());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "status: " + ChatColor.YELLOW
                + report.variant().supportStatus());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "seeds: " + ChatColor.YELLOW
                + report.metadata().evaluationSeeds());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "loadouts: " + ChatColor.YELLOW
                + MovementEvaluationHarness.describeCounts(aggregate.loadoutDistribution()));
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "active families: " + ChatColor.YELLOW
                + MovementEvaluationHarness.describeCounts(aggregate.activeBranchFamilyDistribution()));
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "fallbacks: " + ChatColor.YELLOW
                + aggregate.fallbackCount() + ChatColor.RESET + "/"
                + ChatColor.YELLOW + aggregate.sampleCount() + ChatColor.RESET
                + " (" + ChatColor.YELLOW + MathUtils.round2Dec(aggregate.fallbackRate() * 100.0)
                + "%" + ChatColor.RESET + "), missing/incompatible="
                + ChatColor.YELLOW + aggregate.missingIncompatibleBrainFallbackCount());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "export: " + ChatColor.YELLOW + result.path());
        sender.sendMessage(ChatColor.GRAY + report.variant().metricStatus());
        sender.sendMessage(ChatColor.GRAY + "Mixed training records per-family telemetry but updates "
                + MovementBrainBank.FALLBACK_BRAIN_NAME
                + "; curriculum mode updates ai.training.curriculum-family.");
        sender.sendMessage(ChatUtils.LINE);
    }

    @Command(
            name = "info",
            desc = "Display neural network information about a bot.",
            autofill = "infoAutofill",
            visible = false
    )
    public void info(CommandSender sender, @Arg("bot-name") String name) {
        sender.sendMessage("Processing request...");

        try {
            Terminator bot = manager.getFirst(name, (sender instanceof Player pl) ? pl.getLocation() : null);

            if (bot == null) {
                sender.sendMessage("Could not find bot " + ChatColor.GREEN + name + ChatColor.RESET + "!");
                return;
            }

            if (!bot.hasNeuralNetwork()) {
                sender.sendMessage("The bot " + ChatColor.GREEN + name + ChatColor.RESET + " does not have a neural network!");
                return;
            }

            NeuralNetwork network = bot.getNeuralNetwork();
            List<String> strings = new ArrayList<>();

            if (network.usesMovementController()) {
                MovementNetwork movementNetwork = network.movementNetwork();
                MovementBrainBank bank = network.movementBrainBank();
                strings.add(ChatColor.YELLOW + "\"movement-controller\"" + ChatColor.RESET + ":");
                strings.add(ChatUtils.BULLET_FORMATTED + "bank brains: " + ChatColor.RED
                        + (bank == null ? 0 : bank.availableBrainNames().size()));
                strings.add(ChatUtils.BULLET_FORMATTED + "fallback: " + ChatColor.RED
                        + (bank == null ? "none" : bank.defaultBrainName()));
                strings.add(ChatUtils.BULLET_FORMATTED + "shape: " + ChatColor.RED
                        + Arrays.toString(movementNetwork.layerSizes()));
                strings.add(ChatUtils.BULLET_FORMATTED + "parameters: " + ChatColor.RED
                        + movementNetwork.parameterCount());
            }

            network.nodes().forEach((nodeType, node) -> {
                strings.add("");
                strings.add(ChatColor.YELLOW + "\"" + nodeType.name().toLowerCase() + "\"" + ChatColor.RESET + ":");
                List<String> values = new ArrayList<>();
                node.getValues().forEach((dataType, value) -> values.add(ChatUtils.BULLET_FORMATTED + "node"
                        + dataType.getShorthand().toUpperCase() + ": " + ChatColor.RED + MathUtils.round2Dec(value)));
                strings.addAll(values);
            });

            sender.sendMessage(ChatUtils.LINE);
            sender.sendMessage(ChatColor.DARK_GREEN + "NeuralNetwork" + ChatUtils.BULLET_FORMATTED + ChatColor.GRAY + "[" + ChatColor.GREEN + name + ChatColor.GRAY + "]");
            strings.forEach(sender::sendMessage);
            sender.sendMessage(ChatUtils.LINE);
        } catch (Exception e) {
            sender.sendMessage(ChatUtils.EXCEPTION_MESSAGE);
        }
    }

    public List<String> infoAutofill(CommandSender sender, String[] args) {
        return args.length == 2 ? manager.fetchNames() : new ArrayList<>();
    }

    public List<String> brainAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) return new ArrayList<>(List.of("status", "load", "save", "reset"));
        if (args.length == 3 && args[1].equalsIgnoreCase("save")) return manager.fetchNames();
        return new ArrayList<>();
    }

    public List<String> evaluateAutofill(CommandSender sender, String[] args) {
        if (args.length == 2) {
            List<String> values = new ArrayList<>();
            values.add("list");
            values.addAll(MovementEvaluationHarness.variantIds());
            return values;
        }
        if (args.length == 3) return MovementEvaluationHarness.scenarioIds();
        if (args.length == 4) return List.of("1337,7331,424242");
        return new ArrayList<>();
    }

    private MovementTrainingConfig movementConfig() {
        return MovementTrainingConfig.load(plugin);
    }

    private MovementBrainPersistence.SaveFeedback saveTrainingBrain(
            String familyId,
            MovementNetwork network,
            MovementBrainPersistence.TrainingMetadata metadata,
            MovementBrainBank.RolloutStats rolloutStats
    ) {
        MovementTrainingConfig config = movementConfig();
        String family = MovementTrainingConfig.normalizeFamilyId(familyId);
        String brainName = brainNameForFamily(family);
        MovementBrainBank.Brain existing = movementBank == null ? null : movementBank.brains().get(brainName);
        if (config.saveOnlyImprovedBrain() && existing != null
                && metadata.bestFitness() <= existing.metadata().bestFitness()) {
            return new MovementBrainPersistence.SaveFeedback(false,
                    "Skipped " + family + " autosave; best fitness did not improve.");
        }

        movementBank = movementBankWithBrain(config, family, network, metadata, rolloutStats, "training-session");
        movementBrain = movementBank == null ? null : movementBank.fallbackNetwork();
        movementBrainMetadata = movementBank == null
                ? MovementBrainPersistence.TrainingMetadata.manual()
                : movementBank.fallbackMetadata();
        if (!config.autosaveBestBrain()) {
            return new MovementBrainPersistence.SaveFeedback(false,
                    "Updated in-memory " + family + " movement brain; autosave is disabled.");
        }

        MovementBrainPersistence.BankSaveResult result = MovementBrainPersistence.saveBank(plugin, config,
                movementBankWithBrain(config, family, network, metadata, rolloutStats, "training-autosave"));
        if (!result.saved()) {
            return new MovementBrainPersistence.SaveFeedback(false,
                    "Movement brain autosave failed: " + ChatColor.RED + result.message());
        }

        MovementBrainPersistence.BankLoadResult reload = MovementBrainPersistence.loadBank(plugin, config);
        if (reload.loaded()) {
            movementBank = reload.bank();
            movementBrain = movementBank == null ? null : movementBank.fallbackNetwork();
            movementBrainMetadata = movementBank == null
                    ? MovementBrainPersistence.TrainingMetadata.manual()
                    : movementBank.fallbackMetadata();
        }
        return new MovementBrainPersistence.SaveFeedback(true,
                "Saved " + ChatColor.YELLOW + family + ChatColor.RESET
                        + " movement brain to " + ChatColor.YELLOW + result.manifestPath()
                        + ChatColor.RESET + ".");
    }

    private void saveBrainCommand(CommandSender sender, String botName) {
        MovementNetwork brain = movementBrain;
        MovementBrainPersistence.TrainingMetadata metadata = movementBrainMetadata;
        MovementBrainBank bankToSave = movementBank;

        if (botName != null && !botName.isBlank()) {
            Terminator bot = manager.getFirst(botName, sender instanceof Player player ? player.getLocation() : null);
            if (bot == null || !bot.hasNeuralNetwork() || !bot.getNeuralNetwork().usesMovementController()) {
                sender.sendMessage(ChatColor.RED + "That bot does not have a movement-controller brain.");
                return;
            }
            brain = bot.getNeuralNetwork().movementNetwork();
            bankToSave = bot.getNeuralNetwork().movementBrainBank();
            metadata = MovementBrainPersistence.TrainingMetadata.manual();
        }

        if (!MovementNetworkGenetics.isValid(brain)) {
            sender.sendMessage(ChatColor.RED + "No valid movement-controller brain is loaded.");
            return;
        }

        MovementTrainingConfig config = movementConfig();
        MovementBrainPersistence.BankSaveResult result = bankToSave != null && botName != null && !botName.isBlank()
                ? MovementBrainPersistence.saveBank(plugin, config, bankToSave)
                : MovementBrainPersistence.saveBank(plugin, config,
                        movementBankWithBrain(config, MovementBrainBank.FALLBACK_BRAIN_NAME, brain, metadata, null, "manual-save"));
        if (!result.saved()) {
            sender.sendMessage(ChatColor.RED + "Failed to save movement brain: " + result.message());
            return;
        }

        movementBrain = MovementNetworkGenetics.copy(brain);
        movementBrainMetadata = metadata == null ? MovementBrainPersistence.TrainingMetadata.manual() : metadata;
        MovementBrainPersistence.BankLoadResult reload = MovementBrainPersistence.loadBank(plugin, config);
        if (reload.loaded()) movementBank = reload.bank();
        sender.sendMessage("Saved movement brain bank to " + ChatColor.YELLOW + result.manifestPath() + ChatColor.RESET + ".");
    }

    private MovementBrainBank movementBankWithBrain(
            MovementTrainingConfig config,
            String familyId,
            MovementNetwork brain,
            MovementBrainPersistence.TrainingMetadata metadata,
            MovementBrainBank.RolloutStats rolloutStats,
            String source
    ) {
        MovementBrainBank base = movementBank != null
                ? movementBank
                : MovementBrainBank.empty(config.fallbackBrainName(), plugin.getDescription().getVersion());
        String family = MovementTrainingConfig.normalizeFamilyId(familyId);
        String brainName = brainNameForFamily(family);
        MovementBrainBank.Brain familyBrain = new MovementBrainBank.Brain(
                brainName,
                family,
                brain,
                metadata == null ? MovementBrainPersistence.TrainingMetadata.manual() : metadata,
                MovementBrainBank.NormalizationStats.none(),
                rolloutStats == null ? MovementBrainBank.RolloutStats.empty() : rolloutStats,
                source,
                plugin.getDescription().getVersion()
        );
        return base.withBrain(familyBrain);
    }

    private void loadMovementBrain(CommandSender sender, boolean reportMissing) {
        MovementTrainingConfig config = movementConfig();
        MovementBrainPersistence.BankLoadResult result = MovementBrainPersistence.loadBank(plugin, config);
        if (result.loaded()) {
            movementBank = result.bank();
            movementBrain = movementBank == null ? null : movementBank.fallbackNetwork();
            movementBrainMetadata = movementBank == null
                    ? MovementBrainPersistence.TrainingMetadata.manual()
                    : movementBank.fallbackMetadata();
            if (sender != null) {
                String fallbackShape = movementBrain == null ? "none" : Arrays.toString(movementBrain.layerSizes());
                sender.sendMessage("Loaded movement brain bank from " + ChatColor.YELLOW + result.manifestPath()
                        + ChatColor.RESET + " brains=" + ChatColor.YELLOW
                        + (movementBank == null ? 0 : movementBank.availableBrainNames().size())
                        + ChatColor.RESET + " fallback-shape=" + ChatColor.YELLOW
                        + fallbackShape + ChatColor.RESET + ".");
                result.warnings().forEach(warning -> sender.sendMessage(ChatColor.YELLOW + warning));
            }
            return;
        }

        movementBank = null;
        movementBrain = null;
        movementBrainMetadata = null;
        String message = "Movement brain bank not loaded from " + result.manifestPath() + ": " + result.message();
        if (sender != null) {
            if (result.missing() && !reportMissing) return;
            sender.sendMessage((result.missing() ? ChatColor.YELLOW : ChatColor.RED) + message);
            if (result.backupPath() != null) {
                sender.sendMessage("Backup: " + ChatColor.YELLOW + result.backupPath());
            }
            result.warnings().forEach(warning -> sender.sendMessage(ChatColor.YELLOW + warning));
        } else if (!result.missing() || config.enabled()) {
            plugin.getLogger().warning(message);
            result.warnings().forEach(plugin.getLogger()::warning);
        }
    }

    private void resetMovementBrain(CommandSender sender) {
        MovementBrainPersistence.BankResetResult result = MovementBrainPersistence.resetBank(plugin, movementConfig(), brainRandom);
        if (!result.reset()) {
            sender.sendMessage(ChatColor.RED + "Failed to reset movement brain: " + result.message());
            return;
        }

        movementBank = result.bank();
        movementBrain = movementBank.fallbackNetwork();
        movementBrainMetadata = movementBank.fallbackMetadata();
        sender.sendMessage("Reset movement brain bank at " + ChatColor.YELLOW + result.manifestPath() + ChatColor.RESET + ".");
        if (result.backupPath() != null) {
            sender.sendMessage("Previous brain backed up to " + ChatColor.YELLOW + result.backupPath() + ChatColor.RESET + ".");
        }
    }

    private MovementBrainBank ensureMovementBank(CommandSender sender) {
        if (movementBank != null && movementBank.hasValidFallback()) {
            return movementBank;
        }
        loadMovementBrain(sender, false);
        if (movementBank != null && movementBank.hasValidFallback()) {
            return movementBank;
        }
        sender.sendMessage(ChatColor.YELLOW + "No valid movement brain fallback is available; creating a fresh bank.");
        resetMovementBrain(sender);
        return movementBank != null && movementBank.hasValidFallback() ? movementBank : null;
    }

    private void sendBrainStatus(CommandSender sender) {
        MovementTrainingConfig config = movementConfig();
        Path path = config.manifestPath(plugin);
        sender.sendMessage(ChatUtils.LINE);
        sender.sendMessage(ChatColor.DARK_GREEN + "Movement Brain Bank" + ChatUtils.BULLET_FORMATTED
                + ChatColor.GRAY + "[" + ChatColor.YELLOW + path + ChatColor.GRAY + "]");
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "config enabled: " + ChatColor.YELLOW + config.enabled());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "mode: " + ChatColor.YELLOW + config.mode());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "brains dir: " + ChatColor.YELLOW
                + config.brainsDirectoryPath(plugin));
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "fallback brain: " + ChatColor.YELLOW
                + config.fallbackBrainName());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "shape: " + ChatColor.YELLOW
                + Arrays.toString(config.movementLayerShape()));
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "autosave: " + ChatColor.YELLOW
                + config.autosaveBestBrain() + ChatColor.RESET + ", save-only-improved: "
                + ChatColor.YELLOW + config.saveOnlyImprovedBrain());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "quarantine bad files: " + ChatColor.YELLOW
                + config.quarantineBadFiles() + ChatColor.RESET + ", legacy import: "
                + ChatColor.YELLOW + config.legacyImportBehavior());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "loadouts: " + ChatColor.YELLOW + config.loadoutSummary());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "selected loadout mix: " + ChatColor.YELLOW
                + config.effectiveLoadoutMix() + ChatColor.RESET + " -> " + ChatColor.YELLOW
                + config.selectedLoadoutMix());
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "curriculum family: " + ChatColor.YELLOW
                + config.curriculumFamily());
        if (movementBank != null) {
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "loaded bank: " + ChatColor.GREEN + "yes"
                    + ChatColor.RESET + ", brains=" + ChatColor.YELLOW + movementBank.availableBrainNames().size()
                    + ChatColor.RESET + ", routes=" + ChatColor.YELLOW + movementBank.routes().size());
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "manifest schema: " + ChatColor.YELLOW
                    + movementBank.manifestSchemaVersion() + ChatColor.RESET + ", route table: "
                    + ChatColor.YELLOW + movementBank.routingTableVersion());
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "observation schema: " + ChatColor.YELLOW
                    + shortenHash(movementBank.observationSchemaHash()));
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "action schema: " + ChatColor.YELLOW
                    + shortenHash(movementBank.actionSchemaHash()));
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "route fallback: " + ChatColor.YELLOW
                    + "lock -> intent branchFamily -> desired range/role -> "
                    + MovementBrainBank.FALLBACK_BRAIN_NAME);
            List<String> missing = movementBank.missingRouteFamilies();
            if (!missing.isEmpty()) {
                sender.sendMessage(ChatUtils.BULLET_FORMATTED + "missing optional experts: "
                        + ChatColor.YELLOW + String.join(", ", missing));
            }
        } else {
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "loaded bank: " + ChatColor.RED + "no");
        }
        if (MovementNetworkGenetics.isValid(movementBrain)) {
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "active fallback brain: " + ChatColor.GREEN + "yes"
                    + ChatColor.RESET + ", shape=" + ChatColor.YELLOW + Arrays.toString(movementBrain.layerSizes())
                    + ChatColor.RESET + ", parameters=" + ChatColor.YELLOW + movementBrain.parameterCount());
            if (movementBrainMetadata != null) {
                sender.sendMessage(ChatUtils.BULLET_FORMATTED + "generation: " + ChatColor.YELLOW
                        + movementBrainMetadata.generation() + ChatColor.RESET + ", best="
                        + ChatColor.YELLOW + MathUtils.round2Dec(movementBrainMetadata.bestFitness())
                        + ChatColor.RESET + ", avg=" + ChatColor.YELLOW
                        + MathUtils.round2Dec(movementBrainMetadata.averageFitness()));
            }
        } else {
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + "active fallback brain: " + ChatColor.RED + "no");
        }
        sender.sendMessage(ChatUtils.BULLET_FORMATTED + "active session: " + ChatColor.YELLOW + (agent != null));
        sender.sendMessage(ChatUtils.LINE);
    }

    private static String shortenHash(String hash) {
        if (hash == null || hash.length() <= 24) return hash;
        return hash.substring(0, 24) + "...";
    }

    private Location parseLocation(CommandSender sender, String loc) {
        Location location = sender instanceof Player player
                ? player.getLocation()
                : new Location(Bukkit.getWorlds().get(0), 0, 0, 0);
        if (loc == null || loc.isBlank()) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Spawning bot at 0, 0, 0 in world " + location.getWorld().getName()
                        + " because no location was specified.");
            }
            return location;
        }

        Player player = Bukkit.getPlayer(loc);
        if (player != null) return player.getLocation();

        String[] split = loc.split(" ");
        if (split.length < 3) {
            sender.sendMessage("The location '" + ChatColor.YELLOW + loc + ChatColor.RESET + "' is not valid!");
            return null;
        }
        try {
            double x = Double.parseDouble(split[0]);
            double y = Double.parseDouble(split[1]);
            double z = Double.parseDouble(split[2]);
            World world = Bukkit.getWorld(split.length >= 4 ? split[3] : location.getWorld().getName());
            if (world == null) {
                sender.sendMessage(ChatColor.RED + "World not found.");
                return null;
            }
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            sender.sendMessage("The location '" + ChatColor.YELLOW + loc + ChatColor.RESET + "' is not valid!");
            return null;
        }
    }

    private static String brainNameForFamily(String familyId) {
        String family = MovementTrainingConfig.normalizeFamilyId(familyId);
        return MovementBrainBank.FALLBACK_BRAIN_NAME.equals(family)
                ? MovementBrainBank.FALLBACK_BRAIN_NAME
                : family;
    }

    private static int roundMinutesToTicks(double minutes) {
        if (!Double.isFinite(minutes) || minutes <= 0.0) return 0;
        return (int) Math.max(1, Math.round(minutes * 1200.0));
    }

    private static String formatRoundLimit(int maxRoundTicks) {
        if (maxRoundTicks <= 0) return "unlimited";
        return MathUtils.round2Dec(maxRoundTicks / 1200.0) + " minute(s)";
    }

    private static MovementTrainingRequest parseTrainingRequest(String modeText) {
        if (modeText == null || modeText.isBlank()) {
            return new MovementTrainingRequest(IntelligenceAgent.TrainingMode.MOVEMENT_CONTROLLER, null, null);
        }
        String[] parts = modeText.split(":");
        String first = parts[0].trim();
        IntelligenceAgent.TrainingMode mode = isLegacyMode(first)
                ? IntelligenceAgent.TrainingMode.LEGACY
                : IntelligenceAgent.TrainingMode.MOVEMENT_CONTROLLER;
        int start = isLegacyMode(first) || isMovementMode(first) ? 1 : 0;
        String mix = null;
        String family = null;
        for (int i = start; i < parts.length; i++) {
            String token = parts[i].trim();
            if (token.isBlank()) continue;
            int eq = token.indexOf('=');
            String key = eq >= 0 ? token.substring(0, eq).trim().toLowerCase(Locale.ROOT) : "";
            String value = eq >= 0 ? token.substring(eq + 1).trim() : token;
            if (key.equals("mix") || key.equals("loadout") || key.equals("loadout-mix")) {
                mix = value;
            } else if (key.equals("family") || key.equals("curriculum") || key.equals("curriculum-family")) {
                family = value;
            } else if (looksLikeFamily(value)) {
                family = value;
            } else {
                mix = value;
            }
        }
        return new MovementTrainingRequest(mode, mix, family);
    }

    private static boolean isMovementMode(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("movement")
                || normalized.equals("movement_controller")
                || normalized.equals("movement-controller");
    }

    private static boolean isLegacyMode(String value) {
        return value != null && value.trim().equalsIgnoreCase("legacy");
    }

    private static boolean looksLikeFamily(String value) {
        String normalized = MovementTrainingConfig.normalizeFamilyId(value);
        return MovementBrainBank.FALLBACK_BRAIN_NAME.equals(normalized)
                || net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementNetworkShape.BRANCH_FAMILY_IDS.contains(normalized);
    }

    private void sendGroupHelp(CommandSender sender, String command, String... entries) {
        sender.sendMessage(ChatUtils.LINE);
        sender.sendMessage(ChatColor.GOLD + "Command help" + ChatColor.GRAY + " [" + ChatColor.YELLOW + command + ChatColor.GRAY + "]");
        for (String entry : entries) {
            sender.sendMessage(ChatUtils.BULLET_FORMATTED + ChatColor.YELLOW + command + " " + entry);
        }
        sender.sendMessage(ChatUtils.LINE);
    }

    private record MovementTrainingRequest(
            IntelligenceAgent.TrainingMode mode,
            String loadoutMixOverride,
            String curriculumFamilyOverride
    ) {
    }
}
