/*
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.freeLocalsAt
import app.morphe.patches.pinterest.ui.instructions
import app.morphe.patches.pinterest.ui.mutable
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val NATIVE_SETTINGS_ITEM = "$EXTENSION_PACKAGE/settings/NativeSettingsItem;"
internal const val NATIVE_SETTINGS_TITLE = "hushpinterest_settings_entry"
private const val LIST_ADD = "Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"

/** Resolve all host dependencies and prove register liveness before changing any code. */
internal fun BytecodePatchContext.hookNativeSettingsEntry() {
    // Small framework-only test contexts don't merge the extension. A real bundle always does.
    val owned = mutableClassDefByOrNull(NATIVE_SETTINGS_ITEM) ?: return
    if (owned.superclass != "Ljava/lang/Object;") throw PatchException("HushPinterest native settings row already exists")
    val methods = mutableListOf<Method>()
    classDefForEach { owner ->
        if (!owner.type.startsWith(EXTENSION_PACKAGE)) methods += owner.methods.filter { it.implementation != null }
    }
    val external = methods.singleOrNull { method ->
        method.name == "<init>" && method.instructions().any {
            it.opcode == Opcode.SGET && ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name ==
                "settings_menu_teen_safety_resources"
        }
    } ?: throw PatchException("HushPinterest settings: no unique native external-row constructor")
    val parentCall = external.instructions().mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .singleOrNull { it.name == "<init>" && it.parameterTypes.size == 2 && it.parameterTypes[0] == "I" }
        ?: throw PatchException("HushPinterest settings: external-row superclass constructor changed")
    val parent = classDefBy(parentCall.definingClass)
    if (!AccessFlags.ABSTRACT.isSet(parent.accessFlags)) throw PatchException("HushPinterest settings: external-row base isn't abstract")
    val textType = parentCall.parameterTypes[1].toString()
    val textConstructor = classDefBy(textType).methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) ==
            listOf("I", "Ljava/lang/Integer;", "Ljava/lang/String;")
    } ?: throw PatchException("HushPinterest settings: native text constructor changed")
    val viewType = classDefBy(external.definingClass).methods.singleOrNull { it.name == "getViewType" && it.returnType == "I" }
        ?: throw PatchException("HushPinterest settings: external-row view type missing")
    val viewField = (viewType.instructions().firstOrNull() as? ReferenceInstruction)?.reference as? FieldReference
        ?: throw PatchException("HushPinterest settings: external-row view type changed")
    val externalBody = external.instructions()
    val viewWrite = externalBody.indexOfFirst { it.opcode == Opcode.IPUT && (it as ReferenceInstruction).reference == viewField }
    val literal = externalBody.getOrNull(viewWrite - 1) as? NarrowLiteralInstruction
    if (literal?.narrowLiteral != 13 || viewField.type != "I" ||
        (literal as? OneRegisterInstruction)?.registerA != (externalBody.getOrNull(viewWrite) as? TwoRegisterInstruction)?.registerA) {
        throw PatchException("HushPinterest settings: external-row view type isn't 13")
    }

    val builder = methods.singleOrNull { method ->
        val fields = method.instructions().mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        fields.any { it.name == "settings_main_header_settings" } && fields.any { it.name == "settings_main_header_account" }
    } ?: throw PatchException("HushPinterest settings: no unique settings/account list builder")
    val body = builder.instructions()
    val headerAt = body.indices.singleOrNull {
        body[it].opcode == Opcode.SGET && ((body[it] as ReferenceInstruction).reference as? FieldReference)?.name ==
            "settings_main_header_account"
    } ?: throw PatchException("HushPinterest settings: account header changed")
    val create = body.getOrNull(headerAt + 1)
    val constructor = body.getOrNull(headerAt + 2) as? FiveRegisterInstruction
    val add = body.getOrNull(headerAt + 3) as? FiveRegisterInstruction
    val headerType = (create as? ReferenceInstruction)?.reference?.toString()
    val headerRegister = (create as? OneRegisterInstruction)?.registerA
    val headerConstructor = (constructor as? ReferenceInstruction)?.reference as? MethodReference
    val settingsHeader = body.getOrNull(headerAt - 2)
    if (settingsHeader?.opcode != Opcode.SGET ||
        ((settingsHeader as ReferenceInstruction).reference as? FieldReference)?.name != "settings_main_header_settings" ||
        (settingsHeader as OneRegisterInstruction).registerA != (body[headerAt] as OneRegisterInstruction).registerA ||
        body.getOrNull(headerAt - 1)?.opcode != Opcode.GOTO ||
        ControlFlow.of(builder).normal[headerAt - 1] != listOf(headerAt + 1) ||
        create?.opcode != Opcode.NEW_INSTANCE || constructor?.opcode != Opcode.INVOKE_DIRECT ||
        headerConstructor?.definingClass != headerType || headerConstructor?.name != "<init>" ||
        headerConstructor.parameterTypes.map(CharSequence::toString) != listOf("I") ||
        constructor.registerCount != 2 || constructor.registerC != headerRegister ||
        constructor.registerD != (body[headerAt] as OneRegisterInstruction).registerA ||
        add?.opcode != Opcode.INVOKE_VIRTUAL || (add as ReferenceInstruction).reference.toString() != LIST_ADD ||
        add.registerCount != 2 || add.registerD != headerRegister || add.registerC > 15) {
        throw PatchException("HushPinterest settings: guaranteed header/list insertion changed")
    }
    val insertAt = headerAt + 4
    val row = builder.freeLocalsAt("HushPinterest native row", insertAt, 1, reads = listOf(add.registerC)).single()

    val clicks = methods.filter { method ->
        method.name == "invoke" && method.returnType == "Ljava/lang/Object;" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") &&
            method.instructions().any { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name ==
                "TEEN_SAFETY_RESOURCES_LINK" }
    }.flatMap { method ->
        method.instructions().withIndex().filter {
            it.value.opcode == Opcode.INSTANCE_OF && (it.value as ReferenceInstruction).reference.toString() == parent.type
        }.map { method to it.index }
    }
    val (click, checkAt) = clicks.singleOrNull()
        ?: throw PatchException("HushPinterest settings: no unique native external-row click branch")
    val clickBody = click.instructions()
    val check = clickBody[checkAt] as TwoRegisterInstruction
    val branch = clickBody.getOrNull(checkAt + 1) as? OneRegisterInstruction
    val cast = clickBody.getOrNull(checkAt + 3) as? OneRegisterInstruction
    val textRead = clickBody.getOrNull(checkAt + 4) as? TwoRegisterInstruction
    val textField = (textRead as? ReferenceInstruction)?.reference as? FieldReference
    val contextCall = clickBody.getOrNull(checkAt + 5) as? FiveRegisterInstruction
    val contextReference = (contextCall as? ReferenceInstruction)?.reference as? MethodReference
    if (branch?.opcode != Opcode.IF_EQZ || branch.registerA != check.registerA ||
        clickBody.getOrNull(checkAt + 2)?.opcode != Opcode.MOVE_OBJECT || cast?.opcode != Opcode.CHECK_CAST ||
        (cast as ReferenceInstruction).reference.toString() != parent.type || textRead?.opcode != Opcode.IGET_OBJECT ||
        textRead.registerB != cast.registerA || textField?.definingClass != parent.type || textField.type != textType ||
        contextCall?.opcode != Opcode.INVOKE_VIRTUAL || contextCall.registerCount != 1 ||
        contextReference?.definingClass != "Landroidx/fragment/app/Fragment;" || contextReference.name != "requireContext" ||
        check.registerB > 15 || contextCall.registerC > 15) {
        throw PatchException("HushPinterest settings: native external-row click shape changed")
    }
    val clickAt = checkAt + 2
    val scratch = click.freeLocalsAt("HushPinterest native click", clickAt, 1,
        reads = listOf(check.registerB, contextCall.registerC)).single()
    val unit = clickBody.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        .distinct().singleOrNull { it.type == "Lkotlin/Unit;" }
        ?: throw PatchException("HushPinterest settings: native click return changed")
    val constructorStub = owned.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.isEmpty() }
        ?: throw PatchException("HushPinterest settings: owned row constructor missing")
    if (classDefBy(ENTRY).methods.none { it.name == "nativeTitleId" && it.parameterTypes.isEmpty() && it.returnType == "I" }) {
        throw PatchException("HushPinterest settings: owned title hook missing")
    }

    val generated = ImmutableMethod(NATIVE_SETTINGS_ITEM, "<init>", emptyList(), "V", constructorStub.accessFlags,
        null, null, MutableMethodImplementation(5)).toMutable().apply {
        addInstructions(0, """
            const/4 v0, 0x1
            const/4 v1, 0x0
            const-string v2, ""
            new-instance v3, $textType
            invoke-direct { v3, v0, v1, v2 }, $textConstructor
            invoke-static {}, $ENTRY->nativeTitleId()I
            move-result v0
            invoke-direct { p0, v0, v3 }, $parentCall
            return-void
        """)
    }
    owned.setSuperClass(parent.type)
    // The native list casts to a marker implemented by the concrete row, not by its base.
    owned.interfaces.addAll(classDefBy(external.definingClass).interfaces)
    owned.methods.remove(constructorStub)
    owned.methods.add(generated)
    mutable(builder).addInstructionsAtControlFlowLabel(insertAt, """
        new-instance v$row, $NATIVE_SETTINGS_ITEM
        invoke-direct { v$row }, $NATIVE_SETTINGS_ITEM-><init>()V
        invoke-virtual { v${add.registerC}, v$row }, $LIST_ADD
    """)
    mutable(click).addInstructionsAtControlFlowLabel(clickAt, """
        instance-of v$scratch, v${check.registerB}, $NATIVE_SETTINGS_ITEM
        if-eqz v$scratch, :hush_native_settings_original
        invoke-virtual { v${contextCall.registerC} }, Landroidx/fragment/app/Fragment;->requireActivity()Landroidx/fragment/app/FragmentActivity;
        move-result-object v$scratch
        invoke-static { v$scratch }, $ENTRY->open(Landroid/app/Activity;)Z
        sget-object v$scratch, $unit
        return-object v$scratch
        :hush_native_settings_original
        nop
    """)
}
