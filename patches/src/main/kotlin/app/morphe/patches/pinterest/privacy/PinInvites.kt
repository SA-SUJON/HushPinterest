/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.freeLocalsAt
import app.morphe.patches.pinterest.misc.extension.parameterRegisterNumber
import app.morphe.patches.pinterest.misc.extension.requireParameterIntact
import app.morphe.patches.pinterest.misc.extension.writeStub
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.Label
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PLAIN_PIN_LINKS = "$EXTENSION_PACKAGE/privacy/PlainPinLinks;"
internal const val PIN_INVITE_HOOK = "$PLAIN_PIN_LINKS->invite(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V"
internal const val DIRECT_SHARE_HOOK =
    "$PLAIN_PIN_LINKS->directShare(Ljava/lang/Object;Ljava/lang/Object;Landroid/content/Intent;)V"
private const val SENDABLE = "Lcom/pinterest/sendshare/model/SendableObject;"
private const val STRING = "Ljava/lang/String;"
private const val INTENT = "Landroid/content/Intent;"
private const val START_ACTIVITY = "Landroid/content/Context;->startActivity(Landroid/content/Intent;)V"
private const val WHAT = "Plain pin links"

/**
 * Pinterest's invite logger, which gets the shared object and the invite link it made for it, and
 * where in it the hook goes: in front of its call that builds the log event, which reads the
 * object's kind from [kind] and its id from [id]. [url] is the logger's invite link parameter and
 * [scratch] the local the hook borrows to reach it. [kindGetter] and [idGetter] are the shared
 * object's own getters the log event's kind and id come from.
 */
internal data class PinInvite(
    val logger: Method,
    val at: Int,
    val kind: Int,
    val id: Int,
    val url: Int,
    val scratch: Int,
    val kindGetter: MethodReference,
    val idGetter: MethodReference,
)

/**
 * Pinterest's share straight to one app, and where its hook goes: in front of [at], the call that
 * starts the app with the intent in [intent]. [shared] and [url] hold the shared object and the
 * invite link that Pinterest only hands its invite logger once the app is open.
 */
internal data class DirectShare(val method: Method, val at: Int, val shared: Int, val url: Int, val intent: Int)

/**
 * The logger: an instance method taking (SendableObject, A, B, int, String, String) and returning
 * nothing, called by a method of its own class that reads the "invite_url" field of the invite
 * answer. Pinterest copies or shares that same link right after the call. Throws naming what is
 * missing, before any change, when the shape isn't there exactly once.
 */
internal fun BytecodePatchContext.findPinInvite(): PinInvite {
    val loggers = classDefByStrings("invite_url").flatMap { owner ->
        val own = owner.methods.filter { it.isInviteLogger() }
        owner.methods.filter { caller -> "invite_url" in caller.strings() }.flatMap { caller ->
            caller.calls().mapNotNull { call -> own.firstOrNull { it.sameAs(call) } }
        }
    }.distinctBy { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})" }
    val logger = loggers.singleOrNull()
        ?: throw PatchException("$WHAT: expected one invite logger called beside the invite link, found ${loggers.size}")
    val types = logger.parameterTypes.map { it.toString() }
    val instructions = logger.implementation!!.instructions.toList()
    val builds = instructions.indices.filter { index ->
        val call = instructions[index].callTo() ?: return@filter false
        val parameters = call.parameterTypes.map { it.toString() }
        call.returnType != "V" && parameters.size >= 7 && parameters[0] == types[1] && parameters[1].startsWith("L") &&
            parameters[2] == types[2] && parameters.subList(3, 7) == listOf(STRING, "I", STRING, STRING)
    }
    val at = builds.singleOrNull()
        ?: throw PatchException("$WHAT: expected one log event built in ${logger.signature()}, found ${builds.size}")
    val call = instructions[at]
    val arguments = when (call) {
        is RegisterRangeInstruction -> (call.startRegister until call.startRegister + call.registerCount).toList()
        is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
            .take(call.registerCount)
        else -> throw PatchException("$WHAT: the log event call in ${logger.signature()} has no supported registers")
    }
    val offset = if (call.opcode == Opcode.INVOKE_STATIC || call.opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
    if (arguments.size < offset + 7) throw PatchException("$WHAT: the log event call in ${logger.signature()} takes too few registers")
    val kind = arguments[offset + 1]
    val id = arguments[offset + 3]
    if (kind > 15 || id > 15 || kind == id) {
        throw PatchException("$WHAT: the shared object's kind and id in ${logger.signature()} no longer fit the hook's call")
    }
    val flow = ControlFlow.of(logger)
    if (flow.entered(at)) throw PatchException("$WHAT: the log event call in ${logger.signature()} is a branch target")
    // Both values come straight from the shared object, so the hook pairs the link with that pin.
    val callType = (call as ReferenceInstruction).reference as MethodReference
    val kindGetter = logger.requireGetter(flow, at, kind, callType.parameterTypes[1].toString(), "kind")
    val idGetter = logger.requireGetter(flow, at, id, STRING, "id")
    logger.requireParameterIntact(WHAT, 5, listOf(at))
    val url = logger.parameterRegisterNumber(5)
    val scratch = logger.freeLocalsAt(WHAT, at, 1, reads = listOf(kind, id)).single()
    return PinInvite(logger, at, kind, id, url, scratch, kindGetter, idGetter)
}

