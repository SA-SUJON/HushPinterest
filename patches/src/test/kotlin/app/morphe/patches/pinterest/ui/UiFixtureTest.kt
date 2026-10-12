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
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.pinterest.misc.extension.PatchLogCapture
import app.morphe.patches.pinterest.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(FixtureTests::class)
class UiFixtureTest {
    private val patches = listOf(
        hideScreenshotSharePatch, hideSearchHistoryPatch, hideNavigationButtonsPatch, hideHeaderButtonsPatch,
        hidePinMenuItemsPatch, hideCommentsPatch, quietEmailReminderPatch, disableUpdateNagPatch, amoledBlackPatch,
    )
    private val flags = listOf(
        "hideScreenshotShare" to "screenshotShare", "hideSearchHistory" to "searchHistory",
        "hideNavigationButtons" to "navigationButtons", "hideHeaderButtons" to "headerButtons",
        "hidePinMenuItems" to "pinMenuItems", "hideComments" to "comments",
        "quietEmailReminder" to "emailReminder", "disableUpdateNag" to "updateNag",
        "amoledBlack" to "blackBottomBar",
    )

    @Test fun `every interface family matches each supported vendor build without a warning`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = read(build)
            val commentsModules = classes.filter { it.isCommentsModule() }
            assertEquals("${build.name}: dedicated comments module count", 2, commentsModules.size)
            val unifiedComments = commentsModules.single { it.fields.any { field -> field.type == COMMENT_PREVIEW } }
            val rejectedComments = PatchContexts.of(ExtensionDex.classes() + classes)
            rejectedComments.mutableClassDefBy(unifiedComments.type).fields.removeAll { it.type == COMMENT_PREVIEW }
            val wrongScope = assertThrows(PatchException::class.java) { hideCommentsPatch.execute(rejectedComments) }
            assertTrue(wrongScope.message, wrongScope.message.orEmpty().contains("dedicated layout"))
            assertFlag(rejectedComments, "hideComments", false)
            assertFlag(rejectedComments, "comments", false)
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val warnings = PatchLogCapture.warnings { patches.forEach { it.execute(context) } }
            assertEquals("${build.name} warnings", emptyList<String>(), warnings)
            for ((family, capability) in flags) {
                assertFlag(context, family, true)
                assertFlag(context, capability, true)
            }
            for (type in SEARCH_HISTORY_VIEWS + COMMENT_PREVIEW + commentsModules.map { it.type }) {
                val view = context.mutableClassDefBy(type)
                for (name in listOf("setVisibility", "onMeasure")) {
                    val own = view.methods.single { it.name == name && it.returnType == "V" }
                    assertTrue("${build.name}: $type->$name has no extension hook",
                        own.calls().any { it.definingClass == UI_HOOKS })
                }
            }
            val closeups = classes.filter { owner ->
                owner.methods.any { "unifiedCommentsModulePresenterFactory" in it.strings() } ||
                    owner.type == "Lcom/pinterest/activity/pin/view/modules/PinCloseupVideoModule;"
            }
            assertTrue("${build.name}: missing closeup controls", closeups.size >= 2)
            for (original in closeups) {
                val closeup = context.mutableClassDefBy(original.type)
                assertEquals("${build.name}: ${original.type} method count changed", original.methods.count(), closeup.methods.size)
                assertTrue("${build.name}: ${original.type} must keep pin media and non-comment modules visible",
                    closeup.methods.none { it.calls().any { call -> call.definingClass == UI_HOOKS } })
            }
            val header = context.mutableClassDefBy(HEADER_BAR).methods.single { it.name == "onMeasure" }
            assertTrue(header.calls().any { it.definingClass == INTERFACE_CONTROLS && it.name == "headerButtons" })
            val menuType = context.classDefBy(PIN_MENU).fields.single { it.name == "modalView" }.type
            val menu = context.mutableClassDefBy(menuType).methods.single { it.name == "<init>" }
            assertTrue("${build.name} pin-menu entry hooks",
                menu.calls().count { it.definingClass == INTERFACE_CONTROLS && it.name == "pinMenuItem" } >= 4)
            assertTrue(menu.strings().containsAll(FILTERABLE_PIN_MENU_TITLES))
            assertDistinctArguments("${build.name} pin-menu", menu.instructions(), "pinMenuItem")
            val navType = classes.single { owner ->
                owner.methods.any { "BottomNavBar tab insertion out of range" in it.strings() }
            }.type
            val nav = context.mutableClassDefBy(navType)
            val bound = nav.methods.filter { method ->
                method.calls().any { it.definingClass == INTERFACE_CONTROLS && it.name == "bindNavigation" }
            }
            assertEquals("${build.name}: the tab binding and the tab replacement each bind once", 2, bound.sumOf { method ->
                method.calls().count { it.definingClass == INTERFACE_CONTROLS && it.name == "bindNavigation" }
            })
            val swap = bound.single { method -> method.calls().any { it.name == "removeViewAt" } }
            val body = swap.instructions()
            val hook = body.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "bindNavigation" }
            assertEquals("${build.name}: the replacement binds right after the new view's ID", "setId",
                ((body[hook - 3] as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(Opcode.INVOKE_INTERFACE, body[hook - 2].opcode)
            // 0.0.4's first replacement hook took v3 as scratch while v3 still held the new view, and
            // ART then refused the whole bar class, so the bar never appeared.
            bound.forEach { assertDistinctArguments("${build.name} ${it.name}", it.instructions(), "bindNavigation") }
            assertTrue(nav.methods.single { it.name == "onMeasure" }.calls().any {
                it.definingClass == INTERFACE_CONTROLS && it.name == "refreshNavigation"
            })
            // Its own dispatchDraw, so it can't share the onMeasure prefix Hide navigation buttons adds.
            val drawn = context.mutableClassDefBy(BOTTOM_BAR).methods.single {
                it.name == "dispatchDraw" && it.parameters() == listOf("Landroid/graphics/Canvas;")
            }.instructions()
            val paint = (drawn[0] as ReferenceInstruction).reference as MethodReference
            assertEquals("${build.name}: the bar paints before it draws its tab row", AMOLED_BLACK to "bottomBar",
                paint.definingClass to paint.name)
            assertEquals(listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_SUPER_RANGE, Opcode.RETURN_VOID), drawn.map { it.opcode })
            assertEquals("${build.name}: the bar still draws through its own superclass",
                classes.single { it.type == BOTTOM_BAR }.superclass,
                ((drawn[1] as ReferenceInstruction).reference as MethodReference).definingClass)
            val original = classes.flatMap { it.methods }.single { "inAppUpdateManager" in it.strings() }
            val modified = context.mutable(original)
            assertEquals("the shared coroutine's other task must keep its dispatcher", original.instructions()[0].opcode,
                modified.instructions()[0].opcode)
            assertEquals(1, modified.calls().count { it.definingClass == UI_HOOKS && it.name == "disableUpdateNag" })
            val called = modified.instructions().map { ((it as? ReferenceInstruction)?.reference as? MethodReference) }
            val guard = called.indexOfFirst { it?.definingClass == UI_HOOKS && it.name == "disableUpdateNag" }
            val prompt = called.indexOfFirst { it?.definingClass?.startsWith(PLAY_CORE_UPDATES) == true }
            assertTrue("${build.name}: the update hook runs before Play Core's update call ($guard, $prompt)",
                guard >= 0 && prompt > guard)
        }
    }

    @Test fun `a missing family status refuses before changing any vendor method`() {
        for ((index, patch) in patches.withIndex()) {
            val context = PatchContexts.of(ExtensionDex.classes())
            val family = flags[index].first
            context.mutableClassDefBy(SETTINGS_STATUS).methods.removeAll { it.name == family }
            val failure = assertThrows(PatchException::class.java) { patch.execute(context) }
            assertTrue(failure.message, failure.message.orEmpty().contains(family))
            assertFlag(context, flags[index].second, false)
        }
    }

    @Test fun `an update anchor that no longer comes before Play Core's call refuses with the coroutine untouched`() {
        val classes = read(Fixtures.declaredBuilds().last())
        val owner = classes.single { type -> type.methods.any { "inAppUpdateManager" in it.strings() } }
        val coroutine: (Method) -> Boolean = { "inAppUpdateManager" in it.strings() }
        val isPrompt: (Instruction) -> Boolean = {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass?.startsWith(PLAY_CORE_UPDATES) == true
        }
        // Retargeting a call keeps its size, so every branch offset still holds.
        fun retarget(instruction: Instruction, owner: String): Instruction {
            val call = ((instruction as ReferenceInstruction).reference as MethodReference)
            val moved = ImmutableMethodReference(owner, call.name, call.parameterTypes, call.returnType)
            return when (instruction) {
                is FiveRegisterInstruction -> ImmutableInstruction35c(instruction.opcode, instruction.registerCount,
                    instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF,
                    instruction.registerG, moved)
                is RegisterRangeInstruction -> ImmutableInstruction3rc(instruction.opcode, instruction.startRegister,
                    instruction.registerCount, moved)
                else -> throw AssertionError("unexpected call format ${instruction.opcode}")
            }
        }
        // A build that reaches Play Core before it reads the update manager. Branches are relative, so
        // they survive the shift; the try ranges don't, and the patch must refuse before reading them.
        val earlyPrompt = replace(owner, coroutine, keepTries = false) { body ->
            listOf(ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                ImmutableMethodReference("${PLAY_CORE_UPDATES}Moved;", "prompt", emptyList(), "V"))) + body
        }
        val noPrompt = replace(owner, coroutine) { body ->
            body.map { if (isPrompt(it)) retarget(it, "Lhush/test/NotPlayCore;") else it }
        }
        for ((reason, changed) in listOf("Play Core called before the anchor" to earlyPrompt, "no Play Core call" to noPrompt)) {
            val context = PatchContexts.of(ExtensionDex.classes() + classes.filter { it.type != owner.type } + changed)
            val failure = assertThrows(reason, PatchException::class.java) { disableUpdateNagPatch.execute(context) }
            assertTrue("$reason: ${failure.message}", failure.message.orEmpty().contains("no longer comes before"))
            assertEquals(reason, 0, context.mutableClassDefBy(owner.type).methods.single(coroutine).calls()
                .count { it.definingClass == UI_HOOKS })
            assertFlag(context, "disableUpdateNag", false)
        }
    }

    private fun replace(owner: ClassDef, which: (Method) -> Boolean, keepTries: Boolean = true,
                        change: (List<Instruction>) -> List<Instruction>) =
        ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile,
            owner.annotations, owner.fields, owner.methods.map { method ->
                val implementation = method.implementation
                if (!which(method) || implementation == null) method else ImmutableMethod(method.definingClass, method.name,
                    method.parameters, method.returnType, method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                    ImmutableMethodImplementation(implementation.registerCount, change(implementation.instructions.toList()),
                        if (keepTries) implementation.tryBlocks else null, null))
            })

    private fun read(build: File): List<ClassDef> {
        val anchors = setOf("sg_android_new_screenshot_api_14", "BottomNavTabModel(type=",
            "BottomNavBar tab insertion out of range", "confirmEmailButton", "inAppUpdateManager",
            "unifiedCommentsModulePresenterFactory", "CommentsZone(isVisible=")
        val required = (SEARCH_HISTORY_VIEWS + COMMENT_PREVIEW + HEADER_BAR + BOTTOM_BAR + PIN_MENU +
            "Lcom/pinterest/activity/pin/view/modules/PinCloseupVideoModule;").toSet()
        val found = mutableMapOf<String, ClassDef>()
        FixtureDex.forEach(build) { dex ->
            for (owner in dex.classes) {
                val match = owner.type in required || owner.isCommentsModule() ||
                    (owner.superclass == "Ljava/lang/Enum;" && owner.fields.map { it.name }
                        .containsAll(listOf("HOME", "PROFILE", "CREATE", "NOTIFICATIONS"))) ||
                    owner.methods.any { method -> method.strings().any { it in anchors } ||
                        method.fields().any { it.name in FILTERABLE_PIN_MENU_TITLES ||
                            it.name == "email_verification_reminder_title" } }
                if (match) found[owner.type] = ImmutableClassDef.of(owner)
            }
        }
        // Include every application superclass so final inherited methods are checked before an override is added.
        var parents = found.values.mapNotNull { it.superclass }.toSet() - found.keys
        while (parents.isNotEmpty()) {
            val loaded = FixtureDex.classes(build, parents)
            found += loaded
            parents = loaded.values.mapNotNull { it.superclass }.toSet() - found.keys
        }
        assertFalse("${build.name}: no vendor targets", found.isEmpty())
        return found.values.toList()
    }

    /** Each hook gets the original view and a separate value; one register twice means a scratch replaced the view. */
    private fun assertDistinctArguments(what: String, body: List<Instruction>, hook: String) {
        val sites = body.filter { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)
                ?.let { it.definingClass == INTERFACE_CONTROLS && it.name == hook } == true
        }
        assertTrue("$what: no $hook call", sites.isNotEmpty())
        for (site in sites) {
            val call = site as FiveRegisterInstruction
            assertTrue("$what: $hook passes v${call.registerC} twice", call.registerC != call.registerD)
        }
    }

    private fun assertFlag(context: BytecodePatchContext, name: String, enabled: Boolean) {
        val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.instructions().first()
        assertEquals(name, Opcode.CONST_4, first.opcode)
        assertEquals(name, if (enabled) 1 else 0, (first as NarrowLiteralInstruction).narrowLiteral)
    }
}
