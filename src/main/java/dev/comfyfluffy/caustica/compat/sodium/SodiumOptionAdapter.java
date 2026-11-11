package dev.comfyfluffy.caustica.compat.sodium;

import dev.comfyfluffy.caustica.mixin.OptionInstanceAccessor;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.OptionInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Sodium-independent half of the settings bridge: describes one vanilla {@link OptionInstance} row in the
 * terms a deferred-apply settings screen needs (stable key, integer domain, value text, live load/save).
 *
 * <p>Caustica's option rows are created on demand and capture the config value at creation, so a row
 * object must never be kept as the source of truth. Every {@link #load()} and {@link #save(int)} therefore
 * asks {@link #source} for a fresh row set and finds its row again by caption key. A row that is absent
 * in the current state (some groups only list the rows of the selected upscaler or cloud style) simply
 * keeps its last known value and ignores writes, instead of failing.
 *
 * <p>No Sodium class is referenced here, which keeps this logic loadable and testable without Sodium.
 */
public final class SodiumOptionAdapter {
    /** How the row is presented. Everything is carried as an int: 0/1 for toggles, an index for cycles. */
    public enum Kind { TOGGLE, SLIDER, CYCLE }

    private final Supplier<OptionInstance<?>[]> source;
    private final String key;
    private final Kind kind;
    private final int min;
    private final int max;
    /** Registration-time value list of a cycle row; indices handed to the settings screen refer to it. */
    private final List<Object> cycleValues;
    private final Component caption;
    private final Function<Object, Component> valueLabel;
    private volatile int lastKnown;

    private SodiumOptionAdapter(Supplier<OptionInstance<?>[]> source, String key, Kind kind, int min, int max,
                                List<Object> cycleValues, Component caption,
                                Function<Object, Component> valueLabel, int initial) {
        this.source = source;
        this.key = key;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.cycleValues = cycleValues;
        this.caption = caption;
        this.valueLabel = valueLabel;
        this.lastKnown = Math.clamp(initial, min, max);
    }

    /**
     * Describe {@code row}, or return null when it cannot be represented (no translatable caption, an
     * unknown value-set type, or a domain with fewer than two values).
     */
    @SuppressWarnings("unchecked")
    public static SodiumOptionAdapter describe(Supplier<OptionInstance<?>[]> source, OptionInstance<?> row) {
        if (row == null) return null;
        OptionInstanceAccessor<Object> access = (OptionInstanceAccessor<Object>) (Object) row;
        Component caption = access.caustica$caption();
        String key = captionKey(caption);
        if (key == null) return null;
        Function<Object, Component> label = access.caustica$valueLabel();
        Object value = row.get();
        OptionInstance.ValueSet<?> values = row.values();
        if (values instanceof OptionInstance.IntRange range) {
            int min = range.minInclusive();
            int max = range.maxInclusive();
            if (min >= max || !(value instanceof Integer current)) return null;
            return new SodiumOptionAdapter(source, key, Kind.SLIDER, min, max, null, caption, label, current);
        }
        if (values instanceof OptionInstance.Enum<?> cycle) {
            List<Object> list = List.copyOf(cycle.values());
            if (list.size() < 2) return null;
            if (list.size() == 2 && list.contains(Boolean.TRUE) && list.contains(Boolean.FALSE)) {
                return new SodiumOptionAdapter(source, key, Kind.TOGGLE, 0, 1, null, caption, label,
                        Boolean.TRUE.equals(value) ? 1 : 0);
            }
            int index = list.indexOf(value);
            return new SodiumOptionAdapter(source, key, Kind.CYCLE, 0, list.size() - 1, list, caption, label,
                    Math.max(index, 0));
        }
        return null;
    }

    /** The translation key of a caption, or null for captions that are not plain translatable text. */
    static String captionKey(Component caption) {
        if (caption != null && caption.getContents() instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        return null;
    }

    /** Lower-case identifier path derived from the caption key; unique per key in practice. */
    public static String identifierPath(String key) {
        StringBuilder path = new StringBuilder(key.length() + 8);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                // Keep camelCase keys distinct from an all-lower-case sibling: fooBar -> foo_bar.
                path.append('_').append((char) (c + 32));
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'
                    || c == '/') {
                path.append(c);
            } else {
                path.append('_');
            }
        }
        return path.toString().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public Component caption() {
        return caption;
    }

    /** Explanatory text: the row's own {@code <key>.tooltip} entry when the language has one. */
    public Component tooltip() {
        String tooltipKey = key + ".tooltip";
        return Language.getInstance().has(tooltipKey) ? Component.translatable(tooltipKey) : caption;
    }

    /** Text for one value of the row's domain, without the "Caption: " prefix sliders prepend. */
    public Component valueText(int value) {
        try {
            Object domainValue = switch (kind) {
                case TOGGLE -> value != 0;
                case SLIDER -> Math.clamp(value, min, max);
                case CYCLE -> cycleValues.get(Math.clamp(value, min, max));
            };
            return stripCaption(valueLabel.apply(domainValue), caption);
        } catch (RuntimeException | LinkageError e) {
            return Component.literal(Integer.toString(value));
        }
    }

    /**
     * Vanilla sliders format as {@code options.generic_value(caption, value)} (or a sibling pattern with
     * the caption as first argument); cycle rows already return the bare value. Unwrap the former.
     */
    static Component stripCaption(Component label, Component caption) {
        if (label != null && label.getContents() instanceof TranslatableContents translatable) {
            Object[] args = translatable.getArgs();
            if (args.length == 2 && caption != null && caption.equals(args[0])) {
                Object value = args[1];
                return value instanceof Component component ? component : Component.literal(String.valueOf(value));
            }
        }
        return label != null ? label : Component.empty();
    }

    /** Current value of the row as the settings screen's int, always inside [min, max]. */
    public int load() {
        try {
            OptionInstance<?> row = find();
            if (row != null) {
                Object value = row.get();
                int loaded = switch (kind) {
                    case TOGGLE -> Boolean.TRUE.equals(value) ? 1 : 0;
                    case SLIDER -> value instanceof Integer i ? i : lastKnown;
                    case CYCLE -> {
                        int index = cycleValues.indexOf(value);
                        yield index >= 0 ? index : lastKnown;
                    }
                };
                lastKnown = Math.clamp(loaded, min, max);
            }
        } catch (RuntimeException | LinkageError e) {
            // Keep the last known value: a settings screen must never fail because one row could not be
            // read (LinkageError covers an optional vendor runtime that failed to load).
        }
        return lastKnown;
    }

    /** Apply a value chosen in the settings screen through the row's own change listener. */
    @SuppressWarnings("unchecked")
    public void save(int value) {
        int clamped = Math.clamp(value, min, max);
        try {
            OptionInstance<Object> row = (OptionInstance<Object>) find();
            if (row == null) return;
            Object domainValue = switch (kind) {
                case TOGGLE -> clamped != 0;
                case SLIDER -> clamped;
                case CYCLE -> cycleValues.get(clamped);
            };
            // OptionInstance.set validates against the row's current value set and only fires the listener
            // (which writes CausticaConfig and triggers any rebuild) when the value actually changes.
            row.set(domainValue);
            lastKnown = clamped;
        } catch (RuntimeException | LinkageError e) {
            // Thrown from inside Sodium's "Apply"; letting it escape would abort the other pending changes.
            dev.comfyfluffy.caustica.CausticaMod.LOGGER.warn("Could not apply Caustica setting {}", key, e);
        }
    }

    private OptionInstance<?> find() {
        OptionInstance<?>[] rows = source.get();
        if (rows == null) return null;
        for (OptionInstance<?> row : rows) {
            if (row == null) continue;
            Component rowCaption = ((OptionInstanceAccessor<?>) (Object) row).caustica$caption();
            if (key.equals(captionKey(rowCaption))) return row;
        }
        return null;
    }
}
