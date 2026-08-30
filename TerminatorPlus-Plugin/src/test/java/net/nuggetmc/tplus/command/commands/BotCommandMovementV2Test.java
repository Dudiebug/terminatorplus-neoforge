package net.nuggetmc.tplus.command.commands;

import net.nuggetmc.tplus.command.annotation.Require;
import net.nuggetmc.tplus.compat.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BotCommandMovementV2Test {

    @Test
    void freshInstallDefaultsMovementV2On() throws IOException {
        String config = Files.readString(Path.of("src", "main", "resources", "config.yml"));

        org.junit.jupiter.api.Assertions.assertTrue(
                config.matches("(?s).*\\bv2:\\s*.*?\\benabled:\\s*true\\b.*"));
    }

    @Test
    void parsesRuntimeToggleAndStatus() {
        assertEquals(BotCommand.MovementV2Action.ON, BotCommand.MovementV2Action.parse("ON"));
        assertEquals(BotCommand.MovementV2Action.OFF, BotCommand.MovementV2Action.parse("off"));
        assertEquals(BotCommand.MovementV2Action.STATUS, BotCommand.MovementV2Action.parse("status"));
        assertEquals(BotCommand.MovementV2Action.STATUS, BotCommand.MovementV2Action.parse(null));
        assertNull(BotCommand.MovementV2Action.parse("true"));
    }

    @Test
    void toggleUsesAdminPermission() throws NoSuchMethodException {
        Require require = BotCommand.class
                .getMethod("movementV2", CommandSender.class, String.class)
                .getAnnotation(Require.class);

        assertEquals("terminatorplus.admin", require.value());
    }
}
