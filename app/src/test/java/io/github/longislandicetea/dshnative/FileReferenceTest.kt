package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning what a reply *wrote* into what the Host can be asked for.
 *
 * The cases are the spellings the harness's own instructions produce -- a
 * bracketed destination relative to the working directory, an absolute one, a
 * line anchor -- plus the ones a model writes anyway (percent escapes, an en
 * dash, a Windows path) and the ones that must stay shut (a fragment, a foreign
 * scheme, a directory).
 */
class FileReferenceTest {
    // ── what a destination names ──────────────────────────────────────────────

    @Test
    fun `a relative destination is a file, resolved against the base`() {
        val target = linkTargetOf("analysis_out/figures/fig1_electronic_structure.png")
        assertTrue(target is LinkTarget.File)
        assertEquals(
            "/mnt/c/Users/project/ips-SC/analysis_out/figures/fig1_electronic_structure.png",
            resolveWorkspacePath("/mnt/c/Users/project/ips-SC", (target as LinkTarget.File).ref.path),
        )
    }

    @Test
    fun `an absolute destination is a file and is left alone`() {
        val target = linkTargetOf("/home/cxxiao/dsh-native/README.md")
        assertEquals("/home/cxxiao/dsh-native/README.md", (target as LinkTarget.File).ref.path)
        assertEquals(
            "/home/cxxiao/dsh-native/README.md",
            resolveWorkspacePath("/somewhere/else", target.ref.path),
        )
    }

    @Test
    fun `angle brackets are syntax and come off`() {
        val target = linkTargetOf("<out/My Report (final).md>")
        assertEquals("out/My Report (final).md", (target as LinkTarget.File).ref.path)
    }

    @Test
    fun `a line anchor rides along and is not part of the name`() {
        val single = linkTargetOf("scripts/elias_py/make_figures.py#L24")
        assertEquals("scripts/elias_py/make_figures.py", (single as LinkTarget.File).ref.path)
        assertEquals(24..24, single.ref.lines)

        val range = linkTargetOf("AAp_FINAL_RESULT.md#L24-L30")
        assertEquals(24..30, (range as LinkTarget.File).ref.lines)
    }

    @Test
    fun `an en dash range is a range`() {
        // The same instruction that asks for `#L24-L30` asks for `24–30` in
        // prose, and the two arrive mixed.
        assertEquals(24..30, (linkTargetOf("a.md#L24\u201330") as LinkTarget.File).ref.lines)
    }

    @Test
    fun `a fragment that names no line still names the file`() {
        val target = linkTargetOf("report.md#section")
        assertEquals("report.md", (target as LinkTarget.File).ref.path)
        assertNull(target.ref.lines)
    }

    @Test
    fun `a reversed or zero range is no range`() {
        assertNull((linkTargetOf("a.md#L30-L24") as LinkTarget.File).ref.lines)
        assertNull((linkTargetOf("a.md#L0") as LinkTarget.File).ref.lines)
        assertNull((linkTargetOf("a.md#L") as LinkTarget.File).ref.lines)
    }

    @Test
    fun `a query is not part of the name either`() {
        val target = linkTargetOf("report.md?plain=1")
        assertEquals("report.md", (target as LinkTarget.File).ref.path)
    }

    @Test
    fun `percent escapes are decoded, and a literal percent survives`() {
        assertEquals("out/My Report.md", (linkTargetOf("out/My%20Report.md") as LinkTarget.File).ref.path)
        // UTF-8 escapes, the way a Chinese folder name arrives encoded.
        assertEquals(
            "/mnt/c/Users/Sync/\u90d1\u5dde\u5927\u5b66/\u8bc4\u5ba1.md",
            (linkTargetOf("/mnt/c/Users/Sync/%E9%83%91%E5%B7%9E%E5%A4%A7%E5%AD%A6/%E8%AF%84%E5%AE%A1.md") as LinkTarget.File).ref.path,
        )
        // A `%` that is not an escape is a character in a file name.
        assertEquals("100%.md", (linkTargetOf("100%.md") as LinkTarget.File).ref.path)
        assertEquals("100%2.md", (linkTargetOf("100%2.md") as LinkTarget.File).ref.path)
    }

