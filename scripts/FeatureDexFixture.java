import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small compiled outputs with safe registers but incorrect family installation or mutation contracts. */
public final class FeatureDexFixture {
    private static final String BASE = "Lapp/hushpinterest/extension/pinterest/";
    private static final String STATUS = BASE + "settings/SettingsStatus;";
    private static final String ENTRY = BASE + "settings/SettingsEntry;";
    private static final String UTILS = "Lapp/hushpinterest/extension/shared/Utils;";
    private static final String APP = "Lcom/pinterest/ReleaseHiltApplication;";
    private static final String ACTIVITY = "Lcom/pinterest/activity/PinterestActivity;";
    private static final String OBSERVER = "Lfixture/ScreenshotObserver;";
    private static final String FEED = "Lfixture/FeedPage;";
    private static final String SENDER = "Lfixture/OutgoingText;";
    private static final String VOID = "V";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String LIST = "Ljava/util/List;";
    private static final String CONTEXT = "Landroid/content/Context;";
    private static final String SCREENSHOT = BASE + "ui/UiHooks;";
    private static final String FILTER = BASE + "ads/FeedFilter;";
    private static final String TRACKING = BASE + "privacy/LinkTracking;";
    private static final String INTENT = "Landroid/content/Intent;";
    private static final String CLIPBOARD = "Landroid/content/ClipData;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String TEXT = "Ljava/lang/CharSequence;";
    private static final Map<String, String[]> FAMILIES = new LinkedHashMap<>();
    private static final Map<String, Boolean> FLAGS = new LinkedHashMap<>();

    private static ImmutableMethodReference ref(String type, String name, String result, String... parameters) {
        return new ImmutableMethodReference(type, name, Arrays.asList(parameters), result);
    }

    private static Instruction invoke(Opcode opcode, ImmutableMethodReference ref, int... registers) {
        int[] words = new int[5];
        System.arraycopy(registers, 0, words, 0, registers.length);
        return new ImmutableInstruction35c(opcode, registers.length, words[0], words[1], words[2], words[3], words[4], ref);
    }

    private static Method method(String owner, String name, String result, boolean isStatic, int registers, List<Instruction> body, String... parameters) {
        List<ImmutableMethodParameter> types = new ArrayList<>();
        for (String parameter : parameters) types.add(new ImmutableMethodParameter(parameter, Set.of(), null));
        return new ImmutableMethod(owner, name, types, result,
                AccessFlags.PUBLIC.getValue() | (isStatic ? AccessFlags.STATIC.getValue() : 0), Set.of(), Set.of(),
                new ImmutableMethodImplementation(registers, body, List.of(), List.of()));
    }

    private static ClassDef type(String owner, List<Method> methods) {
        return new ImmutableClassDef(owner, AccessFlags.PUBLIC.getValue(), OBJECT, List.of(), null, Set.of(), List.of(), methods);
    }

    private static Instruction end() { return new ImmutableInstruction10x(Opcode.RETURN_VOID); }
    private static Instruction literal(String text, int register) {
        return new ImmutableInstruction21c(Opcode.CONST_STRING, register, new ImmutableStringReference(text));
    }

    private static Map<String, List<Method>> hosts(boolean patched) {
        Map<String, List<Method>> classes = new LinkedHashMap<>();
        List<Instruction> app = new ArrayList<>();
        if (patched) {
            app.add(invoke(Opcode.INVOKE_STATIC, ref(UTILS, "setContext", VOID, CONTEXT), 0));
            app.add(invoke(Opcode.INVOKE_STATIC, ref(ENTRY, "onApplicationCreate", VOID, CONTEXT), 0));
        }
        app.add(end());
        classes.put(APP, new ArrayList<>(List.of(method(APP, "onCreate", VOID, false, 1, app))));
        List<Instruction> create = new ArrayList<>();
        if (patched) create.add(invoke(Opcode.INVOKE_STATIC, ref(ENTRY, "onActivityCreate", VOID, "Landroid/app/Activity;"), 0));
        create.add(end());
        List<Instruction> intent = new ArrayList<>();
        if (patched) intent.add(invoke(Opcode.INVOKE_STATIC, ref(ENTRY, "onNewIntent", VOID, "Landroid/app/Activity;", INTENT), 0, 1));
        intent.add(end());
        classes.put(ACTIVITY, new ArrayList<>(List.of(
                method(ACTIVITY, "onCreate", VOID, false, 2, create, "Landroid/os/Bundle;"),
                method(ACTIVITY, "onNewIntent", VOID, false, 2, intent, INTENT))));
        return classes;
    }

