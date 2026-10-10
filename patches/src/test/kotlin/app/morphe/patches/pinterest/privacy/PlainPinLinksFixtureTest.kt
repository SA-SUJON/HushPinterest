/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.privacy

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.pinterest.misc.extension.PatchLogCapture
import app.morphe.patches.pinterest.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * Plain pin links' two hooks against each declared Pinterest build: the invite logger's and the
 * share straight to one app. Every class holding the "invite_url" string is loaded, so a logger or
 * direct share the patch finds once here is the only one in the APK, along with every class making
 * an outgoing link call, so Strip link tracking runs as it does on a phone.
 */
@Category(FixtureTests::class)
class PlainPinLinksFixtureTest {
    @Test
    fun `both hooks resolve once in each declared build, in front of the log event and the app start`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = read(build)
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val found = context.findPinInvite()
            val share = context.findDirectShare()
            val original = found.logger.implementation!!
            val shareOriginal = share.method.implementation!!
            val warnings = PatchLogCapture.warnings { stripLinkTrackingPatch.execute(context) }
            assertEquals(build.name, emptyList<String>(), warnings)
            for (flag in listOf("stripLinkTracking", "linkTracking", "plainPinLinks")) assertFlag(context, flag, 1)

            val logger = mutableMethod(context, found.logger)
            val body = logger.implementation!!.instructions.toList()
            val move = body[found.at]
            val hook = body[found.at + 1]
            assertEquals("${build.name} move", Opcode.MOVE_OBJECT_FROM16, move.opcode)
            assertEquals("${build.name} scratch", found.scratch, (move as TwoRegisterInstruction).registerA)
            assertEquals("${build.name} invite link parameter", found.url, move.registerB)
            assertEquals("${build.name} link parameter is p6", original.registerCount - 1, found.url)
            assertEquals("${build.name} hook opcode", Opcode.INVOKE_STATIC, hook.opcode)
            assertEquals("${build.name} hook target", PIN_INVITE_HOOK, (hook as ReferenceInstruction).reference.toString())
            assertEquals("${build.name} hook arguments", listOf(found.kind, found.id, found.scratch), arguments(hook))
            // The kind and id the hook reads are the log event call's own, so they're the shared object's.
            val event = original.instructions.toList()[found.at]
            val passed = arguments(event)
            val offset = if (event.opcode == Opcode.INVOKE_STATIC || event.opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
            assertEquals("${build.name} kind", passed[offset + 1], found.kind)
            assertEquals("${build.name} id", passed[offset + 3], found.id)
            assertTrue("${build.name} scratch is a local", found.scratch < original.registerCount - 7)
            assertEquals("${build.name} register count", original.registerCount, logger.implementation!!.registerCount)
            assertEquals("${build.name} the rest of the logger", render(original.instructions.toList()),
                render(body.filterIndexed { index, _ -> index != found.at && index != found.at + 1 }))

            // The direct share: the hook right in front of the app start, with the shared object and link
            // the invite logger gets once the app is open, and the intent the app starts with.
            val sharer = mutableMethod(context, share.method)
            val shareBody = sharer.implementation!!.instructions.toList()
            val shareHook = shareBody[share.at]
            assertEquals("${build.name} direct share hook opcode", Opcode.INVOKE_STATIC, shareHook.opcode)
            assertEquals("${build.name} direct share hook target", DIRECT_SHARE_HOOK, (shareHook as ReferenceInstruction).reference.toString())
            assertEquals("${build.name} direct share hook arguments", listOf(share.shared, share.url, share.intent), arguments(shareHook))
            val start = shareBody[share.at + 1]
            assertEquals("${build.name} app start", START_ACTIVITY, (start as ReferenceInstruction).reference.toString())
            assertEquals("${build.name} app start's intent", share.intent, arguments(start)[1])
            val logged = shareOriginal.instructions.toList().drop(share.at + 1).first { call ->
                (call.opcode == Opcode.INVOKE_VIRTUAL || call.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                    ((call as ReferenceInstruction).reference as MethodReference).parameterTypes.firstOrNull()?.toString() == SENDABLE
            }
            assertEquals("${build.name} shared object", share.shared, arguments(logged)[1])
            assertEquals("${build.name} invite link", share.url, arguments(logged)[6])
            assertEquals("${build.name} direct share register count", shareOriginal.registerCount, sharer.implementation!!.registerCount)
            assertEquals("${build.name} the rest of the direct share", outsideLinkCalls(shareOriginal.instructions.toList()),
                outsideLinkCalls(shareBody.filterIndexed { index, _ -> index != share.at }))

            // The stubs the direct share reads the shared object through call the log event's own getters.
            for ((stub, getter) in listOf("sharedKind" to found.kindGetter, "sharedId" to found.idGetter)) {
                assertEquals("${build.name} $stub getter owner", SENDABLE, getter.definingClass)
                val stubBody = context.mutableClassDefBy(PLAIN_PIN_LINKS).methods.single { it.name == stub }.implementation!!.instructions.toList()
                assertEquals("${build.name} $stub", listOf(Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT,
                    Opcode.RETURN_OBJECT, Opcode.CONST_4, Opcode.RETURN_OBJECT), stubBody.map { it.opcode })
                assertEquals("${build.name} $stub reads", getter.toString(), (stubBody[2] as ReferenceInstruction).reference.toString())
            }

            val hosts = classes.flatMap { context.mutableClassDefBy(it.type).methods }.filter { method ->
                references(method).any { it.startsWith(PLAIN_PIN_LINKS) }
            }
            assertEquals("${build.name} host methods calling Plain pin links",
                setOf(found.logger.signature(), share.method.signature()), hosts.map { it.signature() }.toSet())
            assertEquals("${build.name} one hook call each", 2, hosts.sumOf { method -> references(method).count { it.startsWith(PLAIN_PIN_LINKS) } })
            println(
                "${build.name}: ${found.logger.signature()} regs=${original.registerCount}, hook at ${found.at} " +
                    "in front of ${(event as ReferenceInstruction).reference}, kind v${found.kind}, id v${found.id}, " +
                    "link v${found.url}, scratch v${found.scratch}; direct share ${share.method.signature()} " +
                    "regs=${shareOriginal.registerCount}, hook at ${share.at}, shared v${share.shared}, " +
                    "link v${share.url}, intent v${share.intent}",
            )
        }
    }

