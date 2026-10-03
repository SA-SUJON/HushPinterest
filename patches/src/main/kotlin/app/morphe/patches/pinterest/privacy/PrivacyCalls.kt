/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.privacy

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.pinterest.misc.settings.EXTENSION_ROOT
import app.morphe.patches.pinterest.misc.settings.sendToStandIn
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** A complete method identity, including overloads. */
internal fun MethodReference.identity(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

internal fun Instruction.callReference(): MethodReference? =
    if (opcode in INVOKES) (this as? ReferenceInstruction)?.reference as? MethodReference else null

private val INVOKES = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
    Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE,
)

/** Replaces calls without borrowing registers or disturbing their following move-result. */
internal fun BytecodePatchContext.redirectPrivacyCalls(
    targets: Map<String, String>,
    callerAllowed: (String) -> Boolean = { true },
): Map<String, Int> {
    val callers = mutableListOf<String>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT) || !callerAllowed(owner.type)) return@classDefForEach
        if (owner.methods.any { method -> method.implementation?.instructions?.any {
                it.callReference()?.identity() in targets
            } == true }) callers += owner.type
    }
    val counts = mutableMapOf<String, Int>()
    for (type in callers) for (method in mutableClassDefBy(type).methods) {
        val sites = method.implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
            instruction.callReference()?.identity()?.takeIf { it in targets }?.let { index to it }
        }.orEmpty()
        for ((index, target) in sites.asReversed()) {
            method.sendToStandIn(index, targets.getValue(target))
            counts[target] = counts.getOrDefault(target, 0) + 1
        }
    }
    return counts
}
