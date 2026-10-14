/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ads

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.AnnotationVisibility
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotation
import com.android.tools.smali.dexlib2.immutable.ImmutableAnnotationElement
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pin model check that FeedFixtureTest runs on the real build, here on synthetic models, so
 * each way it fails is seen with its message. The JSON annotation gets a made-up class name: only
 * its `value` element identifies it, as in Pinterest's renamed builds.
 */
class PinModelSchemaTest {
    private val pinFields = linkedMapOf(
        "id" to "Ljava/lang/String;",
        "images" to "Ljava/util/Map;",
        "is_promoted" to "Ljava/lang/Boolean;",
        "is_third_party_ad" to "Ljava/lang/Boolean;",
        "pin_promotion_id_reformatted" to "Ljava/lang/String;",
        "campaign_id_reformatted" to "Ljava/lang/String;",
        "ai_disclosures" to "Ljava/util/List;",
        "digital_media_source_type" to "Lfixture/Pin\$SourceType;",
        "digital_media_source_type_label" to "Ljava/lang/String;",
        "is_eligible_for_pdp" to "Ljava/lang/Boolean;",
    )

    /** A board that also has an id, images and one ad flag, the way Pinterest's do. */
    private val board = model("Lfixture/Board;", mapOf("id" to "Ljava/lang/String;", "images" to "Ljava/util/Map;", "is_promoted" to "Ljava/lang/Boolean;"))

    private fun model(type: String, json: Map<String, String>, visibility: Int = AnnotationVisibility.RUNTIME, flags: Int = AccessFlags.PUBLIC.value): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
            json.entries.mapIndexed { index, (name, fieldType) ->
                val annotation = ImmutableAnnotation(visibility, "Lfixture/Json;", setOf(ImmutableAnnotationElement("value", ImmutableStringEncodedValue(name))))
                ImmutableField(type, "f$index", fieldType, flags, null, setOf(annotation), null)
            }, null)

    private fun problems(vararg models: ClassDef) = pinSchemaProblems(models.associate { it.type to serializedFields(it) })

    @Test
    fun `the pin is told from other id and image models and passes with boxed or primitive flags`() {
        assertEquals(emptyList<String>(), problems(board, model("Lfixture/Pin;", pinFields)))
        assertEquals(emptyList<String>(), problems(board, model("Lfixture/Pin;", pinFields + ("is_promoted" to "Z"))))
    }

    @Test
    fun `a dropped or retyped field fails on its key and the type it needs`() {
        assertEquals(listOf("Lfixture/Pin; has no field named is_third_party_ad (expected Ljava/lang/Boolean; or Z)"),
            problems(board, model("Lfixture/Pin;", pinFields - "is_third_party_ad")))
        assertEquals(listOf("Lfixture/Pin; field ai_disclosures is Ljava/lang/String;, expected Ljava/util/List;",
            "Lfixture/Pin; field is_eligible_for_pdp is I, expected Ljava/lang/Boolean; or Z"),
            problems(board, model("Lfixture/Pin;", pinFields + ("ai_disclosures" to "Ljava/lang/String;") + ("is_eligible_for_pdp" to "I"))))
        assertEquals(listOf("Lfixture/Pin; field digital_media_source_type is I, expected an object",
            "Lfixture/Pin; has no field named digital_media_source_type_label (expected Ljava/lang/String;)"),
            problems(board, model("Lfixture/Pin;", pinFields + ("digital_media_source_type" to "I") - "digital_media_source_type_label")))
    }

    @Test
    fun `names the extension can't read at run time don't count`() {
        val all = "is_promoted (expected Ljava/lang/Boolean; or Z), is_third_party_ad (expected Ljava/lang/Boolean; or Z), " +
            "pin_promotion_id_reformatted (expected Ljava/lang/String;), campaign_id_reformatted (expected Ljava/lang/String;), " +
            "ai_disclosures (expected Ljava/util/List;), digital_media_source_type (expected an object), " +
            "digital_media_source_type_label (expected Ljava/lang/String;), is_eligible_for_pdp (expected Ljava/lang/Boolean; or Z)"
        assertEquals(listOf("no model with a serialized id and images carries any of $all"),
            problems(model("Lfixture/Pin;", pinFields, visibility = AnnotationVisibility.BUILD)))
        assertEquals(listOf("no model with a serialized id and images carries any of $all"),
            problems(model("Lfixture/Pin;", pinFields, flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)))
    }

    @Test
    fun `two equally likely pins are refused rather than guessed between`() {
        assertEquals(listOf("the pin model is ambiguous: Lfixture/Pin;, Lfixture/Story; each carry 8 of " +
            "[is_promoted, is_third_party_ad, pin_promotion_id_reformatted, campaign_id_reformatted, ai_disclosures, " +
            "digital_media_source_type, digital_media_source_type_label, is_eligible_for_pdp]"),
            problems(board, model("Lfixture/Story;", pinFields), model("Lfixture/Pin;", pinFields)))
    }
}
