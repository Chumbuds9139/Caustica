package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;
import net.minecraft.client.Minecraft;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Arrays;

/**
 * The one-off performance measurement behind the recommended settings: about a minute of ordinary play,
 * once, when the recommended settings are applied. Nothing is measured before it is armed or after it
 * finishes, and the GPU timers only run while it is armed.
 *
 * <p>It records the GPU time of each ray-traced frame and how much of it the two trace passes took (the
 * part that grows with the number of traced pixels). A frame only counts when it shows what the settings
 * really cost:
 * <ul>
 *   <li>no screen open, not paused, the window focused, and no F8 test running;</li>
 *   <li>at least {@value #SETTLE_SECONDS} s after the measurement started (the settings it applied have
 *       settled and SHaRC's cache has warmed) and {@value #JOIN_SECONDS} s after joining a world;</li>
 *   <li>Shader Execution Reordering not held back, and no distant-terrain refresh streaming in (both
 *       pass by themselves and make frames far slower meanwhile);</li>
 *   <li>the TDR guard tracing at the configured samples and bounces (it starts low and climbs);</li>
 *   <li>BLAS builds taken out of the frame time (terrain streaming in while the player moves).</li>
 * </ul>
 * The result is an upper percentile, so a run that happens to face away from the sky does not read as
 * a faster GPU than it is. It also notes the Java garbage collector's pauses as a share of the time since
 * the first counted frame and how full the heap is after a collection, for the memory advice. How many
 * frames each condition turned away is kept for the log, so a measurement that never finishes can be
 * told apart from one that was merely slow to.
 */
public final class RtCalibration {
    public static final RtCalibration INSTANCE = new RtCalibration();

    static final int SAMPLES = 450;
    static final int SETTLE_SECONDS = 20;
    static final int JOIN_SECONDS = 30;
    /** The percentile of the frame times the result reports. */
    static final double PERCENTILE = 0.75;

    /**
     * What the measurement found.
     *
     * @param frameMs           GPU time of a ray-traced frame at {@link #PERCENTILE}, BLAS builds excluded
     * @param traceShare        median share of that time the two trace passes took
     * @param intervalMs        median wall time between ray-traced frames (includes CPU and pacing)
     * @param gcShare           share of the wall time from the first counted frame on spent in GC pauses
     * @param heapAfterGcShare  how full the old generation was after its last collection, 0..1 (NaN if unknown)
     * @param skipped           frames turned away, by reason, for the log
     */
    public record Result(int samples, double frameMs, double traceShare, double intervalMs, double gcShare,
                         double heapAfterGcShare, String skipped) {
    }

    private final double[] frame = new double[SAMPLES];
    private final double[] share = new double[SAMPLES];
    private final double[] interval = new double[SAMPLES];
    private volatile boolean armed;
    private int samples;
    private long startNanos;
    private long joinNanos;
    private Object lastLevel;
    private long lastFrameNanos;
    private long gcStartMillis;
    private long firstSampleNanos;
    private double pendingFrame;
    private double pendingTrace;
    private double pendingBlas;
    private boolean pendingValid;
    private Result result;
    /** Frames turned away: in a menu or unfocused, settling, a passing slowdown, the TDR guard held low, no timing. */
    private int skippedMenu;
    private int skippedSettling;
    private int skippedPassing;
    private int skippedGovernor;
    private int skippedTiming;

    private RtCalibration() {
    }

    /** Start (or restart) a measurement. */
    public synchronized void arm() {
        armed = true;
        samples = 0;
        result = null;
        startNanos = System.nanoTime();
        lastFrameNanos = 0L;
        firstSampleNanos = 0L;
        gcStartMillis = 0L;
        pendingFrame = pendingTrace = pendingBlas = 0.0;
        pendingValid = false;
        skippedMenu = skippedSettling = skippedPassing = skippedGovernor = skippedTiming = 0;
    }

    /** Stop without a result. */
    public synchronized void disarm() {
        armed = false;
    }

    public boolean armed() {
        return armed;
    }

    /** The GPU timers run while a measurement is armed. */
    boolean wantsTiming() {
        return armed;
    }

    /** The finished measurement, or null while it is still running (or was never armed). */
    public synchronized Result result() {
        return result;
    }

    /** Valid frames so far. */
    public synchronized int samples() {
        return samples;
    }

    /**
     * Whether the TDR guard's slow start kept the GPU from reaching the configured samples and bounces
     * for most of the frames that were otherwise fit to count: a GPU too slow to measure at these settings.
     */
    public synchronized boolean heldByGovernor() {
        return skippedGovernor > Math.max(SAMPLES, samples * 2);
    }

