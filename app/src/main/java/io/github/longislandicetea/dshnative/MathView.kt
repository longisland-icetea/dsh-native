package io.github.longislandicetea.dshnative

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Mathematics, rendered by KaTeX.
 *
 * KaTeX is the web client's renderer, and it is JavaScript -- so this is a
 * WebView, with KaTeX's own files bundled in the APK's assets. Nothing is fetched
 * at runtime: a formula renders on a train with no signal, which is the whole
 * reason the assets are shipped rather than linked.
 *
 * Only a block that actually contains mathematics comes here; everything else
 * stays with the Compose renderer, which keeps selectable text, the app's fonts
 * and its markdown subset. A formula is the one thing that renderer cannot draw,
 * so it pays for the WebView and nothing else does.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MathBlockView(
    lines: List<String>,
    kind: ProseKind,
    textColor: Int,
    modifier: Modifier = Modifier,
) {
    val html = remember(lines, kind, textColor) { mathHtml(lines, kind, textColor) }
    var measuredPx by remember(html) { mutableIntStateOf(0) }

    AndroidView(
        // Height is the document's own, once it has been asked: a fixed box would
        // clip a tall formula, and a scrollbar inside a chat transcript is worse
        // than the formula being a little tall.
        modifier = modifier
            .fillMaxWidth()
            .then(if (measuredPx > 0) Modifier.heightIn(min = (measuredPx / 3).dp) else Modifier),
        factory = { context ->
            WebView(context).apply {
                // Transparent and unscrollable: this is being used as a renderer,
                // not as a page.
                setBackgroundColor(0x00000000)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.defaultFontSize = 15
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.evaluateJavascript(
                            "(function(){return Math.ceil(document.body.scrollHeight);})()",
                        ) { value ->
                            value?.trim('"')?.toDoubleOrNull()?.let { cssPx ->
                                val px = (cssPx * view.resources.displayMetrics.density).toInt()
                                if (px > 0) measuredPx = px
                            }
                        }
                    }
                }
                tag = html
                load(html)
            }
        },
        update = { view ->
            // Only when the document itself changed: reloading on every
            // recomposition would restart the renderer while the reader scrolls.
            if (view.tag != html) {
                view.tag = html
                view.load(html)
            }
        },
    )
}

/** Load one generated document, with the assets as its base. */
private fun WebView.load(html: String) {
    loadDataWithBaseURL("file:///android_asset/katex/", html, "text/html", "utf-8", null)
}

/**
 * The document KaTeX renders: the block's text, escaped, with its maths left
 * alone for `auto-render` to find.
 *
 * Built here rather than inside the WebView so that what the renderer is handed
 * is a value a test can read -- the escaping and the line handling are the parts
 * that go wrong quietly.
 */
internal fun mathHtml(lines: List<String>, kind: ProseKind, textColor: Int): String {
    val prefix = when (kind) {
        ProseKind.Bullet -> "• "
        ProseKind.Ordered -> "· "
        ProseKind.Quote -> "❯ "
        else -> ""
    }
    val body = StringBuilder()
    lines.forEachIndexed { index, line ->
        val text = escapeHtml(line)
        val rendered = if (prefix.isNotEmpty() && index == 0) "$prefix$text" else text
        body.append("<p>").append(rendered).append("</p>")
    }
    val colour = String.format("#%06X", 0xFFFFFF and textColor)
    return """
        <!DOCTYPE html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="katex.min.css">
        <script src="katex.min.js"></script>
        <script src="auto-render.min.js"></script>
        <style>
          html, body { margin: 0; padding: 0; background: transparent; }
          body {
            color: $colour;
            font-family: -apple-system, Roboto, sans-serif;
            font-size: 15px;
            line-height: 1.45;
            word-wrap: break-word;
          }
          p { margin: 0 0 6px 0; }
          p:last-child { margin-bottom: 0; }
          .katex { font-size: 1.05em; }
          .katex-display { margin: 6px 0; overflow-x: auto; overflow-y: hidden; }
        </style>
        </head><body>
        $body
        <script>
          renderMathInElement(document.body, {
            delimiters: [
              { left: "$$", right: "$$", display: true },
              { left: "\\[", right: "\\]", display: true },
              { left: "\\(", right: "\\)", display: false },
              { left: "$", right: "$", display: false }
            ],
            throwOnError: false
          });
        </script>
        </body></html>
    """.trimIndent()
}

/** Minimal HTML escaping: the text is the model's, and it is going into a document. */
internal fun escapeHtml(text: String): String = buildString(text.length) {
    text.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(char)
        }
    }
}
