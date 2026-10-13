/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.freeLocalsAt
import app.morphe.patches.pinterest.misc.extension.handleTargets
import app.morphe.patches.pinterest.misc.extension.parameterRegisterNumber
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.extension.requireStub
import app.morphe.patches.pinterest.misc.extension.writeStub
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.extendsClass
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

private const val PATCH = "Hide save toasts"
internal const val TOAST_CONTAINER = "Lcom/pinterest/gestalt/toast/PinterestToastContainer;"
internal const val TOAST_VIEW = "Lcom/pinterest/gestalt/toast/BaseGestaltToast;"

/** Resource names of the save confirmations' text, as the toast models and text builders that show them read them. */
internal val SAVE_TOAST_STRINGS = setOf(
    "saved_to", "saved_onto_board_bold", "pinned", "pinned_multiple", "pinned_multiple_to_board",
    "multi_board_save_saved_to_profile",
)

/** The enum constant the follow suggestion after a save names when you open its creator. */
internal const val FOLLOW_UPSELL = "FollowUpsellToast"

/**
 * The enum constant the save confirmation with a "discover more ideas" line names when you tap it.
 * Pinterest shows that one in place of the plain confirmation from the third save in a row to a
 * board it suggests ideas for. Its text is built outside the model, so the model reads no save string.
 */
internal const val MORE_IDEAS_UPSELL = "BOARD_MORE_IDEAS_UPSELL_TOAST"

/**
 * A toast model that shows the text an object in one of its fields builds: the [model], that
 * [field] as smali names it, and the [sources], the classes behind the field's interface that
 * build a save confirmation.
 */
internal data class TextToast(val model: String, val field: String, val sources: List<String>)

@Suppress("unused")
val hideSaveToastsPatch = bytecodePatch(
    name = PATCH,
    description = "Stops the pop-up Pinterest shows after you save a pin, such as the Saved to your board message " +
        "or a suggestion to follow the creator. The pin is still saved. Starts off. Turn it on in " +
        "HushPinterest settings > Interface.",
) {
    category("Interface")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("hideSaveToasts")
        requireStatusMethod("saveToasts")
        requireStub(UI_HOOKS, "isSaveTextToast")
        val show = toastShowMethod()
        val model = show.parameterTypes.single().toString()
        val (saved, follow) = saveToastModels(model)
        val found = handleTargets(PATCH, "save toasts", listOf("saved to board" to saved, "follow suggestion" to follow)) { (what, types) ->
            if (types.isEmpty()) "no $what toast was found" else null
        }
        val types = (saved + follow).distinct()
        val texts = saveTextToasts(model)
        val method = mutable(show)
        val register = method.freeLocalsAt(PATCH, 0, 1).single()
        val toast = method.parameterRegisterNumber(0)
        if (toast > 15) throw PatchException("$PATCH: the toast container's model register is past v15")
        // Instance checks the extension asks when a toast reaches the container.
        writeStub(UI_HOOKS, "isSaveToast", 2, types.joinToString("\n") { type ->
            "instance-of v0, p0, $type\nif-nez v0, :hush_save_toast"
        } + "\nconst/4 v0, 0x0\nreturn v0\n:hush_save_toast\nconst/4 v0, 0x1\nreturn v0")
        // The same question for a toast that shows whatever text an object it holds builds: the
        // model alone doesn't say, so each check reads that object and asks which class built it.
        if (texts.isNotEmpty()) {
            writeStub(UI_HOOKS, "isSaveTextToast", 3, texts.withIndex().joinToString("\n") { (index, text) ->
                "instance-of v0, p0, ${text.model}\nif-eqz v0, :hush_text_$index\ncheck-cast p0, ${text.model}\n" +
                    "iget-object v0, p0, ${text.field}\n" +
                    text.sources.joinToString("\n") { "instance-of v1, v0, $it\nif-nez v1, :hush_save_toast" } +
                    "\n:hush_text_$index"
            } + "\nconst/4 v0, 0x0\nreturn v0\n:hush_save_toast\nconst/4 v0, 0x1\nreturn v0")
        }
        method.addInstructionsWithLabels(0, """
            invoke-static { v$toast }, $UI_HOOKS->hideSaveToast(Ljava/lang/Object;)Z
            move-result v$register
            if-eqz v$register, :hush_show_toast
            return-void
        """, ExternalLabel("hush_show_toast", method.getInstruction(0)))
        if (found == 2) enableCapability("saveToasts")
        enableStatus("hideSaveToasts")
    }
}

