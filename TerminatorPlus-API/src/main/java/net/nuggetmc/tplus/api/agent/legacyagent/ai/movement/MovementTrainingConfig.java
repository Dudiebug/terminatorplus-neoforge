package net.nuggetmc.tplus.api.agent.legacyagent.ai.movement;

import net.nuggetmc.tplus.compat.bukkit.configuration.file.FileConfiguration;
import net.nuggetmc.tplus.compat.bukkit.configuration.ConfigurationSection;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;
import java.util.List;

public record MovementTrainingConfig(
        boolean enabled,
        String mode,
        int[] movementLayerShape,
        boolean autosaveBestBrain,
        boolean saveOnlyImprovedBrain,
        String legacyBrainFile,
        String manifestFile,
        String brainsDirectory,
        String fallbackBrainName,
        boolean quarantineBadFiles,
        String legacyImportBehavior,
        boolean debugLogging,
        String loadoutMix,
        String curriculumFamily,
        Map<String, Map<String, Integer>> loadoutMixes
) {
    public MovementTrainingConfig {
        loadoutMix = normalizeToken(loadoutMix, "movement_balanced");
        curriculumFamily = normalizeFamilyId(curriculumFamily);
        loadoutMixes = immutableMixes(loadoutMixes);
        if (!loadoutMixes.containsKey(loadoutMix) && loadoutMixes.containsKey("movement_balanced")) {
            loadoutMix = "movement_balanced";
        }
    }

    public static MovementTrainingConfig load(Plugin plugin) {
        FileConfiguration config = plugin.getConfig();
        boolean enabled = getBoolean(config, true,
                "ai.movement.bank.enabled",
                "ai.movement.enabled",
                "ai.movement-network.enabled");
        String mode = getString(config, "movement_controller",
                "ai.movement.mode",
                "ai.movement-network.mode");
        List<Integer> shapeList = config.getIntegerList("ai.movement.layer-shape");
        if (shapeList.isEmpty()) {
            shapeList = config.getIntegerList("ai.movement-network.layer-shape");
        }
        if (shapeList.isEmpty()) {
            List<Integer> hidden = config.getIntegerList("ai.movement-network.hidden-layers");
            if (!hidden.isEmpty()) {
                shapeList = new java.util.ArrayList<>();
                shapeList.add(MovementNetworkShape.INPUT_COUNT);
                shapeList.addAll(hidden);
                shapeList.add(MovementNetworkShape.OUTPUT_COUNT);
            }
        }
        int[] shape = shapeList.isEmpty()
                ? MovementNetworkShape.DEFAULT_LAYERS
                : shapeList.stream().mapToInt(Integer::intValue).toArray();
        boolean autosave = getBoolean(config, true,
                "ai.movement.bank.autosave-best-brain",
                "ai.movement.autosave-best-brain",
                "ai.movement-network.autosave-best-brain");
        boolean improvedOnly = getBoolean(config, true,
                "ai.movement.bank.save-only-improved-brain",
                "ai.movement.save-only-improved-brain",
                "ai.movement-network.save-only-improved-brain");
        String legacyBrainFile = getString(config, "ai/brain.json",
                "ai.movement.legacy-brain-path",
                "ai.movement-network.brain-path",
                "brain-path",
                "ai.movement.brain-file");
        String manifestFile = getString(config, "ai/movement/manifest.json",
                "ai.movement.bank.manifest-path",
                "ai.movement.manifest-path");
        String brainsDirectory = getString(config, "ai/movement/brains",
                "ai.movement.bank.brains-directory",
                "ai.movement.brains-directory");
        String fallbackBrainName = getString(config, MovementBrainBank.FALLBACK_BRAIN_NAME,
                "ai.movement.bank.fallback-brain-name",
                "ai.movement.fallback-brain-name");
        boolean quarantineBadFiles = getBoolean(config, true,
                "ai.movement.bank.quarantine-bad-files",
                "ai.movement.quarantine-bad-files");
        String legacyImportBehavior = getString(config, "import-compatible-or-reset",
                "ai.movement.bank.legacy-import-behavior",
                "ai.movement.legacy-import-behavior");
        boolean debugLogging = getBoolean(config, false,
                "ai.movement.bank.debug-logging",
                "ai.movement.debug-logging",
                "ai.movement-network.debug");
        String loadoutMix = config.getString("ai.training.loadout-mix", "movement_balanced");
        String curriculumFamily = config.getString("ai.training.curriculum-family", MovementBrainBank.FALLBACK_BRAIN_NAME);
        Map<String, Map<String, Integer>> loadoutMixes = readLoadoutMixes(config);
        return new MovementTrainingConfig(enabled, mode, shape, autosave, improvedOnly,
                legacyBrainFile, manifestFile, brainsDirectory, fallbackBrainName,
                quarantineBadFiles, legacyImportBehavior, debugLogging, loadoutMix,
                curriculumFamily, loadoutMixes);
    }

    @Override
    public int[] movementLayerShape() {
        return Arrays.copyOf(movementLayerShape, movementLayerShape.length);
    }

    public Path brainPath(Plugin plugin) {
        return legacyBrainPath(plugin);
    }

    public Path legacyBrainPath(Plugin plugin) {
        return plugin.getDataFolder().toPath().resolve(legacyBrainFile);
    }

    public Path manifestPath(Plugin plugin) {
        return plugin.getDataFolder().toPath().resolve(manifestFile);
    }

    public Path brainsDirectoryPath(Plugin plugin) {
        return plugin.getDataFolder().toPath().resolve(brainsDirectory);
    }

    public String loadoutSummary() {
        return effectiveLoadoutMix() + " " + selectedLoadoutMix() + ", curriculum-family=" + curriculumFamily;
    }

    public String effectiveLoadoutMix() {
        if (loadoutMixes.containsKey(loadoutMix)) return loadoutMix;
        return loadoutMixes.containsKey("movement_balanced") ? "movement_balanced" : loadoutMix;
    }

    public Map<String, Integer> selectedLoadoutMix() {
        return loadoutMix(effectiveLoadoutMix());
    }

    public Map<String, Integer> loadoutMix(String name) {
        String key = normalizeToken(name, effectiveLoadoutMix());
        Map<String, Integer> mix = loadoutMixes.get(key);
        if (mix != null) return mix;
        return loadoutMixes.getOrDefault("movement_balanced", Map.of());
    }

    public MovementTrainingConfig withOverrides(String loadoutMixOverride, String curriculumFamilyOverride) {
        String mix = loadoutMixOverride == null || loadoutMixOverride.isBlank()
                ? loadoutMix
                : normalizeToken(loadoutMixOverride, loadoutMix);
        String family = curriculumFamilyOverride == null || curriculumFamilyOverride.isBlank()
                ? curriculumFamily
                : normalizeFamilyId(curriculumFamilyOverride);
        return new MovementTrainingConfig(enabled, mode, movementLayerShape, autosaveBestBrain,
                saveOnlyImprovedBrain, legacyBrainFile, manifestFile, brainsDirectory,
                fallbackBrainName, quarantineBadFiles, legacyImportBehavior, debugLogging,
                mix, family, loadoutMixes);
    }

    public static String normalizeFamilyId(String value) {
        String normalized = normalizeToken(value, MovementBrainBank.FALLBACK_BRAIN_NAME);
        return switch (normalized) {
            case "general", "fallback" -> MovementBrainBank.FALLBACK_BRAIN_NAME;
            case "trident", "ranged_trident" -> "trident_ranged";
            case "spear" -> "spear_melee";
            case "explosive", "crystal", "anchor" -> "explosive_survival";
            case "projectile", "ranged", "ranged_utility" -> "projectile_ranged";
            default -> normalized;
        };
    }

    public static Map<String, Map<String, Integer>> defaultLoadoutMixes() {
        Map<String, Map<String, Integer>> mixes = new LinkedHashMap<>();
        mixes.put("movement_balanced", orderedWeights(
                "sword", 12, "axe", 12, "smp", 12, "pot", 8,
                "mace", 10, "spear", 8, "trident", 8, "windcharge", 6,
                "skydiver", 5, "hybrid", 6, "projectile", 6, "vanilla", 5,
                "pvp", 3, "crystalpvp", 3, "anchorbomb", 2));
        mixes.put("melee_curriculum", orderedWeights(
                "sword", 20, "axe", 20, "smp", 25, "pot", 15,
                "spear", 10, "hybrid", 10));
        mixes.put("mace_curriculum", orderedWeights(
                "mace", 45, "hybrid", 20, "windcharge", 15,
                "vanilla", 10, "pvp", 5, "skydiver", 5));
        mixes.put("trident_curriculum", orderedWeights(
                "trident", 40, "spear", 25, "skydiver", 15,
                "hybrid", 10, "vanilla", 10));
        mixes.put("mobility_curriculum", orderedWeights(
                "windcharge", 25, "skydiver", 25, "hybrid", 20,
                "trident", 10, "mace", 10, "pvp", 5, "vanilla", 5));
        mixes.put("explosive_survival_curriculum", orderedWeights(
                "crystalpvp", 20, "anchorbomb", 15, "vanilla", 20,
                "pvp", 15, "hybrid", 10, "windcharge", 10, "sword", 10));
        mixes.put("projectile_ranged_curriculum", orderedWeights(
                "projectile", 45, "trident", 15, "skydiver", 10,
                "hybrid", 10, "pot", 10, "sword", 10));
        return immutableMixes(mixes);
    }

    private static boolean getBoolean(FileConfiguration config, boolean fallback, String... keys) {
        for (String key : keys) {
            if (config.contains(key)) {
                return config.getBoolean(key);
            }
        }
        return fallback;
    }

    private static String getString(FileConfiguration config, String fallback, String... keys) {
        for (String key : keys) {
            if (config.contains(key)) {
                String value = config.getString(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return fallback;
    }

    private static Map<String, Map<String, Integer>> readLoadoutMixes(FileConfiguration config) {
        Map<String, Map<String, Integer>> mixes = new LinkedHashMap<>(defaultLoadoutMixes());
        ConfigurationSection root = config.getConfigurationSection("ai.training.loadout-mixes");
        if (root == null) return mixes;

        for (String mixName : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(mixName);
            if (section == null) continue;
            Map<String, Integer> weights = new LinkedHashMap<>();
            for (String loadout : section.getKeys(false)) {
                int weight = section.getInt(loadout, 0);
                if (weight > 0) {
                    weights.put(normalizeToken(loadout, ""), weight);
                }
            }
            if (!weights.isEmpty()) {
                mixes.put(normalizeToken(mixName, "movement_balanced"), weights);
            }
        }
        return mixes;
    }

    private static Map<String, Integer> orderedWeights(Object... pairs) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            weights.put(String.valueOf(pairs[i]), (Integer) pairs[i + 1]);
        }
        return weights;
    }

    private static Map<String, Map<String, Integer>> immutableMixes(Map<String, Map<String, Integer>> mixes) {
        Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
        if (mixes != null) {
            mixes.forEach((name, weights) -> {
                String mixName = normalizeToken(name, "");
                if (mixName.isBlank() || weights == null || weights.isEmpty()) return;
                Map<String, Integer> mix = new LinkedHashMap<>();
                weights.forEach((loadout, weight) -> {
                    String key = normalizeToken(loadout, "");
                    if (!key.isBlank() && weight != null && weight > 0) {
                        mix.put(key, weight);
                    }
                });
                if (!mix.isEmpty()) copy.put(mixName, Collections.unmodifiableMap(mix));
            });
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String normalizeToken(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
