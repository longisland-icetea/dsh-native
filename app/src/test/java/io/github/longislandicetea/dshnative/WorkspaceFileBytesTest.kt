package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a figure back: the byte window, and the multipart answer it arrives in.
 *
 * This is the regression that broke image preview on the phone. The Host moved
 * from base64 inside the JSON to native bytes carried *beside* it, and renamed
 * the window from `range` to `options` in the same release, so the call was
 * refused before the file was ever opened and the sheet showed a protocol error
 * where the figure should have been.
 *
 * The body the main test decodes is the Host's own answer, captured over the
 * wire (`read-bytes-multipart.bin`), and the bytes inside it are the file the
 * test compares against -- a real PNG, small enough to spell out, chosen because
 * its own bytes contain a CRLF, which is what a boundary scan has to survive.
 */
class WorkspaceFileBytesTest {
    /** The boundary the captured answer declares in its `content-type`. */
    private val boundary = "----formdata-undici-083278686126"

    private val captured: ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/read-bytes-multipart.bin")).use { it.readBytes() }

    @Test
    fun `the byte read names its window the way the descriptor does`() {
        val args = byteReadArgs("session-1", "/tmp/probe.png")
        assertEquals(setOf("workspaceFileScopeId", "path", "options"), args.keys)
        assertEquals(JsonPrimitive("session-1"), args["workspaceFileScopeId"])
        assertEquals(JsonPrimitive("/tmp/probe.png"), args["path"])
        // The empty window is the whole file.
        assertEquals(0, (args["options"] as JsonObject).size)
        // `range` is what 0.1.5 called this window, and what this client sent to
        // a 0.1.7 Host: `missing "options"; unexpected "range"`, refused before
        // the read, which is the whole of what the preview sheet had to show.
        assertNull(args["range"])
    }

    @Test
    fun `a captured multipart answer yields the file's own bytes`() {
        val parts = MultipartForm.parts(captured, boundary)
        val parsed = DshWire.json.decodeFromString<RpcResponse>(
            parts.getValue(MultipartForm.METADATA_PART).decodeToString(),
        )
        val answer = RpcAnswer(parsed.result.value, parts, RpcAttachmentCodec.refs(parsed.attachments))

        val value = parsed.result.value as JsonObject
        // The placeholder, and the entry that says which part fills it.
        assertEquals("the value keeps the field and puts nothing in it", JsonNull, value["data"])
        assertEquals(
            listOf(RpcAttachmentRef("bytes-0", listOf("data"))),
            RpcAttachmentCodec.refs(parsed.attachments),
        )
        // The rest of the page is still JSON, and still read.
        assertEquals(89, (value["bytes"] as JsonPrimitive).int)
        assertEquals(true, (value["eof"] as JsonPrimitive).boolean)
        assertEquals("/tmp/dshpreview/probe.png", (value["absolutePath"] as JsonPrimitive).contentOrNull)

        assertEquals(FIGURE_HEX, answer.bytesAt(listOf("data"))?.toHex())
    }

    @Test
    fun `an answer with no attachments carries no bytes, and is not read as an empty file`() {
        val plainJson = """
            {"type":"server-response","rpcId":"r1","result":{"ok":true,
             "value":{"absolutePath":"/tmp/probe.png","offset":0,"data":null,"eof":true}}}
        """.trimIndent()
        val parsed = DshWire.json.decodeFromString<RpcResponse>(plainJson)
        val answer = RpcAnswer(parsed.result.value, emptyMap(), RpcAttachmentCodec.refs(parsed.attachments))
        assertNull(answer.bytesAt(listOf("data")))
        assertTrue(RpcAttachmentCodec.refs(parsed.attachments).isEmpty())
    }

    @Test
    fun `an attachment this client cannot place is skipped, not fatal`() {
        val refs = RpcAttachmentCodec.refs(
            DshWire.json.parseToJsonElement(
                """[{"path":["data"],"codec":"bytes","part":"bytes-0"},
                    {"path":["data"],"codec":"text","part":"bytes-1"},
                    {"path":[0],"codec":"bytes","part":"bytes-2"},
                    {"path":["data"],"codec":"bytes"},
                    "nonsense"]""",
            ),
        )
        assertEquals(listOf(RpcAttachmentRef("bytes-0", listOf("data"))), refs)
    }

    @Test
    fun `a part is found by its name, quoted or bare`() {
        // OkHttp's reader gives a part its headers and its bytes; the name is
        // this client's to read, and both spellings are legal.
        val body = (
            "--B\r\nContent-Disposition: form-data; name=\"quoted\"\r\n\r\nfirst\r\n" +
                "--B\r\nContent-Disposition: form-data; name=bare\r\n\r\nsecond\r\n" +
                "--B\r\nContent-Disposition: form-data\r\n\r\nnameless\r\n" +
                "--B--\r\n"
        ).toByteArray(Charsets.UTF_8)
        val parts = MultipartForm.parts(body, "B")
        assertEquals("first", parts["quoted"]?.decodeToString())
        assertEquals("second", parts["bare"]?.decodeToString())
        assertEquals(setOf("quoted", "bare"), parts.keys)
    }

    private companion object {
        /**
         * The figure inside the capture, as the file spells it: an 8x8 paletted
         * PNG whose IHDR/PLTE/IDAT bytes include a CRLF.
         */
        const val FIGURE_HEX =
            "89504e470d0a1a0a0000000d4948445200000008000000080103000000fec12cc800000006504c5" +
                "445ffffffbfbfbfa34376390000000e4944415408d763f80085fc1008002e0003fda3696ed1" +
                "0000000049454e44ae426082"
    }
}

/** The bytes as lowercase hex, for an assertion whose failure can be read. */
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
