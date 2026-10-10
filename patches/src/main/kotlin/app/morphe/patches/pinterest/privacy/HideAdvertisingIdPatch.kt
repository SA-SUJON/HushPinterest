/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireLocals
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.extension.requireStub
import app.morphe.patches.pinterest.misc.extension.writeStub
import app.morphe.patches.pinterest.misc.settings.EXTENSION_ROOT
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val PATCH = "Hide advertising ID"
internal const val ADVERTISING_ID = "$EXTENSION_PACKAGE/privacy/AdvertisingId;"
internal const val ADVERTISING_INFO = "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient\$Info;"

/** Google's public getters every reader goes through, and the hook that filters each one's answer. */
internal val ADVERTISING_ID_GETTERS = mapOf(
    "$ADVERTISING_INFO->getId()Ljava/lang/String;" to "$ADVERTISING_ID->id(Ljava/lang/String;)Ljava/lang/String;",
    "$ADVERTISING_INFO->isLimitAdTrackingEnabled()Z" to "$ADVERTISING_ID->limitTracking(Z)Z",
)

private const val BLOCK_STORE = "Lcom/google/android/gms/auth/blockstore/"

/** The request, data and request types Google's Block Store client takes to read, save and delete. */
internal const val RETRIEVE_BYTES_REQUEST = "${BLOCK_STORE}RetrieveBytesRequest;"
internal const val STORE_BYTES_DATA = "${BLOCK_STORE}StoreBytesData;"
internal const val DELETE_BYTES_REQUEST = "${BLOCK_STORE}DeleteBytesRequest;"

/** The hooks at the head of Pinterest's Block Store read and save. */
internal const val SKIP_BROWSER_ID_HOOK = "$ADVERTISING_ID->skipBrowserId(Ljava/lang/String;)Z"
internal const val SAVE_BROWSER_ID_HOOK =
    "$ADVERTISING_ID->saveBrowserId(Ljava/lang/Object;Ljava/lang/String;[BLjava/lang/Object;)Ljava/lang/Object;"

/** The extension's stub that the patch rewrites to call Pinterest's own Block Store delete. */
internal const val DELETE_BROWSER_ID_STUB = "deleteBrowserId"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

@Suppress("unused")
val hideAdvertisingIdPatch = bytecodePatch(
    name = PATCH,
    description = "Pinterest sees an empty advertising ID with ad tracking limited, the same as if you deleted your" +
        " ad ID in Android. Pinterest also stops keeping its browser ID in Google's Block Store, so a reinstall" +
        " doesn't bring the old one back. Good for keeping ads from following you. On by default. Turn it off in " +
        "HushPinterest settings > Privacy.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("hideAdvertisingId")
        requireStatusMethod("advertisingId")
        requireStatusMethod("browserId")
        // Every edit is planned on copies first, so a refusal leaves the APK as it was.
        val edits = ADVERTISING_ID_GETTERS.map { (getter, hook) -> answerFilter(getter, hook) }
        val store = findBrowserIdStore()
        val browserId = browserIdHooks(store)
        (edits + browserId).forEach { it.apply(this) }
        // The store's own delete, on the save's continuation, so the save's callers get its Boolean.
        writeStub(ADVERTISING_ID, DELETE_BROWSER_ID_STUB, 4, """
            check-cast p0, ${store.owner.type}
            check-cast p2, ${store.continuation}
            invoke-virtual { p0, p1, p2 }, ${store.delete.identity()}
            move-result-object v0
            return-object v0
        """)
        enableCapability("advertisingId")
        enableCapability("browserId")
        enableStatus("hideAdvertisingId")
    }
}