    @Test
    fun `no invite logger leaves Plain pin links out and Strip link tracking in`() {
        leavesOut { classes, found, _ ->
            classes.map { owner -> if (owner.type != found.logger.definingClass) owner
                else owner.withMethods(owner.methods.filterNot { it.signature() == found.logger.signature() }) }
        }
    }

    @Test
    fun `a second invite logger beside the invite link leaves Plain pin links out`() {
        leavesOut { classes, found, _ ->
            classes.map { owner ->
                if (owner.type != found.logger.definingClass) return@map owner
                val logger = owner.methods.single { it.signature() == found.logger.signature() }
                val caller = owner.methods.first { method -> "invite_url" in method.strings() &&
                    references(method).any { it == found.logger.signature() } }
                val second = ImmutableMethodReference(owner.type, "hushSecondLogger", logger.parameterTypes, "V")
                owner.withMethods(owner.methods + logger.copy("hushSecondLogger") + caller.copy("hushSecondCaller") { impl ->
                    val at = impl.instructions.indexOfFirst { (it as? ReferenceInstruction)?.reference.toString() == found.logger.signature() }
                    impl.replaceInstruction(at, retarget(impl.instructions[at], second))
                })
            }
        }
    }

    @Test
    fun `a second log event in the logger leaves Plain pin links out`() {
        leavesOut { classes, found, _ ->
            editLogger(classes, found) { impl ->
                val event = impl.instructions[found.at]
                impl.addInstruction(found.at, retarget(event, (event as ReferenceInstruction).reference as MethodReference))
            }
        }
    }

    @Test
    fun `a log event that is a branch target leaves Plain pin links out`() {
        leavesOut { classes, found, _ ->
            editLogger(classes, found) { impl ->
                val label = impl.newLabelForIndex(found.at)
                impl.addInstruction(0, BuilderInstruction21t(Opcode.IF_EQZ, impl.registerCount - 6, label))
            }
        }
    }