/**
 * The share straight to one app: a method holding "invite_url" that starts the app with an intent a
 * static builder made, and only then passes the shared object and the invite link to an invite
 * logger of the shape [findPinInvite] looks for. The link is already in the intent's text by then,
 * so the invite logger's hook comes too late for it. Nothing between the start and the logger call
 * is reached from anywhere else or writes either value, and nothing between the builder and the
 * start writes the intent, so the registers hold at the start what the logger gets afterwards.
 * Throws naming what is missing, before any change, unless that shape is there exactly once.
 */
internal fun BytecodePatchContext.findDirectShare(): DirectShare {
    val found = classDefByStrings("invite_url").distinctBy { it.type }.flatMap { owner ->
        owner.methods.filter { "invite_url" in it.strings() }.flatMap { it.directShares() }
    }
    return found.singleOrNull()
        ?: throw PatchException("$WHAT: expected one share straight to another app beside the invite link, found ${found.size}")
}

private fun Method.directShares(): List<DirectShare> {
    val flow = try {
        ControlFlow.of(this)
    } catch (_: IllegalArgumentException) {
        return emptyList()
    }
    val instructions = flow.instructions
    return instructions.indices.mapNotNull { start ->
        val call = instructions[start]
        if (call.opcode != Opcode.INVOKE_VIRTUAL || call.callTo()?.toString() != START_ACTIVITY) return@mapNotNull null
        val intent = (call as FiveRegisterInstruction).registerD
        val log = (start + 1 until instructions.size).firstOrNull { instructions[it].isInviteLoggerCall() }
            ?: return@mapNotNull null
        val logged = instructions[log].arguments()
        if (logged.size != 7) return@mapNotNull null
        val shared = logged[1]
        val url = logged[6]
        // The hook's call names its three registers directly, so each must be a distinct v0 to v15.
        if (listOf(shared, url, intent).any { it > 15 } || setOf(shared, url, intent).size != 3) return@mapNotNull null
        if (flow.entered(start) || !flow.enteredOnlyFrom(start, log)) return@mapNotNull null
        if ((start + 1 until log).any { instructions[it].writes(shared) || instructions[it].writes(url) }) return@mapNotNull null
        val made = (start - 1 downTo 0).firstOrNull { instructions[it].writes(intent) } ?: return@mapNotNull null
        val builder = if (made > 0) instructions[made - 1] else return@mapNotNull null
        val built = builder.callTo()
        if (instructions[made].opcode != Opcode.MOVE_RESULT_OBJECT || built == null || built.returnType != INTENT ||
            (builder.opcode != Opcode.INVOKE_STATIC && builder.opcode != Opcode.INVOKE_STATIC_RANGE) ||
            !flow.enteredOnlyFrom(made, start)
        ) return@mapNotNull null
        DirectShare(this, start, shared, url, intent)
    }
}

/**
 * Puts both of Plain pin links' hooks in: the invite logger's records each invite link with the pin
 * it was made for, in front of the log event call, and the direct share's turns the link in the
 * intent's text into the plain one before the other app starts. The direct share reads the shared
 * object's kind and id through two stubs that call the same getters as the log event. Checks both
 * places before it changes either.
 */
internal fun BytecodePatchContext.insertPlainPinLinks(invite: PinInvite, share: DirectShare) {
    if (invite.logger.sameAs(share.method)) {
        throw PatchException("$WHAT: the invite logger and the direct share are the same method")
    }
    val logger = mutable(invite.logger)
    val sharer = mutable(share.method)
    if ((logger.implementation!!.instructions[invite.at] as BuilderInstruction).location.labels.isNotEmpty()) {
        throw PatchException("$WHAT: the log event call in ${invite.logger.signature()} is a branch target")
    }
    // Pinterest's app start opens a try block (14.38.0 and 14.39.0 both), so it carries that block's
    // start label. Only a label something jumps to keeps a path away from the hook.
    if (sharer.jumpLabelsAt(share.at).isNotEmpty()) {
        throw PatchException("$WHAT: the direct share's start in ${share.method.signature()} is a branch target")
    }
    for ((stub, getter) in listOf("sharedKind" to invite.kindGetter, "sharedId" to invite.idGetter)) {
        writeStub(
            PLAIN_PIN_LINKS, stub, 2,
            """
                if-eqz p0, :none
                check-cast p0, $SENDABLE
                invoke-virtual { p0 }, $getter
                move-result-object v0
                return-object v0
                :none
                const/4 v0, 0x0
                return-object v0
            """,
        )
    }
    logger.addInstructions(
        invite.at,
        """
            move-object/from16 v${invite.scratch}, v${invite.url}
            invoke-static { v${invite.kind}, v${invite.id}, v${invite.scratch} }, $PIN_INVITE_HOOK
        """,
    )
    sharer.addInstructions(share.at, "invoke-static { v${share.shared}, v${share.url}, v${share.intent} }, $DIRECT_SHARE_HOOK")
}

