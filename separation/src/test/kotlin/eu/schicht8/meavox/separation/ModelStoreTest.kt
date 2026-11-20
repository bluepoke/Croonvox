// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.separation

import eu.schicht8.meavox.core.model.SeparationModelId
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ModelStoreTest {
    @TempDir
    lateinit var dir: File

    private val onnx = "the weights".toByteArray()
    private val http = ModelStore.Http { _, from -> ModelStore.Response(200, onnx.inputStream().also { it.skip(from) }) }

    private fun model(sha256: String) = SeparationModel(
        id = SeparationModelId("mdx"),
        name = "MDX",
        download = ModelDownload("https://example.org/model.onnx", sha256, onnx.size.toLong(), files = listOf("model.onnx"), archive = false),
    ) { error("not opened") }

    @Test
    fun `a model downloaded as a plain file is installed under its file name`() {
        val installed = ModelStore(dir, http).install(model(sha256(onnx)))

        assertContentEquals(onnx, File(installed, "model.onnx").readBytes())
    }

    @Test
    fun `a plain file that doesn't match its checksum is not installed`() {
        val store = ModelStore(dir, http)

        assertFailsWith<ModelCorrupt> { store.install(model("0".repeat(64))) }

        assertFalse(File(dir, "mdx/model.onnx").exists())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
