package dev.comfyfluffy.caustica.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every stage and counter name the code reports must be registered in {@link RtFrameStats#FRAME}. A missing
 * one used to throw from inside the render loop as soon as frame statistics were switched on.
 */
class RtFrameStatsNamesTest {
    private static final Pattern CALL = Pattern.compile("FRAME\\.(stage|endStage|count)\\(");
    private static final Pattern GRAPHICS_WAIT = Pattern.compile("awaitGraphicsUse\\([^;]*?\"([A-Za-z]+)\"\\)");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"\\\\]+)\"");

    @Test
    void everyReportedNameIsRegistered() throws IOException {
        Set<String> stages = new TreeSet<>();
        Set<String> counters = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher call = CALL.matcher(source);
                while (call.find()) {
                    String firstArgument = firstArgument(source, call.end());
                    Matcher literal = LITERAL.matcher(firstArgument);
                    while (literal.find()) {
                        (call.group(1).equals("count") ? counters : stages).add(literal.group(1));
                    }
                }
                Matcher wait = GRAPHICS_WAIT.matcher(source);
                while (wait.find()) {
                    counters.add(wait.group(1));
                }
            }
        }
        assertTrue(stages.size() > 20, "the source scan found too few stage names: " + stages);
        Set<String> missingStages = new TreeSet<>(stages);
        missingStages.removeAll(RtFrameStats.frameStageNames());
        Set<String> missingCounters = new TreeSet<>(counters);
        missingCounters.removeAll(RtFrameStats.frameCounterNames());
        assertEquals(Set.of(), missingStages, "stage names reported but not registered");
        assertEquals(Set.of(), missingCounters, "counter names reported but not registered");
    }

    /** The text of the call's first argument: up to the first comma or ')' at nesting depth zero. */
    private static String firstArgument(String source, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '(' -> depth++;
                case ')' -> {
                    if (depth == 0) {
                        return source.substring(start, i);
                    }
                    depth--;
                }
                case ',' -> {
                    if (depth == 0) {
                        return source.substring(start, i);
                    }
                }
                default -> { }
            }
        }
        return source.substring(start);
    }

    @Test
    void registeredNamesAreUnique() {
        List<String> stages = RtFrameStats.frameStageNames();
        List<String> counters = RtFrameStats.frameCounterNames();
        assertEquals(stages.size(), Set.copyOf(stages).size());
        assertEquals(counters.size(), Set.copyOf(counters).size());
    }
}
