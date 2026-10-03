/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.privacy

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.handleTargets
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireLocals
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val PATCH = "Disable analytics"
internal const val ANALYTICS = "$EXTENSION_PACKAGE/privacy/Analytics;"
internal const val FIREBASE_DEACTIVATED = "firebase_analytics_collection_deactivated"
private const val NETWORK_RESPONSE = "Lcom/pinterest/api/adapter/coroutine/NetworkResponse;"

/** Whitelist from the API annotations in both declared original APKs. */
internal val TELEMETRY_PATHS = setOf(
    "v3/callback/event/", "v3/callback/ping/", "v3/callback/post_install/",
    "v3/callback/track_funnel/{event}/", "v3/register/track_action/{event}/",
    "v4/log/mobile_perf/", "callback/client_network_error/", "log/", "track/",
)

internal fun Method.telemetryPath(): String? = annotations.asSequence().flatMap { it.elements.asSequence() }
    .mapNotNull { (it.value as? StringEncodedValue)?.value }.firstOrNull { it in TELEMETRY_PATHS }

/** This documented Analytics-only flag also covers initialization before application.onCreate. */
internal fun deactivateFirebaseAnalytics(document: Document) {
    val application = document.getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("AndroidManifest.xml has no application element")
    val metadata = application.childNodes.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
        .filter { it.tagName == "meta-data" && it.getAttribute("android:name") == FIREBASE_DEACTIVATED }
    if (metadata.size > 1) throw PatchException("AndroidManifest.xml has repeated $FIREBASE_DEACTIVATED metadata")
    val entry = metadata.singleOrNull() ?: document.createElement("meta-data").also { application.appendChild(it) }
    entry.setAttribute("android:name", FIREBASE_DEACTIVATED)
    entry.removeAttribute("android:resource")
    entry.setAttribute("android:value", "true")
}

internal val disableFirebaseAnalyticsManifestPatch = resourcePatch {
    execute { document("AndroidManifest.xml").use(::deactivateFirebaseAnalytics) }
}

@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = PATCH,
    description = "Stops Pinterest's usage-event and performance uploads and AppsFlyer tracking. " +
        "A switch and Pause restore those runtime paths. Firebase Analytics is disabled in the " +
        "manifest and stays disabled until you patch again without this patch. Sign-in, pin requests " +
        "and Firebase push components are preserved.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch, pinterestExtensionPatch, disableFirebaseAnalyticsManifestPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        listOf("disableAnalytics", "analyticsTasks", "analyticsUploads").forEach(::requireStatusMethod)
        val services = mutableListOf<Method>()
        classDefForEach { owner ->
            if (AccessFlags.INTERFACE.isSet(owner.accessFlags)) services += owner.methods.filter { it.telemetryPath() != null }
        }
        if (services.isEmpty()) throw PatchException("$PATCH: no annotated Pinterest telemetry service was found")

        val completed = completedResponseFactories(services)
        val wrappers = services.mapIndexed { index, method ->
            method.identity() to addUploadWrapper(method, "hushUpload$index", completed.getValue(method.identity()))
        }.toMap()
        val counts = redirectPrivacyCalls(wrappers)
        val covered = handleTargets(PATCH, "Pinterest telemetry endpoints", TELEMETRY_PATHS.toList()) { path ->
            if (services.any { it.telemetryPath() == path && counts.getOrDefault(it.identity(), 0) > 0 }) null
            else "no callable $path telemetry endpoint was found"
        }

        // AppsFlyer's transport keeps its package in both APKs. Only its own URL calls are changed.
        val network = mapOf(
            "Ljava/net/URL;->openConnection()Ljava/net/URLConnection;" to
                "$ANALYTICS->openConnection(Ljava/net/URL;)Ljava/net/URLConnection;",
        )
        val sdkCalls = redirectPrivacyCalls(network) { it.startsWith("Lcom/appsflyer/") }.values.sum()
        if (sdkCalls == 0) throw PatchException("$PATCH: AppsFlyer's URL transport wasn't found")
        if (covered == TELEMETRY_PATHS.size) enableCapability("analyticsUploads")
        hookAnalyticsTasks()
        enableCapability("analyticsTasks")
        enableStatus("disableAnalytics")
    }
}

