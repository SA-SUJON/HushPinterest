/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.patchLog
import app.morphe.patches.pinterest.misc.extension.requireStub
import app.morphe.patches.pinterest.misc.extension.writeStub
import app.morphe.util.extendsClass
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val PINTEREST_TOASTS = "$EXTENSION_PACKAGE/ui/PinterestToasts;"

/** Pinterest's home activity, a manifest name. Its base activity is the one every Pinterest screen shows toasts through. */
internal const val HOME_ACTIVITY = "Lcom/pinterest/activityLibrary/activity/task/activity/MainActivity;"

/**
 * What the [PINTEREST_TOASTS] stubs call, all found by shape from [HOME_ACTIVITY] and the toast
 * container: the base activity, its method that shows a toast model, the one that sets up its
 * toast layer and the getter for that layer, the text toast model with its (String, int)
 * constructor, and the model's flag that has the activity show a text it showed a moment ago.
 */
internal data class NativeToast(
    val activity: String,
    val show: String,
    val setup: String,
    val layer: String,
    val text: String,
    val again: String,
)

/**
 * Fills the [PINTEREST_TOASTS] stubs so HushPinterest's messages go through Pinterest's own text
 * toast. Answers false, leaving the stubs as they are and every message an Android toast, when
 * this build's toast code isn't the shape [nativeToast] reads. Only the extension's stubs change.
 */
internal fun BytecodePatchContext.routeToastsToPinterest(): Boolean {
    val toast = try {
        nativeToast()
    } catch (refused: PatchException) {
        patchLog.warning("HushPinterest settings: ${refused.message}. HushPinterest's messages stay Android toasts")
        return false
    }
    requireStub(PINTEREST_TOASTS, "container")
    requireStub(PINTEREST_TOASTS, "post")
    writeStub(PINTEREST_TOASTS, "container", 2, """
        instance-of v0, p0, ${toast.activity}
        if-eqz v0, :hush_no_layer
        check-cast p0, ${toast.activity}
        invoke-virtual { p0 }, ${toast.setup}
        invoke-virtual { p0 }, ${toast.layer}
        move-result-object v0
        return-object v0
        :hush_no_layer
        const/4 v0, 0x0
        return-object v0
    """)
    writeStub(PINTEREST_TOASTS, "post", 5, """
        instance-of v0, p0, ${toast.activity}
        if-eqz v0, :hush_not_shown
        check-cast p0, ${toast.activity}
        new-instance v0, ${toast.text}
        invoke-direct { v0, p1, p2 }, ${toast.text}-><init>(Ljava/lang/String;I)V
        const/4 v1, 0x1
        iput-boolean v1, v0, ${toast.again}
        invoke-virtual { p0, v0 }, ${toast.show}
        const/4 v0, 0x1
        return v0
        :hush_not_shown
        const/4 v0, 0x0
        return v0
    """)
    return true
}