    @Test
    fun `a kind no longer read from the shared object leaves Plain pin links out`() {
        leavesOut { classes, found, _ ->
            editLogger(classes, found) { impl -> impl.addInstruction(found.at, BuilderInstruction11n(Opcode.CONST_4, found.kind, 0)) }
        }
    }

    @Test
    fun `an invite link parameter written before the log event leaves Plain pin links out`() {
        leavesOut { classes, found, _ ->
            editLogger(classes, found) { impl -> impl.addInstruction(0, BuilderInstruction21s(Opcode.CONST_16, found.url, 0)) }
        }
    }

    @Test
    fun `no app start beside the invite link leaves Plain pin links out`() {
        leavesOut { classes, _, share ->
            editSharer(classes, share) { impl -> impl.replaceInstruction(share.at, BuilderInstruction10x(Opcode.NOP)) }
        }
    }

    @Test
    fun `a second direct share leaves Plain pin links out`() {
        leavesOut { classes, _, share ->
            classes.map { owner ->
                if (owner.type != share.method.definingClass) return@map owner
                val sharer = owner.methods.single { it.signature() == share.method.signature() }
                owner.withMethods(owner.methods + sharer.copy("hushSecondShare"))
            }
        }
    }

    @Test
    fun `a shared object written after the app start leaves Plain pin links out`() {
        leavesOut { classes, _, share ->
            editSharer(classes, share) { impl -> impl.addInstruction(share.at + 1, BuilderInstruction11n(Opcode.CONST_4, share.shared, 0)) }
        }
    }

    @Test
    fun `an app start that is a branch target leaves Plain pin links out`() {
        leavesOut { classes, _, share ->
            editSharer(classes, share) { impl ->
                val label = impl.newLabelForIndex(share.at)
                impl.addInstruction(0, BuilderInstruction21t(Opcode.IF_EQZ, share.intent, label))
            }
        }
    }

    /**
     * Runs Strip link tracking on [host]'s change to the first declared build: it applies, warns
     * once that Plain pin links isn't in, leaves its flag off and the logger and direct share
     * classes as they were, and no method calls Plain pin links.
     */
    private fun leavesOut(host: (List<ClassDef>, PinInvite, DirectShare) -> List<ClassDef>) {
        val build = Fixtures.declaredBuilds().first()
        val clean = read(build)
        val cleanContext = PatchContexts.of(ExtensionDex.classes() + clean)
        val found = cleanContext.findPinInvite()
        val share = cleanContext.findDirectShare()
        val classes = host(clean, found, share)
        val context = PatchContexts.of(ExtensionDex.classes() + classes)
        val owners = listOf(found.logger.definingClass, share.method.definingClass).distinct()
        val before = owners.map { snapshot(context, it) }
        val warnings = PatchLogCapture.warnings { stripLinkTrackingPatch.execute(context) }
        assertEquals(warnings.toString(), 1, warnings.count { "Plain pin links isn't in this build" in it })
        assertFlag(context, "stripLinkTracking", 1)
        assertFlag(context, "linkTracking", 1)
        assertFlag(context, "plainPinLinks", 0)
        assertEquals(before, owners.map { snapshot(context, it) })
        val hosts = classes.flatMap { context.mutableClassDefBy(it.type).methods }.filter { method ->
            references(method).any { it.startsWith(PLAIN_PIN_LINKS) }
        }
        assertEquals(emptyList<String>(), hosts.map { it.signature() })
        // The finders themselves refuse the same build.
        val fresh = PatchContexts.of(ExtensionDex.classes() + classes)
        val refused = try {
            fresh.findPinInvite()
            fresh.findDirectShare()
            null
        } catch (expected: PatchException) {
            expected
        }
        assertNotNull("the finders accepted a build the patch left Plain pin links out of", refused)
        assertTrue(refused!!.message, refused.message!!.startsWith("Plain pin links: "))
    }

    /** The logger's class but for the methods whose outgoing link calls Strip link tracking rewrites. */
    private fun snapshot(context: BytecodePatchContext, type: String): Map<String, List<String>> =
        context.mutableClassDefBy(type).methods.filter { method ->
            references(method).none { it in OUTGOING_LINK_CALLS.keys || it in OUTGOING_LINK_CALLS.values }
        }
            .associate { it.signature() to render(it.implementation?.instructions?.toList().orEmpty()) }

