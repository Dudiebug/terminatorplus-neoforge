package net.nuggetmc.tplus.api.agent.legacyagent.ai.movement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.nuggetmc.tplus.compat.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

public final class MovementBrainPersistence {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int BRAIN_SCHEMA_VERSION = 1;

    private MovementBrainPersistence() {
    }

    public static SaveResult save(
            Plugin plugin,
            MovementTrainingConfig config,
            MovementNetwork network,
            TrainingMetadata metadata
    ) {
        if (!MovementNetworkGenetics.isValid(network)) {
            return new SaveResult(false, "invalid network", config.manifestPath(plugin));
        }

        BankLoadResult current = loadBank(plugin, config);
        MovementBrainBank bank = current.loaded() && current.bank() != null
                ? current.bank()
                : MovementBrainBank.empty(config.fallbackBrainName(), buildVersion(plugin));
        MovementBrainBank.Brain brain = new MovementBrainBank.Brain(
                MovementBrainBank.FALLBACK_BRAIN_NAME,
                MovementBrainBank.FALLBACK_BRAIN_NAME,
                network,
                metadata == null ? TrainingMetadata.manual() : metadata,
                MovementBrainBank.NormalizationStats.none(),
                MovementBrainBank.RolloutStats.empty(),
                "manual",
                buildVersion(plugin)
        );
        BankSaveResult result = saveBank(plugin, config, bank.withBrain(brain));
        return new SaveResult(result.saved(), result.message(), result.manifestPath());
    }

    public static LoadResult load(Plugin plugin, MovementTrainingConfig config) {
        BankLoadResult result = loadBank(plugin, config);
        MovementNetwork fallback = result.bank() == null ? null : result.bank().fallbackNetwork();
        TrainingMetadata metadata = result.bank() == null ? TrainingMetadata.manual() : result.bank().fallbackMetadata();
        return new LoadResult(result.loaded(), result.missing(), result.message(), result.manifestPath(),
                result.backupPath(), fallback, metadata);
    }

    public static ResetResult reset(Plugin plugin, MovementTrainingConfig config, Random random) {
        BankResetResult result = resetBank(plugin, config, random);
        MovementNetwork fallback = result.bank() == null ? null : result.bank().fallbackNetwork();
        TrainingMetadata metadata = result.bank() == null ? TrainingMetadata.manual() : result.bank().fallbackMetadata();
        return new ResetResult(result.reset(), result.message(), result.manifestPath(),
                result.backupPath(), fallback, metadata);
    }

    public static BankSaveResult saveBank(Plugin plugin, MovementTrainingConfig config, MovementBrainBank bank) {
        if (plugin == null || config == null || bank == null) {
            return new BankSaveResult(false, "missing plugin/config/bank", null, List.of(), List.of());
        }
        if (!bank.hasValidFallback()) {
            return new BankSaveResult(false, "bank has no valid fallback brain", config.manifestPath(plugin),
                    List.of(), List.of());
        }

        Path manifestPath = config.manifestPath(plugin);
        Path brainsDir = config.brainsDirectoryPath(plugin);
        List<Path> written = new ArrayList<>();
        List<Path> backups = new ArrayList<>();
        try {
            Files.createDirectories(brainsDir);
            for (MovementBrainBank.Brain brain : bank.brains().values()) {
                if (!MovementNetworkGenetics.isValid(brain.network())) continue;
                Path brainPath = brainPathFor(config, plugin, brain.name());
                AtomicWriteResult write = writeAtomic(brainPath, GSON.toJson(toBrainJson(brain)), true);
                written.add(brainPath);
                if (write.backupPath() != null) backups.add(write.backupPath());
            }
            AtomicWriteResult manifestWrite = writeAtomic(manifestPath, GSON.toJson(toManifestJson(plugin, config, bank)), true);
            written.add(manifestPath);
            if (manifestWrite.backupPath() != null) backups.add(manifestWrite.backupPath());
            return new BankSaveResult(true, "saved movement brain bank", manifestPath,
                    List.copyOf(written), List.copyOf(backups));
        } catch (IOException | RuntimeException e) {
            return new BankSaveResult(false, e.getMessage(), manifestPath, List.copyOf(written), List.copyOf(backups));
        }
    }

