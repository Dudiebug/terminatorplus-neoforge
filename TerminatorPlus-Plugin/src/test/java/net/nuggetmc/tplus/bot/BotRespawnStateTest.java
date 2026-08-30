package net.nuggetmc.tplus.bot;

import net.nuggetmc.tplus.compat.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BotRespawnStateTest {

    @Test
    void preservesInventoryShapeAndEmptySlots() {
        ItemStack[] copied = BotRespawnState.copy(new ItemStack[]{null, null});

        assertEquals(2, copied.length);
        assertNull(copied[0]);
        assertNull(copied[1]);
    }

    @Test
    void ignoresAirborneAndUnsafeCandidates() {
        LocationCandidate candidate = new LocationCandidate(4, 64, 9);

        assertNull(RespawnSafety.captureAnchor(null, candidate.location(), false, ignored -> {
            throw new AssertionError("airborne candidates must not be checked");
        }));
        assertNull(RespawnSafety.captureAnchor(null, candidate.location(), true, ignored -> false));
    }

    @Test
    void retainsTheFirstSafeGroundedLocation() {
        LocationCandidate first = new LocationCandidate(4, 64, 9);
        LocationCandidate later = new LocationCandidate(20, 70, 30);
        net.nuggetmc.tplus.compat.bukkit.Location firstLocation = first.location();

        net.nuggetmc.tplus.compat.bukkit.Location anchor = RespawnSafety.captureAnchor(
                null, firstLocation, true, ignored -> true);
        net.nuggetmc.tplus.compat.bukkit.Location retained = RespawnSafety.captureAnchor(
                anchor, later.location(), true, ignored -> true);

        assertNotSame(firstLocation, anchor);
        assertSame(anchor, retained);
        firstLocation.setX(99);
        assertEquals(4, anchor.getBlockX());
        assertEquals(64, anchor.getBlockY());
        assertEquals(9, anchor.getBlockZ());
    }

    @Test
    void choosesNearestUnblockedFallbackInStableOrder() {
        List<RespawnSafety.Offset> offsets = RespawnSafety.nearestOffsets(1);

        assertEquals(List.of(
                new RespawnSafety.Offset(0, 0),
                new RespawnSafety.Offset(1, 0),
                new RespawnSafety.Offset(-1, 0),
                new RespawnSafety.Offset(0, 1),
                new RespawnSafety.Offset(0, -1),
                new RespawnSafety.Offset(1, 1),
                new RespawnSafety.Offset(1, -1),
                new RespawnSafety.Offset(-1, 1),
                new RespawnSafety.Offset(-1, -1)
        ), offsets);

        Set<RespawnSafety.Offset> blocked = Set.of(
                new RespawnSafety.Offset(0, 0),
                new RespawnSafety.Offset(1, 0));
        RespawnSafety.Offset selected = RespawnSafety.firstSafe(offsets, offset -> !blocked.contains(offset));

        assertEquals(new RespawnSafety.Offset(-1, 0), selected);
        assertNull(RespawnSafety.firstSafe(offsets, ignored -> false));
    }

    @Test
    void emitsPoofThroughTheSmallTestableSeam() {
        net.nuggetmc.tplus.compat.bukkit.Location location = new LocationCandidate(4, 64, 9).location();
        List<net.nuggetmc.tplus.compat.bukkit.Location> emitted = new ArrayList<>();

        RespawnSafety.emitPoof(location, emitted::add);

        assertEquals(1, emitted.size());
        assertNotSame(location, emitted.get(0));
        assertEquals(location, emitted.get(0));
    }

    private record LocationCandidate(int x, int y, int z) {
        net.nuggetmc.tplus.compat.bukkit.Location location() {
            return new net.nuggetmc.tplus.compat.bukkit.Location(null, x, y, z);
        }
    }
}
