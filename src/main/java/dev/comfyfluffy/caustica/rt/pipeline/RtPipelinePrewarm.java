package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;

/**
 * Compiles the world ray tracing pipeline on a background thread while the game is still at the title
 * screen. After an update the driver has no cached copy of the new shaders, and turning the path
 * tracer's raygen shaders into GPU code takes tens of seconds; started here, that time overlaps the menu
 * and the world loading screen instead of freezing the first frame in the world.
 *
 * <p>Nothing in the pipeline depends on the world: {@link RtPipeline.Spec} lists every input, and
 * {@link #take} hands the compiled pipeline over only for an equal spec on the same device. A compile
 * cannot be cancelled once the driver has it, so {@link #take} and {@link #discard} wait for one that is
 * still running rather than start a second.
 */
public final class RtPipelinePrewarm {
    private static final Object LOCK = new Object();
    /** The background compile, while it runs. */
    private static Thread worker;
    /** What was last started, so a finished or failed compile is not started again every tick. */
    private static RtPipeline.Spec startedSpec;
    /** A finished compile nobody has taken yet. */
    private static RtPipeline.Compiled result;
    private static long startNanos;

    private RtPipelinePrewarm() {
    }

    /** Starts compiling {@code spec} unless a compile is running or this spec was already started. */
    public static void start(RtContext ctx, RtPipeline.Spec spec) {
        synchronized (LOCK) {
            if (worker != null || spec.equals(startedSpec)) {
                return;
            }
            if (result != null) {
                // Compiled for settings that have changed since.
                result.destroy();
                result = null;
            }
            startedSpec = spec;
            startNanos = System.nanoTime();
            Thread thread = new Thread(() -> run(ctx, spec), "Caustica RT pipeline prewarm");
            thread.setDaemon(true);
            worker = thread;
            CausticaMod.LOGGER.info("Compiling the world RT pipeline in the background before a world is loaded");
            thread.start();
        }
    }

    private static void run(RtContext ctx, RtPipeline.Spec spec) {
        RtPipeline.Compiled compiled = null;
        try {
            compiled = RtPipeline.compile(ctx, spec, true);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Background RT pipeline compile failed; the world load compiles it instead", t);
        }
        synchronized (LOCK) {
            result = compiled;
            worker = null;
            LOCK.notifyAll();
        }
    }

    /**
     * The background compile of {@code spec}, waiting for it if it is still running, or null when there is
     * none or it was made for other settings (then it is destroyed and the caller compiles as before).
     */
    static RtPipeline.Compiled take(RtContext ctx, RtPipeline.Spec spec) {
        synchronized (LOCK) {
            long waitStart = System.nanoTime();
            boolean waited = worker != null;
            if (waited) {
                CausticaMod.LOGGER.info("Waiting for the background RT pipeline compile ({} ms in so far)",
                        (waitStart - startNanos) / 1_000_000L);
            }
            awaitWorker();
            RtPipeline.Compiled compiled = result;
            result = null;
            if (compiled == null) {
                return null;
            }
            if (compiled.vk().address() != ctx.vk().address() || !compiled.spec().equals(spec)) {
                CausticaMod.LOGGER.info("The background RT pipeline compile no longer matches the settings; "
                        + "compiling the world RT pipeline again");
                compiled.destroy();
                return null;
            }
            CausticaMod.LOGGER.info("Using the world RT pipeline compiled in the background (waited {} ms)",
                    waited ? (System.nanoTime() - waitStart) / 1_000_000L : 0L);
            return compiled;
        }
    }

    /**
     * Waits for a running compile and destroys an unused one. Called before RT shuts down, since the
     * device must not go away while the driver is still compiling on it.
     */
    public static void discard() {
        synchronized (LOCK) {
            if (worker != null) {
                CausticaMod.LOGGER.info("Waiting for the background RT pipeline compile before shutting RT down");
            }
            awaitWorker();
            if (result != null) {
                result.destroy();
                result = null;
            }
            startedSpec = null;
        }
    }

    /** Caller holds {@link #LOCK}. */
    private static void awaitWorker() {
        boolean interrupted = false;
        while (worker != null) {
            try {
                LOCK.wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