    /** Frames turned away so far, by reason, for the log. */
    public synchronized String skippedSummary() {
        return "menu/unfocused " + skippedMenu + ", settling " + skippedSettling + ", SER hold/DH refresh/F8 "
                + skippedPassing + ", TDR guard below settings " + skippedGovernor + ", no timing " + skippedTiming;
    }

    /** One GPU-timed stage of a frame, as harvested by {@link RtGpuTimer}. */
    void onGpuStage(String stage, long nanos) {
        if (!armed) {
            return;
        }
        double ms = nanos / 1.0e6;
        switch (stage) {
            case "gpu.traceIndirect", "gpu.tracePrimary" -> pendingTrace += ms;
            case "gpu.blasBuild" -> pendingBlas += ms;
            case "gpu.frame" -> {
                pendingFrame += ms;
                pendingValid = true;
            }
            default -> { }
        }
    }

    /** Once per ray-traced frame on the render thread, after the timer harvested its results. */
    synchronized void onFrame() {
        if (!armed) {
            return;
        }
        long now = System.nanoTime();
        double intervalMs = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) / 1.0e6;
        lastFrameNanos = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            joinNanos = now;
        }
        double frameMs = pendingFrame - pendingBlas;
        double traceMs = pendingTrace;
        boolean timed = pendingValid && frameMs > 0.0 && intervalMs > 0.0;
        pendingFrame = pendingTrace = pendingBlas = 0.0;
        pendingValid = false;
        if (!countsNow(mc, now)) {
            return;
        }
        if (!timed) {
            skippedTiming++;
            return;
        }
        if (samples >= SAMPLES) {
            return;
        }
        if (samples == 0) {
            // GC is counted from here: the world load before it allocates far more than play does.
            firstSampleNanos = now;
            gcStartMillis = gcMillis();
        }
        frame[samples] = frameMs;
        share[samples] = Math.clamp(traceMs / frameMs, 0.0, 1.0);
        interval[samples] = intervalMs;
        samples++;
        if (samples == SAMPLES) {
            finish(now);
        }
    }

    private boolean countsNow(Minecraft mc, long now) {
        if (mc.level == null || mc.gui.screen() != null || mc.isPaused() || !mc.isWindowActive()) {
            skippedMenu++;
            return false;
        }
        if (now - startNanos < SETTLE_SECONDS * 1_000_000_000L || now - joinNanos < JOIN_SECONDS * 1_000_000_000L) {
            skippedSettling++;
            return false;
        }
        if (RtPerfSweep.INSTANCE.running() || RtSerGuard.holding()
                || RtDistantHorizonsTerrain.INSTANCE.refreshStreaming()) {
            skippedPassing++;
            return false;
        }
        int userSpp = Math.max(RtLoadGovernor.MIN_SPP, CausticaConfig.Rt.Composite.SPP.value());
        int userBounces = Math.max(RtLoadGovernor.MIN_BOUNCES, CausticaConfig.Rt.Composite.MAX_BOUNCES.value());
        if (RtLoadGovernor.INSTANCE.spp() < userSpp || RtLoadGovernor.INSTANCE.bounces() < userBounces) {
            skippedGovernor++;
            return false;
        }
        return true;
    }

    private void finish(long now) {
        double wallMs = (now - firstSampleNanos) / 1.0e6;
        double gcShare = wallMs > 0.0 ? Math.clamp((gcMillis() - gcStartMillis) / wallMs, 0.0, 1.0) : 0.0;
        result = new Result(samples, percentile(frame, samples, PERCENTILE), percentile(share, samples, 0.5),
                percentile(interval, samples, 0.5), gcShare, heapAfterGcShare(), skippedSummary());
        armed = false;
    }

    static double percentile(double[] values, int count, double p) {
        if (count <= 0) {
            return Double.NaN;
        }
        double[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        int index = (int) Math.min(count - 1, Math.max(0, Math.round(p * (count - 1))));
        return sorted[index];
    }

    /**
     * Total GC pause time so far. ZGC and Shenandoah also report their concurrent cycles ("... Cycles"),
     * which run beside the game rather than stopping it; those are left out.
     */
    private static long gcMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName() != null && gc.getName().contains("Cycles")) {
                continue;
            }
            long t = gc.getCollectionTime();
            if (t > 0L) {
                total += t;
            }
        }
        return total;
    }

    /** The fullest heap pool right after its last collection (the old generation, in practice). */
    private static double heapAfterGcShare() {
        double fullest = Double.NaN;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) {
                continue;
            }
            MemoryUsage after = pool.getCollectionUsage();
            if (after == null || after.getMax() <= 0L) {
                continue;
            }
            double s = (double) after.getUsed() / after.getMax();
            fullest = Double.isNaN(fullest) ? s : Math.max(fullest, s);
        }
        return fullest;
    }
}
