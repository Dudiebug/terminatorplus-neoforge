package net.nuggetmc.tplus.migration;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One-shot, non-destructive importer for Paper TerminatorPlus data.
 *
 * <p>Paper and NeoForge installations may coexist. The importer only copies
 * files whose destination does not already exist, keeps a timestamped source
 * backup, and emits a machine-readable report even when a source file is
 * malformed.</p>
 */
public final class PaperImporter {
    public static final String MARKER = ".paper-import-complete";

    private PaperImporter() {
    }

    public static ImportResult run(Path serverRoot, Path neoForgeData) {
        Objects.requireNonNull(serverRoot, "serverRoot");
        Objects.requireNonNull(neoForgeData, "neoForgeData");
        List<String> messages = new ArrayList<>();
        List<String> copied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        Path marker = neoForgeData.resolve(MARKER);
        try {
            Files.createDirectories(neoForgeData);
            if (Files.exists(marker)) {
                return new ImportResult(false, null, List.of(), List.of("already-imported"), List.of(), null);
            }
        } catch (IOException error) {
            return failed(messages, "cannot-create-data-directory: " + error.getMessage());
        }

        Path staged = neoForgeData.resolve("import-paper");
        Path paper = serverRoot.resolve("plugins").resolve("TerminatorPlus");
        Path source = Files.isDirectory(staged) ? staged : (Files.isDirectory(paper) ? paper : null);
        if (source == null) {
            writeMarker(marker, "no-source");
            return new ImportResult(false, null, List.of(), List.of("no-paper-source"), List.of(), null);
        }

        Path backup = neoForgeData.resolve("import-backup-" + Instant.now().toEpochMilli());
        try {
            copyTree(source, backup);
            messages.add("backup=" + backup);
        } catch (IOException error) {
            messages.add("backup-failed=" + error.getMessage());
        }

        importConfig(source, neoForgeData, copied, skipped, invalid, messages);
        importPresets(source, neoForgeData.resolve("presets"), copied, skipped, invalid);
        importJsonDirectory(source, neoForgeData.resolve("ai").resolve("movement").resolve("brains"),
                List.of("brain", "brains", "brain-bank", "brainbank"), "brain", copied, skipped, invalid);
        importJsonDirectory(source, neoForgeData.resolve("ai").resolve("movement").resolve("evaluations"),
                List.of("evaluation", "evaluations", "eval"), "evaluation", copied, skipped, invalid);
        importDirectory(source, neoForgeData.resolve("logs"), List.of("logs", "log"), copied, skipped);

        ImportResult result = new ImportResult(true, source, List.copyOf(copied), List.copyOf(skipped),
                List.copyOf(invalid), backup);
        writeReport(neoForgeData.resolve("import-report.json"), result, messages);
        writeMarker(marker, "imported=" + Instant.now());
        return result;
    }

    private static void importConfig(Path source, Path targetRoot, List<String> copied,
                                     List<String> skipped, List<String> invalid, List<String> messages) {
        Path yaml = firstExisting(source.resolve("config.yml"), source.resolve("config.yaml"),
                source.resolve("configuration.yml"));
        if (yaml == null) return;
        Path target = targetRoot.resolve("imported-paper-config.yml");
        if (Files.exists(target)) {
            skipped.add(target.toString());
            return;
        }
        try (Reader reader = Files.newBufferedReader(yaml, StandardCharsets.UTF_8)) {
            Object parsed = new Yaml().load(reader);
            if (parsed != null && !(parsed instanceof Map<?, ?>)) {
                invalid.add(yaml + ": top-level YAML value is not a map");
                return;
            }
            // Keep the original YAML shape for values with no TOML equivalent;
            // the report makes the conversion auditable without dropping keys.
            Files.writeString(target, new Yaml().dump(parsed), StandardCharsets.UTF_8);
            copied.add(target.toString());
            messages.add("config-converted=" + yaml);
        } catch (Exception error) {
            invalid.add(yaml + ": " + error.getMessage());
        }
    }

    private static void importPresets(Path source, Path target, List<String> copied,
                                      List<String> skipped, List<String> invalid) {
        Path presets = firstDirectory(source.resolve("presets"), source.resolve("preset"));
        if (presets == null) return;
        try {
            Files.createDirectories(target);
            try (var files = Files.list(presets)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().toLowerCase().endsWith(".yml")
                                || path.toString().toLowerCase().endsWith(".yaml"))
                        .sorted()
                        .forEach(path -> copyUnique(path, target.resolve(path.getFileName()), copied, skipped, invalid));
            }
        } catch (IOException error) {
            invalid.add(presets + ": " + error.getMessage());
        }
    }

    private static void importJsonDirectory(Path source, Path target, List<String> names, String kind,
                                            List<String> copied, List<String> skipped, List<String> invalid) {
        Path directory = names.stream().map(source::resolve).filter(Files::isDirectory).findFirst().orElse(null);
        if (directory == null) return;
        try {
            Files.createDirectories(target);
            try (var files = Files.walk(directory)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().toLowerCase().endsWith(".json"))
                        .sorted()
                        .forEach(path -> {
                            try {
                                JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
                                copyUnique(path, target.resolve(directory.relativize(path)), copied, skipped, invalid);
                            } catch (Exception error) {
                                invalid.add(kind + ":" + path + ": " + error.getMessage());
                            }
                        });
            }
        } catch (IOException error) {
            invalid.add(kind + ":" + directory + ": " + error.getMessage());
        }
    }

    private static void importDirectory(Path source, Path target, List<String> names,
                                         List<String> copied, List<String> skipped) {
        Path directory = names.stream().map(source::resolve).filter(Files::isDirectory).findFirst().orElse(null);
        if (directory == null) return;
        try {
            Files.createDirectories(target);
            try (var files = Files.walk(directory)) {
                files.filter(Files::isRegularFile).sorted().forEach(path -> {
                    Path destination = target.resolve(directory.relativize(path));
                    if (Files.exists(destination)) skipped.add(destination.toString());
                    else try {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                        copied.add(destination.toString());
                    } catch (IOException ignored) {
                        skipped.add(destination.toString());
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    private static void copyUnique(Path source, Path target, List<String> copied, List<String> skipped,
                                   List<String> invalid) {
        if (Files.exists(target)) {
            skipped.add(target.toString());
            return;
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            copied.add(target.toString());
        } catch (IOException error) {
            invalid.add(source + ": " + error.getMessage());
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().collect(Collectors.toList())) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static Path firstExisting(Path... paths) {
        for (Path path : paths) if (Files.isRegularFile(path)) return path;
        return null;
    }

    private static Path firstDirectory(Path... paths) {
        for (Path path : paths) if (Files.isDirectory(path)) return path;
        return null;
    }

    private static void writeReport(Path path, ImportResult result, List<String> messages) {
        try {
            Map<String, Object> report = new java.util.LinkedHashMap<>();
            report.put("imported", result.imported());
            report.put("source", result.source() == null ? null : result.source().toString());
            report.put("backup", result.backup() == null ? null : result.backup().toString());
            report.put("copied", result.copied());
            report.put("skipped", result.skipped());
            report.put("invalid", result.invalid());
            report.put("messages", messages);
            report.put("timestamp", Instant.now().toString());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static void writeMarker(Path marker, String value) {
        try {
            Files.writeString(marker, value + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static ImportResult failed(List<String> messages, String message) {
        messages.add(message);
        return new ImportResult(false, null, List.of(), List.of(), List.copyOf(messages), null);
    }

    public record ImportResult(boolean imported, Path source, List<String> copied, List<String> skipped,
                               List<String> invalid, Path backup) {
    }
}