/**
 * The labels on instruction [index] that a branch, a switch or a handler can jump to: every label
 * but a try block's start or end. Those two only mark where a block's range begins or ends, and
 * stay on the instruction when code goes in front of it, so that code lands just before a block
 * starting there or at the end of one ending there. The direct share's hook writes no register and
 * catches everything it throws, so either is the same to it. A jump label stays on the instruction
 * too, and the path through it would skip the hook.
 */
private fun MutableMethod.jumpLabelsAt(index: Int): Set<Label> {
    val implementation = implementation!!
    val boundaries = implementation.tryBlocks.flatMap { listOf(it.start, it.end) }.toSet()
    return (implementation.instructions[index] as BuilderInstruction).location.labels.filterTo(mutableSetOf()) { it !in boundaries }
}

private fun Method.isInviteLogger(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "V" || implementation == null) return false
    return parameterTypes.map { it.toString() }.isInviteLoggerShape()
}

/** An instance call to a method of the invite logger's shape, in any class. */
private fun Instruction.isInviteLoggerCall(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val call = callTo() ?: return false
    return call.returnType == "V" && call.parameterTypes.map { it.toString() }.isInviteLoggerShape()
}

private fun List<String>.isInviteLoggerShape(): Boolean =
    size == 6 && this[0] == SENDABLE && this[3] == "I" && this[4] == STRING && this[5] == STRING &&
        listOf(this[1], this[2]).all { it.startsWith("L") && !it.startsWith("Ljava/") && !it.startsWith("Landroid/") }

/** A call's registers, the receiver first on an instance call. */
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** Whether this instruction writes [register], alone or as half of a wide pair. */
private fun Instruction.writes(register: Int): Boolean {
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && (destination == register || (opcode.setsWideRegister() && destination + 1 == register))
}

/**
 * Whether every way into the instructions after [from], up to and including [to], comes from [from]
 * or from one of them: no branch, switch or handler reaches inside from elsewhere.
 */
private fun ControlFlow.enteredOnlyFrom(from: Int, to: Int): Boolean {
    val inside = from + 1..to
    if (exceptional.any { handlers -> handlers.any { it in inside } }) return false
    return normal.withIndex().all { (index, next) -> index in from until to || next.none { it in inside } }
}

private fun Method.sameAs(call: MethodReference) = call.definingClass == definingClass && call.name == name &&
    call.returnType == returnType && call.parameterTypes.map { it.toString() } == parameterTypes.map { it.toString() }

private fun Method.signature() = "$definingClass->$name"

private fun Method.strings() = implementation?.instructions?.mapNotNull {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
}.orEmpty()

private fun Method.calls() = implementation?.instructions?.mapNotNull { it.callTo() }.orEmpty()

// Opcode.name is dexlib2's smali name field ("invoke-virtual"), not the enum constant's name.
private fun Instruction.callTo(): MethodReference? =
    if (opcode.name.startsWith("invoke-")) (this as? ReferenceInstruction)?.reference as? MethodReference else null

/** Whether anything but the instruction just above reaches [index]: a branch, a switch or a handler. */
private fun ControlFlow.entered(index: Int): Boolean =
    exceptional.any { index in it } || normal.withIndex().any { (from, next) -> from != index - 1 && index in next }

/**
 * Throws unless [register] holds, at [at], what a no-argument getter of parameter 0 (the shared
 * object) returned as [type]: the last write above is its move-result, and nothing enters between.
 * Answers that getter.
 */
private fun Method.requireGetter(flow: ControlFlow, at: Int, register: Int, type: String, what: String): MethodReference {
    val instructions = flow.instructions
    val write = (at - 1 downTo 0).firstOrNull { index ->
        val instruction = instructions[index]
        val destination = (instruction as? OneRegisterInstruction)?.registerA
        instruction.opcode.setsRegister() && destination != null &&
            (destination == register || (instruction.opcode.setsWideRegister() && destination + 1 == register))
    }
    fun refuse(): Nothing =
        throw PatchException("$WHAT: the shared object's $what in ${signature()} isn't read from it right above the log event")
    if (write == null || write == 0 || instructions[write].opcode != Opcode.MOVE_RESULT_OBJECT) refuse()
    val getter = instructions[write - 1]
    val reference = getter.callTo() ?: refuse()
    if (getter.opcode != Opcode.INVOKE_VIRTUAL || (getter as FiveRegisterInstruction).registerCount != 1 ||
        getter.registerC != parameterRegisterNumber(0) || reference.definingClass != SENDABLE ||
        reference.parameterTypes.isNotEmpty() || reference.returnType != type || (write..at).any { flow.entered(it) }
    ) refuse()
    requireParameterIntact(WHAT, 0, listOf(write - 1))
    return reference
}

private fun BytecodePatchContext.mutable(method: Method) = mutableClassDefBy(method.definingClass).methods.single {
    it.name == method.name && it.returnType == method.returnType &&
        it.parameterTypes.map { type -> type.toString() } == method.parameterTypes.map { type -> type.toString() }
}
