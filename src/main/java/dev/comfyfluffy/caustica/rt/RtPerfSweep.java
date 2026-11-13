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
 * time switched off or reduced, and reports how much GPU time each change removed. A laptop GPU's clock
 * drifts by more than most single features cost while it heats up, so the player's settings are measured
 * again between every two variants and each variant is compared with the mean of the two baselines on
 * either side of it, which cancels any drift that is close to linear over that span. Every step runs
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
    private static final int MEASURE_FRAMES = 120;
    private static final double MOVE_TOLERANCE_BLOCKS = 2.0;

    private record Variant(String name, boolean baseline, Runnable apply) {
    }

    record Result(String name, boolean baseline, double indirectMs, double primaryMs, double gpuFrameMs,
                  double frameMs, int samples) {
    }

    private final List<Variant> variants = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private Runnable restore;
    // Shader probe bits of the row being measured (RtComposite.FEATURE_PROBE_*); zero outside probe rows.
    private static volatile int probeBits;

    /** Probe bits for {@code WorldPush.featureFlags}: zero unless a probe row of the test is running. */
    public static int probeFeatureBits() {
        return probeBits;
    }
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
            say(Component.translatable("caustica.options.perfTest.needWorld"), false);
            return;
        }
        buildVariants();
        results.clear();
        startX = mc.player.getX();
        startY = mc.player.getY();
        startZ = mc.player.getZ();
        moved = false;
        int seconds = variants.size() * (WARMUP_FRAMES + MEASURE_FRAMES) / 20;
        say(Component.translatable("caustica.options.perfTest.started", variants.size(), seconds), false);
        beginVariant(0);
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
            probeBits = 0;
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
        List<Variant> tests = new ArrayList<>();
        for (int b = bounces - 1; b >= 2; b--) {
            int target = b;
            tests.add(new Variant("max bounces " + bounces + " -> " + target, false,
                    () -> CausticaConfig.Rt.Composite.MAX_BOUNCES.set(target)));
        }
        if (spp > 1) {
            tests.add(new Variant("samples per pixel " + spp + " -> 1", false,
                    () -> CausticaConfig.Rt.Composite.SPP.set(1)));
        }
        if (ris > 0) {
            if (ris > 4) {
                tests.add(new Variant("RIS light candidates " + ris + " -> 4", false,
                        () -> CausticaConfig.Rt.Lights.RIS_CANDIDATES.set(4)));
            }
            tests.add(new Variant("RIS light sampling off (block lights via bounces only)", false,
                    () -> CausticaConfig.Rt.Lights.RIS_CANDIDATES.set(0)));
        }
        if (ris > 0) {
            // Probes: each removes one piece of emitter lighting to show where its time goes. They are
            // measurements, not settings anyone could play with (the picture is wrong while they run).
            tests.add(new Variant("probe: emitter shadow rays skipped", false,
                    () -> probeBits = RtComposite.FEATURE_PROBE_RIS_UNSHADOWED));
            tests.add(new Variant("probe: emitter lighting at first hit only", false,
                    () -> probeBits = RtComposite.FEATURE_PROBE_RIS_FIRST_VERTEX));
            if (fog) {
                tests.add(new Variant("probe: emitter light in fog skipped", false,
                        () -> probeBits = RtComposite.FEATURE_PROBE_NO_FOG_EMITTER));
            }
        }
        if (restir) {
            tests.add(new Variant("ReSTIR off", false, () -> CausticaConfig.Rt.Lights.RESTIR_SAMPLING.set(false)));
        }
        if (sharc) {
            tests.add(new Variant("SHaRC radiance cache off", false, () -> CausticaConfig.Rt.Sharc.ENABLED.set(false)));
        }
        if (clouds) {
            tests.add(new Variant("clouds off", false, () -> CausticaConfig.Rt.Composite.CLOUDS.set(false)));
        }
        if (fog) {
            tests.add(new Variant("fog off", false, () -> CausticaConfig.Rt.Composite.FOG_ENABLED.set(false)));
        }
        if (parallax) {
            tests.add(new Variant("parallax off", false, () -> CausticaConfig.Rt.Composite.PARALLAX_ENABLED.set(false)));
        }
        if (sss) {
            tests.add(new Variant("subsurface scattering off", false, () -> CausticaConfig.Rt.Composite.SSS.set(false)));
        }
        if (giBounces > 0) {
            tests.add(new Variant("entities out of indirect light (gi-bounces 0)", false,
                    () -> CausticaConfig.Rt.Entities.GI_BOUNCES.set(0)));
        }
        // Baseline, test, baseline, test, ..., baseline: every test sits between two baselines.
        variants.add(baselineVariant());
        for (Variant test : tests) {
            variants.add(test);
            variants.add(baselineVariant());
        }
    }

    private static Variant baselineVariant() {
        return new Variant("your settings (baseline)", true, () -> { });
    }

    private void beginVariant(int index) {
        restore.run();
        variantIndex = index;
        variants.get(index).apply.run();
        frameInVariant = 0;
        samples = 0;
        pendingValid = false;
        lastFrameNanos = 0L;
        Variant v = variants.get(index);
        say(Component.translatable("caustica.options.perfTest.progress", index + 1, variants.size(),
                v.baseline ? Component.translatable("caustica.options.perfTest.baseline")
                        : Component.literal(v.name)), true);
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
            Variant v = variants.get(variantIndex);
            results.add(new Result(v.name, v.baseline, median(indirect, samples), median(primary, samples),
                    median(gpuFrame, samples), median(interval, samples), samples));
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
            say(Component.translatable("caustica.options.perfTest.cancelled"), false);
            return;
        }
        String report = report(results, moved);
        CausticaMod.LOGGER.info("\n{}", report);
        Path file = FabricLoader.getInstance().getGameDir().resolve("rt-frame-stats").resolve("perf-test-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".txt");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, report, StandardCharsets.UTF_8);
            say(Component.translatable("caustica.options.perfTest.done", file.getFileName().toString()), false);
        } catch (IOException e) {
            say(Component.translatable("caustica.options.perfTest.doneLog"), false);
        }
    }

    String report() {
        return report(results, moved);
    }

    /**
     * Each variant against the mean of the baselines measured right before and right after it. The
     * baseline rows show how far the GPU drifted over the test; a variant whose two baselines differ by
     * more than its own difference is flagged, because that difference is then within the noise.
     */
    static String report(List<Result> results, boolean moved) {
        StringBuilder sb = new StringBuilder("Caustica performance test (GPU medians, ms)\n");
        sb.append(String.format(Locale.ROOT, "%-52s %9s %9s %9s %9s %8s%n", "setting", "indirect", "saved",
                "primary", "RT frame", "FPS"));
        double minBase = Double.MAX_VALUE;
        double maxBase = -Double.MAX_VALUE;
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            String note = r.samples < MEASURE_FRAMES ? "  (only " + r.samples + " samples)" : "";
            if (r.baseline) {
                minBase = Math.min(minBase, r.indirectMs);
                maxBase = Math.max(maxBase, r.indirectMs);
                sb.append(String.format(Locale.ROOT, "%-52s %9.2f %9s %9.2f %9.2f %8.1f%s%n", r.name,
                        r.indirectMs, "", r.primaryMs, r.gpuFrameMs, fps(r), note));
                continue;
            }
            Result before = i > 0 && results.get(i - 1).baseline ? results.get(i - 1) : null;
            Result after = i + 1 < results.size() && results.get(i + 1).baseline ? results.get(i + 1) : null;
            double base = Double.NaN;
            double spread = Double.NaN;
            if (before != null && after != null) {
                base = (before.indirectMs + after.indirectMs) * 0.5;
                spread = Math.abs(before.indirectMs - after.indirectMs);
            } else if (before != null || after != null) {
                base = (before != null ? before : after).indirectMs;
            }
            double saved = base - r.indirectMs;
            if (!Double.isNaN(spread) && spread > Math.abs(saved)) {
                note += String.format(Locale.ROOT, "  (noisy: baselines around it differ by %.2f)", spread);
            }
            sb.append(String.format(Locale.ROOT, "  %-50s %9.2f %+9.2f %9.2f %9.2f %8.1f%s%n", r.name,
                    r.indirectMs, -saved, r.primaryMs, r.gpuFrameMs, fps(r), note));
        }
        if (maxBase >= minBase) {
            sb.append(String.format(Locale.ROOT, "baseline range over the test: %.2f - %.2f ms (drift %.2f ms)%n",
                    minBase, maxBase, maxBase - minBase));
        }
        if (moved) {
            sb.append("WARNING: the player moved during the test; differences mix setting cost with scene change.\n");
        }
        sb.append("'saved' is the indirect-pass time a change removes relative to the mean of the baselines "
                + "measured just before and after it (negative = cheaper). FPS includes CPU time.\n");
        return sb.toString();
    }

    private static double fps(Result r) {
        return r.frameMs > 0 ? 1000.0 / r.frameMs : 0.0;
    }

    static double median(double[] values, int count) {
        if (count <= 0) {
            return 0.0;
        }
        double[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        return count % 2 == 1 ? copy[count / 2] : (copy[count / 2 - 1] + copy[count / 2]) * 0.5;
    }

    private static void say(Component message, boolean actionBar) {
        CausticaMod.LOGGER.info(message.getString());
        try {
            Minecraft.getInstance().gui.chatListener().handleSystemMessage(message, actionBar);
        } catch (RuntimeException ignored) {
            // The log line above carries the message when there is no chat to show it in.
        }
    }
}
