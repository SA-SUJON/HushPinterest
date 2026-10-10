/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushpinterest.extension.pinterest.ads;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import app.hushpinterest.extension.shared.Logger;
import app.hushpinterest.extension.shared.diagnostics.HookStatus;

/**
 * The pins a feed module carries inside it (#6).
 *
 * <p>A module, such as a row of ideas on Home or a group of products under a pin, is one item of the
 * page {@link FeedFilter} sees, and its pins sit in a list of its own. Pinterest's renderers read
 * that list straight from a field with no JSON name, so it's found by what it is: a list a story
 * model holds. Each entry is a model, or a wrapper holding one. What the filters say goes is left out
 * of a copy, which replaces the module's list. A module whose list ends up empty goes too, so no
 * heading is left over an empty row.
 *
 * <p>One level only: a module inside a module is judged as an item, not opened.
 */
final class ModuleItems {
    private ModuleItems() {}

    /** The family that takes [model] out, or null to keep it. */
    interface Verdict {
        @Nullable
        String family(Object model);
    }

    private static final Map<Class<?>, List<Field>> LISTS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<Field>> SLOTS = new ConcurrentHashMap<>();

    /** True when [item] is a module: a model with a story_type. */
    static boolean isModule(Object item) {
        return item != null && ModelFields.of(item.getClass()).containsKey(Ads.STORY_TYPE);
    }

    /**
     * Takes what [verdict] names out of each of [module]'s lists, counting each one under its family.
     * Returns the family of the first entry taken from a list that was left empty, since the module
     * has nothing left to show, or null to keep the module. Never throws: a failure is reported under
     * [owner] and leaves the rest of the module as it was.
     */
    @Nullable
    static String prune(Object module, Verdict verdict, String owner) {
        String emptied = null;
        for (Field field : lists(module.getClass())) {
            try {
                Object value = field.get(module);
                if (!(value instanceof List)) continue;
                List<?> entries = (List<?>) value;
                List<Object> kept = null;
                List<String> taken = null;
                int size = entries.size();
                for (int i = 0; i < size; i++) {
                    Object entry = entries.get(i);
                    Object model = model(entry);
                    String family = model == null ? null : verdict.family(model);
                    if (family != null) {
                        if (kept == null) {
                            kept = new ArrayList<>(entries.subList(0, i));
                            taken = new ArrayList<>();
                        }
                        taken.add(family);
                    } else if (kept != null) {
                        kept.add(entry);
                    }
                }
                if (kept == null) continue;
                field.set(module, kept);
                // Counted once the module holds the shorter list, so a list that couldn't be replaced counts nothing.
                for (String family : taken) HookStatus.counted(family, removal(family));
                if (kept.isEmpty() && emptied == null) emptied = taken.get(0);
            } catch (Throwable t) {
                HookStatus.threw(owner, "module filter", t);
            }
        }
        String gone = emptied;
        Logger.printDebug(() -> describe(module) + (gone != null ? ", left empty" : ""));
        return emptied;
    }

    /** Commercial-sounding JSON names, for the Debug logging line only. */
    private static final Pattern COMMERCIAL = Pattern.compile(
            "promot|sponsor|campaign|advertis|^ad_|_ad$|_ads?_|_ads$|shop|merchant|affiliate|featured|boost|third_party|partner"
                    + "|catalog|integration|match_reason|targeting|price");

    /**
     * [module]'s story type and each list it carries after filtering: its size, and for each model
     * kept, its class and the commercial fields it has set. Built only with Debug logging on, so a bug
     * report about an ad that got through says what the module held (#6).
     */
    static String describe(Object module) {
        StringBuilder line = new StringBuilder("Module ");
        line.append(ModelFields.read(ModelFields.of(module.getClass()), module, Ads.STORY_TYPE));
        for (Field field : lists(module.getClass())) {
            Object value;
            try {
                value = field.get(module);
            } catch (IllegalAccessException | RuntimeException unreadable) {
                continue;
            }
            if (!(value instanceof List) || ((List<?>) value).isEmpty()) continue;
            List<?> entries = (List<?>) value;
            line.append("; ").append(field.getName()).append(' ').append(entries.size()).append(':');
            int shown = 0;
            for (Object entry : entries) {
                Object model = model(entry);
                if (model == null) continue;
                if (shown++ == 8) {
                    line.append(" ...");
                    break;
                }
                line.append(' ').append(model.getClass().getSimpleName()).append(markers(model));
            }
        }
        return line.toString();
    }

