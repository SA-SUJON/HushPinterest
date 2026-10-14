/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.misc.settings

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** The Pinterest logo's long press: the home feed header is handed to the settings entry right after Pinterest finds it. */
class LogoLongPressTest {
    /** The logo's resource name, as the extension's SettingsEntry.LOGO_ID looks it up. */
    private val logoId = "home_feed_greeting_header_logotype"

    @Test
    fun `a build that never finds the home feed header keeps patching without the logo`() {
        val context = PatchContexts.of(ExtensionDex.classes())
        assertTrue(context.greetingHeaderSites().isEmpty())
        assertFalse(context.hookLogoLongPress())
    }

    @Test
    @Category(FixtureTests::class)
    fun `each declared original APK hands its home feed header to the settings entry once`() {
        for (build in Fixtures.declaredBuilds()) {
            fun headerRead(method: Method) = method.implementation?.instructions?.firstOrNull { instruction ->
                instruction.opcode == Opcode.SGET &&
                    ((instruction as ReferenceInstruction).reference as? FieldReference)?.name == GREETING_HEADER_ID
            }
            val readers = FixtureDex.classesWhere(build, { true }) { headerRead(it) != null }
            val context = PatchContexts.of(ExtensionDex.classes() + readers)

            val site = context.greetingHeaderSites().single()
            // 14.39.0 binds with the view alone, 14.40.0 with the view and its saved state.
            val parameters = site.method.parameterTypes.map(CharSequence::toString)
            assertEquals("${build.name}: the header's bind takes the home screen's view first", "Landroid/view/View;", parameters.firstOrNull())
            // The R class naming the header names the logo too, which is how the extension finds it.
            val ids = ((headerRead(site.method) as ReferenceInstruction).reference as FieldReference).definingClass
            assertTrue("${build.name}: $ids names $logoId",
                FixtureDex.classes(build, setOf(ids)).getValue(ids).fields.any { it.name == logoId && it.type == "I" })
            val next = site.method.implementation!!.instructions.toList()[site.index]

            assertTrue(build.name, context.hookLogoLongPress())
            val patched = context.mutableClassDefBy(site.method.definingClass).methods.single {
                it.name == site.method.name && it.parameterTypes.map(CharSequence::toString) == parameters
            }.implementation!!.instructions.toList()
            val hook = patched[site.index]
            assertEquals("${build.name}: hook", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
            assertEquals("${build.name}: hook target", GREETING_HEADER_HOOK, (hook as ReferenceInstruction).reference.toString())
            assertEquals("${build.name}: the found header goes in", site.register, (hook as RegisterRangeInstruction).startRegister)
            assertEquals("${build.name}: one register", 1, hook.registerCount)
            assertEquals("${build.name}: Pinterest's own next step follows", next.opcode, patched[site.index + 1].opcode)
        }
    }
}