/** Throws naming what didn't match unless every part of [NativeToast] is found exactly once. */
internal fun BytecodePatchContext.nativeToast(): NativeToast {
    fun refuse(why: String): Nothing = throw PatchException("Pinterest's own toasts: $why")
    val containerShow = toastShowMethod()
    val model = containerShow.parameterTypes.single().toString()
    val modelClass = classDefByOrNull(model) ?: refuse("the toast model $model isn't in this build")
    if (!modelClass.public() || !AccessFlags.ABSTRACT.isSet(modelClass.accessFlags)) refuse("$model isn't a public abstract class")
    if (classDefByOrNull(HOME_ACTIVITY) == null) refuse("$HOME_ACTIVITY isn't in this build")

    // The activity's method that takes a toast model and hands it to its toast layer.
    val shows = superclassChain(HOME_ACTIVITY).mapNotNull { classDefByOrNull(it) }.flatMap { owner ->
        owner.methods.filter { method ->
            method.instance() && method.public() && method.returnType == "V" && method.parameters() == listOf(model) &&
                method.calls().any { call ->
                    call.name == containerShow.name && call.parameters() == listOf(model) &&
                        extendsClass(call.definingClass, TOAST_CONTAINER)
                }
        }.map { owner to it }
    }.toList()
    val (activity, show) = shows.singleOrNull() ?: refuse("${shows.size} methods of the home activity's classes show a toast model")
    if (!activity.public()) refuse("${activity.type} isn't public")

    // Before that, it sets up the layer, which it then reads from its own field. The getter
    // answers the same field.
    val setups = show.calls().filter { it.definingClass == activity.type && it.parameterTypes.isEmpty() && it.returnType == "V" }
    val setup = activity.methods.filter { method ->
        method.instance() && method.public() && setups.any { it.name == method.name } &&
            method.parameterTypes.isEmpty() && method.returnType == "V"
    }.singleOrNull() ?: refuse("${activity.type}'s toast method sets up no single toast layer")
    val layerFields = show.instructions().filter { it.opcode == Opcode.IGET_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.definingClass == activity.type && extendsClass(it.type, TOAST_CONTAINER) }.distinct()
    val layerField = layerFields.singleOrNull() ?: refuse("${activity.type}'s toast method reads ${layerFields.size} toast layers")
    val layer = activity.methods.filter { method ->
        method.instance() && method.public() && method.parameterTypes.isEmpty() && method.returnType == layerField.type &&
            method.instructions().map { it.opcode } == listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT) &&
            (method.instructions().first() as ReferenceInstruction).reference == layerField
    }.singleOrNull() ?: refuse("${activity.type} has no single public getter for ${layerField.name}")

    // The text toast: the model it checks for that a (String, int) constructor builds.
    val checked = show.instructions().filter { it.opcode == Opcode.INSTANCE_OF }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.distinct()
    val texts = checked.mapNotNull { classDefByOrNull(it) }.filter { owner ->
        owner.superclass == model && owner.public() && !AccessFlags.ABSTRACT.isSet(owner.accessFlags) &&
            !AccessFlags.INTERFACE.isSet(owner.accessFlags) &&
            owner.methods.any { it.name == "<init>" && it.public() && it.parameters() == listOf("Ljava/lang/String;", "I") }
    }
    val text = texts.singleOrNull() ?: refuse("${texts.size} text toast models with a (String, int) constructor")

    // The one flag the model answers that lets a repeated text through.
    val flags = show.calls().filter { it.definingClass == model && it.parameterTypes.isEmpty() && it.returnType == "Z" }.distinct()
    val flag = flags.singleOrNull()?.let { call -> modelClass.methods.singleOrNull { it.name == call.name && it.parameterTypes.isEmpty() && it.returnType == "Z" } }
        ?: refuse("${activity.type}'s toast method asks the model ${flags.size} yes-or-no questions")
    val read = flag.instructions()
    val field = ((read.firstOrNull() as? ReferenceInstruction)?.reference as? FieldReference)
    if (read.map { it.opcode } != listOf(Opcode.IGET_BOOLEAN, Opcode.RETURN) || field == null || field.definingClass != model ||
        (read.first() as TwoRegisterInstruction).registerB != flag.implementation!!.registerCount - 1) {
        refuse("$model->${flag.name}()Z doesn't just answer one of its own fields")
    }
    val declared = modelClass.fields.singleOrNull { it.name == field.name && it.type == "Z" }
    if (declared == null || !declared.public() || AccessFlags.STATIC.isSet(declared.accessFlags) ||
        AccessFlags.FINAL.isSet(declared.accessFlags)) {
        refuse("$model->${field.name} isn't a field patching can set")
    }

    return NativeToast(
        activity = activity.type,
        show = "${activity.type}->${show.name}($model)V",
        setup = "${activity.type}->${setup.name}()V",
        layer = "${activity.type}->${layer.name}()${layerField.type}",
        text = text.type,
        again = "$model->${field.name}:Z",
    )
}

private fun ClassDef.public() = AccessFlags.PUBLIC.isSet(accessFlags)
private fun Method.public() = AccessFlags.PUBLIC.isSet(accessFlags)
private fun Method.instance() = !AccessFlags.STATIC.isSet(accessFlags) && !AccessFlags.ABSTRACT.isSet(accessFlags)
private fun Field.public() = AccessFlags.PUBLIC.isSet(accessFlags)