    private fun assertFlag(context: BytecodePatchContext, name: String, expected: Int) {
        val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.implementation!!.instructions.first()
        assertEquals(Opcode.CONST_4, first.opcode)
        assertEquals(name, expected, (first as NarrowLiteralInstruction).narrowLiteral)
    }

    private fun mutableMethod(context: BytecodePatchContext, method: Method) =
        context.mutableClassDefBy(method.definingClass).methods.single { it.signature() == method.signature() }

    private companion object {
        const val SENDABLE = "Lcom/pinterest/sendshare/model/SendableObject;"
        const val START_ACTIVITY = "Landroid/content/Context;->startActivity(Landroid/content/Intent;)V"
        val builds = mutableMapOf<File, List<ClassDef>>()

        fun read(build: File): List<ClassDef> = builds.getOrPut(build) {
            val wanted = mutableListOf<ClassDef>()
            FixtureDex.forEach(build) { dex ->
                for (owner in dex.classes) {
                    if (owner.methods.any { method -> "invite_url" in method.strings() ||
                            method.implementation?.instructions?.any { it.callReference()?.identity() in OUTGOING_LINK_CALLS } == true }) {
                        wanted += ImmutableClassDef.of(owner)
                    }
                }
            }
            wanted
        }

        fun editLogger(classes: List<ClassDef>, found: PinInvite, edit: (MutableMethodImplementation) -> Unit) =
            editMethod(classes, found.logger, edit)

        fun editSharer(classes: List<ClassDef>, share: DirectShare, edit: (MutableMethodImplementation) -> Unit) =
            editMethod(classes, share.method, edit)

        fun editMethod(classes: List<ClassDef>, target: Method, edit: (MutableMethodImplementation) -> Unit) = classes.map { owner ->
            if (owner.type != target.definingClass) owner
            else owner.withMethods(owner.methods.map { if (it.signature() == target.signature()) it.copy(it.name, edit) else it })
        }

        /** The same call shape and registers as [call], made to [target]. */
        fun retarget(call: Instruction, target: MethodReference): BuilderInstruction =
            when (call) {
                is RegisterRangeInstruction -> BuilderInstruction3rc(call.opcode, call.startRegister, call.registerCount, target)
                is FiveRegisterInstruction -> BuilderInstruction35c(call.opcode, call.registerCount, call.registerC, call.registerD,
                    call.registerE, call.registerF, call.registerG, target)
                else -> error("not a call: $call")
            }

        fun arguments(call: Instruction): List<Int> = when (call) {
            is RegisterRangeInstruction -> (call.startRegister until call.startRegister + call.registerCount).toList()
            is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
                .take(call.registerCount)
            else -> emptyList()
        }

        fun Method.strings(): List<String> = implementation?.instructions?.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.orEmpty()

        fun Method.copy(name: String, edit: ((MutableMethodImplementation) -> Unit)? = null): Method {
            val body: MethodImplementation? = implementation?.let { original ->
                if (edit == null) original else MutableMethodImplementation(original).also(edit)
            }
            return ImmutableMethod(definingClass, name, parameters, returnType, accessFlags, annotations, hiddenApiRestrictions, body)
        }

        fun ClassDef.withMethods(methods: Iterable<Method>) =
            ImmutableClassDef(type, accessFlags, superclass, interfaces, sourceFile, annotations, fields, methods)

        fun Method.signature() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

        fun references(method: Method): List<String> =
            method.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }.orEmpty()

        /** Opcodes and references, without the alignment nop the builder moves when a payload shifts. */
        fun render(instructions: List<Instruction>): List<String> =
            instructions.filter { it.opcode != Opcode.NOP }.map { "${it.opcode} ${(it as? ReferenceInstruction)?.reference ?: ""}" }

        /** [render] without the outgoing link calls Strip link tracking rewrites in place, one for one. */
        fun outsideLinkCalls(instructions: List<Instruction>): List<String> = render(instructions.filter { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference?.toString()
            reference !in OUTGOING_LINK_CALLS.keys && reference !in OUTGOING_LINK_CALLS.values
        })
    }
}
