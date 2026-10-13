/*
 * Written for HushPinterest (Pinterest), 2026, after the settings logo long press in
 * https://github.com/SysAdminDoc/Hushfacebook (GPL-3.0).
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.pinterest.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.pinterest.misc.extension.patchLog
import app.morphe.patches.pinterest.ui.instructions
import app.morphe.patches.pinterest.ui.mutable
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/**
 * The R field naming the home feed's greeting header, the bar the Pinterest logo sits in. This
 * build reads ids from R rather than inlining them, and the logo's own id is only in the layout.
 */
internal const val GREETING_HEADER_ID = "home_feed_greeting_header_view"

/** What gets the header once Pinterest has found it. */
internal const val GREETING_HEADER_HOOK = "$ENTRY->onGreetingHeader(Landroid/view/View;)V"

private const val FIND_VIEW = "Landroid/view/View;->findViewById(I)Landroid/view/View;"

/** Where Pinterest finds the greeting header: the hook goes in at [index], passing the header in [register]. */
internal class HeaderSite(val method: Method, val index: Int, val register: Int)

private fun Instruction.readsHeaderId(): Boolean {
    if (opcode != Opcode.SGET) return false
    val field = (this as ReferenceInstruction).reference as? FieldReference ?: return false
    return field.name == GREETING_HEADER_ID && field.type == "I"
}

/**
 * Every place Pinterest finds the greeting header: the R field read, `findViewById` with it, then
 * `move-result-object`. In 14.39.0 that's the header controller's bind, the only method reading
 * the field. No Pinterest call changes, since the logo has no listener of its own to stand in for.
 */
internal fun BytecodePatchContext.greetingHeaderSites(): List<HeaderSite> {
    val sites = mutableListOf<HeaderSite>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        owner.methods.forEach { method ->
            val body = method.instructions()
            body.indices.filter { body[it].readsHeaderId() }.mapNotNullTo(sites) { headerFoundAt(method, body, it) }
        }
    }
    return sites
}

private fun headerFoundAt(method: Method, body: List<Instruction>, at: Int): HeaderSite? {
    val find = body.getOrNull(at + 1) as? FiveRegisterInstruction ?: return null
    if (find.opcode != Opcode.INVOKE_VIRTUAL || (find as ReferenceInstruction).reference.toString() != FIND_VIEW ||
        find.registerCount != 2 || find.registerD != (body[at] as OneRegisterInstruction).registerA) return null
    val kept = body.getOrNull(at + 2)
    if (kept?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    return HeaderSite(method, at + 3, (kept as OneRegisterInstruction).registerA)
}

/**
 * Hands the greeting header to [GREETING_HEADER_HOOK] right after Pinterest finds it, so a long
 * press on the Pinterest logo opens the HushPinterest screen. Answers false and changes nothing
 * when this build doesn't find the header exactly once that way: the logo then stays as it was,
 * and the icon shortcut and App info still open the screen.
 */
internal fun BytecodePatchContext.hookLogoLongPress(): Boolean {
    val sites = greetingHeaderSites()
    val site = sites.singleOrNull() ?: run {
        patchLog.warning("HushPinterest settings: ${sites.size} places find the home feed header, so the Pinterest logo doesn't open the settings")
        return false
    }
    val method = mutable(site.method)
    // The header is only in its register on the path from findViewById.
    if ((method.implementation!!.instructions[site.index] as BuilderInstruction).location.labels.isNotEmpty()) {
        patchLog.warning("HushPinterest settings: ${site.method.definingClass}->${site.method.name} branches past the home feed header, so the Pinterest logo doesn't open the settings")
        return false
    }
    method.addInstruction(site.index, "invoke-static/range { v${site.register} .. v${site.register} }, $GREETING_HEADER_HOOK")
    return true
}
