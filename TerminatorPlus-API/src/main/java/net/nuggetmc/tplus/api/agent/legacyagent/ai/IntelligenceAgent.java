package net.nuggetmc.tplus.api.agent.legacyagent.ai;

import net.nuggetmc.tplus.api.AIManager;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.CombatTrainingSnapshot;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementBrainBank;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementLoadoutSampler;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementBrainPersistence;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementNetwork;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementNetworkGenetics;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementRewardProfile;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementTrainingConfig;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.MovementTrainingSnapshot;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.RouteLeagueSampleLabel;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.movement.RouteLeagueTelemetry;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.MathUtils;
import net.nuggetmc.tplus.api.utils.MojangAPI;
import net.nuggetmc.tplus.api.utils.PlayerUtils;
import net.nuggetmc.tplus.compat.bukkit.Bukkit;
import net.nuggetmc.tplus.compat.bukkit.ChatColor;
import net.nuggetmc.tplus.compat.bukkit.Location;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import net.nuggetmc.tplus.compat.bukkit.entity.LivingEntity;
import net.nuggetmc.tplus.compat.bukkit.entity.Player;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;
import net.nuggetmc.tplus.compat.bukkit.scheduler.BukkitScheduler;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class IntelligenceAgent {

    private final Plugin plugin;
    private final BotManager manager;
    private final AIManager aiManager;
    private final BukkitScheduler scheduler;

    private LegacyAgent agent;
    private Thread thread;
    private boolean active;

    private final String name;

    private final String botName;
    private final String botSkin;
    private final int cutoff;

    private final Map<String, Terminator> bots;

    private int populationSize;
    private int generation;

    private Player primary;

    private final Set<CommandSender> users;
    private final Map<Integer, Set<Map<BotNode, Map<BotDataType, Double>>>> genProfiles;
    private final TrainingMode trainingMode;
    private MovementBrainBank movementBank;
    private final MovementTrainingConfig movementConfig;
    private final MovementBrainSaver movementBrainSaver;
    private final int maxRoundTicks;
    private final Random movementRandom;

    public IntelligenceAgent(AIManager aiManager, int populationSize, String name, String skin, Plugin plugin, BotManager manager) {
        this(aiManager, populationSize, name, skin, plugin, manager,
                TrainingMode.LEGACY, null, MovementTrainingConfig.load(plugin), null, 0);
    }

    public IntelligenceAgent(
            AIManager aiManager,
            int populationSize,
            String name,
            String skin,
            Plugin plugin,
            BotManager manager,
            TrainingMode trainingMode,
            MovementBrainBank movementSeedBank,
            MovementTrainingConfig movementConfig,
            MovementBrainSaver movementBrainSaver,
            int maxRoundTicks
    ) {
        this.plugin = plugin;
        this.manager = manager;
        this.aiManager = aiManager;
        this.scheduler = Bukkit.getScheduler();
        this.name = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Calendar.getInstance().getTime());
        this.botName = name;
        this.botSkin = skin;
        this.bots = new ConcurrentHashMap<>();
        this.users = new HashSet<>(Set.of(Bukkit.getConsoleSender()));
        this.cutoff = 5;
        this.genProfiles = new HashMap<>();
        this.populationSize = populationSize;
        this.active = true;
        this.trainingMode = trainingMode == null ? TrainingMode.LEGACY : trainingMode;
        this.movementBank = movementSeedBank;
        this.movementConfig = movementConfig == null ? MovementTrainingConfig.load(plugin) : movementConfig;
        this.movementBrainSaver = movementBrainSaver;
        this.maxRoundTicks = Math.max(0, maxRoundTicks);
        this.movementRandom = new Random();

        scheduler.runTaskAsynchronously(plugin, () -> {
            thread = Thread.currentThread();

            try {
                task();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                print(e);
                print("The thread has been interrupted.");
                print("The session will now close.");
            } finally {
                cleanupAfterTask();
            }
        });
    }

    public enum TrainingMode {
        LEGACY,
        MOVEMENT_CONTROLLER;

        public static TrainingMode from(String value) {
            if (value == null || value.isBlank()) return LEGACY;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("movement") || normalized.equals("movement_controller")
                    || normalized.equals("movement-controller")) {
                return MOVEMENT_CONTROLLER;
            }
            return LEGACY;
        }
    }

    @FunctionalInterface
    public interface MovementBrainSaver {
        MovementBrainPersistence.SaveFeedback save(
                String familyId,
                MovementNetwork network,
                MovementBrainPersistence.TrainingMetadata metadata,
                MovementBrainBank.RolloutStats rolloutStats
        );
    }

    private void task() throws InterruptedException {
        setup();
        sleep(1000);

        while (active) {
            if (trainingMode == TrainingMode.MOVEMENT_CONTROLLER) {
                runMovementGeneration();
            } else {
                runGeneration();
            }
        }

        sleep(5000);
        close();
    }

    private void runMovementGeneration() throws InterruptedException {
        generation++;

        String trainingFamily = MovementTrainingConfig.normalizeFamilyId(movementConfig.curriculumFamily());
        boolean curriculumMode = !MovementBrainBank.FALLBACK_BRAIN_NAME.equals(trainingFamily);
        MovementLoadoutSampler sampler = MovementLoadoutSampler.fromConfig(movementConfig, movementRandom);

        print("Starting movement-controller generation " + ChatColor.RED + generation + ChatColor.RESET + "...");
        print("Mode=" + ChatColor.YELLOW + (curriculumMode ? "curriculum" : "mixed")
                + ChatColor.RESET + ", loadoutMix=" + ChatColor.YELLOW + sampler.mixName()
                + ChatColor.RESET + ", curriculumFamily=" + ChatColor.YELLOW + trainingFamily
                + ChatColor.RESET + ", saving=" + ChatColor.YELLOW
                + (curriculumMode ? trainingFamily : "per-loadout-family") + ChatColor.RESET + ".");

        MovementBrainBank baseBank = ensureMovementBank();

        sleep(1000);

        String skinName = botSkin == null ? this.botName : botSkin;
        print("Fetching skin data for " + ChatColor.GREEN + skinName + ChatColor.RESET + "...");
        String[] skinData = MojangAPI.getSkin(skinName);

        Location loc = callSync(() -> {
            Location anchor = primary == null ? Bukkit.getWorlds().get(0).getSpawnLocation() : primary.getLocation();
            return PlayerUtils.findAbove(anchor, 20);
        });

        String botName = this.botName.endsWith("%") ? this.botName : this.botName + "%";
        print("Creating " + (populationSize == 1 ? "new movement bot" : ChatColor.RED
                + NumberFormat.getInstance(Locale.US).format(populationSize) + ChatColor.RESET + " movement bots")
                + " with name " + ChatColor.GREEN + botName.replace("%", ChatColor.LIGHT_PURPLE + "%" + ChatColor.RESET)
                + ChatColor.RESET + "...");

        Map<String, Integer> loadoutCounts = new LinkedHashMap<>();
        Map<String, Integer> loadoutFamilyCounts = new LinkedHashMap<>();
        List<MovementCandidate> candidates = buildMovementCandidates(baseBank, sampler, trainingFamily,
                curriculumMode, loadoutCounts, loadoutFamilyCounts);
        callSyncVoid(() -> spawnMovementCandidates(candidates, loc, skinData));

        print("Assigned loadouts from " + ChatColor.YELLOW + sampler.mixName() + ChatColor.RESET + ": "
                + ChatColor.YELLOW + MovementLoadoutSampler.describeCounts(loadoutCounts));
        print("Assigned loadout families: " + ChatColor.YELLOW
                + MovementLoadoutSampler.describeCounts(loadoutFamilyCounts));
        print(ChatColor.GRAY + "Sampler weights: " + sampler.describeWeights());

        sleep(2000);
        print("The movement bots will now attack each other.");
        callSyncVoid(() -> agent.setTargetType(EnumTargetGoal.NEAREST_BOT));

        MovementGenerationTelemetry telemetry = new MovementGenerationTelemetry(trainingFamily, curriculumMode, candidates);
        int elapsedTicks = 0;
        while (active && aliveCountSync() > 1 && (maxRoundTicks <= 0 || elapsedTicks < maxRoundTicks)) {
            sleep(1000);
            elapsedTicks += 20;
            captureMovementSamples(telemetry);
        }
        captureMovementSamples(telemetry);

        if (maxRoundTicks > 0 && elapsedTicks >= maxRoundTicks && aliveCountSync() > 1) {
            print("Movement round hit the configured time limit (" + ChatColor.YELLOW
                    + (maxRoundTicks / 1200.0) + ChatColor.RESET + " minutes).");
        }
        printRouteLeagueSummaries(telemetry);

        List<MovementCandidateScore> ranking = telemetry.rank(candidates);
        Map<String, List<MovementCandidateScore>> familyRankings = telemetry.rankByTrainingFamily(candidates);
        if (ranking.isEmpty() || familyRankings.isEmpty()) {
            print(ChatColor.RED + "No usable movement samples were captured for the configured training family; "
                    + "keeping the current brain bank.");
            clearBots();
            callSyncVoid(() -> agent.setTargetType(EnumTargetGoal.NONE));
            return;
        }

        MovementCandidateScore best = ranking.get(0);
        double average = ranking.stream().mapToDouble(MovementCandidateScore::fitness).average().orElse(0.0);
        print("Generation " + ChatColor.RED + generation + ChatColor.RESET
                + " movement rewards: " + ChatColor.YELLOW + telemetry.describeFamilyAverages());
        for (int i = 0; i < Math.min(cutoff, ranking.size()); i++) {
            MovementCandidateScore score = ranking.get(i);
            print(ChatColor.GRAY + "[" + ChatColor.YELLOW + "#" + (i + 1) + ChatColor.GRAY + "] "
                    + ChatColor.GREEN + score.candidate().botName()
                    + ChatUtils.BULLET_FORMATTED + "fitness=" + ChatColor.RED
                    + MathUtils.round2Dec(score.fitness())
                    + ChatUtils.BULLET_FORMATTED + "loadout=" + ChatColor.YELLOW
                    + score.stats().loadoutSummary()
                    + ChatUtils.BULLET_FORMATTED + "trainingFamily=" + ChatColor.YELLOW
                    + score.candidate().trainingFamily());
        }

        if (curriculumMode) {
            saveMovementFamilyResult(trainingFamily, best, average, telemetry);
        } else {
            for (Map.Entry<String, List<MovementCandidateScore>> entry : familyRankings.entrySet()) {
                String family = entry.getKey();
                List<MovementCandidateScore> scores = entry.getValue();
                if (scores.isEmpty()) continue;
                MovementCandidateScore familyBest = scores.get(0);
                double familyAverage = scores.stream().mapToDouble(MovementCandidateScore::fitness).average().orElse(0.0);
                print("Family " + ChatColor.YELLOW + family + ChatColor.RESET
                        + " winner " + ChatColor.GREEN + familyBest.candidate().botName()
                        + ChatUtils.BULLET_FORMATTED + "fitness=" + ChatColor.RED
                        + MathUtils.round2Dec(familyBest.fitness())
                        + ChatUtils.BULLET_FORMATTED + "loadout=" + ChatColor.YELLOW
                        + familyBest.stats().loadoutSummary());
                saveMovementFamilyResult(family, familyBest, familyAverage, telemetry);
            }
            for (String family : loadoutFamilyCounts.keySet()) {
                if (!familyRankings.containsKey(MovementTrainingConfig.normalizeFamilyId(family))) {
                    print(ChatColor.GRAY + "Skipped " + family
                            + " autosave; no matching route samples were captured this generation.");
                }
            }
        }

        sleep(2000);
        clearBots();
        callSyncVoid(() -> agent.setTargetType(EnumTargetGoal.NONE));
    }

    private void runGeneration() throws InterruptedException {
        generation++;

        print("Starting generation " + ChatColor.RED + generation + ChatColor.RESET + "...");

        sleep(2000);

        String skinName = botSkin == null ? this.botName : botSkin;

        print("Fetching skin data for " + ChatColor.GREEN + skinName + ChatColor.RESET + "...");

        String[] skinData = MojangAPI.getSkin(skinName);

        String botName = this.botName.endsWith("%") ? this.botName : this.botName + "%";

        print("Creating " + (populationSize == 1 ? "new bot" : ChatColor.RED + NumberFormat.getInstance(Locale.US).format(populationSize) + ChatColor.RESET + " new bots")
                + " with name " + ChatColor.GREEN + botName.replace("%", ChatColor.LIGHT_PURPLE + "%" + ChatColor.RESET)
                + (botSkin == null ? "" : ChatColor.RESET + " and skin " + ChatColor.GREEN + botSkin)
                + ChatColor.RESET + "...");

        Set<Map<BotNode, Map<BotDataType, Double>>> loadedProfiles = genProfiles.remove(generation);
        Location loc = PlayerUtils.findAbove(primary.getLocation(), 20);

        scheduler.runTask(plugin, () -> {
            Set<Terminator> bots;

            if (loadedProfiles == null) {
                bots = manager.createBots(loc, botName, skinData, populationSize, NeuralNetwork.RANDOM);
            } else {
                List<NeuralNetwork> networks = new ArrayList<>();
                loadedProfiles.forEach(profile -> networks.add(NeuralNetwork.createNetworkFromProfile(profile)));

                if (populationSize != networks.size()) {
                    print("An exception has occured.");
                    print("The stored population size differs from the size of the stored networks.");
                    close();
                    return;
                }

                bots = manager.createBots(loc, botName, skinData, networks);
            }

            bots.forEach(bot -> {
                bot.setAutoRespawnAllowed(false);
                String name = bot.getBotName();

                while (this.bots.containsKey(name)) {
                    name += "_";
                }

                this.bots.put(name, bot);
            });
        });

        while (bots.size() != populationSize) {
            sleep(1000);
        }

        sleep(2000);
        print("The bots will now attack each other.");

        agent.setTargetType(EnumTargetGoal.NEAREST_BOT);

        while (aliveCount() > 1) {
            sleep(1000);
        }

        print("Generation " + ChatColor.RED + generation + ChatColor.RESET + " has ended.");

        HashMap<Terminator, Integer> values = new HashMap<>();

        for (Terminator bot : bots.values()) {
            values.put(bot, bot.getAliveTicks());
        }

        List<Map.Entry<Terminator, Integer>> sorted = MathUtils.sortByValue(values);
        Set<Terminator> winners = new HashSet<>();

        int i = 1;

        for (Map.Entry<Terminator, Integer> entry : sorted) {
            Terminator bot = entry.getKey();
            boolean check = i <= cutoff;
            if (check) {
                print(ChatColor.GRAY + "[" + ChatColor.YELLOW + "#" + i + ChatColor.GRAY + "] " + ChatColor.GREEN + bot.getBotName()
                        + ChatUtils.BULLET_FORMATTED + ChatColor.RED + bot.getKills() + " kills");
                winners.add(bot);
            }

            i++;
        }

        sleep(3000);

        Map<BotNode, Map<BotDataType, List<Double>>> lists = new HashMap<>();

        winners.forEach(bot -> {
            Map<BotNode, Map<BotDataType, Double>> data = bot.getNeuralNetwork().values();

            data.forEach((nodeType, node) -> {
                if (!lists.containsKey(nodeType)) {
                    lists.put(nodeType, new HashMap<>());
                }

                Map<BotDataType, List<Double>> nodeValues = lists.get(nodeType);

                node.forEach((dataType, value) -> {
                    if (!nodeValues.containsKey(dataType)) {
                        nodeValues.put(dataType, new ArrayList<>());
                    }

                    nodeValues.get(dataType).add(value);
                });
            });
        });

        Set<Map<BotNode, Map<BotDataType, Double>>> profiles = new HashSet<>();

        double mutationSize = Math.pow(Math.E, 2); //MathUtils.getMutationSize(generation);

        for (int j = 0; j < populationSize; j++) {
            Map<BotNode, Map<BotDataType, Double>> profile = new HashMap<>();

            lists.forEach((nodeType, map) -> {
                Map<BotDataType, Double> points = new HashMap<>();

                map.forEach((dataType, dataPoints) -> {
                    double value = ((int) (10 * MathUtils.generateConnectionValue(dataPoints, mutationSize))) / 10D;

                    points.put(dataType, value);
                });

                profile.put(nodeType, points);
            });

            profiles.add(profile);
        }

        genProfiles.put(generation + 1, profiles);

        sleep(2000);

        clearBots();

        agent.setTargetType(EnumTargetGoal.NONE);
    }

    private MovementBrainBank ensureMovementBank() {
        if (movementBank != null && movementBank.hasValidFallback()) {
            return movementBank;
        }
        MovementNetwork fallback = MovementNetworkGenetics.random(movementConfig.movementLayerShape(), movementRandom);
        movementBank = MovementBrainBank.singleFallback(fallback,
                MovementBrainPersistence.TrainingMetadata.manual(),
                plugin.getDescription().getVersion(),
                "training-session-fallback");
        return movementBank;
    }

    private MovementNetwork seedNetwork(MovementBrainBank bank, String familyId) {
        MovementBrainBank.Selection selection = bank.select(familyId);
        MovementNetwork selected = selection.network();
        if (MovementNetworkGenetics.isValid(selected)) {
            return selected;
        }
        MovementNetwork fallback = bank.fallbackNetwork();
        if (MovementNetworkGenetics.isValid(fallback)) {
            return fallback;
        }
        return MovementNetworkGenetics.random(movementConfig.movementLayerShape(), movementRandom);
    }

    private List<MovementCandidate> buildMovementCandidates(
            MovementBrainBank baseBank,
            MovementLoadoutSampler sampler,
            String curriculumFamily,
            boolean curriculumMode,
            Map<String, Integer> loadoutCounts,
            Map<String, Integer> loadoutFamilyCounts
    ) {
        List<MovementCandidate> candidates = new ArrayList<>();
        String botName = this.botName.endsWith("%") ? this.botName : this.botName + "%";
        for (int i = 0; i < populationSize; i++) {
            MovementLoadoutSampler.LoadoutSelection selection = sampler.sample();
            String loadoutFamily = MovementTrainingConfig.normalizeFamilyId(selection.family());
            String trainingFamily = curriculumMode
                    ? MovementTrainingConfig.normalizeFamilyId(curriculumFamily)
                    : loadoutFamily;
            MovementNetwork seed = seedNetwork(baseBank, trainingFamily);
            MovementNetwork network;
            if (i == 0) {
                network = MovementNetworkGenetics.copy(seed);
            } else if (i % 5 == 0) {
                network = MovementNetworkGenetics.random(movementConfig.movementLayerShape(), movementRandom);
            } else {
                double scale = 0.05 + Math.min(0.25, generation * 0.01);
                network = MovementNetworkGenetics.mutate(seed, scale, movementRandom);
            }
            String name = botName.replace("%", String.valueOf(i + 1));
            MovementBrainBank candidateBank = bankWithCandidate(baseBank, trainingFamily, network,
                    MovementBrainPersistence.TrainingMetadata.manual(),
                    MovementBrainBank.RolloutStats.empty(), "training-candidate");
            candidates.add(new MovementCandidate(name, network, candidateBank, trainingFamily,
                    selection.name(), loadoutFamily));
            loadoutCounts.merge(selection.name(), 1, Integer::sum);
            loadoutFamilyCounts.merge(loadoutFamily, 1, Integer::sum);
        }
        return candidates;
    }

    private MovementBrainBank bankWithCandidate(
            MovementBrainBank baseBank,
            String familyId,
            MovementNetwork network,
            MovementBrainPersistence.TrainingMetadata metadata,
            MovementBrainBank.RolloutStats rolloutStats,
            String source
    ) {
        MovementBrainBank base = baseBank != null && baseBank.hasValidFallback()
                ? baseBank
                : MovementBrainBank.singleFallback(
                MovementNetworkGenetics.random(movementConfig.movementLayerShape(), movementRandom),
                MovementBrainPersistence.TrainingMetadata.manual(),
                plugin.getDescription().getVersion(),
                "training-generated-fallback");
        String family = MovementTrainingConfig.normalizeFamilyId(familyId);
        String brainName = MovementBrainBank.FALLBACK_BRAIN_NAME.equals(family)
                ? MovementBrainBank.FALLBACK_BRAIN_NAME
                : family;
        MovementBrainBank.Brain brain = new MovementBrainBank.Brain(
                brainName,
                family,
                network,
                metadata == null ? MovementBrainPersistence.TrainingMetadata.manual() : metadata,
                MovementBrainBank.NormalizationStats.none(),
                rolloutStats == null ? MovementBrainBank.RolloutStats.empty() : rolloutStats,
                source,
                plugin.getDescription().getVersion()
        );
        return base.withBrain(brain);
    }

    private void spawnMovementCandidates(
            List<MovementCandidate> candidates,
            Location loc,
            String[] skinData
    ) {
        for (MovementCandidate candidate : candidates) {
            Set<Terminator> created = manager.createBots(loc, candidate.botName(), skinData, 1,
                    NeuralNetwork.createMovementControllerNetwork(candidate.bank()));
            Terminator bot = created.stream().findFirst().orElse(null);
            if (bot == null) continue;
            bot.setAutoRespawnAllowed(false);

            if (!bot.applyTrainingLoadout(candidate.loadout())) {
                print(ChatColor.YELLOW + "Skipping " + bot.getBotName() + "; training loadout "
                        + ChatColor.RED + candidate.loadout() + ChatColor.YELLOW + " is not registered.");
                bot.removeBot();
                continue;
            }

            String uniqueName = bot.getBotName();
            while (this.bots.containsKey(uniqueName)) {
                uniqueName += "_";
            }
            this.bots.put(uniqueName, bot);
        }
    }

    private void saveMovementFamilyResult(
            String familyId,
            MovementCandidateScore best,
            double average,
            MovementGenerationTelemetry telemetry
    ) {
        String family = MovementTrainingConfig.normalizeFamilyId(familyId);
        MovementBrainPersistence.TrainingMetadata metadata =
                new MovementBrainPersistence.TrainingMetadata(generation, best.fitness(), average);
        MovementBrainBank.RolloutStats rolloutStats = new MovementBrainBank.RolloutStats(
                telemetry.rolloutMetrics(best, family));

        if (movementBrainSaver != null) {
            MovementBrainPersistence.SaveFeedback feedback = movementBrainSaver.save(family,
                    best.candidate().network(), metadata, rolloutStats);
            if (feedback.message() != null && !feedback.message().isBlank()) {
                print(feedback.message());
            } else if (!feedback.saved()) {
                print(ChatColor.GRAY + "Autosave disabled; trained " + family
                        + " remains in-session until manually saved.");
            }
            return;
        }

        MovementBrainBank base = movementBank == null ? ensureMovementBank() : movementBank;
        movementBank = bankWithCandidate(base, family, best.candidate().network(), metadata,
                rolloutStats, "training-generation");
    }

    private void captureMovementSamples(MovementGenerationTelemetry telemetry) throws InterruptedException {
        callSyncVoid(() -> {
            List<Terminator> alive = bots.values().stream().filter(Terminator::isBotAlive).toList();
            for (Terminator bot : alive) {
                Terminator target = nearestOther(bot, alive);
                if (target == null) continue;
                LivingEntity targetEntity = target.getBukkitEntity();
                if (targetEntity == null) continue;
                bot.planCombat(targetEntity);
                MovementTrainingSnapshot movement = bot.movementTrainingSnapshot(targetEntity);
                CombatTrainingSnapshot combat = bot.combatTrainingSnapshot();
                telemetry.record(
                        bot.getBotName(),
                        movement,
                        combat,
                        target.combatTrainingSnapshot(),
                        target.usesMovementController(),
                        bot.getAliveTicks(),
                        bot.getKills(),
                        bot.getBotHealth()
                );
            }
        });
    }

    private Terminator nearestOther(Terminator bot, List<Terminator> alive) {
        Terminator best = null;
        double bestDistance = Double.MAX_VALUE;
        Location loc = bot.getLocation();
        for (Terminator other : alive) {
            if (other == bot) continue;
            LivingEntity entity = other.getBukkitEntity();
            if (entity == null || !entity.isValid() || entity.getWorld() != loc.getWorld()) continue;
            double distance = loc.distanceSquared(entity.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = other;
            }
        }
        return best;
    }

    private void printRouteLeagueSummaries(MovementGenerationTelemetry telemetry) {
        for (String line : telemetry.routeLeagueSummaryLines(generation)) {
            users.forEach(user -> user.sendMessage(line));
            plugin.getLogger().info(line);
        }
    }

    private int aliveCountSync() throws InterruptedException {
        return callSync(this::aliveCount);
    }

    private int aliveCount() {
        return (int) bots.values().stream().filter(Terminator::isBotAlive).count();
    }

    private void close() {
        active = false;
    }

    public void stop() {
        if (this.active) {
            this.active = false;
        }

        if (thread != null && !thread.isInterrupted()) {
            this.thread.interrupt();
        }
    }

    private void cleanupAfterTask() {
        active = false;
        boolean interrupted = Thread.interrupted();

        try {
            clearBots();
        } catch (Exception e) {
            print(e);
        } finally {
            interrupted = interrupted || Thread.currentThread().isInterrupted();
            bots.clear();
            genProfiles.clear();
            users.clear();
            primary = null;
            movementBank = null;
            thread = null;
            aiManager.clearSession();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private <T> T callSync(Callable<T> callable) throws InterruptedException {
        if (Bukkit.isPrimaryThread()) {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        Future<T> future = scheduler.callSyncMethod(plugin, callable);
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException(cause);
        }
    }

    private void callSyncVoid(Runnable runnable) throws InterruptedException {
        callSync(() -> {
            runnable.run();
            return null;
        });
    }

    public String getName() {
        return name;
    }

    public void addUser(CommandSender sender) {
        if (users.contains(sender)) return;

        users.add(sender);
        print(sender.getName() + " has been added to the userlist.");

        if (primary == null && sender instanceof Player) {
            setPrimary((Player) sender);
        }
    }

    public void setPrimary(Player player) {
        this.primary = player;
        print(player.getName() + " has been set as the primary user.");
    }

    private void print(Object... objects) {
        String message = ChatColor.DARK_GREEN + "[REINFORCEMENT] " + ChatColor.RESET
                + String.join(" ", Arrays.stream(objects).map(String::valueOf).toList());
        users.forEach(u -> u.sendMessage(message));
    }

    private void setup() {
        clearBots();

        if (populationSize < cutoff) {
            populationSize = cutoff;
            print("The input value for the population size is lower than the cutoff (" + ChatColor.RED + cutoff + ChatColor.RESET + ")!"
                    + " The new population size is " + ChatColor.RED + populationSize + ChatColor.RESET + ".");
        }

        if (!(manager.getAgent() instanceof LegacyAgent)) {
            print("The AI manager currently only supports " + ChatColor.AQUA + "LegacyAgent" + ChatColor.RESET + ".");
            close();
            return;
        }

        agent = (LegacyAgent) manager.getAgent();
        agent.setTargetType(EnumTargetGoal.NONE);

        print("The bot target goal has been set to " + ChatColor.YELLOW + EnumTargetGoal.NONE.name() + ChatColor.RESET + ".");
        print("Disabling target offsets...");

        agent.offsets = false;

        print("Disabling bot drops...");

        agent.setDrops(false);

        print(ChatColor.GREEN + "Setup is now complete.");
    }

    private void clearBots() {
        if (!Bukkit.isPrimaryThread()) {
            try {
                callSyncVoid(this::clearBots);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                active = false;
            }
            return;
        }

        if (!bots.isEmpty()) {
            print("Removing all cached bots...");

            new HashSet<>(bots.values()).forEach(Terminator::removeBot);
            bots.clear();
        }
    }

    private record MovementCandidate(
            String botName,
            MovementNetwork network,
            MovementBrainBank bank,
            String trainingFamily,
            String loadout,
            String loadoutFamily
    ) {
        private MovementCandidate {
            trainingFamily = MovementTrainingConfig.normalizeFamilyId(trainingFamily);
            loadout = loadout == null || loadout.isBlank() ? "sword" : loadout.trim().toLowerCase(Locale.ROOT);
            loadoutFamily = MovementTrainingConfig.normalizeFamilyId(loadoutFamily);
        }
    }

    private record MovementCandidateScore(
            MovementCandidate candidate,
            MovementCandidateStats stats,
            double fitness
    ) {
    }

    private static final class MovementGenerationTelemetry {
        private final String trainingFamily;
        private final boolean curriculumMode;
        private final Map<String, MovementCandidate> candidatesByBot = new LinkedHashMap<>();
        private final Map<String, MovementCandidateStats> statsByBot = new LinkedHashMap<>();
        private final Map<String, Double> familyTotals = new LinkedHashMap<>();
        private final Map<String, Integer> familySamples = new LinkedHashMap<>();
        private final Map<String, Double> componentTotals = new LinkedHashMap<>();
        private final RouteLeagueTelemetry routeLeagueTelemetry = new RouteLeagueTelemetry();

        private MovementGenerationTelemetry(
                String trainingFamily,
                boolean curriculumMode,
                List<MovementCandidate> candidates
        ) {
            this.trainingFamily = MovementTrainingConfig.normalizeFamilyId(trainingFamily);
            this.curriculumMode = curriculumMode;
            if (candidates != null) {
                for (MovementCandidate candidate : candidates) {
                    candidatesByBot.put(candidate.botName(), candidate);
                }
            }
        }

        void record(
                String botName,
                MovementTrainingSnapshot movement,
                CombatTrainingSnapshot combat,
                CombatTrainingSnapshot opponentCombat,
                boolean opponentUsesMovementController,
                int aliveTicks,
                int kills,
                double health
        ) {
            MovementCandidate candidate = candidatesByBot.get(botName);
            // Issue #10 Phase 1: route-league labels are telemetry-only and do
            // not feed reward, selection, persistence, mutation, or combat.
            routeLeagueTelemetry.record(RouteLeagueSampleLabel.fromSnapshots(
                    movement,
                    combat,
                    opponentCombat,
                    candidate == null ? null : candidate.trainingFamily(),
                    opponentUsesMovementController,
                    RouteLeagueTelemetry.OpponentPool.LATEST_GENERATION,
                    null,
                    health
            ));
            MovementCandidateStats stats = statsByBot.computeIfAbsent(botName, ignored -> new MovementCandidateStats());
            MovementRewardProfile.RewardBreakdown reward = stats.record(movement, combat, aliveTicks, kills, health);
            familyTotals.merge(reward.familyId(), reward.total(), Double::sum);
            familySamples.merge(reward.familyId(), 1, Integer::sum);
            reward.components().forEach((component, value) ->
                    componentTotals.merge(reward.familyId() + "." + component, value, Double::sum));
        }

        List<MovementCandidateScore> rank(List<MovementCandidate> candidates) {
            List<MovementCandidateScore> scores = new ArrayList<>();
            for (MovementCandidate candidate : candidates) {
                MovementCandidateStats stats = statsByBot.get(candidate.botName());
                if (stats == null || stats.samples() == 0) continue;
                if (curriculumMode && !stats.hasFamilySamples(trainingFamily)) continue;
                scores.add(new MovementCandidateScore(candidate, stats, stats.fitness(trainingFamily, curriculumMode)));
            }
            scores.sort(Comparator.comparingDouble(MovementCandidateScore::fitness).reversed());
            return scores;
        }

        Map<String, List<MovementCandidateScore>> rankByTrainingFamily(List<MovementCandidate> candidates) {
            Map<String, List<MovementCandidateScore>> grouped = new LinkedHashMap<>();
            for (MovementCandidate candidate : candidates) {
                MovementCandidateStats stats = statsByBot.get(candidate.botName());
                if (stats == null || stats.samples() == 0) continue;
                String family = MovementTrainingConfig.normalizeFamilyId(candidate.trainingFamily());
                if (!stats.hasFamilySamples(family)) continue;
                double fitness = stats.fitness(family, true);
                grouped.computeIfAbsent(family, ignored -> new ArrayList<>())
                        .add(new MovementCandidateScore(candidate, stats, fitness));
            }
            grouped.values().forEach(scores ->
                    scores.sort(Comparator.comparingDouble(MovementCandidateScore::fitness).reversed()));
            return grouped;
        }

        String describeFamilyAverages() {
            if (familyTotals.isEmpty()) return "none";
            StringBuilder out = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, Double> entry : familyTotals.entrySet()) {
                if (!first) out.append(", ");
                first = false;
                int samples = Math.max(1, familySamples.getOrDefault(entry.getKey(), 1));
                out.append(entry.getKey())
                        .append("_avg=")
                        .append(MathUtils.round2Dec(entry.getValue() / samples))
                        .append(" total=")
                        .append(MathUtils.round2Dec(entry.getValue()));
            }
            return out.toString();
        }

        List<String> routeLeagueSummaryLines(int generation) {
            return routeLeagueTelemetry.generationSummaryLines(generation);
        }

        Map<String, Double> rolloutMetrics(MovementCandidateScore best, String savedFamily) {
            Map<String, Double> metrics = new LinkedHashMap<>();
            metrics.put("generationFitnessBest", best.fitness());
            metrics.put("generationSamples", (double) statsByBot.values().stream().mapToInt(MovementCandidateStats::samples).sum());
            metrics.put("curriculumMode", curriculumMode ? 1.0 : 0.0);
            metrics.put("trainingFamily." + trainingFamily, 1.0);
            metrics.put("savedFamily." + MovementTrainingConfig.normalizeFamilyId(savedFamily), 1.0);
            metrics.put("best.trainingFamily." + best.candidate().trainingFamily(), 1.0);
            metrics.put("best.loadoutFamily." + best.candidate().loadoutFamily(), 1.0);
            familyTotals.forEach((family, total) -> {
                int samples = Math.max(1, familySamples.getOrDefault(family, 1));
                metrics.put("family." + family + ".total", total);
                metrics.put("family." + family + ".avg", total / samples);
                metrics.put("family." + family + ".samples", (double) samples);
            });
            componentTotals.forEach((component, total) -> metrics.put("component." + component, total));
            metrics.put("best.samples", (double) best.stats().samples());
            metrics.put("best.damageDealt", best.stats().lastCombat().damageDealt());
            metrics.put("best.damageTaken", best.stats().lastCombat().damageTaken());
            metrics.put("best.kills", (double) best.stats().kills());
            return metrics;
        }
    }

    private static final class MovementCandidateStats {
        private int samples;
        private int aliveTicks;
        private int kills;
        private double health;
        private double aggregateFitness;
        private String loadout = "";
        private String loadoutFamily = MovementBrainBank.FALLBACK_BRAIN_NAME;
        private CombatTrainingSnapshot lastCombat = CombatTrainingSnapshot.unavailable();
        private final Map<String, Double> familyTotals = new LinkedHashMap<>();
        private final Map<String, Integer> familySamples = new LinkedHashMap<>();

        MovementRewardProfile.RewardBreakdown record(
                MovementTrainingSnapshot movement,
                CombatTrainingSnapshot combat,
                int aliveTicks,
                int kills,
                double health
        ) {
            MovementTrainingSnapshot safeMovement = movement == null ? MovementTrainingSnapshot.unavailable() : movement;
            CombatTrainingSnapshot safeCombat = combat == null ? CombatTrainingSnapshot.unavailable() : combat;
            MovementRewardProfile.CombatDeltas deltas =
                    MovementRewardProfile.CombatDeltas.between(lastCombat, safeCombat);
            String family = safeMovement.activeBranchFamily();
            MovementRewardProfile.RewardBreakdown reward =
                    MovementRewardProfile.forFamily(family).score(safeMovement, safeCombat, deltas);

            samples++;
            this.aliveTicks = Math.max(this.aliveTicks, aliveTicks);
            this.kills = Math.max(this.kills, kills);
            this.health = health;
            this.aggregateFitness += reward.total();
            this.loadout = safeCombat.loadout();
            this.loadoutFamily = safeCombat.loadoutFamily();
            this.lastCombat = safeCombat;
            familyTotals.merge(reward.familyId(), reward.total(), Double::sum);
            familySamples.merge(reward.familyId(), 1, Integer::sum);
            return reward;
        }

        double fitness(String trainingFamily, boolean curriculumMode) {
            String family = MovementTrainingConfig.normalizeFamilyId(trainingFamily);
            double familyFitness = familyTotals.getOrDefault(family, 0.0);
            if (curriculumMode) {
                if (!hasFamilySamples(family)) return Double.NEGATIVE_INFINITY;
                return familyFitness + kills * 5.0 + aliveTicks / 160.0 + health * 0.08;
            }
            return aggregateFitness + kills * 5.0 + aliveTicks / 180.0 + health * 0.08;
        }

        boolean hasFamilySamples(String family) {
            return familySamples.getOrDefault(MovementTrainingConfig.normalizeFamilyId(family), 0) > 0;
        }

        int samples() {
            return samples;
        }

        int kills() {
            return kills;
        }

        CombatTrainingSnapshot lastCombat() {
            return lastCombat;
        }

        String loadoutSummary() {
            return (loadout == null || loadout.isBlank() ? "unknown" : loadout)
                    + "/" + loadoutFamily;
        }
    }
}
