/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.parameterRegister
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireParameterIntact
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.EXTENSION_ROOT
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.instruction.BuilderPackedSwitchPayload
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide topic suggestions"

/**
 * Pinterest's log line for a topic row whose presenter is the wrong kind. It sits in the topic arm
 * of the binder, so it names the binder in the declared build.
 */
internal const val TOPIC_BINDER_ANCHOR = "Presenter bound to BubblesListView must be of type BubblesListPresenter"

/** The first instruction a discriminator with no case of its own runs: the one right after the switch. */
internal const val TOPIC_FALL_THROUGH = 2

/** Pinterest's topic row view. Obfuscation leaves its name alone, and 14.39.0 and 14.40.0 both use it. */
internal const val TOPIC_ROW = "Lcom/pinterest/feature/search/typeahead/view/SearchTypeaheadAutoCompleteUpsellCell;"

internal const val TOPIC_ROW_HOOK = "$UI_HOOKS->topicSuggestions(Ljava/lang/Object;)V"
internal const val TOPIC_MEASURE_HOOK = "$UI_HOOKS->topicSuggestionsMeasureSpec(Landroid/view/View;I)I"

private val ANDROID_VIEWS = listOf("Landroid/view/", "Landroid/widget/")

private fun CharSequence.width() = if (toString() == "J" || toString() == "D") 2 else 1

/**
 * The binder's shape: an instance method answering nothing that reads an int field of its own
 * class from `this` first, switches on it with a packed-switch second, and logs [TOPIC_BINDER_ANCHOR].
 * One binder class serves several closeup sections, told apart by that field. Its packed-switch has
 * a case for every section but one, which falls through to the instruction after the switch.
 */
internal fun Method.isTopicBinder(): Boolean {
    val implementation = implementation ?: return false
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || parameterTypes.isEmpty()) return false
    if (!parameterTypes[0].startsWith("L")) return false
    val body = instructions()
    if (body.size <= TOPIC_FALL_THROUGH) return false
    val read = body[0]
    val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: return false
    if (read.opcode != Opcode.IGET || field.definingClass != definingClass || field.type != "I") return false
    val self = implementation.registerCount - parameterTypes.sumOf { it.width() } - 1
    if ((read as TwoRegisterInstruction).registerB != self) return false
    val switch = body[1]
    if (switch.opcode != Opcode.PACKED_SWITCH || (switch as OneRegisterInstruction).registerA != read.registerA) return false
    return TOPIC_BINDER_ANCHOR in strings()
}

internal class TopicSuggestions(val binder: MutableMethod, val view: String, val row: MutableClass, val hookAt: Int)

/**
 * The binder, the row's view class and the places for both hooks, all found and checked before
 * anything changes.
 *
 * Every arm starts by casting the binder's view parameter to an interface, and the topic arm's is
 * the interface [TOPIC_ROW] implements, which no other class does. 14.39.0 gives the topic row the
 * arm with no case and 14.40.0 a case of its own, so the arm is found by that cast, among the
 * places the switch starts one. Its hook goes right after the cast: a case lands on the cast
 * itself, so anything put before it would be jumped over. Pinterest adds the row straight to its
 * grid, a RecyclerView, which lays out a GONE child like any other, so hiding the row takes a zero
 * measure as well as GONE. Neither declared build gives that class an onMeasure of its own.
 */
internal fun BytecodePatchContext.topicSuggestions(): TopicSuggestions {
    val found = methodsWithString(TOPIC_BINDER_ANCHOR).filter { it.isTopicBinder() }
    val binder = mutable(found.one("$PATCH: topic row binder"))
    val instructions = binder.implementation!!.instructions
    val view = binder.parameterRegister(0)
    val viewRegister = binder.implementation!!.registerCount - binder.parameterTypes.sumOf { it.width() }
    if (classDefByOrNull(TOPIC_ROW) == null) throw PatchException("$PATCH: no topic row view $TOPIC_ROW")
    val row = mutableClassDefBy(TOPIC_ROW)
    val payload = (instructions[1] as? BuilderOffsetInstruction)?.target?.location?.instruction as? BuilderPackedSwitchPayload
        ?: throw PatchException("$PATCH: the binder's switch has no packed-switch table")
    val starts = (listOf(TOPIC_FALL_THROUGH) + payload.switchElements.map { it.target.location.index }).distinct()
    val arm = starts.filter { at ->
        val cast = instructions[at]
        cast.opcode == Opcode.CHECK_CAST && (cast as OneRegisterInstruction).registerA == viewRegister &&
            ((cast as ReferenceInstruction).reference as TypeReference).type in row.interfaces
    }.one("$PATCH: topic arm")
    val face = ((instructions[arm] as ReferenceInstruction).reference as TypeReference).type
    if (classDefByOrNull(face)?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } != true) {
        throw PatchException("$PATCH: the topic arm casts its view to $face, which is not an interface")
    }
    val implementers = mutableListOf<ClassDef>()
    classDefForEach { owner ->
        if (!owner.type.startsWith(EXTENSION_ROOT) && face in owner.interfaces) implementers += owner
    }
    implementers.one("$PATCH: topic row view")
    val hookAt = arm + 1
    if ((instructions[hookAt] as BuilderInstruction).location.labels.isNotEmpty()) {
        throw PatchException("$PATCH: the instruction after the topic arm's cast is also a branch target")
    }
    // The hook reads the view parameter just after its cast, which keeps the same object and only
    // narrows its type, so nothing that runs before the cast may have put anything else there.
    binder.requireParameterIntact(PATCH, 0, listOf(arm))
    if (!isFrameworkView(row)) throw PatchException("$PATCH: the topic row ${row.type} is not an Android view")
    requireOverride(row, "onMeasure", listOf("I", "I"))
    if (row.methods.any { it.name == "onMeasure" && it.parameters() == listOf("I", "I") }) {
        throw PatchException("$PATCH: the topic row ${row.type} measures itself, so its own onMeasure would need a different hook")
    }
    return TopicSuggestions(binder, view, row, hookAt)
}

/** True when the class's first ancestor outside the APK is one of Android's view classes. */
internal fun BytecodePatchContext.isFrameworkView(type: ClassDef): Boolean {
    val visited = mutableSetOf<String>()
    var ancestor = type.superclass
    while (ancestor != null && visited.add(ancestor)) {
        val outside = ancestor
        val owner = classDefByOrNull(outside) ?: return ANDROID_VIEWS.any { outside.startsWith(it) }
        ancestor = owner.superclass
    }
    return false
}

@Suppress("unused")
val hideTopicSuggestionsPatch = bytecodePatch(
    name = PATCH,
    description = "Hides the Ideas you might love row of topic bubbles under pins, without leaving a gap. Comments " +
        "and related pins stay. Starts off. Turn it on in HushPinterest settings > Interface.",
) {
    category("Interface")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("hideTopicSuggestions")
        requireStatusMethod("topicSuggestions")
        val found = topicSuggestions()
        found.binder.addInstructions(found.hookAt, "invoke-static/range { ${found.view} .. ${found.view} }, $TOPIC_ROW_HOOK")
        addOverride(found.row, "onMeasure", listOf("I", "I"), """
            invoke-static { p0, p1 }, $TOPIC_MEASURE_HOOK
            move-result p1
            invoke-static { p0, p2 }, $TOPIC_MEASURE_HOOK
            move-result p2
        """)
        enableCapability("topicSuggestions")
        enableStatus("hideTopicSuggestions")
    }
}