    public static BankLoadResult loadBank(Plugin plugin, MovementTrainingConfig config) {
        if (plugin == null || config == null) {
            return new BankLoadResult(false, true, "missing plugin/config", null,
                    null, null, List.of());
        }

        Path manifestPath = config.manifestPath(plugin);
        if (!Files.exists(manifestPath)) {
            return importLegacyOrMissing(plugin, config);
        }

        List<String> warnings = new ArrayList<>();
        Path backupPath = null;
        try {
            JsonObject manifest = readObject(manifestPath);
            int schemaVersion = intValue(manifest, "schemaVersion", -1);
            if (schemaVersion != MovementBrainBank.MANIFEST_SCHEMA_VERSION) {
                backupPath = quarantineOrBackup(manifestPath, config, "bad-manifest-schema");
                return resetFallbackAfterManifestFailure(plugin, config, warnings,
                        "manifest schema mismatch: " + schemaVersion, backupPath);
            }
            String observationHash = stringValue(manifest, "observationSchemaHash", "");
            String actionHash = stringValue(manifest, "actionSchemaHash", "");
            if (!hashesMatch(observationHash, actionHash)) {
                backupPath = quarantineOrBackup(manifestPath, config, "bad-manifest-hash");
                return resetFallbackAfterManifestFailure(plugin, config, warnings,
                        "manifest schema hash mismatch", backupPath);
            }

            String defaultBrainName = MovementBrainBank.normalizeName(
                    stringValue(manifest, "defaultBrainName", config.fallbackBrainName()),
                    MovementBrainBank.FALLBACK_BRAIN_NAME);
            int routingVersion = intValue(manifest, "routingTableVersion", MovementBrainBank.ROUTING_TABLE_VERSION);
            String trainingBuildVersion = stringValue(manifest, "trainingBuildVersion", buildVersion(plugin));
            Map<String, String> routeFiles = routeFilesFromManifest(manifest, defaultBrainName, config, plugin);
            Map<String, String> routeNames = new LinkedHashMap<>();
            Map<String, MovementBrainBank.Brain> brains = new LinkedHashMap<>();

            for (String family : MovementNetworkShape.BRANCH_FAMILY_IDS) {
                String routeBrainName = family.equals(MovementBrainBank.FALLBACK_BRAIN_NAME) ? defaultBrainName : family;
                routeNames.put(family, routeBrainName);

                String relative = routeFiles.getOrDefault(family,
                        relativeBrainPath(config, plugin, routeBrainName, manifestPath.getParent()));
                Path brainPath = resolveManifestRelative(manifestPath.getParent(), relative);
                BrainReadResult read = readBrainFile(brainPath, family, config);
                if (read.loaded()) {
                    brains.put(read.brain().name(), read.brain());
                    routeNames.put(family, read.brain().name());
                } else if (!read.missing()) {
                    warnings.add(family + ": " + read.message());
                    Path quarantined;
                    try {
                        quarantined = quarantineOrBackup(brainPath, config, "bad-brain");
                    } catch (IOException ignored) {
                        quarantined = null;
                    }
                    if (quarantined != null) warnings.add("quarantined " + brainPath + " -> " + quarantined);
                }
            }

            MovementBrainBank bank = new MovementBrainBank(defaultBrainName, schemaVersion, routingVersion,
                    observationHash, actionHash, trainingBuildVersion, routeNames, brains,
                    migrationFromManifest(manifest));
            if (!bank.hasValidFallback()) {
                warnings.add("fallback brain missing or invalid; generated a fresh fallback");
                MovementBrainBank reset = freshFallbackBank(config, plugin, new Random(), "load-reset")
                        .withMigrationMetadata(bank.migrationMetadata());
                BankSaveResult save = saveBank(plugin, config, reset);
                warnings.add(save.message());
                return new BankLoadResult(save.saved(), false,
                        "loaded bank with generated fallback", manifestPath, backupPath, reset, List.copyOf(warnings));
            }

            String message = "loaded " + bank.brains().size() + " movement brain(s)";
            if (!bank.missingRouteFamilies().isEmpty()) {
                message += "; missing optional experts route to " + bank.defaultBrainName();
            }
            return new BankLoadResult(true, false, message, manifestPath, backupPath, bank, List.copyOf(warnings));
        } catch (IOException | JsonParseException | IllegalStateException e) {
            try {
                backupPath = quarantineOrBackup(manifestPath, config, "bad-manifest");
            } catch (IOException ignored) {
                backupPath = null;
            }
            return resetFallbackAfterManifestFailure(plugin, config, warnings,
                    "manifest load failed: " + e.getMessage(), backupPath);
        }
    }