/** Sends each value the getter returns through the hook, leaving the getter's own read untouched. */
private fun BytecodePatchContext.answerFilter(getter: String, hook: String): PrivacyMethodEdit {
    val owner = classDefByOrNull(getter.substringBefore("->"))
        ?: throw PatchException("$PATCH: Google's advertising ID info class wasn't found")
    val original = owner.methods.singleOrNull { it.identity() == getter }
        ?: throw PatchException("$PATCH: $getter wasn't found")
    requireHook(hook)
    if (AccessFlags.STATIC.isSet(original.accessFlags) || original.implementation == null) {
        throw PatchException("$PATCH: $getter isn't an instance method with a body")
    }
    val answer = if (original.returnType == "Z") Opcode.RETURN else Opcode.RETURN_OBJECT
    val returns = original.implementation!!.instructions.withIndex().filter { (_, it) ->
        it.opcode in setOf(Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE, Opcode.RETURN_VOID)
    }
    if (returns.isEmpty() || returns.any { (_, it) -> it.opcode != answer || (it as OneRegisterInstruction).registerA > 15 }) {
        throw PatchException("$PATCH: $getter doesn't return its answer from a register the hook can take")
    }
    val move = if (answer == Opcode.RETURN) "move-result" else "move-result-object"
    val method = ImmutableMethod.of(original).toMutable()
    for ((index, instruction) in returns.asReversed()) {
        val register = (instruction as OneRegisterInstruction).registerA
        method.addInstructions(index, "invoke-static { v$register }, $hook\n$move v$register")
    }
    return PrivacyMethodEdit(getter, ImmutableMethod.of(method))
}

/** Throws unless the extension has [hook] as a public static method the app's code can call. */
private fun BytecodePatchContext.requireHook(hook: String) {
    val extension = classDefByOrNull(ADVERTISING_ID)?.methods?.singleOrNull { it.identity() == hook }
    if (extension == null || !AccessFlags.PUBLIC.isSet(extension.accessFlags) || !AccessFlags.STATIC.isSet(extension.accessFlags)) {
        throw PatchException("$PATCH: no callable hook $hook")
    }
}

/**
 * Pinterest's Block Store wrapper, [owner], and its three suspend methods: [read] takes a key,
 * [save] a key and the bytes to keep under it, [delete] a list of keys. Each takes the coroutine's
 * continuation last, the same type for all three, which is [continuation].
 */
internal data class BrowserIdStore(val owner: ClassDef, val read: Method, val save: Method, val delete: Method) {
    val continuation: String get() = read.parameterTypes.last().toString()
}

/**
 * The one class outside the extension whose instance methods read, save and delete a Block Store
 * record by key: a `(String, continuation)Serializable` that builds Google's retrieve request, a
 * `(String, byte[], continuation)Object` that builds its store data and a `(List, continuation)Object`
 * that builds its delete request, on one continuation type. Pinterest keeps its browser ID there under
 * the key "pid". Throws naming what's wrong, before any change, unless exactly one class has all three.
 */
internal fun BytecodePatchContext.findBrowserIdStore(): BrowserIdStore {
    val complete = mutableListOf<BrowserIdStore>()
    val partial = mutableListOf<String>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        val (store, shape) = storeShape(owner) ?: return@classDefForEach
        if (store != null) complete += store else partial += shape
    }
    if (complete.size > 1) {
        throw PatchException(
            "$PATCH: expected one Block Store wrapper with a read, save and delete, found ${complete.size}: " +
                complete.joinToString { it.owner.type },
        )
    }
    return complete.singleOrNull() ?: throw PatchException(
        "$PATCH: Pinterest's Block Store wrapper wasn't found: no class has one read, one save and one delete " +
            "on one continuation type" + if (partial.isEmpty()) "" else " (" + partial.joinToString("; ") + ")",
    )
}

/** [owner]'s Block Store read, save and delete when [findBrowserIdStore] would take them, else null. */
internal fun browserIdStoreIn(owner: ClassDef): BrowserIdStore? = storeShape(owner)?.first

/**
 * Null when [owner] has none of the three calls. Otherwise the wrapper when it has one of each on
 * one continuation type, or null and what it has instead.
 */
