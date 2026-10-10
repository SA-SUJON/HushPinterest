/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.actions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.localRegisterCount
import app.morphe.patches.pinterest.misc.extension.parameterRegister
import app.morphe.patches.pinterest.misc.extension.parameterRegisterNumber
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireLocals
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.extension.requireStub
import app.morphe.patches.pinterest.misc.extension.writeStub
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.RegisterKind
import app.morphe.util.RegisterKinds
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "System share sheet"
private const val SYSTEM_SHARE = "$EXTENSION_PACKAGE/actions/SystemShare;"
private const val SENDABLE = "Lcom/pinterest/sendshare/model/SendableObject;"

/** The extension stubs this patch fills with the target build's own SendableObject fields. */
private val SYSTEM_SHARE_STUBS = listOf("sendableId", "sendableType")

@Suppress("unused")
val systemSharePatch = bytecodePatch(
    name = PATCH,
    description = "Uses Android's own share menu when you share a pin link. Screenshot and download actions work as" +
        " before. Good if you want your usual share targets. Starts off. Turn it on in HushPinterest " +
        "settings > Pin actions.",
) {
    category("Interface")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())
    execute {
        requireStatusMethod("systemShare")
        requireStatusMethod("pinShare")
        SYSTEM_SHARE_STUBS.forEach { requireStub(SYSTEM_SHARE, it) }
        // Native shares from the menu and closeup converge here. The source enum distinguishes
        // screenshots/downloads, and the extension separately refuses boards, people and invites.
        val method = Fingerprint(
            returnType = "V",
            custom = { candidate, _ ->
                candidate.parameterTypes.size == 5 && candidate.parameterTypes[1].toString() == "I" &&
                    candidate.parameterTypes[3].toString() == "Z" && candidate.fields().map { it.name }.toSet().containsAll(
                        setOf("APP_LIST_AND_CONTACT_SUGGESTIONS_FOR_UPSELL", "SCREENSHOT", "DOWNLOAD"),
                    )
            },
        ).methodOrNull ?: throw PatchException("$PATCH: no native pin share chooser with screenshot/download source guards")
        method.requireLocals(PATCH, 2)
        val model = method.parameterRegister(0)
        val source = method.parameterRegister(2)
        val sourceType = method.parameterTypes[2].toString()
        val fragment = Fingerprint(
            returnType = "V",
            parameters = listOf("Landroid/os/Bundle;"),
            strings = listOf("context"),
            custom = { candidate, _ ->
                val body = candidate.implementation?.instructions ?: return@Fingerprint false
                candidate.definingClass != method.definingClass &&
                    body.any { instruction ->
                        instruction.opcode == Opcode.CHECK_CAST &&
                            (instruction as? ReferenceInstruction)?.reference?.toString() == SENDABLE
                    } &&
                    body.any { instruction ->
                        instruction.opcode == Opcode.CHECK_CAST &&
                            (instruction as? ReferenceInstruction)?.reference?.toString() == sourceType
                    } &&
                    body.any { (it as? ReferenceInstruction)?.reference?.toString()?.endsWith("->onCreate(Landroid/os/Bundle;)V") == true }
            },
        ).methodOrNull ?: throw PatchException("$PATCH: no native closeup share sheet fragment")
        fragment.requireLocals(PATCH, 1)
        val instructions = fragment.implementation!!.instructions
        val superCall = instructions.indexOfLast {
            (it as? ReferenceInstruction)?.reference?.toString()?.endsWith("->onCreate(Landroid/os/Bundle;)V") == true
        }
        if (superCall < 0) throw PatchException("$PATCH: closeup share sheet onCreate order changed")
        val sendableValue = instructions.withIndex().take(superCall).lastOrNull { (_, instruction) ->
            instruction.opcode == Opcode.CHECK_CAST &&
                (instruction as? ReferenceInstruction)?.reference?.toString() == SENDABLE
        }?.let { (_, instruction) -> (instruction as OneRegisterInstruction).registerA }
            ?: throw PatchException("$PATCH: closeup share sheet sendable register changed")
        val sourceValue = instructions.withIndex().take(superCall).lastOrNull { (_, instruction) ->
            instruction.opcode == Opcode.CHECK_CAST &&
                (instruction as? ReferenceInstruction)?.reference?.toString() == sourceType
        }?.let { (_, instruction) -> (instruction as OneRegisterInstruction).registerA }
            ?: throw PatchException("$PATCH: closeup share sheet source register changed")
        // The sheet closes through Pinterest's base screen fragment, whose obfuscated owner and
        // name change every build (xu1/f.z6 in 14.38.0). A written-in name once left a build
        // calling a class it doesn't have, so the method is found above the fragment instead.
        val close = superclassChain(fragment.definingClass).flatMap { type ->
            classDefByOrNull(type)?.methods?.filter { it.closesScreen() }?.map { "$type->${it.name}()V" } ?: emptyList()
        }.toList().singleOrNull()
            ?: throw PatchException("$PATCH: no single close-screen method above ${fragment.definingClass}")
        // The sheet's sendable names its pin by fields whose names, and the accessors over them,
        // change every build (e() was the id in 14.38.0 and is the kind in 14.39.0), so the stubs
        // read the two fields the (String, int) constructor stores.
        val sendableClass = classDefByOrNull(SENDABLE) ?: throw PatchException("$PATCH: no $SENDABLE")
        val sendable = sendableFields(sendableClass)
        // The extension makes a pin link only for kind 0, so a build that numbered its kinds
        // differently would hand Android's sheet a board's id as a pin's.
        pinSendableKind(sendableClass, sendable.second, pinType())
        // Every lookup is done, so a refusal above leaves both host methods as they were.
        writeStub(SYSTEM_SHARE, "sendableId", 2, """
            check-cast p0, $SENDABLE
            iget-object v0, p0, ${sendable.first}
            return-object v0
        """)
        writeStub(SYSTEM_SHARE, "sendableType", 2, """
            check-cast p0, $SENDABLE
            iget v0, p0, ${sendable.second}
            return v0
        """)
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, $model
                move-object/from16 v1, $source
                invoke-static { v0, v1 }, $EXTENSION_PACKAGE/actions/SystemShare;->open(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :hush_original_share
                return-void
            """,
            ExternalLabel("hush_original_share", method.getInstruction(0)),
        )
        fragment.addInstructionsWithLabels(
            superCall + 1,
            """
                invoke-static { v$sendableValue, v$sourceValue }, $SYSTEM_SHARE->openSendable(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :hush_original_closeup_share
                invoke-virtual { p0 }, $close
                return-void
            """,
            ExternalLabel("hush_original_closeup_share", fragment.getInstruction(superCall + 1)),
        )
        enableCapability("pinShare")
        enableStatus("systemShare")
    }
}

/**
 * The sendable's id and kind: the String and the int its `(String, int)` constructor stores in its
 * own fields, each written once from that parameter. [pinSendableKind] checks a pin's kind is 0.
 */
internal fun sendableFields(owner: ClassDef): Pair<FieldReference, FieldReference> {
    val init = owner.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "I")
    } ?: throw PatchException("$PATCH: no single ${owner.type}(String, int) constructor")
    val body = init.implementation?.instructions?.toList() ?: throw PatchException("$PATCH: ${owner.type}(String, int) has no code")
    val self = init.localRegisterCount()
    fun stored(opcode: Opcode, parameter: Int, type: String): FieldReference {
        val from = init.parameterRegisterNumber(parameter)
        return body.filter { it.opcode == opcode && (it as TwoRegisterInstruction).registerA == from && it.registerB == self }
            .map { (it as ReferenceInstruction).reference as FieldReference }
            .filter { it.definingClass == owner.type && it.type == type }
            .distinct().singleOrNull()
            ?: throw PatchException("$PATCH: ${owner.type}(String, int) doesn't store parameter $parameter in one $type field")
    }
    return stored(Opcode.IPUT_OBJECT, 0, "Ljava/lang/String;") to stored(Opcode.IPUT, 1, "I")
}

/**
 * Refuses unless a pin's sendable kind is 0, the only kind the extension makes a pin link for. The
 * sendable's model constructor sorts what it's given by class: the one branch that tests for the
 * pin model, [pin], has to store a literal zero in [kind] on every path, before it can branch.
 */
internal fun pinSendableKind(owner: ClassDef, kind: FieldReference, pin: String) {
    fun Instruction.type() = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type
    val init = owner.methods.singleOrNull { method ->
        method.name == "<init>" && method.parameterTypes.size == 1 &&
            method.implementation?.instructions?.any { it.opcode == Opcode.INSTANCE_OF && it.type() == pin } == true
    } ?: throw PatchException("$PATCH: no single ${owner.type} constructor that tells a pin model ($pin) apart")
    val body = init.implementation!!.instructions.toList()
    val test = body.indices.singleOrNull { body[it].opcode == Opcode.INSTANCE_OF && body[it].type() == pin }
        ?: throw PatchException("$PATCH: ${owner.type} tests for a pin model more than once")
    val branch = body.getOrNull(test + 1)
    val result = (body[test] as TwoRegisterInstruction).registerA
    if ((body[test] as TwoRegisterInstruction).registerB != init.parameterRegisterNumber(0) ||
        branch?.opcode != Opcode.IF_EQZ || (branch as OneRegisterInstruction).registerA != result
    ) throw PatchException("$PATCH: ${owner.type} no longer branches on its model being a pin")
    val self = init.localRegisterCount()
    var at = test + 2
    while (true) {
        val instruction = body.getOrNull(at) ?: throw PatchException("$PATCH: ${owner.type}'s pin branch never sets a kind")
        if (instruction.opcode == Opcode.IPUT && (instruction as TwoRegisterInstruction).registerB == self &&
            (instruction as ReferenceInstruction).reference == kind
        ) break
        if (instruction is OffsetInstruction || !instruction.opcode.canContinue()) {
            throw PatchException("$PATCH: ${owner.type}'s pin branch branches before it sets its kind")
        }
        at++
    }
    val value = (body[at] as TwoRegisterInstruction).registerA
    if (RegisterKinds.of(init).at(at)?.getOrNull(value) != RegisterKind.ZERO) {
        throw PatchException("$PATCH: ${owner.type} doesn't give a pin kind 0, the only kind a pin link is made for")
    }
}

/**
 * Pinterest's base screen fragment closes itself by comparing its own ScreenDescription with the top
 * of the screen stack: the top screen signals back navigation with TRUE, any other one removes itself.
 */
private fun Method.closesScreen(): Boolean {
    if (returnType != "V" || parameterTypes.isNotEmpty() || AccessFlags.STATIC.isSet(accessFlags)) return false
    val references = implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        ?: return false
    return references.count { it.endsWith("()Lcom/pinterest/framework/screens/ScreenDescription;") } == 2 &&
        "Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;" in references &&
        references.any { it.endsWith("->onNext(Ljava/lang/Object;)V") }
}