    public static BankResetResult resetBank(Plugin plugin, MovementTrainingConfig config, Random random) {
        MovementBrainBank bank = freshFallbackBank(config, plugin, random, "reset");
        BankSaveResult save = saveBank(plugin, config, bank);
        Path backupPath = save.backupPaths().isEmpty() ? null : save.backupPaths().get(0);
        return new BankResetResult(save.saved(), save.saved() ? "reset movement brain bank" : save.message(),
                config.manifestPath(plugin), backupPath, bank, save.writtenPaths(), save.backupPaths());
    }

    private static BankLoadResult importLegacyOrMissing(Plugin plugin, MovementTrainingConfig config) {
        Path manifestPath = config.manifestPath(plugin);
        Path legacyPath = config.legacyBrainPath(plugin);
        if (!Files.exists(legacyPath)) {
            return new BankLoadResult(false, true, "missing movement brain manifest", manifestPath,
                    null, null, List.of());
        }

        String behavior = config.legacyImportBehavior().toLowerCase(Locale.ROOT);
        if (behavior.equals("disabled") || behavior.equals("none") || behavior.equals("ignore")) {
            return new BankLoadResult(false, true, "legacy brain import disabled", manifestPath,
                    null, null, List.of("legacy brain present but import is disabled: " + legacyPath));
        }

        List<String> warnings = new ArrayList<>();
        try {
            BrainReadResult legacy = readLegacyBrain(legacyPath, config);
            if (legacy.loaded()) {
                MovementBrainBank bank = MovementBrainBank.singleFallback(legacy.brain().network(), legacy.brain().metadata(),
                        buildVersion(plugin), "legacy-import")
                        .withMigrationMetadata(new MovementBrainBank.MigrationMetadata(
                                true, true, relativeToData(plugin, legacyPath), "imported", Instant.now().toString()));
                BankSaveResult save = saveBank(plugin, config, bank);
                warnings.add("imported legacy brain as general_fallback only");
                return new BankLoadResult(save.saved(), false, "imported legacy movement brain",
                        manifestPath, null, bank, List.copyOf(warnings));
            }

            warnings.add("legacy brain is incompatible: " + legacy.message());
        } catch (RuntimeException e) {
            warnings.add("legacy brain import failed: " + e.getMessage());
        }

        Path backupPath = null;
        try {
            backupPath = backupExisting(legacyPath, "incompatible");
        } catch (IOException e) {
            warnings.add("failed to back up incompatible legacy brain: " + e.getMessage());
        }

        MovementBrainBank reset = freshFallbackBank(config, plugin, new Random(), "legacy-incompatible-reset")
                .withMigrationMetadata(new MovementBrainBank.MigrationMetadata(
                        true, false, relativeToData(plugin, legacyPath), "incompatible-reset", Instant.now().toString()));
        BankSaveResult save = saveBank(plugin, config, reset);
        warnings.add(save.message());
        return new BankLoadResult(save.saved(), false,
                "legacy brain incompatible; generated safe general_fallback", manifestPath,
                backupPath, reset, List.copyOf(warnings));
    }

