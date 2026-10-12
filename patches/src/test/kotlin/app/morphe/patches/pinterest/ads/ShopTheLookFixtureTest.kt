/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ads

import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * A pin closeup's Shop the look section is a story, and Hide shopping drops it by its story_type
 * (#6). Pinterest builds that section only after comparing a story's story_type to the literal, so
 * each declared build has to still make that comparison through the getter of a String field
 * serialized as story_type. A renamed type would leave Shopping.STORIES matching nothing.
 */
@Category(FixtureTests::class)
class ShopTheLookFixtureTest {
    private fun Field.json(): String? = annotations.flatMap { it.elements }.firstNotNullOfOrNull {
        if (it.name == "value") (it.value as? StringEncodedValue)?.value else null
    }

    private fun Method.references() = (implementation?.instructions ?: emptyList())
        .mapNotNull { (it as? ReferenceInstruction)?.reference }

    @Test fun `each declared build builds the closeup Shop the look section from that story type`() {
        for (build in Fixtures.declaredBuilds()) {
            val storyTypeFields = mutableSetOf<String>()
            val comparers = mutableListOf<Method>()
            FixtureDex.forEach(build) { dex ->
                for (model in dex.classes) {
                    for (field in model.fields) {
                        if (field.type == "Ljava/lang/String;" && field.json() == "story_type") {
                            storyTypeFields += "${model.type}->${field.name}"
                        }
                    }
                    for (method in model.methods) {
                        if (method.references().any { (it as? StringReference)?.string == SHOP_THE_LOOK }) comparers += method
                    }
                }
            }
            assertTrue("${build.name}: no String field serialized as story_type", storyTypeFields.isNotEmpty())

            val getters = mutableSetOf<String>()
            FixtureDex.forEach(build) { dex ->
                for (model in dex.classes) {
                    for (method in model.methods) {
                        if (method.parameters.isNotEmpty() || method.returnType != "Ljava/lang/String;") continue
                        val reads = (method.implementation?.instructions ?: emptyList()).filter { it.opcode == Opcode.IGET_OBJECT }
                            .map { ((it as ReferenceInstruction).reference as FieldReference).let { f -> "${f.definingClass}->${f.name}" } }
                        if (reads.any { it in storyTypeFields }) getters += "${model.type}->${method.name}"
                    }
                }
            }

            val compared = comparers.filter { method ->
                val calls = method.references().filterIsInstance<MethodReference>()
                calls.any { "${it.definingClass}->${it.name}" in getters && it.parameterTypes.isEmpty() } &&
                    calls.any { it.definingClass == "Ljava/lang/String;" && it.name == "equals" }
            }
            assertTrue(
                "${build.name}: no method compares a story_type to \"$SHOP_THE_LOOK\" " +
                    "(${comparers.size} methods name it, ${getters.size} story_type getters)",
                compared.isNotEmpty(),
            )
        }
    }

    private companion object {
        const val SHOP_THE_LOOK = "shop_the_look"
    }
}
