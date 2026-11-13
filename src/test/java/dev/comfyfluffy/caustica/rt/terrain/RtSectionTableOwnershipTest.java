package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionTable.SectionGeom;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A retired section that still carries the slot and instance index it once had must not be able to
 * release them after they were handed to its replacement.
 */
class RtSectionTableOwnershipTest {
    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    private static SectionGeom geom(long key) {
        return new SectionGeom(key, null, null, null, new int[4], 0, 0, 0, null);
    }

    private static void publish(RtSectionTable table, Long2ObjectOpenHashMap<SectionGeom> resident,
                                LongOpenHashSet published, SectionGeom geom) {
        geom.slot = table.allocateSlot();
        geom.instanceIndex = table.instanceList.size();
        table.slots.set(geom.slot, geom);
        table.instanceList.add(new RtAccel.Instance(IDENTITY, 0L, geom.slot));
        resident.put(geom.key, geom);
        published.add(geom.key);
    }

    @Test
    void staleGeometryCannotReleaseItsSuccessorsSlot() {
        RtSectionTable table = new RtSectionTable();
        Long2ObjectOpenHashMap<SectionGeom> resident = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet published = new LongOpenHashSet();
        SectionGeom first = geom(1L);
        SectionGeom other = geom(2L);
        publish(table, resident, published, first);
        publish(table, resident, published, other);

        // In-place replacement that (as the old code did) leaves the retired geometry's numbers intact.
        SectionGeom successor = geom(1L);
        successor.slot = first.slot;
        successor.instanceIndex = first.instanceIndex;
        table.slots.set(successor.slot, successor);
        resident.put(1L, successor);

        table.removePublished(resident, published, first);

        assertEquals(2, table.instanceList.size());
        assertSame(successor, resident.get(1L));
        assertSame(successor, table.slots.get(successor.slot));
        assertTrue(table.freeSlots.isEmpty());
        assertTrue(published.contains(1L));
        assertEquals(-1, first.slot);
        assertEquals(-1, first.instanceIndex);

        // The rightful owner still releases normally.
        table.removePublished(resident, published, successor);
        assertEquals(1, table.instanceList.size());
        assertEquals(0, other.instanceIndex);
        assertEquals(1, table.freeSlots.size());
    }
}
