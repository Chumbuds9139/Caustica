package dev.comfyfluffy.caustica.rt.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Slot bookkeeping for the bindless entity-texture array: image-view handle → slot, the descriptor
 * writes those allocations still owe, and the capacity limit. Deliberately free of Minecraft and Vulkan
 * types so the allocation policy itself is unit-testable — see {@code RtBindlessSlotTableTest}.
 *
 * <p>Slots are <b>append-only</b>: once issued, a slot's handle never changes, so the update-after-bind
 * writes {@link #drainPending} produces can never disturb a frame still in flight. Freeing happens only
 * wholesale, via {@link #reset}, at a pipeline-rebuild / resource-epoch boundary.
 *
 * <p>Two allocation modes, because "which texture is this?" has two different answers depending on how
 * the texture's lifetime works:
 *
 * <ul>
 *   <li>{@link #shared} — keyed by handle. For textures whose GPU image outlives everything between
 *       resource reloads (entity files like {@code zombie.png}, stitched atlases). Distinct render types
 *       backed by one image collapse onto one slot, which is what bounds slot use when a render type is
 *       re-allocated every frame (the charged-creeper swirl).</li>
 *   <li>{@link #perOwner} — keyed by the <i>owning object's identity</i>. For textures that are destroyed
 *       and re-created while the game is running, where the handle alone is not a safe identity: Vulkan
 *       explicitly permits a driver to hand a destroyed object's handle value to the next creation, so a
 *       handle-keyed hit cannot distinguish "the texture I already have a slot for" from "a new texture
 *       that inherited the dead one's handle". Trusting that hit leaves the slot's descriptor pointing at
 *       freed memory with no write queued to correct it. Font atlas pages are the case in practice.</li>
 * </ul>
 */
final class RtBindlessSlotTable {
    /** One slot whose descriptor still has to be written into the bindless set. */
    record Write(int slot, long handle) {
    }

    private final Map<Long, Integer> byHandle = new HashMap<>();
    // Identity, not equals: two distinct texture objects that happen to compare equal are still two
    // different images as far as the descriptor set is concerned.
    private final Map<Object, Integer> byOwner = new IdentityHashMap<>();
    private final List<Write> pending = new ArrayList<>();
    // Evaluated per allocation rather than captured, so lowering the configured maximum at runtime stops
    // handing out new slots immediately without invalidating the ones already in use.
    private final IntSupplier limit;
    private int nextSlot = 1; // slot 0 is the reserved fallback (the block atlas)
    private boolean warnedFull;

    RtBindlessSlotTable(IntSupplier limit) {
        this.limit = limit;
    }

    /**
     * Slot for a texture identified by its handle for as long as the registry lives. Returns 0 (the
     * fallback slot) for an unresolved handle or once the array is full.
     */
    int shared(long handle) {
        if (handle == 0L) {
            return 0;
        }
        Integer cached = byHandle.get(handle);
        if (cached != null) {
            return cached;
        }
        return allocate(handle, null);
    }

    /**
     * Slot for a texture that can be re-created at runtime, identified by the object that owns the image
     * ({@code owner}) rather than by {@code handle}. A previously unseen owner always takes a fresh slot
     * and queues its own descriptor write, even when {@code handle} is already mapped — that mapping
     * describes the texture this one has replaced. Returns 0 (fallback) if unresolvable or the array is
     * full.
     */
    int perOwner(Object owner, long handle) {
        if (owner == null) {
            return 0;
        }
        Integer known = byOwner.get(owner);
        if (known != null) {
            return known; // same live object: its slot already describes this exact image
        }
        if (handle == 0L) {
            return 0;
        }
        return allocate(handle, owner);
    }

    private int allocate(long handle, Object owner) {
        if (nextSlot >= limit.getAsInt()) {
            if (!warnedFull) {
                warnedFull = true;
                dev.comfyfluffy.caustica.CausticaMod.LOGGER.warn("RT entity textures: all {} bindless slots are in "
                        + "use; further entity textures fall back to the block atlas until the next resource "
                        + "reload (raise entities.textures.max-textures)", limit.getAsInt());
            }
            return 0; // full: fall back to slot 0 without caching, so a later reset can retry
        }
        int slot = nextSlot++;
        byHandle.put(handle, slot); // a re-created texture re-points its handle at the newer slot
        if (owner != null) {
            byOwner.put(owner, slot);
        }
        pending.add(new Write(slot, handle));
        return slot;
    }

    /**
     * Stop describing textures whose owner has been destroyed. Each such slot keeps its number (geometry
     * captured earlier may still carry it) but its descriptor is re-pointed at {@code fallbackHandle}, so
     * nothing can sample an image that no longer exists. The slot is not handed out again before
     * {@link #reset}; a replacement texture simply takes a fresh slot through {@link #perOwner}.
     *
     * <p>This is the one deliberate exception to "a slot's handle never changes": the old handle is about
     * to dangle, and a frame still in flight that reads the slot is better served by the fallback than by
     * freed memory.
     *
     * @return how many slots were re-pointed
     */
    int retireDeadOwners(java.util.function.Predicate<Object> dead, long fallbackHandle) {
        if (byOwner.isEmpty()) {
            return 0;
        }
        int retired = 0;
        for (java.util.Iterator<Map.Entry<Object, Integer>> it = byOwner.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Object, Integer> entry = it.next();
            if (!dead.test(entry.getKey())) {
                continue;
            }
            int slot = entry.getValue();
            it.remove();
            // The handle value may be reused by the driver for an unrelated image; forget the mapping so
            // a later shared() lookup cannot mistake that image for this slot.
            byHandle.values().removeIf(mapped -> mapped == slot);
            // A write still owed for the dead texture must not be performed after the fallback write.
            pending.removeIf(write -> write.slot() == slot);
            if (fallbackHandle != 0L) {
                pending.add(new Write(slot, fallbackHandle));
            }
            retired++;
        }
        return retired;
    }

    /** The descriptor writes owed since the last drain; the caller performs them and they are cleared. */
    List<Write> drainPending() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Write> drained = List.copyOf(pending);
        pending.clear();
        return drained;
    }

    boolean hasPending() {
        return !pending.isEmpty();
    }

    /** Number of slots handed out so far, excluding the reserved fallback slot 0. */
    int allocatedSlots() {
        return nextSlot - 1;
    }

    /** Drop every mapping; the next allocation starts again from slot 1. */
    void reset() {
        byHandle.clear();
        byOwner.clear();
        pending.clear();
        nextSlot = 1;
        warnedFull = false;
    }
}