    @Test
    fun `http and https belong to the browser`() {
        assertEquals(LinkTarget.External("https://example.com/a/b?c=1"), linkTargetOf("https://example.com/a/b?c=1"))
        assertEquals(LinkTarget.External("http://example.com"), linkTargetOf("http://example.com"))
    }

    @Test
    fun `a scheme this client does not speak opens nothing`() {
        assertEquals(LinkTarget.Inert, linkTargetOf("mailto:someone@example.com"))
        assertEquals(LinkTarget.Inert, linkTargetOf("dsh-resource://file/session/x/y.md"))
        // `notes.md:24` is spelled like a scheme, and the web client's own rule
        // reads it the same way rather than as a path with a colon in it.
        assertEquals(LinkTarget.Inert, linkTargetOf("notes.md:24"))
    }

    @Test
    fun `a windows path is a path, not a scheme`() {
        val target = linkTargetOf("C:\\work\\out\\report.md")
        assertEquals("C:\\work\\out\\report.md", (target as LinkTarget.File).ref.path)
        assertEquals("\\\\server\\share\\report.md", (linkTargetOf("\\\\server\\share\\report.md") as LinkTarget.File).ref.path)
    }

    @Test
    fun `a bare fragment and an empty destination open nothing`() {
        assertEquals(LinkTarget.Inert, linkTargetOf("#L24"))
        assertEquals(LinkTarget.Inert, linkTargetOf(""))
        assertEquals(LinkTarget.Inert, linkTargetOf("<>"))
    }

    // ── resolving against a base ──────────────────────────────────────────────

    @Test
    fun `a base picks the separator its own spelling uses`() {
        assertEquals("C:\\work\\out\\a.md", resolveWorkspacePath("C:\\work", "out/a.md"))
        assertEquals("C:\\work\\out\\a.md", resolveWorkspacePath("C:\\work", "out\\a.md"))
        assertEquals("/home/x/out/a.md", resolveWorkspacePath("/home/x", "out/a.md"))
    }

    @Test
    fun `a trailing separator on the base is not doubled`() {
        assertEquals("/home/x/out/a.md", resolveWorkspacePath("/home/x/", "out/a.md"))
        // A destination that begins with a separator is absolute, not relative
        // with a stray slash: joining it to the base would invent a path.
        assertEquals("/out/a.md", resolveWorkspacePath("/home/x", "/out/a.md"))
    }

    @Test
    fun `no base leaves the relative path to the host`() {
        // Inventing a root would send the read somewhere the reply never meant.
        assertEquals("out/a.md", resolveWorkspacePath(null, "out/a.md"))
        assertEquals("out/a.md", resolveWorkspacePath("", "out/a.md"))
        assertEquals("out/a.md", resolveWorkspacePath("   ", "out/a.md"))
    }

    @Test
    fun `a folder reference keeps its trailing separator`() {
        // `[figures](<out/figures/>)` is how a turn points at where the figures
        // went, and the trailing separator is what says it is a folder.
        val target = linkTargetOf("out/figures/")
        assertEquals("out/figures/", (target as LinkTarget.File).ref.path)
        assertTrue(directoryOf("/home/x/out/figures/") == "/home/x/out/figures/")
    }

    @Test
    fun `directory of a separator-free name is empty`() {
        assertEquals("", directoryOf("report.md"))
        assertEquals("/a/b/", directoryOf("/a/b/report.md"))
        assertEquals("C:\\work\\", directoryOf("C:\\work\\report.md"))
    }

    @Test
    fun `absolute detection covers both spellings and rejects a drive-relative name`() {
        assertTrue(isAbsoluteWorkspacePath("/a/b"))
        assertTrue(isAbsoluteWorkspacePath("C:\\a"))
        assertTrue(isAbsoluteWorkspacePath("C:/a"))
        assertTrue(isAbsoluteWorkspacePath("\\\\server\\share"))
        assertFalse(isAbsoluteWorkspacePath("c:notes.md"))
        assertFalse(isAbsoluteWorkspacePath("out/a.md"))
        assertFalse(isAbsoluteWorkspacePath(""))
    }
}