/** Creates completed values with the vendor's own response types, so subscribers can clean up. */
private fun BytecodePatchContext.completedResponseFactories(services: List<Method>): Map<String, String> {
    val factories = mutableMapOf<String, String>()
    val bodies = mutableMapOf<String, String>()
    for (service in services) {
        val response = service.returnType
        val reused = bodies[response]
        if (reused != null) {
            factories[service.identity()] = reused
            continue
        }
        val body = when {
            response == "Ljava/lang/Object;" -> {
                val signature = service.genericSignature()
                if (!signature.contains("${NETWORK_RESPONSE.removeSuffix(";")}<Lkotlin/Unit;>")) {
                    throw PatchException("$PATCH: ${service.identity()} isn't a coroutine returning NetworkResponse<Unit>")
                }
                val success = mutableListOf<ClassDef>()
                classDefForEach { owner ->
                    if (owner.superclass == NETWORK_RESPONSE && owner.methods.any { method ->
                            method.name == "toString" && method.strings().contains("Success(value=")
                        }) success += owner
                }
                val type = success.singleOrNull()?.type ?: throw PatchException("$PATCH: no unique successful NetworkResponse")
                requireConstructor(type, listOf("Ljava/lang/Object;"))
                val unit = classDefBy("Lkotlin/Unit;").fields.singleOrNull {
                    AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "Lkotlin/Unit;"
                } ?: throw PatchException("$PATCH: Kotlin's Unit singleton wasn't found")
                """
                    new-instance v0, $type
                    sget-object v1, Lkotlin/Unit;->${unit.name}:Lkotlin/Unit;
                    invoke-direct { v0, v1 }, $type-><init>(Ljava/lang/Object;)V
                """
            }
            service.telemetryPath() == "log/" -> {
                val signature = service.genericSignature()
                val json = signature.substringAfter("${response.removeSuffix(";")}<", "").substringBefore('>')
                if (!json.startsWith("L") || !json.endsWith(';') || json.contains('<')) {
                    throw PatchException("$PATCH: ${service.identity()} has no concrete JSON response type")
                }
                requireConstructor(json, emptyList())
                val factory = classDefBy(response).methods.singleOrNull { method ->
                    AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Object;") &&
                        classDefByOrNull(method.returnType)?.superclass == response
                } ?: throw PatchException("$PATCH: no unique Single.just factory in $response")
                """
                    new-instance v0, $json
                    invoke-direct { v0 }, $json-><init>()V
                    invoke-static { v0 }, ${factory.identity()}
                    move-result-object v0
                """
            }
            else -> {
                val candidates = mutableListOf<ClassDef>()
                classDefForEach { owner ->
                    if (owner.superclass == response && owner.fields.count {
                            AccessFlags.STATIC.isSet(it.accessFlags) && it.type == owner.type
                        } == 1 && owner.instanceFields.none() && owner.methods.any { method ->
                            val calls = method.implementation?.instructions?.mapNotNull { it.callReference() }.orEmpty()
                            calls.size == 1 && calls.single().name == "complete" && calls.single().returnType == "V"
                        }) candidates += owner
                }
                val owner = candidates.singleOrNull() ?: throw PatchException("$PATCH: no unique completed Completable in $response")
                val field = owner.fields.single { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == owner.type }
                "sget-object v0, ${owner.type}->${field.name}:${field.type}"
            }
        }
        val completed = body.trimIndent() + "\nreturn-object v0"
        bodies[response] = completed
        factories[service.identity()] = completed
    }
    return factories
}

private fun Method.genericSignature(): String = annotations.firstOrNull { it.type == "Ldalvik/annotation/Signature;" }
    ?.elements?.firstOrNull { it.name == "value" }?.value.let { value ->
        (value as? ArrayEncodedValue)?.value?.joinToString("") { (it as? StringEncodedValue)?.value.orEmpty() }.orEmpty()
    }

private fun Method.strings(): List<String> = implementation?.instructions?.mapNotNull {
    ((it as? com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction)?.reference as?
        com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string
}.orEmpty()

private fun BytecodePatchContext.requireConstructor(type: String, parameters: List<String>) {
    if (classDefBy(type).methods.none { method -> method.name == "<init>" &&
            AccessFlags.PUBLIC.isSet(method.accessFlags) && method.parameterTypes.map { it.toString() } == parameters }) {
        throw PatchException("$PATCH: $type has no public constructor for $parameters")
    }
}

/** A new static method takes the original receiver and parameters without changing their values. */
private fun BytecodePatchContext.addUploadWrapper(original: MethodReference, name: String, blocked: String): String {
    val parameters = listOf(original.definingClass) + original.parameterTypes.map { it.toString() }
    val width = parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
    val wrapper = ImmutableMethod(
        ANALYTICS, name, parameters.map { ImmutableMethodParameter(it, null, null) }, original.returnType,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(width + 2),
    ).toMutable().apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $ANALYTICS->blockUpload()Z
                move-result v0
                if-eqz v0, :original
                $blocked
                :original
                invoke-interface/range { p0 .. p${width - 1} }, ${original.identity()}
                move-result-object v0
                return-object v0
            """,
        )
    }
    mutableClassDefBy(ANALYTICS).methods.add(wrapper)
    return wrapper.identity()
}

/** The scheduler owns a Runnable and the enum whose un-obfuscated labels identify startup tasks. */
private fun BytecodePatchContext.hookAnalyticsTasks() {
    val enums = mutableListOf<String>()
    classDefForEach { owner ->
        if (owner.superclass == "Ljava/lang/Enum;" && owner.fields.any { it.name == "TAG_APPSFLYER_INIT" } &&
            owner.fields.any { it.name == "TAG_FIREBASE_ANALYTICS_INIT" }) enums += owner.type
    }
    val tagType = enums.singleOrNull() ?: throw PatchException("$PATCH: no unique analytics task enum")
    val schedulers = mutableListOf<Pair<String, String>>()
    classDefForEach { owner ->
        if (owner.fields.none { it.type == "Ljava/lang/Runnable;" }) return@classDefForEach
        val tag = owner.instanceFields.singleOrNull { it.type == tagType } ?: return@classDefForEach
        for (method in owner.methods) {
            if (method.returnType != "V" || method.parameterTypes.isNotEmpty() || method.name.startsWith('<')) continue
            if (method.implementation?.instructions?.mapNotNull { it.callReference() }?.any {
                    it.definingClass == "Ljava/util/Map;" && it.name == "put"
                } == true) schedulers += method.identity() to tag.name
        }
    }
    val (identity, tag) = schedulers.singleOrNull() ?: throw PatchException("$PATCH: no unique tagged startup scheduler")
    val owner = identity.substringBefore("->")
    val name = identity.substringAfter("->").substringBefore('(')
    val method = mutableClassDefBy(owner).methods.single { it.name == name && it.parameterTypes.isEmpty() }
    method.requireLocals(PATCH, 1)
    method.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, $owner->$tag:$tagType
            invoke-static { v0 }, $ANALYTICS->blockTask(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :hush_original_task
            return-void
        """,
        ExternalLabel("hush_original_task", method.getInstruction(0)),
    )
}
