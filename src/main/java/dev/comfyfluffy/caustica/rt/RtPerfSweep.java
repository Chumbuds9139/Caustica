package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * One-key GPU cost breakdown of the ray tracing passes.
 *
 * <p>GPU timestamps can time a whole dispatch but not the pieces of one shader. This measures the pieces
 * by subtraction instead: it runs the scene with the player's own settings, then with one feature at a
 * time switched off or reduced, and reports how much GPU time each change removed. Every variant runs
 * {@value #WARMUP_FRAMES} frames to settle (the GPU timer reads results four frames late, and the TDR
 * guard steps bounces up over a few frames) and is then measured over {@value #MEASURE_FRAMES} frames,
 * reported as medians. Settings are changed in memory only, are restored after every variant and when
 * the sweep ends or is cancelled, and are never written to the config file.
 *
 * <p>The player should stand still: a moving camera changes the scene between variants. Movement of
 * more than {@value #MOVE_TOLERANCE_BLOCKS} blocks is flagged in the report.
 */
public final class RtPerfSweep {
    public static final RtPerfSweep INSTANCE = new RtPerfSweep();

    private static final int WARMUP_FRAMES = 45;
    private static final int MEASURE_FRAMES = 150;
    private static final double MOVE_TOLERANCE_BLOCKS = 2.0;

    private record Variant(String name, Runnable apply) {
    }

    private record Result(String name, double indirectMs, double primaryMs, double gpuFrameMs,
                          double frameMs, int samples, boolean moved) {
    }

    private final List<Variant> variants = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private Runnable restore;
    private int variantIndex = -1;
    private int frameInVariant;
    private long lastFrameNanos;
    private final double[] indirect = new double[MEASURE_FRAMES];
    private final double[] primary = new double[MEASURE_FRAMES];
    private final double[] gpuFrame = new double[MEASURE_FRAMES];
    private final double[] interval = new double[MEASURE_FRAMES];
    private int samples;
    private double pendingIndirect;
    private double pendingPrimary;
    private double pendingGpuFrame;
    private boolean pendingValid;
    private double startX;
    private double startY;
    private double startZ;
    private boolean moved;

    private RtPerfSweep() {
    }

    public boolean running() {
        return variantIndex >= 0;
    }

    /** Start a sweep, or cancel the one in progress. */
    public void toggle() {
        if (running()) {
            finish(true);
        } else {
            start();
        }
    }

    private void start() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || RtContext.currentOrNull() == null) {
            say("Caustica: join a world with ray tracing on before running the performance test.");
            return;
        }
        buildVariants();
        results.clear();
        startX = mc.player.getX();
        startY = mc.player.getY();
        startZ = mc.player.getZ();
        moved = false;
        beginVariant(0);
        int seconds = variants.size() * (WARMUP_FRAMES + MEASURE_FRAMES) / 20;
        say("Caustica performance test started: " + variants.size() + " settings, about " + seconds
                + " s at 20 FPS. Stand still and do not open menus. Press the key again to cancel.");
    }

    private void buildVariants() {
        variants.clear();
        int bounces = CausticaConfig.Rt.Composite.MAX_BOUNCES.value();
        int spp = CausticaConfig.Rt.Composite.SPP.value();
        int ris = CausticaConfig.Rt.Lights.RIS_CANDIDATES.value();
        boolean restir = CausticaConfig.Rt.Lights.RESTIR_SAMPLING.value();
        boolean sharc = CausticaConfig.Rt.Sharc.ENABLED.value();
        boolean clouds = CausticaConfig.Rt.Composite.CLOUDS.value();
        boolean fog = CausticaConfig.Rt.Composite.FOG_ENABLED.value();
        boolean parallax = CausticaConfig.Rt.Composite.PARALLAX_ENABLED.value();
        boolean sss = CausticaConfig.Rt.Composite.SSS.value();
        int giBounces = CausticaConfig.Rt.Entities.GI_BOUNCES.value();
        restore = () -> {
            CausticaConfig.Rt.Composite.MAX_BOUNCES.set(bounces);
            CausticaConfig.Rt.Composite.SPP.set(spp);
            CausticaConfig.Rt.Lights.RIS_CANDIDATES.set(ris);
            CausticaConfig.Rt.Lights.RESTIR_SAMPLING.set(restir);
            CausticaConfig.Rt.Sharc.ENABLED.set(sharc);
            CausticaConfig.Rt.Composite.CLOUDS.set(clouds);
            CausticaConfig.Rt.Composite.FOG_ENABLED.set(fog);
            CausticaConfig.Rt.Composite.PARALLAX_ENABLED.set(parallax);
            CausticaConfig.Rt.Composite.SSS.set(sss);
            CausticaConfig.Rt.Entities.GI_BOUNCES.set(giBounces);
        };
        variants.add(new Variant("your settings (baseline)", () -> { }));
        for (int b = bounces - 1; b >= 2; b--) {
            int target = b;
            variants.add(new Variant("max bounces " + bounces + " -> " + target,
                    () -> CausticaConfig.Rt.Composite.MAX_BOUNCES.set(target)));
        }
        if (spp > 1) {
            variants.add(new Variant("samples per pixel " + spp + " -> 1",
                    () -> CausticaConfig.Rt.Composite.SPP.set(1)));
        }
        if (ris > 0) {
            if (ris > 4) {
                variants.add(new Variant("RIS light candidates " + ris + " -> 4",
                        () -> CausticaConfig.Rt.Lights.RIS_CANDIDATES.set(4)));
            }
            variants.add(new Variant("RIS light sampling off (block lights via bounces only)",
                    () -> CausticaConfig.Rt.Lights.RIS_CANDIDATES.set(0)));
        }
        if (restir) {
            variants.add(new Variant("ReSTIR off", () -> CausticaConfig.Rt.Lights.RESTIR_SAMPLING.set(false)));
        }
        if (sharc) {
            variants.add(new Variant("SHaRC radiance cache off", () -> CausticaConfig.Rt.Sharc.ENABLED.set(false)));
        }
        if (clouds) {
            variants.add(new Variant("clouds off", () -> CausticaConfig.Rt.Composite.CLOUDS.set(false)));
        }
        if (fog) {
            variants.add(new Variant("fog off", () -> CausticaConfig.Rt.Composite.FOG_ENABLED.set(false)));
        }
        if (parallax) {
            variants.add(new Variant("parallax off", () -> CausticaConfig.Rt.Composite.PARALLAX_ENABLED.set(false)));
        }
        if (sss) {
            variants.add(new Variant("subsurface scattering off", () -> CausticaConfig.Rt.Composite.SSS.set(false)));
        }
        if (giBounces > 0) {
            variants.add(new Variant("entities out of indirect light (gi-bounces 0)",
                    () -> CausticaConfig.Rt.Entities.GI_BOUNCES.set(0)));
        }
        // The baseline once more, last: if it differs from the first run, the scene or the GPU clock
        // changed during the test and the differences above are not trustworthy.
        variants.add(new Variant("your settings again (drift check)", () -> { }));
    }

    private void beginVariant(int index) {
        restore.run();
        variantIndex = index;
        variants.get(index).apply.run();
        frameInVariant = 0;
        samples = 0;
        pendingValid = false;
        lastFrameNanos = 0L;
    }

    /** One GPU-timed frame's pass durations, as harvested by {@link RtGpuTimer}. */
    void onGpuStage(String stage, long nanos) {
        if (!running()) {
            return;
        }
        double ms = nanos / 1.0e6;
        switch (stage) {
            case "gpu.traceIndirect" -> pendingIndirect += ms;
            case "gpu.tracePrimary" -> pendingPrimary += ms;
            case "gpu.frame" -> {
                pendingGpuFrame += ms;
                pendingValid = true;
            }
            default -> { }
        }
    }

    /** Called once per composite frame on the render thread, after the timer harvested its results. */
    void onFrame() {
        if (!running()) {
            return;
        }
        long now = System.nanoTime();
        double frameMs = lastFrameNanos == 0L ? 0.0 : (now - lastFrameNanos) / 1.0e6;
        lastFrameNanos = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            double dx = mc.player.getX() - startX, dy = mc.player.getY() - startY, dz = mc.player.getZ() - startZ;
            if (dx * dx + dy * dy + dz * dz > MOVE_TOLERANCE_BLOCKS * MOVE_TOLERANCE_BLOCKS) {
                moved = true;
            }
        }
        frameInVariant++;
        if (frameInVariant > WARMUP_FRAMES && pendingValid && frameMs > 0.0 && samples < MEASURE_FRAMES) {
            indirect[samples] = pendingIndirect;
            primary[samples] = pendingPrimary;
            gpuFrame[samples] = pendingGpuFrame;
            interval[samples] = frameMs;
            samples++;
        }
        pendingIndirect = pendingPrimary = pendingGpuFrame = 0.0;
        pendingValid = false;
        if (samples >= MEASURE_FRAMES || frameInVariant > WARMUP_FRAMES + MEASURE_FRAMES * 3) {
            results.add(new Result(variants.get(variantIndex).name, median(indirect, samples),
                    median(primary, samples), median(gpuFrame, samples), median(interval, samples), samples,
                    moved));
            if (variantIndex + 1 < variants.size()) {
                beginVariant(variantIndex + 1);
            } else {
                finish(false);
            }
        }
    }

    /** Client tick: cancel if the world went away, so no setting stays changed. */
    public void tick(Minecraft mc) {
        if (running() && (mc.level == null || mc.player == null || RtContext.currentOrNull() == null)) {
            finish(true);
        }
    }

    private void finish(boolean cancelled) {
        if (restore != null) {
            restore.run();
        }
        variantIndex = -1;
        if (cancelled) {
            say("Caustica performance test cancelled; your settings are restored.");
            return;
        }
        String report = report();
        CausticaMod.LOGGER.info("\n{}", report);
        Path file = FabricLoader.getInstance().getGameDir().resolve("rt-frame-stats").resolve("perf-test-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, report, StandardCharsets.UTF_8);
            say("Caustica performance test done; settings restored. Report: rt-frame-stats/" + file.getFileName());
        } catch (IOException e) {
            say("Caustica performance test done; settings restored. Report is in latest.log.");
        }
    }

    String report() {
        StringBuilder sb = new StringBuilder("Caustica performance test (GPU medians, ms)\n");
        Result base = results.isEmpty() ? null : results.get(0);
        sb.append(String.format(Locale.ROOT, "%-52s %9s %9s %9s %9s %8s%n", "setting", "indirect", "saved",
                "primary", "RT frame", "FPS"));
        for (Result r : results) {
            double saved = base == null ? 0.0 : base.indirectMs - r.indirectMs;
            sb.append(String.format(Locale.ROOT, "%-52s %9.2f %+9.2f %9.2f %9.2f %8.1f%s%n", r.name, r.indirectMs,
                    -saved, r.primaryMs, r.gpuFrameMs, r.frameMs > 0 ? 1000.0 / r.frameMs : 0.0,
                    r.samples < MEASURE_FRAMES ? "  (only " + r.samples + " samples)" : ""));
        }
        if (results.size() >= 2) {
            Result last = results.get(results.size() - 1);
            double drift = Math.abs(last.indirectMs - base.indirectMs);
            sb.append(String.format(Locale.ROOT, "drift between first and last baseline: %.2f ms%s%n", drift,
                    drift > 0.1 * base.indirectMs ? " - TOO LARGE, the scene or GPU clocks changed; rerun" : ""));
        }
        if (moved) {
            sb.append("WARNING: the player moved during the test; differences mix setting cost with scene change.\n");
        }
        sb.append("'saved' is the indirect-pass time each change removes relative to the baseline "
                + "(negative = cheaper). FPS includes CPU time.\n");
        return sb.toString();
    }

    static double median(double[] values, int count) {
        if (count <= 0) {
            return 0.0;
        }
        double[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        return count % 2 == 1 ? copy[count / 2] : (copy[count / 2 - 1] + copy[count / 2]) * 0.5;
    }

    private static void say(String message) {
        CausticaMod.LOGGER.info(message);
        try {
            Minecraft.getInstance().gui.chatListener().handleSystemMessage(Component.literal(message), false);
        } catch (RuntimeException ignored) {
            // The log line above carries the message when there is no chat to show it in.
        }
    }
}