    /** The commercial fields [model] has set, as {@code [name=value,...]} in name order, or nothing. */
    static String markers(Object model) {
        if (model == null) return "";
        StringBuilder set = new StringBuilder();
        for (Map.Entry<String, Field> field : new TreeMap<>(ModelFields.of(model.getClass())).entrySet()) {
            if (!COMMERCIAL.matcher(field.getKey()).find()) continue;
            Object value = ModelFields.read(ModelFields.of(model.getClass()), model, field.getKey());
            if (value == null || Boolean.FALSE.equals(value)) continue;
            String shown = value instanceof Boolean || value instanceof Number || value instanceof Enum ? String.valueOf(value) : "set";
            set.append(set.length() == 0 ? "[" : ",").append(field.getKey()).append('=').append(shown);
        }
        return set.length() == 0 ? "" : set.append(']').toString();
    }

    /** What a removal from inside a module is counted as. English, for the report. */
    static String removal(String family) {
        return FeedFilter.removal(family) + " from a module";
    }

    /** [entry] when it's a model, the model a wrapper holds, or null for anything else. */
    @Nullable
    static Object model(@Nullable Object entry) {
        if (entry == null) return null;
        Class<?> type = entry.getClass();
        if (!ModelFields.of(type).isEmpty()) return entry;
        for (Field slot : slots(type)) {
            Object held;
            try {
                held = slot.get(entry);
            } catch (IllegalAccessException | RuntimeException unreadable) {
                continue;
            }
            if (held != null && !ModelFields.of(held.getClass()).isEmpty()) return held;
        }
        return null;
    }

    /**
     * The fields of [type] that hold a list the filter can swap for its own copy: declared as List,
     * AbstractList or ArrayList, and not final.
     */
    private static List<Field> lists(Class<?> type) {
        List<Field> known = LISTS.get(type);
        if (known != null) return known;
        List<Field> found = new ArrayList<>();
        for (Field field : fields(type)) {
            Class<?> declared = field.getType();
            if (Modifier.isFinal(field.getModifiers())) continue;
            if (List.class.isAssignableFrom(declared) && declared.isAssignableFrom(ArrayList.class)) found.add(field);
        }
        List<Field> fixed = found.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(found);
        LISTS.put(type, fixed);
        return fixed;
    }

    /** The fields of a wrapper that can hold a model: everything but primitives. */
    private static List<Field> slots(Class<?> type) {
        List<Field> known = SLOTS.get(type);
        if (known != null) return known;
        List<Field> found = new ArrayList<>();
        for (Field field : fields(type)) {
            if (!field.getType().isPrimitive()) found.add(field);
        }
        List<Field> fixed = found.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(found);
        SLOTS.put(type, fixed);
        return fixed;
    }

    /** Every instance field of [type] and its superclasses that opens, stopping at the platform's own classes. */
    private static List<Field> fields(Class<?> type) {
        List<Field> found = new ArrayList<>();
        for (Class<?> at = type; at != null && !platform(at); at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    found.add(field);
                } catch (RuntimeException inaccessible) {
                    // A field the runtime won't open is one this filter leaves alone.
                }
            }
        }
        return found;
    }

    /** Java's, Android's and Kotlin's own classes: never a Pinterest model or wrapper. */
    private static boolean platform(Class<?> type) {
        String name = type.getName();
        return type.isPrimitive() || type.isArray() || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("kotlin.")
                || name.startsWith("dalvik.");
    }

    /** Forgets every class read so far. */
    static void clearForTests() {
        LISTS.clear();
        SLOTS.clear();
    }
}
