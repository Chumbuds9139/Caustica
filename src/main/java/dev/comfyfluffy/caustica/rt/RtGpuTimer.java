package dev.comfyfluffy.caustica.rt;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

/**
 * GPU time per ray tracing pass, for frame statistics.
 *
 * <p>The stage timings in {@link RtFrameStats} measure the render thread; when the GPU is the bottleneck
 * they say nothing about where its time goes. While frame statistics are on, every {@link RtDebugLabels}
 * scope recorded into the composite command buffer also writes a timestamp pair, and the durations are
 * added to the frame profile as {@code gpu.*} stages. The results of a frame are read back
 * {@value #RING} frames later, when Minecraft has already waited for that submission, so reading never
 * stalls. Off, this class costs one branch per scope.
 */
public final class RtGpuTimer {
    private static final int RING = 4;
    private static final int MAX_QUERIES = 64;

    private static long pool;
    private static float periodNanos;
    private static int frame;
    private static VkCommandBuffer frameCmd;
    private static int nextQuery;
    private static final String[][] STAGES = new String[RING][MAX_QUERIES / 2];
    private static final int[] STAGE_COUNT = new int[RING];
    private static final boolean[] RECORDED = new boolean[RING];

    private RtGpuTimer() {
    }

    /** Start timing a composite frame recorded into {@code cmd}; harvests the slot's previous frame first. */
    public static void beginFrame(RtContext ctx, VkCommandBuffer cmd) {
        frameCmd = null;
        boolean sweep = RtPerfSweep.INSTANCE.running();
        if (!(RtFrameStats.enabled() || sweep) || ctx == null || ctx.timestampPeriodNanos() <= 0.0f) {
            return;
        }
        if (pool == 0L && !create(ctx)) {
            return;
        }
        frame = (frame + 1) % RING;
        harvest(ctx, frame);
        RtPerfSweep.INSTANCE.onFrame();
        VK10.vkCmdResetQueryPool(cmd, pool, frame * MAX_QUERIES, MAX_QUERIES);
        STAGE_COUNT[frame] = 0;
        RECORDED[frame] = true;
        nextQuery = 0;
        frameCmd = cmd;
    }

    /** End timing for the current frame; scopes recorded after this are not timed. */
    public static void endFrame() {
        frameCmd = null;
    }

    /**
     * Open a timed region named after an {@link RtDebugLabels} label. Returns the query index to pass to
     * {@link #close}, or -1 when the region is not timed (statistics off, another command buffer, an
     * unprofiled label, or the frame's query budget is spent).
     */
    static int open(VkCommandBuffer cmd, String label) {
        if (cmd != frameCmd || frameCmd == null) {
            return -1;
        }
        String stage = stageFor(label);
        if (stage == null || nextQuery + 2 > MAX_QUERIES) {
            return -1;
        }
        int query = nextQuery;
        nextQuery += 2;
        STAGES[frame][query / 2] = stage;
        STAGE_COUNT[frame] = query / 2 + 1;
        VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, frame * MAX_QUERIES + query);
        return query;
    }

    static void close(VkCommandBuffer cmd, int query) {
        if (query < 0) {
            return;
        }
        VK10.vkCmdWriteTimestamp(cmd, VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool,
                frame * MAX_QUERIES + query + 1);
    }

    /** The {@code gpu.*} frame-statistics stage a label is reported under, or null to leave it untimed. */
    static String stageFor(String label) {
        if (label.equals("composite frame")) return "gpu.frame";
        if (label.startsWith("BLAS builds")) return "gpu.blasBuild";
        if (label.startsWith("frame TLAS")) return "gpu.tlasBuild";
        if (label.equals("trace rays (raygen 0)")) return "gpu.tracePrimary";
        // Raygen 2 is the indirect pass built without reordering, run while the SER guard holds it back.
        if (label.equals("trace rays (raygen 1)") || label.equals("trace rays (raygen 2)")) {
            return "gpu.traceIndirect";
        }
        if (label.equals("DLSS-RR evaluate")) return "gpu.dlssRr";
        if (label.equals("NRD denoise")) return "gpu.nrd";
        if (label.equals("SVGF denoise")) return "gpu.svgf";
        if (label.endsWith("upscale")) return "gpu.upscale";
        if (label.equals("exposure")) return "gpu.exposure";
        if (label.equals("rt bloom")) return "gpu.bloom";
        if (label.equals("map RT to display")) return "gpu.display";
        return null;
    }

    private static boolean create(RtContext ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default()
                    .queryType(VK10.VK_QUERY_TYPE_TIMESTAMP).queryCount(RING * MAX_QUERIES);
            LongBuffer out = stack.mallocLong(1);
            if (VK10.vkCreateQueryPool(ctx.vk(), info, null, out) != VK10.VK_SUCCESS) {
                return false;
            }
            pool = out.get(0);
            periodNanos = ctx.timestampPeriodNanos();
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_QUERY_POOL, pool, "GPU pass timer");
            return true;
        }
    }

    /** Add the durations of the frame last recorded into {@code slot} to the current frame's profile. */
    private static void harvest(RtContext ctx, int slot) {
        if (!RECORDED[slot]) {
            return;
        }
        RECORDED[slot] = false;
        int stages = STAGE_COUNT[slot];
        if (stages == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Value + availability per query; no WAIT flag, so an unfinished frame is skipped, not waited on.
            LongBuffer data = stack.mallocLong(stages * 2 * 2);
            int result = VK10.vkGetQueryPoolResults(ctx.vk(), pool, slot * MAX_QUERIES, stages * 2, data,
                    16L, VK10.VK_QUERY_RESULT_64_BIT | VK10.VK_QUERY_RESULT_WITH_AVAILABILITY_BIT);
            if (result != VK10.VK_SUCCESS && result != VK10.VK_NOT_READY) {
                return;
            }
            for (int i = 0; i < stages; i++) {
                long begin = data.get(i * 4);
                long beginReady = data.get(i * 4 + 1);
                long end = data.get(i * 4 + 2);
                long endReady = data.get(i * 4 + 3);
                if (beginReady == 0L || endReady == 0L || end < begin) {
                    continue;
                }
                long nanos = (long) ((end - begin) * (double) periodNanos);
                RtFrameStats.FRAME.addStageNanos(STAGES[slot][i], nanos);
                RtPerfSweep.INSTANCE.onGpuStage(STAGES[slot][i], nanos);
            }
        }
    }

    /** Release the query pool (device teardown). */
    public static void destroy(RtContext ctx) {
        if (pool != 0L && ctx != null) {
            VK10.vkDestroyQueryPool(ctx.vk(), pool, null);
        }
        pool = 0L;
        frameCmd = null;
        java.util.Arrays.fill(RECORDED, false);
    }
}