/** The container's one method that takes a toast model and asks it for its view. */
internal fun BytecodePatchContext.toastShowMethod(): Method {
    val container = classDefByOrNull(TOAST_CONTAINER) ?: throw PatchException("$PATCH: Pinterest's toast container wasn't found")
    return container.methods.filter { method ->
        val model = method.parameterTypes.singleOrNull()?.toString()
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && model != null &&
            method.calls().any { it.definingClass == model && it.returnType == TOAST_VIEW && it.parameters() == listOf(TOAST_CONTAINER) }
    }.one("$PATCH: toast container show method")
}

/**
 * Concrete toast models whose own methods read a save confirmation's text or name the "more ideas"
 * confirmation, and the follow suggestion's.
 */
internal fun BytecodePatchContext.saveToastModels(model: String): Pair<List<String>, List<String>> {
    val saved = mutableListOf<String>()
    val follow = mutableListOf<String>()
    classDefForEach { owner ->
        if (AccessFlags.ABSTRACT.isSet(owner.accessFlags) || AccessFlags.INTERFACE.isSet(owner.accessFlags) ||
            owner.type == model || !extendsClass(owner.type, model)) return@classDefForEach
        val fields = owner.methods.flatMap { it.fields() }
        if (fields.any { it.type == "I" && it.name in SAVE_TOAST_STRINGS || it.name == MORE_IDEAS_UPSELL && it.type == it.definingClass }) {
            saved += owner.type
        }
        if (fields.any { it.name == FOLLOW_UPSELL && it.type == it.definingClass }) follow += owner.type
    }
    return saved.sorted() to follow.sorted()
}

/**
 * Toast models that ask an interface they hold in a public field for their text, where a class
 * behind that interface reads a save confirmation's text. The same model shows other messages
 * through the interface's other classes, a removal for one, so the model alone isn't a save toast.
 * Everything the extension's check names has to be public, since it runs outside Pinterest's packages.
 */
internal fun BytecodePatchContext.saveTextToasts(model: String): List<TextToast> {
    val asks = mutableListOf<Triple<String, String, String>>()
    val sources = mutableMapOf<String, MutableList<String>>()
    classDefForEach { owner ->
        if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || AccessFlags.ABSTRACT.isSet(owner.accessFlags) ||
            AccessFlags.INTERFACE.isSet(owner.accessFlags) || owner.type == model) return@classDefForEach
        if (extendsClass(owner.type, model)) {
            // What the model's view builder asks for a CharSequence.
            val asked = owner.methods.filter { it.returnType == TOAST_VIEW && it.parameters() == listOf(TOAST_CONTAINER) }
                .flatMap { it.calls() }.filter { it.returnType == "Ljava/lang/CharSequence;" }.map { it.definingClass }.toSet()
            owner.fields.filter { field ->
                field.type in asked && AccessFlags.PUBLIC.isSet(field.accessFlags) && !AccessFlags.STATIC.isSet(field.accessFlags)
            }.forEach { asks += Triple(owner.type, it.name, it.type) }
        } else if (owner.interfaces.isNotEmpty() &&
            owner.methods.any { method -> method.fields().any { it.type == "I" && it.name in SAVE_TOAST_STRINGS } }) {
            owner.interfaces.forEach { sources.getOrPut(it) { mutableListOf() } += owner.type }
        }
    }
    return asks.mapNotNull { (owner, name, type) ->
        sources[type]?.let { TextToast(owner, "$owner->$name:$type", it.sorted()) }
    }.sortedBy { it.field }
}