    private static BankLoadResult resetFallbackAfterManifestFailure(
            Plugin plugin,
            MovementTrainingConfig config,
            List<String> warnings,
            String reason,
            Path backupPath
    ) {
        warnings.add(reason);
        MovementBrainBank reset = freshFallbackBank(config, plugin, new Random(), "manifest-failure-reset");
        BankSaveResult save = saveBank(plugin, config, reset);
        warnings.add(save.message());
        return new BankLoadResult(save.saved(), false, reason + "; generated safe general_fallback",
                config.manifestPath(plugin), backupPath, reset, List.copyOf(warnings));
    }

    private static MovementBrainBank freshFallbackBank(
            MovementTrainingConfig config,
            Plugin plugin,
            Random random,
            String source
    ) {
        MovementNetwork network = MovementNetworkGenetics.random(config.movementLayerShape(), random);
        return MovementBrainBank.singleFallback(network, TrainingMetadata.manual(), buildVersion(plugin), source);
    }

    private static BrainReadResult readBrainFile(Path path, String expectedFamily, MovementTrainingConfig config) {
        if (!Files.exists(path)) {
            return BrainReadResult.missing(path);
        }
        try {
            JsonObject json = readObject(path);
            return parseBankBrain(path, json, expectedFamily, config);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return BrainReadResult.failed(path, e.getMessage());
        }
    }

    private static BrainReadResult readLegacyBrain(Path path, MovementTrainingConfig config) {
        if (!Files.exists(path)) {
            return BrainReadResult.missing(path);
        }
        try {
            JsonObject json = readObject(path);
            if (json.has("observationSchemaHash") || json.has("actionSchemaHash")) {
                if (!hashesMatch(stringValue(json, "observationSchemaHash", ""),
                        stringValue(json, "actionSchemaHash", ""))) {
                    return BrainReadResult.failed(path, "schema hash mismatch");
                }
            } else {
                String inputSchema = stringValue(json, "inputSchema", "");
                String outputSchema = stringValue(json, "outputSchema", "");
                if (!inputSchema.contains(":" + MovementNetworkShape.INPUT_COUNT)
                        || !outputSchema.contains(":" + MovementNetworkShape.OUTPUT_COUNT)) {
                    return BrainReadResult.failed(path, "legacy schema is not compatible with current movement input/output");
                }
            }
            return parseBankBrain(path, json, MovementBrainBank.FALLBACK_BRAIN_NAME, config);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            return BrainReadResult.failed(path, e.getMessage());
        }
    }

    private static BrainReadResult parseBankBrain(
            Path path,
            JsonObject json,
            String expectedFamily,
            MovementTrainingConfig config
    ) {
        if (json.has("schemaVersion") && intValue(json, "schemaVersion", BRAIN_SCHEMA_VERSION) != BRAIN_SCHEMA_VERSION) {
            return BrainReadResult.failed(path, "brain schema mismatch");
        }
        if (json.has("mode") && !"movement-controller".equalsIgnoreCase(stringValue(json, "mode", ""))) {
            return BrainReadResult.failed(path, "not a movement-controller brain");
        }
        if (json.has("observationSchemaHash") || json.has("actionSchemaHash")) {
            if (!hashesMatch(stringValue(json, "observationSchemaHash", ""),
                    stringValue(json, "actionSchemaHash", ""))) {
                return BrainReadResult.failed(path, "schema hash mismatch");
            }
        }

        String family = MovementBrainBank.normalizeFamily(stringValue(json, "brainFamily", expectedFamily));
        if (MovementBrainBank.FALLBACK_FILE_FAMILY.equals(family)) {
            family = MovementBrainBank.FALLBACK_BRAIN_NAME;
        }
        String normalizedExpected = MovementBrainBank.normalizeFamily(expectedFamily);
        if (!Objects.equals(normalizedExpected, family)
                && !Objects.equals(family, MovementBrainBank.FALLBACK_BRAIN_NAME)) {
            return BrainReadResult.failed(path, "brain family mismatch: expected " + normalizedExpected + " got " + family);
        }

        int[] shape = readShape(json);
        int[] expectedShape = normalizedShape(config.movementLayerShape());
        if (!Arrays.equals(shape, expectedShape)) {
            return BrainReadResult.failed(path, "shape mismatch: expected "
                    + Arrays.toString(expectedShape) + " got " + Arrays.toString(shape));
        }

        double[] parameters = readParameters(json, shape);
        int expectedParameters = new MovementNetwork(shape, new double[0]).parameterCount();
        if (parameters.length != expectedParameters) {
            return BrainReadResult.failed(path, "parameter count mismatch: expected "
                    + expectedParameters + " got " + parameters.length);
        }
        for (double parameter : parameters) {
            if (!Double.isFinite(parameter)) return BrainReadResult.failed(path, "non-finite parameter");
        }

        MovementNetwork network = new MovementNetwork(shape, parameters);
        MovementNetwork.Validation validation = network.validate(MovementNetworkShape.INPUT_COUNT, MovementNetworkShape.OUTPUT_COUNT);
        if (!validation.valid()) {
            return BrainReadResult.failed(path, validation.reason());
        }

        MovementBrainBank.Brain brain = new MovementBrainBank.Brain(
                MovementBrainBank.normalizeName(stringValue(json, "brainName", family), family),
                family,
                network,
                readTrainingMetadata(json),
                readNormalization(json),
                readRolloutStats(json),
                stringValue(json, "source", path.getFileName().toString()),
                stringValue(json, "trainingBuildVersion", "")
        );
        return BrainReadResult.loaded(path, brain);
    }

