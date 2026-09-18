package io.github.longislandicetea.dshnative

import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding the `workspaceFiles/read` page, and what "the end of the file" means.
 *
 * Every payload here was captured from a live Host rather than written by hand,
 * for the reason `EventDecodeTest` gives: a hand-written fixture encodes what
 * this client *believes* the wire says. The 7000-line case is the one that
 * matters -- the Host answered a request for a whole file with 5000 lines and
 * `eof: false`, and the app drew that page as the document, so the reader had no
 * way to know a third of it was missing.
 */
class WorkspaceFilePageTest {
    // Parsed as the decoded element is, with no casting at the call site: one
    // case below hands the decoder a JSON array on purpose, and an unchecked
    // cast here would throw instead of testing what the decoder does with it.
    private fun value(json: String): JsonElement = DshWire.json.parseToJsonElement(json)

    /** A page cut at the Host's 5000-line default: the capture that found the bug. */
    private val cutPage = """
        {"absolutePath":"/tmp/probe_big.md",
         "version":"2096:386721:390890:1789737158474978292:1789737158474978292",
         "bytes":390890,"offset":1,"lines":5000,"eof":false,
         "text":"line 0\nline 1\nline 2"}
    """.trimIndent()

    /** A whole file: reads end at the last line and say so. */
    private val wholeFile = """
        {"absolutePath":"/home/example/dsh-native/README.md",
         "version":"2096:359192:8101:1789133069144031763:1789133069144031763",
         "bytes":8101,"offset":1,"lines":211,"eof":true,"text":"# dsh-native\n"}
    """.trimIndent()

    @Test
    fun `a page that does not reach the end is marked truncated`() {
        val page = WorkspaceFileCodec.readPage(value(cutPage))
        assertEquals("line 0\nline 1\nline 2", page?.text)
        assertEquals(false, page?.eof)
        assertEquals(true, page?.truncated)
        assertEquals(5000, page?.lines)
    }

    @Test
    fun `a whole file is not marked truncated`() {
        val page = WorkspaceFileCodec.readPage(value(wholeFile))
        assertEquals("# dsh-native\n", page?.text)
        assertEquals(true, page?.eof)
        assertEquals(false, page?.truncated)
    }

    @Test
    fun `a result with no text is refused, not read as an empty file`() {
        // The Host omits `text` for a page holding one empty line, and it also
        // omits it when the result is some other shape entirely. Rendering
        // "(empty file)" for the second case would hide a protocol change.
        assertNull(WorkspaceFileCodec.readPage(value("""{"absolutePath":"/tmp/x","bytes":0,"eof":true}""")))
        assertNull(WorkspaceFileCodec.readPage(value("""[]""")))
    }

    @Test
    fun `an empty page that did carry text is kept`() {
        val page = WorkspaceFileCodec.readPage(value("""{"absolutePath":"/tmp/x","offset":1,"lines":1,"eof":true,"text":""}"""))
        assertEquals("", page?.text)
        assertEquals(false, page?.truncated)
    }

    @Test
    fun `a page that could not decode text is an error the caller can see`() {
        // The wire's shape for a wrong-encoding read; `readPreview` keys on the
        // "not-text" code to fall through to the byte endpoint.
        val page = WorkspaceFileCodec.readPage(
            value("""{"absolutePath":"/home/example/icon.png","bytes":10674,"offset":1,"lines":0,"eof":true,"text":"?"}"""),
        )
        assertEquals("?", page?.text)
    }

    @Test
    fun `an unknown addition does not fail the read`() {
        // The decoder is hand-written so that a field this client does not
        // render cannot turn a readable file into an unreadable one.
        val page = WorkspaceFileCodec.readPage(
            value("""{"absolutePath":"/tmp/x","text":"hello","eof":true,"mtime":1,"owner":"someone","lines":1}"""),
        )
        assertEquals("hello", page?.text)
    }

    @Test
    fun `a page from a host that sends no eof is treated as whole`() {
        // Guessing "truncated" would put the notice on every read from such a
        // Host, which is worse than not having it: a warning that is always on
        // is a warning nobody reads.
        val page = WorkspaceFileCodec.readPage(value("""{"absolutePath":"/tmp/x","text":"hello"}"""))
        assertEquals(true, page?.eof)
        assertTrue(page?.lines == null)
    }
}