private fun storeShape(owner: ClassDef): Pair<BrowserIdStore?, String>? {
    val methods = owner.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
    val reads = methods.filter { it.storeCall(listOf(STRING), "Ljava/io/Serializable;", RETRIEVE_BYTES_REQUEST) }
    val saves = methods.filter { it.storeCall(listOf(STRING, "[B"), OBJECT, STORE_BYTES_DATA) }
    val deletes = methods.filter { it.storeCall(listOf("Ljava/util/List;"), OBJECT, DELETE_BYTES_REQUEST) }
    if (reads.isEmpty() && saves.isEmpty() && deletes.isEmpty()) return null
    val continuations = (reads + saves + deletes).map { it.parameterTypes.last().toString() }.toSet()
    if (reads.size == 1 && saves.size == 1 && deletes.size == 1 && continuations.size == 1) {
        return BrowserIdStore(owner, reads.single(), saves.single(), deletes.single()) to ""
    }
    return null to "${owner.type} has ${reads.size} read(s), ${saves.size} save(s) and ${deletes.size} delete(s) " +
        "on ${continuations.size} continuation type(s)"
}

/** An instance method taking [leading] then a continuation, returning [returns], that names [request]. */
private fun Method.storeCall(leading: List<String>, returns: String, request: String): Boolean {
    if (returnType != returns || parameterTypes.size != leading.size + 1) return false
    val types = parameterTypes.map { it.toString() }
    return types.dropLast(1) == leading && types.last().startsWith("L") && mentions(request)
}

/** Whether any instruction names [type]: as a type, a member's owner, or a field, parameter or return type. */
private fun Method.mentions(type: String): Boolean = implementation?.instructions?.any { instruction ->
    when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        is TypeReference -> reference.type == type
        is MethodReference -> reference.definingClass == type || reference.returnType == type ||
            reference.parameterTypes.any { it.toString() == type }
        is FieldReference -> reference.definingClass == type || reference.type == type
        else -> false
    }
} == true

/**
 * The read and save hooks, planned on copies, each in front of the method's own first
 * instruction. The read answers null, Block Store's "no record", when [SKIP_BROWSER_ID_HOOK] says
 * true. The save returns what [SAVE_BROWSER_ID_HOOK] hands back unless that's null. Both borrow v0,
 * which holds nothing before the method's own first instruction. The read hands the hook its key
 * and the save its store, key, bytes and continuation, by range, so no parameter register is
 * copied. A coroutine resume calls each again with null for every object argument, which the
 * hooks pass through to the method's own path.
 *
 * Also checks the delete the extension's stub calls: it and the wrapper have to be public, and so
 * does the continuation type the stub casts to, since the stub sits in another package.
 */
private fun BytecodePatchContext.browserIdHooks(store: BrowserIdStore): List<PrivacyMethodEdit> {
    requireHook(SKIP_BROWSER_ID_HOOK)
    requireHook(SAVE_BROWSER_ID_HOOK)
    requireStub(ADVERTISING_ID, DELETE_BROWSER_ID_STUB)
    val continuation = classDefByOrNull(store.continuation)
    if (!AccessFlags.PUBLIC.isSet(store.owner.accessFlags) || AccessFlags.INTERFACE.isSet(store.owner.accessFlags) ||
        !AccessFlags.PUBLIC.isSet(store.delete.accessFlags) ||
        (continuation != null && !AccessFlags.PUBLIC.isSet(continuation.accessFlags))
    ) {
        throw PatchException("$PATCH: the Block Store delete ${store.delete.identity()} can't be called from the extension")
    }
    val read = ImmutableMethod.of(store.read).toMutable()
    read.requireLocals("$PATCH: Block Store read", 1)
    read.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p1 .. p1 }, $SKIP_BROWSER_ID_HOOK
            move-result v0
            if-eqz v0, :hush_read
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("hush_read", read.getInstruction(0)),
    )
    val save = ImmutableMethod.of(store.save).toMutable()
    save.requireLocals("$PATCH: Block Store save", 1)
    save.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p3 }, $SAVE_BROWSER_ID_HOOK
            move-result-object v0
            if-eqz v0, :hush_save
            return-object v0
        """,
        ExternalLabel("hush_save", save.getInstruction(0)),
    )
    return listOf(
        PrivacyMethodEdit(store.read.identity(), ImmutableMethod.of(read)),
        PrivacyMethodEdit(store.save.identity(), ImmutableMethod.of(save)),
    )
}