    private static JsonObject toManifestJson(Plugin plugin, MovementTrainingConfig config, MovementBrainBank bank) {
        Path manifestParent = config.manifestPath(plugin).getParent();
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", MovementBrainBank.MANIFEST_SCHEMA_VERSION);
        json.addProperty("observationSchemaHash", MovementNetworkShape.OBSERVATION_SCHEMA_HASH);
        json.addProperty("actionSchemaHash", MovementNetworkShape.ACTION_SCHEMA_HASH);
        json.addProperty("defaultBrainName", bank.defaultBrainName());
        json.addProperty("routingTableVersion", MovementBrainBank.ROUTING_TABLE_VERSION);
        json.addProperty("trainingBuildVersion", buildVersion(plugin));
        json.addProperty("savedAt", Instant.now().toString());

        JsonObject routes = new JsonObject();
        for (String family : MovementNetworkShape.BRANCH_FAMILY_IDS) {
            String brainName = bank.routes().getOrDefault(family,
                    family.equals(MovementBrainBank.FALLBACK_BRAIN_NAME) ? bank.defaultBrainName() : family);
            routes.addProperty(family, relativeBrainPath(config, plugin, brainName, manifestParent));
        }
        json.add("routes", routes);

        JsonObject migration = new JsonObject();
        MovementBrainBank.MigrationMetadata metadata = bank.migrationMetadata();
        migration.addProperty("legacyImportAttempted", metadata.legacyImportAttempted());
        migration.addProperty("legacyImported", metadata.legacyImported());
        migration.addProperty("legacyPath", metadata.legacyPath());
        migration.addProperty("result", metadata.result());
        migration.addProperty("timestamp", metadata.timestamp());
        json.add("migration", migration);
        return json;
    }

    private static JsonObject toBrainJson(MovementBrainBank.Brain brain) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", BRAIN_SCHEMA_VERSION);
        json.addProperty("brainName", brain.name());
        json.addProperty("brainFamily", brain.familyId());
        json.addProperty("observationSchemaHash", MovementNetworkShape.OBSERVATION_SCHEMA_HASH);
        json.addProperty("actionSchemaHash", MovementNetworkShape.ACTION_SCHEMA_HASH);
        json.addProperty("trainingBuildVersion", brain.trainingBuildVersion());
        json.addProperty("source", brain.source());
        json.addProperty("savedAt", Instant.now().toString());

        JsonObject architecture = new JsonObject();
        architecture.add("layerShape", GSON.toJsonTree(brain.network().layerSizes()));
        architecture.addProperty("inputCount", MovementNetworkShape.INPUT_COUNT);
        architecture.addProperty("outputCount", MovementNetworkShape.OUTPUT_COUNT);
        architecture.addProperty("parameterCount", brain.network().parameterCount());
        architecture.addProperty("activation", "tanh");
        architecture.addProperty("parameterLayout", "bias-then-input-weights-per-neuron");
        json.add("architecture", architecture);

