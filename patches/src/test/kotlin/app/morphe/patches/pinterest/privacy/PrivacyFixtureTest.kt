/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.privacy

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.pinterest.misc.extension.PatchLogCapture
import app.morphe.patches.pinterest.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real API annotations, Rx factories, startup scheduler and outgoing framework calls in both APKs. */
class PrivacyFixtureTest {
    @Test
    fun `each declared original APK gets all privacy hooks without warnings and keeps core URL calls`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = read(build)
            val services = classes.flatMap { it.methods }.filter { it.telemetryPath() != null }
            assertEquals(build.name, TELEMETRY_PATHS, services.mapNotNull { it.telemetryPath() }.toSet())
            val coreUrlCalls = classes.filterNot { it.type.startsWith("Lcom/appsflyer/") }.flatMap { owner ->
                owner.methods.filter { method -> method.implementation?.instructions?.any {
                    it.callReference()?.identity() == URL_CALL
                } == true }.map { it.identity() }
            }
            assertTrue("${build.name}: no core networking stand-ins to preserve", coreUrlCalls.isNotEmpty())
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val warnings = PatchLogCapture.warnings {
                disableAnalyticsPatch.execute(context)
                stripLinkTrackingPatch.execute(context)
            }
            assertEquals(build.name, emptyList<String>(), warnings)
            for (flag in listOf("disableAnalytics", "analyticsTasks", "analyticsUploads", "stripLinkTracking", "linkTracking")) {
                assertFlag(context, flag)
            }
            // Original API calls remain solely inside the new wrappers' inactive branch.
            val wrappers = context.mutableClassDefBy(ANALYTICS).methods.filter { it.name.startsWith("hushUpload") }
            assertEquals(build.name, services.size, wrappers.size)
            for (wrapper in wrappers) {
                val instructions = wrapper.implementation!!.instructions.toList()
                assertEquals("${build.name}: ${wrapper.name} gate", "$ANALYTICS->blockUpload()Z",
                    instructions.first().callReference()?.identity())
                assertEquals(1, instructions.count { it.callReference()?.identity() in services.map { api -> api.identity() } })
                assertTrue(instructions.any { it.opcode == Opcode.IF_EQZ })
                assertTrue(instructions.count { it.opcode == Opcode.RETURN_OBJECT } >= 2)
            }
            val originalCalls = services.map { it.identity() }.toSet()
            for (owner in classes) for (method in context.mutableClassDefBy(owner.type).methods) {
                assertFalse("${build.name}: an unguarded telemetry call remains in ${method.identity()}",
                    method.implementation?.instructions?.any { it.callReference()?.identity() in originalCalls } == true)
            }
            for (identity in coreUrlCalls) {
                val owner = context.mutableClassDefBy(identity.substringBefore("->"))
                val method = owner.methods.single { it.identity() == identity }
                assertTrue("${build.name}: core networking changed in $identity", method.implementation!!.instructions.any {
                    it.callReference()?.identity() == URL_CALL
                })
            }
            for (owner in classes.filter { it.type.startsWith("Lcom/appsflyer/") }) {
                for (method in context.mutableClassDefBy(owner.type).methods) {
                    assertFalse("${build.name}: AppsFlyer still opens a URL in ${method.identity()}",
                        method.implementation?.instructions?.any { it.callReference()?.identity() == URL_CALL } == true)
                }
            }
        }
    }

    private fun read(build: File): List<ClassDef> {
        val selected = linkedMapOf<String, ClassDef>()
        val wanted = mutableSetOf("Lkotlin/Unit;", "Lcom/google/firebase/messaging/FirebaseMessagingService;")
        val returns = mutableSetOf<String>()
        val serviceCalls = mutableSetOf<String>()
        var tagType = ""
        FixtureDex.forEach(build) { dex ->
            for (owner in dex.classes) {
                val services = owner.methods.filter { it.telemetryPath() != null }
                if (services.isNotEmpty()) {
                    selected[owner.type] = ImmutableClassDef.of(owner)
                    for (service in services) {
                        if (service.returnType != "Ljava/lang/Object;") returns += service.returnType
                        wanted += service.returnType
                        serviceCalls += service.identity()
                        val signature = service.annotations.firstOrNull { it.type == "Ldalvik/annotation/Signature;" }
                            ?.elements?.firstOrNull { it.name == "value" }?.value as? ArrayEncodedValue
                        val text = signature?.value?.joinToString("") { (it as? StringEncodedValue)?.value.orEmpty() }.orEmpty()
                        wanted += Regex("L[^;<>()]+;").findAll(text).map { it.value }
                    }
                }
                if (owner.superclass == "Ljava/lang/Enum;" && owner.fields.any { it.name == "TAG_APPSFLYER_INIT" }) {
                    tagType = owner.type
                    selected[owner.type] = ImmutableClassDef.of(owner)
                }
                if (owner.superclass == "Lcom/pinterest/api/adapter/coroutine/NetworkResponse;" ||
                    owner.methods.any { method -> method.implementation?.instructions?.any { instruction ->
                        val call = instruction.callReference()?.identity()
                        call == URL_CALL || call in OUTGOING_LINK_CALLS
                    } == true }) selected[owner.type] = ImmutableClassDef.of(owner)
            }
        }
        FixtureDex.forEach(build) { dex ->
            for (owner in dex.classes) {
                if (owner.type in wanted || owner.superclass in returns ||
                    (owner.fields.any { it.type == "Ljava/lang/Runnable;" } && owner.fields.any { it.type == tagType }) ||
                    owner.methods.any { method -> method.implementation?.instructions?.any {
                        it.callReference()?.identity() in serviceCalls
                    } == true }) selected[owner.type] = ImmutableClassDef.of(owner)
            }
        }
        return selected.values.toList()
    }

    private fun assertFlag(context: BytecodePatchContext, name: String) {
        val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }
            .implementation!!.instructions.first()
        assertEquals(Opcode.CONST_4, first.opcode)
        assertEquals("$name coverage", 1, (first as NarrowLiteralInstruction).narrowLiteral)
    }

    private companion object {
        const val URL_CALL = "Ljava/net/URL;->openConnection()Ljava/net/URLConnection;"
    }
}
