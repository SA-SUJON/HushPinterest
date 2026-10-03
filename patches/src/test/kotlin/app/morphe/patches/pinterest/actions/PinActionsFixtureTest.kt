/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.actions

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Resolves and applies the real native action targets independently in both supported builds. */
class PinActionsFixtureTest {
    @Test
    fun `all action hooks use the real pin menu Visit dispatcher and chooser in each declared build`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = read(build)
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            downloadPinsPatch.execute(context)
            externalBrowserPatch.execute(context)
            systemSharePatch.execute(context)
            for (name in listOf("downloadPins", "pinDownloads", "externalBrowser", "visitLinks", "systemShare", "pinShare")) {
                assertFlag(context, name, 1)
            }
            val menu = context.mutableClassDefBy(PIN_MENU)
            assertTrue(build.name, menu.methods.map { it.name }.containsAll(
                listOf("hushDownloadPin", "hushDownloadMenu", "hushDismissDownload"),
            ))
            val create = menu.methods.single { it.name == "createModalView" }
            val instructions = create.implementation!!.instructions.toList()
            val attach = instructions.indexOfFirst {
                (it as? ReferenceInstruction)?.reference?.toString() == "$EXTENSION_PACKAGE/actions/PinDownloads;->attach(Ljava/lang/Object;)V"
            }
            assertTrue("${build.name} menu hook", attach > 0)
            assertEquals("${build.name} hook must follow native layout assignment", Opcode.IPUT_OBJECT, instructions[attach - 1].opcode)
            val downloads = context.mutableClassDefBy("$EXTENSION_PACKAGE/actions/PinDownloads;")
            val row = downloads.methods.single { it.name == "menuRow" }.implementation!!.instructions.toList()
            assertTrue("${build.name} native Download icon", references(row).any { "->DOWNLOAD:" in it })
            assertTrue("${build.name} native row factory", references(row).any { it.endsWith(")Landroid/widget/RelativeLayout;") })
            assertTrue("${build.name} native presenter dismissal", references(menu.methods.single { it.name == "hushDismissDownload" }
                .implementation!!.instructions.toList()).any { "->" in it && it.endsWith("()V") })
            for ((owner, helper) in listOf("ExternalBrowser" to "open(Ljava/lang/String;Ljava/lang/Object;)Z",
                "SystemShare" to "open(Ljava/lang/Object;Ljava/lang/Object;)Z")) {
                val calls = classes.flatMap { original -> context.mutableClassDefBy(original.type).methods }.count { method ->
                    method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() ==
                        "$EXTENSION_PACKAGE/actions/$owner;->$helper" } == true
                }
                assertEquals("${build.name} $owner handler count", 1, calls)
            }
        }
    }

    @Test
    fun `missing menu row dependency refuses download capability before host changes`() {
        val build = Fixtures.declaredBuilds().first()
        val classes = read(build)
        val menu = classes.single { it.type == PIN_MENU }
        val layout = menu.fields.single { it.name == "modalView" }.type
        val context = PatchContexts.of(ExtensionDex.classes() + classes.filterNot { it.type == layout })
        assertThrows(PatchException::class.java) { downloadPinsPatch.execute(context) }
        assertFalse(context.mutableClassDefBy(PIN_MENU).methods.any { it.name.startsWith("hushDownload") })
        assertFlag(context, "downloadPins", 0)
        assertFlag(context, "pinDownloads", 0)
    }

    private fun references(instructions: List<com.android.tools.smali.dexlib2.iface.instruction.Instruction>): List<String> =
        instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }

    private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
    }?.toSet() ?: emptySet()

    private fun read(build: File): List<ClassDef> {
        val wanted = mutableMapOf<String, ClassDef>()
        var dispatchers = 0
        var choosers = 0
        FixtureDex.forEach(build) { dex ->
            for (owner in dex.classes) {
                val visit = owner.methods.any { it.strings().containsAll(setOf("_url", "android_client_tracking_params_consistency")) }
                val share = owner.methods.any { method ->
                    method.parameterTypes.size == 5 && method.parameterTypes[1].toString() == "I" &&
                        method.parameterTypes[3].toString() == "Z" && method.fields().map { it.name }.toSet().containsAll(
                            setOf("APP_LIST_AND_CONTACT_SUGGESTIONS_FOR_UPSELL", "SCREENSHOT", "DOWNLOAD"),
                        )
                }
                if (visit) dispatchers++
                if (share) choosers++
                if (owner.type == PIN_MENU || visit || share) wanted[owner.type] = ImmutableClassDef.of(owner)
            }
        }
        assertEquals("${build.name} Visit owner", 1, dispatchers)
        assertEquals("${build.name} share chooser owner", 1, choosers)
        val menu = wanted.getValue(PIN_MENU)
        val dependencies = FixtureDex.classes(build, menu.fields.filter { it.name in setOf("modalView", "presenter") }.map { it.type }.toSet())
        wanted += dependencies
        val icons = dependencies.values.flatMap { owner -> owner.methods.filter { it.returnType == "Landroid/widget/RelativeLayout;" }
            .flatMap { it.parameterTypes }.map { it.toString() }.filter { it.startsWith('L') && it != "Ljava/lang/String;" } }.toSet()
        wanted += FixtureDex.classes(build, icons)
        return wanted.values.toList()
    }

    private fun assertFlag(context: BytecodePatchContext, name: String, expected: Int) {
        val instructions = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.implementation!!.instructions.toList()
        assertEquals(Opcode.CONST_4, instructions[0].opcode)
        assertEquals(name, expected, (instructions[0] as NarrowLiteralInstruction).narrowLiteral)
    }
}
