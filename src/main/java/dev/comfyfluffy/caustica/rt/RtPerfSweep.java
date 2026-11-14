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
 * by subtraction instead: it runs the scene with the player's own settings, then with one change at a
 * time, and reports how much GPU time each change removed. The changes are kept few so the test stays
 * short: first the "try:" experiments, cheaper ways to render something that a setting could adopt
 * next; then the speed-up options switched off, which shows what each is still worth here (a positive
 * "saved" means it costs that much to have it off); then the expensive features switched off, as the
 * most any further work on them could win.
 *
 * <p>A laptop GPU's clock drifts by more than most single features cost while it heats up, so the
 * player's settings are measured again between every two variants and each variant is compared with the
 * mean of the two baselines on either side of it, which cancels any drift that is close to linear over
 * that span. Every step runs {@value #WARMUP_FRAMES} frames to settle (the GPU timer reads results four
 * frames late, and the TDR guard steps bounces up over a few frames) and is then measured over
 * {@value #MEASURE_FRAMES} frames, reported as medians. Settings are changed in memory only, are restored after every variant and when
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

    // Experiment bits of the row being measured (RtComposite.FEATURE_TRY_*); zero outside "try:" rows.
    private static volatile int experimentBits;

    private final List<Variant> variants = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private Runnable restore;
    /** The settings the test started from, printed at the top of the report. */
    private String context;
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

    /** Experiment bits for {@code WorldPush.featureFlags}: zero unless a "try:" row of the test is running. */
    public static int experimentBits() {
        return experimentBits;
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
        boolean clouds = CausticaConfig.Rt.Composite.CLOUDS.value();
        boolean fog = CausticaConfig.Rt.Composite.FOG_ENABLED.value();
        boolean volumetric = CausticaConfig.Rt.Composite.cloudStyleIndex() == 1;
        boolean simpleSky = CausticaConfig.Rt.Composite.SIMPLE_ROUGH_REFLECTION_SKY.value();
        boolean earlyRoulette = CausticaConfig.Rt.Composite.EARLY_ROULETTE.value();
        boolean fastClouds = CausticaConfig.Rt.Composite.FAST_VOLUMETRIC_CLOUDS.value();
        boolean sharc = CausticaConfig.Rt.Sharc.ENABLED.value();
        int sharcStart = CausticaConfig.Rt.Sharc.START_BOUNCE.value();
        String cloudText = clouds ? (volumetric ? "volumetric" : "classic") : "off";
        context = String.format(Locale.ROOT, "bounces %d, samples per pixel %d, RIS candidates %d, "
                + "clouds %s, fog %s, SHaRC %s", bounces, spp, ris, cloudText, fog ? "on" : "off",
                sharc ? "on from bounce " + sharcStart : "off");
        restore = () -> {
            experimentBits = 0;
            CausticaConfig.Rt.Sharc.START_BOUNCE.set(sharcStart);
            CausticaConfig.Rt.Composite.MAX_BOUNCES.set(bounces);
            CausticaConfig.Rt.Composite.SPP.set(spp);
            CausticaConfig.Rt.Composite.CLOUDS.set(clouds);
            CausticaConfig.Rt.Composite.FOG_ENABLED.set(fog);
            CausticaConfig.Rt.Composite.SIMPLE_ROUGH_REFLECTION_SKY.set(simpleSky);
            CausticaConfig.Rt.Composite.EARLY_ROULETTE.set(earlyRoulette);
            CausticaConfig.Rt.Composite.FAST_VOLUMETRIC_CLOUDS.set(fastClouds);
        };
        List<Variant> tests = new ArrayList<>();
        // Experiments, in the order of what they are expected to save. Both cut the path after the camera
        // hit: SHaRC by reading its cache at the first bounce's hit instead of shading it and tracing on,
        // the roulette by ending some of the camera hit's diffuse continuations before they are traced.
        boolean sharcEarlier = sharc && sharcStart > 1;
        if (sharcEarlier) {
            tests.add(new Variant("try: SHaRC from the first bounce", false,
                    () -> CausticaConfig.Rt.Sharc.START_BOUNCE.set(1)));
        }
        tests.add(new Variant("try: roulette at the camera hit (diffuse)", false,
                () -> experimentBits = RtComposite.FEATURE_TRY_CAMERA_HIT_ROULETTE));
        if (sharcEarlier) {
            tests.add(new Variant("try: both", false, () -> {
                CausticaConfig.Rt.Sharc.START_BOUNCE.set(1);
                experimentBits = RtComposite.FEATURE_TRY_CAMERA_HIT_ROULETTE;
            }));
        }
        // Fog's camera segment traces two shadow rays, one for the sun shafts and one for the torch the
        // surface was lit by; this traces one of the two per pixel, the other's light carried at 2x.
        if (fog) {
            tests.add(new Variant("try: one fog shadow ray instead of two", false,
                    () -> experimentBits = RtComposite.FEATURE_TRY_HALF_FOG_SHADOW_RAYS));
        }
        // What each speed-up option still saves here: the row turns it off, so a positive "saved" is its
        // worth. Rows for options that change nothing in this scene are left out.
        if (simpleSky) {
            tests.add(new Variant("simplified sky in rough reflections off", false,
                    () -> CausticaConfig.Rt.Composite.SIMPLE_ROUGH_REFLECTION_SKY.set(false)));
        }
        if (earlyRoulette && bounces >= 4) {
            tests.add(new Variant("early path termination off", false,
                    () -> CausticaConfig.Rt.Composite.EARLY_ROULETTE.set(false)));
        }
        if (fastClouds && clouds && volumetric) {
            tests.add(new Variant("fast volumetric clouds off", false,
                    () -> CausticaConfig.Rt.Composite.FAST_VOLUMETRIC_CLOUDS.set(false)));
        }
        // The most anything further could win: the expensive features switched off outright.
        if (clouds) {
            tests.add(new Variant("clouds off", false, () -> CausticaConfig.Rt.Composite.CLOUDS.set(false)));
        }
        if (fog) {
            tests.add(new Variant("fog off", false, () -> CausticaConfig.Rt.Composite.FOG_ENABLED.set(false)));
        }
        if (spp > 1) {
            tests.add(new Variant("samples per pixel " + spp + " -> 1", false,
                    () -> CausticaConfig.Rt.Composite.SPP.set(1)));
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
        String report = report(results, moved, context);
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
        return report(results, moved, context);
    }

    /**
     * Each variant against the mean of the baselines measured right before and right after it. The
     * baseline rows show how far the GPU drifted over the test; a variant whose two baselines differ by
     * more than its own difference is flagged, because that difference is then within the noise.
     */
    static String report(List<Result> results, boolean moved) {
        return report(results, moved, null);
    }

    static String report(List<Result> results, boolean moved, String context) {
        StringBuilder sb = new StringBuilder("Caustica performance test (GPU medians, ms)\n");
        if (context != null) {
            sb.append("settings: ").append(context).append('\n');
        }
        sb.append(String.format(Locale.ROOT, "%-52s %9s %9s %9s %9s %9s %8s%n", "setting", "indirect", "saved",
                "primary", "RT frame", "saved", "FPS"));
        double minBase = Double.MAX_VALUE;
        double maxBase = -Double.MAX_VALUE;
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            String note = r.samples < MEASURE_FRAMES ? "  (only " + r.samples + " samples)" : "";
            if (r.baseline) {
                minBase = Math.min(minBase, r.indirectMs);
                maxBase = Math.max(maxBase, r.indirectMs);
                sb.append(String.format(Locale.ROOT, "%-52s %9.2f %9s %9.2f %9.2f %9s %8.1f%s%n", r.name,
                        r.indirectMs, "", r.primaryMs, r.gpuFrameMs, "", fps(r), note));
                continue;
            }
            Result before = i > 0 && results.get(i - 1).baseline ? results.get(i - 1) : null;
            Result after = i + 1 < results.size() && results.get(i + 1).baseline ? results.get(i + 1) : null;
            double saved = aroundBaselines(before, after, Result::indirectMs) - r.indirectMs;
            double frameSaved = aroundBaselines(before, after, Result::gpuFrameMs) - r.gpuFrameMs;
            double spread = before != null && after != null
                    ? Math.abs(before.indirectMs - after.indirectMs) : Double.NaN;
            if (!Double.isNaN(spread) && spread > Math.abs(saved)) {
                note += String.format(Locale.ROOT, "  (noisy: baselines around it differ by %.2f)", spread);
            }
            sb.append(String.format(Locale.ROOT, "  %-50s %9.2f %+9.2f %9.2f %9.2f %+9.2f %8.1f%s%n", r.name,
                    r.indirectMs, -saved, r.primaryMs, r.gpuFrameMs, -frameSaved, fps(r), note));
        }
        if (maxBase >= minBase) {
            sb.append(String.format(Locale.ROOT, "baseline range over the test: %.2f - %.2f ms (drift %.2f ms)%n",
                    minBase, maxBase, maxBase - minBase));
        }
        if (moved) {
            sb.append("WARNING: the player moved during the test; differences mix setting cost with scene change.\n");
        }
        sb.append("'saved' is the time a change removes from the indirect pass and from the whole RT frame, "
                + "relative to the mean of the baselines measured just before and after it (negative = cheaper, "
                + "positive = the option turned off was saving that much). 'try:' rows are experiments that "
                + "change the picture only while they run. FPS includes CPU time.\n");
        return sb.toString();
    }

    /** Mean of the baselines on either side of a variant, or the one that exists; NaN with neither. */
    private static double aroundBaselines(Result before, Result after,
                                          java.util.function.ToDoubleFunction<Result> value) {
        if (before != null && after != null) {
            return (value.applyAsDouble(before) + value.applyAsDouble(after)) * 0.5;
        }
        if (before != null || after != null) {
            return value.applyAsDouble(before != null ? before : after);
        }
        return Double.NaN;
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
