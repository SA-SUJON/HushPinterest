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
import app.morphe.patches.pinterest.misc.extension.SETTINGS_STATUS
import app.morphe.patches.pinterest.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Google's advertising ID info class in each declared APK, and the answer filters placed before its
 * returns. Pinterest's Block Store wrapper in the same APK, and the hooks at the head of its browser
 * ID read and save.
 */
@Category(FixtureTests::class)
class AdvertisingIdFixtureTest {
    @Test
    fun `each declared original APK filters both advertising ID answers before every return`() {
        for (build in Fixtures.declaredBuilds()) {
            val (info, wrapper) = read(build)
            val context = PatchContexts.of(ExtensionDex.classes() + info + wrapper)
            hideAdvertisingIdPatch.execute(context)
            for (flag in listOf("hideAdvertisingId", "advertisingId", "browserId")) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }.implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
            for ((getter, hook) in ADVERTISING_ID_GETTERS) {
                val original = info.methods.single { it.identity() == getter }.implementation!!.instructions.toList()
                val patched = context.mutableClassDefBy(ADVERTISING_INFO).methods.single { it.identity() == getter }
                    .implementation!!.instructions.toList()
                assertEquals("${build.name}: $getter keeps its own read", original.dropLast(1).map { it.opcode }, patched.take(original.size - 1).map { it.opcode })
                val returnAt = patched.indexOfLast { it.opcode == Opcode.RETURN || it.opcode == Opcode.RETURN_OBJECT }
                val register = (patched[returnAt] as OneRegisterInstruction).registerA
                assertEquals("${build.name}: $getter hook", hook, patched[returnAt - 2].callReference()?.identity())
                assertEquals(build.name, listOf(register), (patched[returnAt - 2] as FiveRegisterInstruction).let {
                    listOf(it.registerC).take(it.registerCount) })
                assertEquals(build.name, register, (patched[returnAt - 1] as OneRegisterInstruction).registerA)
                assertEquals(build.name, original.size + 2, patched.size)
            }
        }
    }

    @Test
    fun `each declared original APK keeps Pinterest's browser ID out of Block Store`() {
        for (build in Fixtures.declaredBuilds()) {
            val (info, wrapper) = read(build)
            val store = browserIdStoreIn(wrapper)!!
            val context = PatchContexts.of(ExtensionDex.classes() + info + wrapper)
            hideAdvertisingIdPatch.execute(context)
            val patched = context.mutableClassDefBy(wrapper.type)

            // The read answers null, Block Store's "no record", when the hook says so, before anything else runs.
            val readOriginal = instructions(store.read)
            val read = instructions(patched.methods.single { it.identity() == store.read.identity() })
            assertEquals("${build.name}: read hook", SKIP_BROWSER_ID_HOOK, read[0].callReference()?.identity())
            assertEquals("${build.name}: read hook takes the key", listOf(parameter(store.read, 1)), range(read[0]))
            assertEquals(build.name, Opcode.MOVE_RESULT, read[1].opcode)
            assertEquals(build.name, 0, (read[1] as OneRegisterInstruction).registerA)
            assertFallThrough(build.name, read, 5)
            assertEquals(build.name, Opcode.CONST_4, read[3].opcode)
            assertEquals(build.name, 0, (read[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(build.name, 0, (read[3] as OneRegisterInstruction).registerA)
            assertEquals(build.name, Opcode.RETURN_OBJECT, read[4].opcode)
            assertEquals(build.name, 0, (read[4] as OneRegisterInstruction).registerA)
            assertEquals("${build.name}: the read keeps its own body", readOriginal.map { it.opcode }, read.drop(5).map { it.opcode })

            // The save returns what the hook hands back unless that's null, before anything else runs.
            val saveOriginal = instructions(store.save)
            val save = instructions(patched.methods.single { it.identity() == store.save.identity() })
            assertEquals("${build.name}: save hook", SAVE_BROWSER_ID_HOOK, save[0].callReference()?.identity())
            assertEquals("${build.name}: save hook takes the store, key, bytes and continuation",
                (0..3).map { parameter(store.save, it) }, range(save[0]))
            assertEquals(build.name, Opcode.MOVE_RESULT_OBJECT, save[1].opcode)
            assertEquals(build.name, 0, (save[1] as OneRegisterInstruction).registerA)
            assertFallThrough(build.name, save, 4)
            assertEquals(build.name, Opcode.RETURN_OBJECT, save[3].opcode)
            assertEquals(build.name, 0, (save[3] as OneRegisterInstruction).registerA)
            assertEquals("${build.name}: the save keeps its own body", saveOriginal.map { it.opcode }, save.drop(4).map { it.opcode })
            assertEquals("${build.name}: the delete is untouched", instructions(store.delete).map { it.opcode },
                instructions(patched.methods.single { it.identity() == store.delete.identity() }).map { it.opcode })

            // The stub hands the keys to the wrapper's own delete on the save's continuation.
            val stub = context.mutableClassDefBy(ADVERTISING_ID).methods.single { it.name == DELETE_BROWSER_ID_STUB }
            val body = instructions(stub)
            assertEquals(build.name, 4, stub.implementation!!.registerCount)
            assertEquals(build.name, listOf(Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL,
                Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), body.map { it.opcode })
            assertEquals(build.name, listOf(1, 3), body.take(2).map { (it as OneRegisterInstruction).registerA })
            assertEquals(build.name, listOf(wrapper.type, store.continuation),
                body.take(2).map { (it as ReferenceInstruction).reference.toString() })
            assertEquals(build.name, store.delete.identity(), body[2].callReference()?.identity())
            assertEquals(build.name, listOf(1, 2, 3), (body[2] as FiveRegisterInstruction).let {
                listOf(it.registerC, it.registerD, it.registerE).take(it.registerCount) })
            assertEquals(build.name, listOf(0, 0), body.drop(3).map { (it as OneRegisterInstruction).registerA })
        }
    }

    @Test
    fun `missing info class getter hook or Block Store wrapper and an unusable return refuse before any change`() {
        val (info, wrapper) = read(Fixtures.declaredBuilds().last())
        val store = browserIdStoreIn(wrapper)!!
        val extension = ExtensionDex.classes()
        val getId = info.methods.single { it.name == "getId" }
        val wideReturn = ImmutableMethod(getId.definingClass, getId.name, getId.parameters, getId.returnType, getId.accessFlags,
            getId.annotations, getId.hiddenApiRestrictions, ImmutableMethodImplementation(20, listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 16, com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference("x")),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 16)), null, null))
        fun without(type: String, name: String) = extension.map { owner -> if (owner.type != type) owner else
            copy(owner, owner.methods.filterNot { it.name == name }) }
        val cases = linkedMapOf(
            "missing info class" to extension + wrapper,
            "missing getter" to extension + copy(info, info.methods.filterNot { it.name == "isLimitAdTrackingEnabled" }) + wrapper,
            "missing hook" to without(ADVERTISING_ID, "limitTracking") + info + wrapper,
            "return register past v15" to extension + copy(info, info.methods.map { if (it.name == "getId") wideReturn else it }) + wrapper,
            "missing status stub" to without(SETTINGS_STATUS, "advertisingId") + info + wrapper,
            "missing browser ID status stub" to without(SETTINGS_STATUS, "browserId") + info + wrapper,
            "missing Block Store wrapper" to extension + info,
            "two Block Store wrappers" to extension + info + wrapper + moved(wrapper, "Lfixture/SecondBlockStore;"),
            "Block Store wrapper missing its delete" to extension + info +
                copy(wrapper, wrapper.methods.filterNot { it.identity() == store.delete.identity() }),
            "missing read hook" to without(ADVERTISING_ID, "skipBrowserId") + info + wrapper,
            "missing save hook" to without(ADVERTISING_ID, "saveBrowserId") + info + wrapper,
            "missing delete stub" to without(ADVERTISING_ID, DELETE_BROWSER_ID_STUB) + info + wrapper,
        )
        for ((reason, input) in cases) {
            val context = PatchContexts.of(input)
            val before = snapshot(context, input)
            assertThrows(reason, PatchException::class.java) { hideAdvertisingIdPatch.execute(context) }
            assertArrayEquals("$reason left retained edits", before, snapshot(context, input))
        }
    }

    /** The branch at 2 skips the enabled answer and lands on the method's own first instruction, at [resume]. */
    private fun assertFallThrough(build: String, body: List<Instruction>, resume: Int) {
        assertEquals(build, Opcode.IF_EQZ, body[2].opcode)
        assertEquals(build, 0, (body[2] as OneRegisterInstruction).registerA)
        assertEquals(build, body.subList(2, resume).sumOf { it.codeUnits }, (body[2] as OffsetInstruction).codeOffset)
    }

    private fun instructions(method: Method): List<Instruction> = method.implementation!!.instructions.toList()

    /** The register holding [index], counting `this` as 0 on an instance method. */
    private fun parameter(method: Method, index: Int): Int =
        method.implementation!!.registerCount - method.parameterTypes.size - 1 + index

    private fun range(call: Instruction): List<Int> = (call as RegisterRangeInstruction).let {
        (it.startRegister until it.startRegister + it.registerCount).toList()
    }

    private fun copy(owner: ClassDef, methods: Iterable<Method>) = ImmutableClassDef(owner.type,
        owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile, owner.annotations, owner.fields, methods)

    /** [owner] again as [type], each method defined there. */
    private fun moved(owner: ClassDef, type: String) = ImmutableClassDef(type, owner.accessFlags, owner.superclass,
        owner.interfaces, owner.sourceFile, owner.annotations, emptyList(), owner.methods.map {
            ImmutableMethod(type, it.name, it.parameters, it.returnType, it.accessFlags, it.annotations,
                it.hiddenApiRestrictions, it.implementation)
        })

    private fun snapshot(context: BytecodePatchContext, classes: List<ClassDef>): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        classes.forEach { pool.internClass(context.mutableClassDefBy(it.type)) }
        val store = MemoryDataStore()
        try {
            pool.writeTo(store)
            return store.data
        } finally {
            store.close()
        }
    }

    /** The advertising ID info class and Pinterest's one Block Store wrapper, read in one pass. */
    private fun read(build: File): Pair<ClassDef, ClassDef> {
        var info: ClassDef? = null
        val wrappers = mutableListOf<ClassDef>()
        FixtureDex.forEach(build) { dex ->
            for (owner in dex.classes) {
                if (owner.type == ADVERTISING_INFO) info = ImmutableClassDef.of(owner)
                else if (!owner.type.startsWith(EXTENSION_ROOT) && browserIdStoreIn(owner) != null) wrappers += ImmutableClassDef.of(owner)
            }
        }
        val wrapper = wrappers.singleOrNull() ?: throw AssertionError("${build.name} has ${wrappers.size} Block Store wrappers, expected 1")
        return (info ?: throw AssertionError("${build.name} has no $ADVERTISING_INFO")) to wrapper
    }
}