        json.add("weights", GSON.toJsonTree(brain.network().parameters()));

        JsonObject normalization = new JsonObject();
        normalization.addProperty("mode", brain.normalization().mode());
        normalization.add("mean", GSON.toJsonTree(brain.normalization().mean()));
        normalization.add("scale", GSON.toJsonTree(brain.normalization().scale()));
        normalization.add("observationFields", GSON.toJsonTree(MovementNetworkShape.INPUT_FIELDS));
        normalization.add("actionFields", GSON.toJsonTree(MovementNetworkShape.OUTPUT_FIELDS));
        json.add("normalization", normalization);

        JsonObject training = new JsonObject();
        training.addProperty("generation", brain.metadata().generation());
        training.addProperty("bestFitness", brain.metadata().bestFitness());
        training.addProperty("averageFitness", brain.metadata().averageFitness());
        json.add("training", training);

        JsonObject rollout = new JsonObject();
        brain.rolloutStats().metrics().forEach((key, value) -> rollout.addProperty(key, value));
        json.add("rolloutStats", rollout);
        return json;
    }

    private static Map<String, String> routeFilesFromManifest(
            JsonObject manifest,
            String defaultBrainName,
            MovementTrainingConfig config,
            Plugin plugin
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonObject routes = objectValue(manifest, "routes");
        if (routes == null) routes = objectValue(manifest, "brainFiles");
        if (routes != null) {
            for (Map.Entry<String, JsonElement> entry : routes.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    result.put(MovementBrainBank.normalizeFamily(entry.getKey()), entry.getValue().getAsString());
                }
            }
        }
        Path manifestParent = config.manifestPath(plugin).getParent();
        for (String family : MovementNetworkShape.BRANCH_FAMILY_IDS) {
            String brainName = family.equals(MovementBrainBank.FALLBACK_BRAIN_NAME) ? defaultBrainName : family;
            result.putIfAbsent(family, relativeBrainPath(config, plugin, brainName, manifestParent));
        }
        return result;
    }

    private static MovementBrainBank.MigrationMetadata migrationFromManifest(JsonObject manifest) {
        JsonObject migration = objectValue(manifest, "migration");
        if (migration == null) return MovementBrainBank.MigrationMetadata.none();
        return new MovementBrainBank.MigrationMetadata(
                booleanValue(migration, "legacyImportAttempted", false),
                booleanValue(migration, "legacyImported", false),
                stringValue(migration, "legacyPath", ""),
                stringValue(migration, "result", ""),
                stringValue(migration, "timestamp", "")
        );
    }

    private static TrainingMetadata readTrainingMetadata(JsonObject json) {
        JsonObject training = objectValue(json, "training");
        if (training == null) training = objectValue(json, "trainingMetadata");
        if (training == null) return TrainingMetadata.manual();
        return new TrainingMetadata(
                intValue(training, "generation", 0),
                doubleValue(training, "bestFitness", 0.0),
                doubleValue(training, "averageFitness", 0.0)
        );
    }

    private static MovementBrainBank.NormalizationStats readNormalization(JsonObject json) {
        JsonObject normalization = objectValue(json, "normalization");
        if (normalization == null) return MovementBrainBank.NormalizationStats.none();
        return new MovementBrainBank.NormalizationStats(
                stringValue(normalization, "mode", "pre_normalized"),
                readOptionalDoubleArray(normalization, "mean"),
                readOptionalDoubleArray(normalization, "scale")
        );
    }

    private static MovementBrainBank.RolloutStats readRolloutStats(JsonObject json) {
        JsonObject rollout = objectValue(json, "rolloutStats");
        if (rollout == null) return MovementBrainBank.RolloutStats.empty();
        Map<String, Double> metrics = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : rollout.entrySet()) {
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isNumber()) {
                double value = entry.getValue().getAsDouble();
                if (Double.isFinite(value)) metrics.put(entry.getKey(), value);
            }
        }
        return new MovementBrainBank.RolloutStats(metrics);
    }

    private static int[] readShape(JsonObject json) {
        JsonObject architecture = objectValue(json, "architecture");
        JsonArray shapeArray = architecture == null ? null : arrayValue(architecture, "layerShape");
        if (shapeArray == null) shapeArray = arrayValue(json, "shape");
        if (shapeArray == null) throw new IllegalStateException("missing layer shape");
        return normalizedShape(GSON.fromJson(shapeArray, int[].class));
    }

    private static double[] readParameters(JsonObject json, int[] shape) {
        JsonArray flat = arrayValue(json, "weights");
        if (flat != null && (flat.size() == 0 || flat.get(0).isJsonPrimitive())) {
            return readDoubleArray(flat);
        }
        if (json.has("parameters")) {
            return readDoubleArray(arrayValue(json, "parameters"));
        }
        JsonArray biases = arrayValue(json, "biases");
        if (flat == null || biases == null) throw new IllegalStateException("missing weights");
        return flattenLegacyWeights(shape, flat, biases);
    }

    private static double[] flattenLegacyWeights(int[] shape, JsonArray weights, JsonArray biases) {
        List<Double> values = new ArrayList<>();
        for (int layer = 1; layer < shape.length; layer++) {
            JsonArray layerWeights = weights.get(layer - 1).getAsJsonArray();
            JsonArray layerBiases = biases.get(layer - 1).getAsJsonArray();
            int previous = shape[layer - 1];
            int next = shape[layer];
            if (layerWeights.size() != next || layerBiases.size() != next) {
                throw new IllegalStateException("legacy layer size mismatch");
            }
            for (int node = 0; node < next; node++) {
                values.add(layerBiases.get(node).getAsDouble());
                JsonArray nodeWeights = layerWeights.get(node).getAsJsonArray();
                if (nodeWeights.size() != previous) throw new IllegalStateException("legacy node weight count mismatch");
                for (JsonElement weight : nodeWeights) {
                    values.add(weight.getAsDouble());
                }
            }
        }
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static int[] normalizedShape(int[] requested) {
        int[] shape = requested == null || requested.length < 2
                ? MovementNetworkShape.DEFAULT_LAYERS
                : Arrays.copyOf(requested, requested.length);
        shape[0] = MovementNetworkShape.INPUT_COUNT;
        shape[shape.length - 1] = MovementNetworkShape.OUTPUT_COUNT;
        for (int i = 1; i < shape.length - 1; i++) {
            shape[i] = Math.max(1, shape[i]);
        }
        return shape;
    }

    private static boolean hashesMatch(String observationHash, String actionHash) {
        return MovementNetworkShape.OBSERVATION_SCHEMA_HASH.equals(observationHash)
                && MovementNetworkShape.ACTION_SCHEMA_HASH.equals(actionHash);
    }

    private static Path brainPathFor(MovementTrainingConfig config, Plugin plugin, String brainName) {
        String normalized = MovementBrainBank.normalizeName(brainName, MovementBrainBank.FALLBACK_BRAIN_NAME);
        String fileName = normalized.equals(MovementBrainBank.FALLBACK_BRAIN_NAME) ? "general.json" : normalized + ".json";
        return config.brainsDirectoryPath(plugin).resolve(fileName);
    }

    private static String relativeBrainPath(
            MovementTrainingConfig config,
            Plugin plugin,
            String brainName,
            Path manifestParent
    ) {
        Path brainPath = brainPathFor(config, plugin, brainName);
        try {
            return manifestParent.relativize(brainPath).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return plugin.getDataFolder().toPath().relativize(brainPath).toString().replace('\\', '/');
        }
    }

    private static Path resolveManifestRelative(Path manifestParent, String relative) {
        Path path = Path.of(relative == null ? "" : relative);
        return path.isAbsolute() ? path : manifestParent.resolve(path).normalize();
    }

    private static String relativeToData(Plugin plugin, Path path) {
        try {
            return plugin.getDataFolder().toPath().relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    private static JsonObject readObject(Path path) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path));
        if (!parsed.isJsonObject()) throw new JsonParseException("expected JSON object");
        return parsed.getAsJsonObject();
    }

    private static AtomicWriteResult writeAtomic(Path path, String content, boolean backup) throws IOException {
        Files.createDirectories(path.getParent());
        Path backupPath = backup ? backupExisting(path, "bak") : null;
        Path temp = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temp, content);
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        return new AtomicWriteResult(backupPath);
    }

    private static Path backupExisting(Path path, String suffix) throws IOException {
        if (!Files.exists(path)) return null;
        Path backup = path.resolveSibling(path.getFileName() + "." + suffix + "-" + timestamp());
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private static Path quarantineOrBackup(Path path, MovementTrainingConfig config, String suffix) throws IOException {
        if (!Files.exists(path)) return null;
        if (config.quarantineBadFiles()) {
            Path quarantined = path.resolveSibling(path.getFileName() + "." + suffix + "-" + timestamp());
            Files.move(path, quarantined, StandardCopyOption.REPLACE_EXISTING);
            return quarantined;
        }
        return backupExisting(path, suffix);
    }

    private static String timestamp() {
        return Instant.now().toString().replace(':', '-');
    }

    private static String buildVersion(Plugin plugin) {
        return plugin == null || plugin.getDescription() == null ? "" : plugin.getDescription().getVersion();
    }

    private static double[] readOptionalDoubleArray(JsonObject json, String key) {
        JsonArray array = arrayValue(json, key);
        return array == null ? new double[0] : readDoubleArray(array);
    }

    private static double[] readDoubleArray(JsonArray array) {
        if (array == null) throw new IllegalStateException("missing number array");
        return GSON.fromJson(array, double[].class);
    }

    private static JsonObject objectValue(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonObject()) return null;
        return object.getAsJsonObject(key);
    }

    private static JsonArray arrayValue(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) return null;
        return object.getAsJsonArray(key);
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsString();
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsInt();
    }

    private static double doubleValue(JsonObject object, String key, double fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsDouble();
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        return object.get(key).getAsBoolean();
    }

    public record TrainingMetadata(int generation, double bestFitness, double averageFitness) {
        public static TrainingMetadata manual() {
            return new TrainingMetadata(0, 0.0, 0.0);
        }
    }

    public record SaveFeedback(boolean saved, String message) {
    }

    public record SaveResult(boolean saved, String message, Path path) {
    }

    public record LoadResult(
            boolean loaded,
            boolean missing,
            String message,
            Path path,
            Path backupPath,
            MovementNetwork network,
            TrainingMetadata metadata
    ) {
    }

    public record ResetResult(
            boolean reset,
            String message,
            Path path,
            Path backupPath,
            MovementNetwork network,
            TrainingMetadata metadata
    ) {
    }

    public record BankSaveResult(
            boolean saved,
            String message,
            Path manifestPath,
            List<Path> writtenPaths,
            List<Path> backupPaths
    ) {
    }

    public record BankLoadResult(
            boolean loaded,
            boolean missing,
            String message,
            Path manifestPath,
            Path backupPath,
            MovementBrainBank bank,
            List<String> warnings
    ) {
    }

    public record BankResetResult(
            boolean reset,
            String message,
            Path manifestPath,
            Path backupPath,
            MovementBrainBank bank,
            List<Path> writtenPaths,
            List<Path> backupPaths
    ) {
    }

    private record AtomicWriteResult(Path backupPath) {
    }

    private record BrainReadResult(
            boolean loaded,
            boolean missing,
            String message,
            Path path,
            MovementBrainBank.Brain brain
    ) {
        static BrainReadResult loaded(Path path, MovementBrainBank.Brain brain) {
            return new BrainReadResult(true, false, "loaded", path, brain);
        }

        static BrainReadResult missing(Path path) {
            return new BrainReadResult(false, true, "missing", path, null);
        }

        static BrainReadResult failed(Path path, String message) {
            return new BrainReadResult(false, false, message == null ? "invalid" : message, path, null);
        }
    }
}
