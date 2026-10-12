/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.actions

import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SharePreviewManifestTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test
    fun `the share preview provider is declared once, unexported and granting reads, beside Pinterest's own`() {
        val document = patchManifest("<provider android:name=\"com.pinterest.AccountProvider\" android:authorities=\"com.pinterest.account\" />", 2)
        val providers = document.getElementsByTagName("provider")
        assertEquals(2, providers.length)
        val added = (0 until providers.length).map { providers.item(it) as Element }
            .single { it.getAttribute("android:name") == SHARE_PREVIEW_PROVIDER }
        assertEquals("application", added.parentNode.nodeName)
        assertEquals("com.pinterest.hushpinterest.share", added.getAttribute("android:authorities"))
        assertEquals("false", added.getAttribute("android:exported"))
        assertEquals("true", added.getAttribute("android:grantUriPermissions"))
        assertEquals(4, added.attributes.length)
        assertTrue(added.childNodes.length == 0)
        assertEquals("com.pinterest.account", (providers.item(0) as Element).getAttribute("android:authorities"))
    }

    @Test
    fun `a provider already claiming the authority refuses`() {
        val failure = assertThrows(PatchException::class.java) {
            patchManifest("<provider android:name=\"com.example.Other\" android:authorities=\"com.example.a;com.pinterest.hushpinterest.share\" />")
        }
        assertTrue(failure.message, failure.message.orEmpty().contains("already claims com.pinterest.hushpinterest.share"))
    }

    private fun patchManifest(components: String, times: Int = 1): Document {
        val work = temporary.newFolder()
        val apk = File(work, "input.apk")
        ApkModule().use { module ->
            module.setManifest(AndroidManifestBlock.empty().apply {
                setPackageName("com.pinterest")
                setVersionName("14.39.0")
                setVersionCode(14398020)
            })
            module.writeApk(apk)
        }
        val config = PatcherConfig(apkFile = apk, temporaryFilesPath = File(work, "patcher"))
        ResourcePatchContext::class.java.getConstructor(PatcherConfig::class.java).newInstance(config).use { context ->
            val manifest = context["AndroidManifest.xml"]
            manifest.parentFile.mkdirs()
            manifest.writeText("""
                <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.pinterest">
                    <application>
                        <activity android:name="com.pinterest.activity.PinterestActivity" android:exported="false" />
                        $components
                    </application>
                </manifest>
            """.trimIndent())
            repeat(times) { sharePreviewProviderPatch.execute(context) }
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        }
    }
}