    private static void extensions(Map<String, List<Method>> classes) {
        List<Method> flags = new ArrayList<>();
        FLAGS.forEach((name, value) -> flags.add(method(STATUS, name, "Z", true, 1,
                List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, value ? 1 : 0), new ImmutableInstruction11x(Opcode.RETURN, 0)))));
        classes.put(STATUS, flags);
        classes.put(UTILS, List.of(method(UTILS, "setContext", VOID, true, 1, List.of(end()), CONTEXT)));
        classes.put(ENTRY, List.of(
                method(ENTRY, "onApplicationCreate", VOID, true, 1, List.of(end()), CONTEXT),
                method(ENTRY, "onActivityCreate", VOID, true, 1, List.of(end()), "Landroid/app/Activity;"),
                method(ENTRY, "onNewIntent", VOID, true, 2, List.of(end()), "Landroid/app/Activity;", INTENT)));
    }

    private static void enable(String name) {
        FLAGS.put(name, true);
        for (String cap : FAMILIES.get(name)[1].split(",")) FLAGS.put(cap, true);
    }

    private static void reset() { FLAGS.replaceAll((key, value) -> false); }

    private static void write(File root, String name, Map<String, List<Method>> classes, boolean patched, String... selected) throws Exception {
        if (patched) extensions(classes);
        if (name.equals("feature-no-status")) classes.remove(STATUS);
        if (name.equals("feature-nonboolean-status")) {
            List<Method> status = new ArrayList<>(classes.get(STATUS));
            status.set(0, method(STATUS, "hideAds", "Z", true, 1,
                    List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, 2), new ImmutableInstruction11x(Opcode.RETURN, 0))));
            classes.put(STATUS, status);
        }
        List<ClassDef> defs = new ArrayList<>();
        classes.forEach((owner, methods) -> defs.add(type(owner, methods)));
        DexPool.writeTo(new File(root, name + ".dex").getPath(), new ImmutableDexFile(Opcodes.getDefault(), defs));
        if (patched) {
            List<String> names = new ArrayList<>(List.of("HushPinterest settings"));
            for (String family : selected) names.add(FAMILIES.get(family)[0]);
            Files.write(new File(root, name + ".selected").toPath(), names, StandardCharsets.UTF_8);
        }
    }

    private static void screenshot(Map<String, List<Method>> classes, String variant) {
        List<Instruction> original = List.of(literal("sg_android_new_screenshot_api_14", 0),
                new ImmutableInstruction11n(Opcode.CONST_4, 0, 1), new ImmutableInstruction21t(Opcode.IF_EQZ, 0, 3), end(), end());
        List<Instruction> body = new ArrayList<>();
        boolean calls = !variant.equals("clean") && !variant.equals("missing") && !variant.equals("misrouted");
        if (calls) {
            int count = variant.equals("duplicate") ? 2 : 1;
            for (int k = 0; k < count; k++) {
                body.add(invoke(Opcode.INVOKE_STATIC, ref(SCREENSHOT, "hideScreenshotShare", "Z")));
                body.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0));
                body.add(new ImmutableInstruction21t(Opcode.IF_EQZ, 0, variant.equals("bad-fallback") ? 2 : 3));
                body.add(end());
            }
        }
        body.addAll(original);
        if (variant.equals("changed-original")) body.set(body.size() - 4, new ImmutableInstruction11n(Opcode.CONST_4, 0, 0));
        List<Method> methods = new ArrayList<>(List.of(method(OBSERVER, "onScreenshot", VOID, false, 4, body,
                OBJECT, "Landroidx/fragment/app/FragmentActivity;")));
        if (variant.equals("misrouted")) methods.add(method(OBSERVER, "unrelated", VOID, true, 1,
                List.of(invoke(Opcode.INVOKE_STATIC, ref(SCREENSHOT, "hideScreenshotShare", "Z")), new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), end())));
        classes.put(OBSERVER, methods);
        if (!variant.equals("clean")) classes.put(SCREENSHOT, List.of(method(SCREENSHOT, "hideScreenshotShare", "Z", true, 1,
                List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), new ImmutableInstruction11x(Opcode.RETURN, 0)))));
    }

    private static void feed(Map<String, List<Method>> classes, String variant) {
        List<Instruction> ctor = new ArrayList<>();
        if (!variant.equals("clean")) {
            int count = variant.equals("duplicate") ? 2 : 1;
            for (int i = 0; i < count; i++) {
                ctor.add(invoke(Opcode.INVOKE_STATIC, ref(FILTER, "filter", LIST, LIST), variant.equals("wrong-register") ? 0 : 1));
                ctor.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, variant.equals("wrong-result") ? 0 : 1));
            }
        }
        ctor.add(invoke(Opcode.INVOKE_DIRECT, ref(OBJECT, "<init>", VOID), 0));
        ctor.add(end());
        classes.put(FEED, List.of(method(FEED, "<init>", VOID, false, 2, ctor, LIST), method(FEED, "toString", STRING, false, 2,
                List.of(literal(", _items count:", 0), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)))));
        if (!variant.equals("clean")) classes.put(FILTER, List.of(method(FILTER, "filter", LIST, true, 1,
                List.of(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), LIST)));
    }

    private static void links(Map<String, List<Method>> classes, boolean patched, String variant, boolean clipboard) {
        ImmutableMethodReference nativeIntent = ref(INTENT, "putExtra", INTENT, STRING, STRING);
        ImmutableMethodReference intentHook = ref(TRACKING, "putStringExtra", INTENT, INTENT, STRING, STRING);
        ImmutableMethodReference nativeClipboard = ref(CLIPBOARD, "newPlainText", CLIPBOARD, TEXT, TEXT);
        ImmutableMethodReference clipboardHook = ref(TRACKING, "newPlainText", CLIPBOARD, TEXT, TEXT);
        List<Instruction> body = new ArrayList<>();
        if (patched && !variant.equals("left-original")) body.add(invoke(Opcode.INVOKE_STATIC, intentHook, variant.equals("wrong-register") ? 2 : 1, 2, 3));
        else body.add(invoke(Opcode.INVOKE_VIRTUAL, nativeIntent, 1, 2, 3));
        body.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        if (clipboard) {
            body.add(invoke(Opcode.INVOKE_STATIC, patched ? clipboardHook : nativeClipboard, 2, 3));
            body.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
        }
        body.add(end());
        classes.put(SENDER, List.of(method(SENDER, "send", VOID, true, 4, body, INTENT, STRING, STRING)));
        if (patched) {
            List<Instruction> wrapper = new ArrayList<>();
            if (!variant.equals("missing-original-fallback")) {
                wrapper.add(invoke(Opcode.INVOKE_VIRTUAL, nativeIntent, 0, 1, 2));
                wrapper.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
            }
            wrapper.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
            classes.put(TRACKING, List.of(method(TRACKING, "putStringExtra", INTENT, true, 3, wrapper, INTENT, STRING, STRING),
                    method(TRACKING, "newPlainText", CLIPBOARD, true, 2, List.of(invoke(Opcode.INVOKE_STATIC, nativeClipboard, 0, 1),
                            new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), TEXT, TEXT)));
        }
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        Files.createDirectories(root.toPath());
        for (String line : Files.readAllLines(new File(args[1]).toPath(), StandardCharsets.UTF_8)) if (line.startsWith("family|")) {
            String[] values = line.split("\\|");
            FAMILIES.put(values[1], new String[]{values[2], values[3]});
            FLAGS.put(values[1], false);
            for (String cap : values[3].split(",")) FLAGS.put(cap, false);
        }
        Map<String, List<Method>> clean = hosts(false);
        screenshot(clean, "clean");
        feed(clean, "clean");
        links(clean, false, "good", true);
        // This makes the update family required in the selected-but-not-installed negative case.
        clean.put("Lfixture/UpdateTask;", List.of(method("Lfixture/UpdateTask;", "invokeSuspend", OBJECT, false, 3,
                List.of(literal("inAppUpdateManager", 0), new ImmutableInstruction11n(Opcode.CONST_4, 0, 0), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), OBJECT)));
        write(root, "feature-clean", clean, false);
        reset();
        Map<String, List<Method>> settings = new LinkedHashMap<>(clean); settings.putAll(hosts(true));
        write(root, "feature-unselected", settings, true);
        write(root, "feature-internal-dependencies", new LinkedHashMap<>(settings), true);
        Files.write(new File(root, "feature-internal-dependencies.selected").toPath(),
                List.of("HushPinterest settings", "BytecodePatch", "ResourcePatch"), StandardCharsets.UTF_8);
        write(root, "feature-unknown-public", new LinkedHashMap<>(settings), true);
        Files.write(new File(root, "feature-unknown-public.selected").toPath(),
                List.of("HushPinterest settings", "Hide imaginary pins"), StandardCharsets.UTF_8);
        write(root, "feature-no-status", new LinkedHashMap<>(settings), true);
        write(root, "feature-nonboolean-status", new LinkedHashMap<>(settings), true);
        write(root, "feature-status-copy", new LinkedHashMap<>(Map.of(STATUS, settings.get(STATUS))), false);
        for (String family : FAMILIES.keySet()) {
            reset(); enable(family);
            write(root, "feature-installed-missing-" + family, new LinkedHashMap<>(settings), true, family);
            reset();
            write(root, "feature-selected-missing-" + family, new LinkedHashMap<>(settings), true, family);
        }
        for (String variant : List.of("good", "missing", "duplicate", "misrouted", "bad-fallback", "changed-original", "false-capability", "unselected-call")) {
            reset(); enable("hideScreenshotShare");
            if (variant.equals("false-capability")) FLAGS.put("screenshotShare", false);
            if (variant.equals("unselected-call")) reset();
            Map<String, List<Method>> classes = new LinkedHashMap<>(settings);
            screenshot(classes, variant);
            write(root, "feature-guard-" + variant, classes, true, variant.equals("unselected-call") ? new String[]{} : new String[]{"hideScreenshotShare"});
        }
        reset(); enable("hideScreenshotShare");
        Map<String, List<Method>> noHook = new LinkedHashMap<>(settings);
        screenshot(noHook, "good"); noHook.remove(SCREENSHOT);
        write(root, "feature-guard-missing-callee", noHook, true, "hideScreenshotShare");
        Map<String, List<Method>> interiorClean = new LinkedHashMap<>(clean);
        screenshot(interiorClean, "clean");
        List<Instruction> originalLoop = new ArrayList<>();
        interiorClean.get(OBSERVER).get(0).getImplementation().getInstructions().forEach(originalLoop::add);
        originalLoop.set(2, new ImmutableInstruction21t(Opcode.IF_EQZ, 0, -3));
        interiorClean.put(OBSERVER, List.of(method(OBSERVER, "onScreenshot", VOID, false, 4, originalLoop, OBJECT, "Landroidx/fragment/app/FragmentActivity;")));
        write(root, "feature-guard-interior-clean", interiorClean, false);
        for (boolean unsafe : List.of(false, true)) {
            Map<String, List<Method>> loop = new LinkedHashMap<>(settings);
            screenshot(loop, "good");
            List<Instruction> guarded = new ArrayList<>();
            loop.get(OBSERVER).get(0).getImplementation().getInstructions().forEach(guarded::add);
            guarded.set(6, new ImmutableInstruction21t(Opcode.IF_EQZ, 0, unsafe ? -4 : -3));
            loop.put(OBSERVER, List.of(method(OBSERVER, "onScreenshot", VOID, false, 4, guarded, OBJECT, "Landroidx/fragment/app/FragmentActivity;")));
            write(root, "feature-guard-interior-" + (unsafe ? "bad" : "good"), loop, true, "hideScreenshotShare");
        }
        for (String variant : List.of("ads", "ai", "shopping", "shared", "duplicate", "wrong-register", "wrong-result")) {
            reset();
            String[] selected = variant.equals("ai") ? new String[]{"hideAiPins"} : variant.equals("shopping") ? new String[]{"hideShopping"}
                    : variant.equals("ads") ? new String[]{"hideAds"} : new String[]{"hideAds", "hideAiPins", "hideShopping"};
            for (String family : selected) enable(family);
            FLAGS.put("adViews", false);
            Map<String, List<Method>> classes = new LinkedHashMap<>(settings);
            feed(classes, variant);
            write(root, "feature-feed-" + variant, classes, true, selected);
        }
        for (String variant : List.of("good", "left-original", "wrong-register", "missing-original-fallback", "partial", "false-capability")) {
            boolean partial = variant.equals("partial");
            if (partial) {
                Map<String, List<Method>> partialClean = new LinkedHashMap<>(clean);
                links(partialClean, false, "good", false);
                write(root, "feature-links-partial-clean", partialClean, false);
            }
            reset(); enable("stripLinkTracking");
            if (partial || variant.equals("false-capability")) FLAGS.put("linkTracking", false);
            Map<String, List<Method>> classes = new LinkedHashMap<>(settings);
            links(classes, true, variant, !partial);
            write(root, "feature-links-" + variant, classes, true, "stripLinkTracking");
        }
        reset();
        Map<String, List<Method>> absentClean = new LinkedHashMap<>(clean); absentClean.remove("Lfixture/UpdateTask;");
        write(root, "feature-optional-clean", absentClean, false);
        absentClean.putAll(hosts(true));
        write(root, "feature-optional-absent", absentClean, true, "disableUpdateNag");
        Map<String, List<Method>> unrelatedClean = new LinkedHashMap<>(clean); unrelatedClean.remove("Lfixture/UpdateTask;");
        String unrelated = "Lfixture/UnrelatedUpdateText;";
        unrelatedClean.put(unrelated, List.of(method(unrelated, "description", STRING, true, 1,
                List.of(literal("inAppUpdateManager", 0), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)))));
        write(root, "feature-optional-unrelated-clean", unrelatedClean, false);
        unrelatedClean.putAll(hosts(true));
        write(root, "feature-optional-unrelated", unrelatedClean, true, "disableUpdateNag");
    }
}
