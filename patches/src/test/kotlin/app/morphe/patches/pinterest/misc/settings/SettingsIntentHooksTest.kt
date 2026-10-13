/*
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.misc.settings

import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.pinterest.misc.extension.PINTEREST_APPLICATION
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** Warm alias and launcher requests can reach the home activity after the splash has finished. */
class SettingsIntentHooksTest {
    private val home = "Lcom/pinterest/activityLibrary/activity/task/activity/MainActivity;"
    private val hosts = setOf(MAIN_ACTIVITY, home)

    @Test
    fun `both launcher and home report incoming settings requests before Pinterest reads them`() {
        checkHooks("stand-ins", SettingsPatchHosts.all())
    }

    @Test
    @Category(FixtureTests::class)
    fun `each supported APK reports warm settings requests from its real home activity`() {
        for (build in Fixtures.declaredBuilds()) {
            val activities = FixtureDex.classes(build, hosts)
            assertEquals("${build.name}: entry activities", hosts, activities.keys)
            val application = SettingsPatchHosts.all().filter { it.type == PINTEREST_APPLICATION }
            checkHooks(build.name, application + activities.values)
        }
    }

    private fun checkHooks(label: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        settingsPatch.execute(context)

        for (type in hosts) {
            val original = classes.single { it.type == type }.methods.single {
                it.name == "onNewIntent" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Intent;")
            }.implementation!!
            val patched = context.declaredInHierarchy(type, "onNewIntent", "Landroid/content/Intent;")
                .implementation!!.instructions.toList()
            val hook = patched.first()
            val where = "$label: $type.onNewIntent"
            assertEquals("$where: hook", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
            assertEquals("$where: target", "$ENTRY->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V",
                (hook as ReferenceInstruction).reference.toString())
            assertEquals("$where: receiver and intent", 2, (hook as RegisterRangeInstruction).registerCount)
            assertEquals("$where: incoming registers", original.registerCount - 2, hook.startRegister)
            assertEquals("$where: Pinterest's instructions survive", original.instructions.map { it.opcode },
                patched.drop(1).map { it.opcode })
            assertTrue("$where: one hook only", patched.drop(1).none {
                (it as? ReferenceInstruction)?.reference?.toString() ==
                    "$ENTRY->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V"
            })
        }
    }
}
