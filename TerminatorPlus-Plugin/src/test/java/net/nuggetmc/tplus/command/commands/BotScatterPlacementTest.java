package net.nuggetmc.tplus.command.commands;

import org.junit.jupiter.api.Test;
import net.nuggetmc.tplus.compat.bukkit.Material;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotScatterPlacementTest {

    @Test
    void rejectsCommonFloorAndBodyHazards() {
        assertTrue(BotCommand.isScatterHazard(Material.CAMPFIRE));
        assertTrue(BotCommand.isScatterHazard(Material.SOUL_CAMPFIRE));
        assertTrue(BotCommand.isScatterHazard(Material.COBWEB));
        assertTrue(BotCommand.isScatterHazard(Material.VINE));
        assertTrue(BotCommand.isScatterHazard(Material.WATER));
    }

    @Test
    void defaultAndExplicitRadiusAreAccepted() {
        assertEquals(BotCommand.DEFAULT_SCATTER_RADIUS, BotCommand.parseScatterRadius(null));
        assertEquals(12.5, BotCommand.parseScatterRadius("12.5"));
        assertEquals(1_000_000, BotCommand.parseScatterRadius("1000000"));
    }

    @Test
    void invalidRadiusIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BotCommand.parseScatterRadius("not-a-number"));
        assertThrows(IllegalArgumentException.class, () -> BotCommand.parseScatterRadius("0"));
        assertThrows(IllegalArgumentException.class, () -> BotCommand.parseScatterRadius("0.5"));
        assertThrows(IllegalArgumentException.class, () -> BotCommand.parseScatterRadius("-2"));
        assertThrows(IllegalArgumentException.class, () -> BotCommand.parseScatterRadius("NaN"));
    }

    @Test
    void oneHundredBotsUseAnEvenCircularDistribution() {
        List<BotCommand.ScatterOffset> offsets = BotCommand.evenlySpacedOffsets(100, 10.0);

        assertEquals(100, offsets.size());
        assertEquals(100, offsets.stream().distinct().count());
        assertTrue(offsets.stream().allMatch(offset -> {
            double distance = Math.hypot(offset.x(), offset.z());
            return distance > 0.0 && distance <= 10.0 + 1.0e-9;
        }));
        assertTrue(minimumSpacing(offsets) >= 0.75);
        assertEquals(10.0, Math.hypot(offsets.get(99).x(), offsets.get(99).z()), 1.0e-9);
    }

    @Test
    void noSelectedBotsProducesNoDestinations() {
        assertTrue(BotCommand.evenlySpacedOffsets(0, BotCommand.DEFAULT_SCATTER_RADIUS).isEmpty());
        assertTrue(BotCommand.selectScatterOffsets(List.of(), 3, BotCommand.DEFAULT_SCATTER_RADIUS).isEmpty());
    }

    @Test
    void tooFewSafeCandidatesDoNotGetDuplicated() {
        List<BotCommand.ScatterOffset> candidates = List.of(
                new BotCommand.ScatterOffset(8.0, 0.0),
                new BotCommand.ScatterOffset(-8.0, 0.0)
        );

        List<BotCommand.ScatterOffset> selected = BotCommand.selectScatterOffsets(candidates, 3, 8.0);

        assertEquals(2, selected.size());
        assertEquals(2, selected.stream().distinct().count());
    }

    @Test
    void crowdedCandidatesAreNotStacked() {
        List<BotCommand.ScatterOffset> candidates = List.of(
                new BotCommand.ScatterOffset(0.01, 0.0),
                new BotCommand.ScatterOffset(0.02, 0.0),
                new BotCommand.ScatterOffset(-0.02, 0.0)
        );

        assertEquals(1, BotCommand.selectScatterOffsets(candidates, 3, 0.1).size());
    }

    private static double minimumSpacing(List<BotCommand.ScatterOffset> offsets) {
        double minimum = Double.POSITIVE_INFINITY;
        for (int i = 0; i < offsets.size(); i++) {
            for (int j = 0; j < i; j++) {
                minimum = Math.min(minimum, Math.hypot(
                        offsets.get(i).x() - offsets.get(j).x(),
                        offsets.get(i).z() - offsets.get(j).z()));
            }
        }
        return minimum;
    }
}
