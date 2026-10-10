/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** Pinterest's text toast, found from the home activity in each declared APK, fills the extension's toast stubs. */
@Category(FixtureTests::class)
class PinterestToastsFixtureTest {
    @Test
    fun `each declared original APK builds its text toast for HushPinterest's messages`() {
        for (build in Fixtures.declaredBuilds()) {
            val all = mutableMapOf<String, ClassDef>()
            FixtureDex.forEach(build) { dex -> dex.classes.forEach { all[it.type] = it } }
            fun chain(type: String) = generateSequence(type) { all[it]?.superclass }.take(20).toList()
            val home = chain(HOME_ACTIVITY)
            // The model the container's show method asks for its view, as the save toasts test finds it.
            val model = all.getValue(TOAST_CONTAINER).methods.mapNotNull { method ->
                method.parameterTypes.singleOrNull()?.toString()?.takeIf { type ->
                    method.implementation?.instructions?.any { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                            call.definingClass == type && call.parameterTypes.map(CharSequence::toString) == listOf(TOAST_CONTAINER)
                        } == true
                    } == true
                }
            }.single()
            val selected = all.values.filter { owner ->
                owner.type in home || chain(owner.type).let { TOAST_CONTAINER in it || model in it }
            }.map { ImmutableClassDef.of(it) }
            val context = PatchContexts.of(ExtensionDex.classes() + selected)

            val toast = context.nativeToast()
            assertTrue("${build.name}: ${toast.activity} is one of the home activity's classes", toast.activity in home)
            assertTrue("${build.name}: ${toast.show} is the activity's and takes the container's model",
                toast.show.startsWith("${toast.activity}->") && toast.show.endsWith("($model)V"))
            val text = all.getValue(toast.text)
            assertEquals("${build.name}: the text toast's model", model, text.superclass)
            assertTrue("${build.name}: (String, int) constructor", text.methods.any {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "I") })
            assertTrue("${build.name}: ${toast.again} is the model's own flag", toast.again.startsWith("$model->"))
            val show = all.getValue(toast.activity).methods.single { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})V" == toast.show }
            val checked = show.implementation!!.instructions.filter { it.opcode == Opcode.INSTANCE_OF }
                .map { ((it as ReferenceInstruction).reference as TypeReference).type }
            assertTrue("${build.name}: Pinterest's toast method checks for ${toast.text}: $checked", toast.text in checked)
            val called = show.implementation!!.instructions.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() }
            assertTrue("${build.name}: it sets up ${toast.setup} first", toast.setup in called)

            assertTrue(build.name, context.routeToastsToPinterest())
            fun stub(name: String) = context.mutableClassDefBy(PINTEREST_TOASTS).methods.single { it.name == name }
                .implementation!!.instructions.toList()
            fun references(name: String) = stub(name).mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
            assertEquals(
                "${build.name}: container",
                listOf(Opcode.INSTANCE_OF, Opcode.IF_EQZ, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL,
                    Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                stub("container").map { it.opcode },
            )
            assertEquals("${build.name}: container calls", listOf(toast.activity, toast.activity, toast.setup, toast.layer), references("container"))
            assertEquals(
                "${build.name}: post",
                listOf(toast.activity, toast.activity, toast.text, "${toast.text}-><init>(Ljava/lang/String;I)V", toast.again, toast.show),
                references("post"),
            )
        }
    }
}
