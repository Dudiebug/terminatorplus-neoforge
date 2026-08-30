package net.nuggetmc.tplus.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperImporterTest {
    @TempDir
    Path root;

    @Test
    void importsPaperDataWithoutOverwritingNativeFiles() throws Exception {
        Path paper = root.resolve("plugins/TerminatorPlus");
        Files.createDirectories(paper.resolve("presets"));
        Files.createDirectories(paper.resolve("brain-bank"));
        Files.createDirectories(paper.resolve("logs"));
        Files.writeString(paper.resolve("config.yml"), "ai:\n  movement:\n    v2:\n      enabled: true\n", StandardCharsets.UTF_8);
        Files.writeString(paper.resolve("presets/duel.yml"), "loadout: sword\n", StandardCharsets.UTF_8);
        Files.writeString(paper.resolve("brain-bank/valid.json"), "{\"nodes\":[]}", StandardCharsets.UTF_8);
        Files.writeString(paper.resolve("brain-bank/broken.json"), "{", StandardCharsets.UTF_8);
        Files.writeString(paper.resolve("logs/duel.log"), "duel", StandardCharsets.UTF_8);

        Path nativeData = root.resolve("config/terminatorplus");
        Files.createDirectories(nativeData.resolve("presets"));
        Files.writeString(nativeData.resolve("presets/duel.yml"), "native: true\n", StandardCharsets.UTF_8);

        PaperImporter.ImportResult result = PaperImporter.run(root, nativeData);

        assertTrue(result.imported());
        assertNotNull(result.backup());
        assertTrue(Files.isDirectory(result.backup()));
        assertTrue(Files.isRegularFile(nativeData.resolve("imported-paper-config.yml")));
        assertTrue(Files.isRegularFile(root.resolve("config/terminatorplus-server.toml")));
        assertTrue(Files.readString(root.resolve("config/terminatorplus-server.toml"))
                .contains("enabled = true"));
        assertEquals("native: true\n", Files.readString(nativeData.resolve("presets/duel.yml")));
        assertTrue(Files.isRegularFile(nativeData.resolve("ai/movement/brains/valid.json")));
        assertFalse(Files.exists(nativeData.resolve("ai/movement/brains/broken.json")));
        assertTrue(result.invalid().stream().anyMatch(value -> value.contains("broken.json")));
        assertTrue(Files.isRegularFile(nativeData.resolve("import-report.json")));
        assertTrue(Files.isRegularFile(nativeData.resolve(PaperImporter.MARKER)));

        PaperImporter.ImportResult repeat = PaperImporter.run(root, nativeData);
        assertFalse(repeat.imported());
        assertTrue(repeat.skipped().contains("already-imported"));
    }

    @Test
    void stagedSourceTakesPrecedenceAndMalformedYamlIsReported() throws Exception {
        Path nativeData = root.resolve("config/terminatorplus");
        Path staged = nativeData.resolve("import-paper");
        Files.createDirectories(staged);
        Files.writeString(staged.resolve("config.yml"), "- not-a-map\n", StandardCharsets.UTF_8);

        PaperImporter.ImportResult result = PaperImporter.run(root, nativeData);

        assertTrue(result.imported());
        assertEquals(staged, result.source());
        assertTrue(result.invalid().stream().anyMatch(value -> value.contains("top-level YAML")));
    }
}
