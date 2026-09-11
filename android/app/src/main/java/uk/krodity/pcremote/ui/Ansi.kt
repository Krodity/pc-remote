package uk.krodity.pcremote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import uk.krodity.pcremote.ui.theme.P

/**
 * A small ANSI interpreter: enough terminal to read one honestly.
 *
 * This is not a VT220. It keeps a scrollback of lines and a cursor column, and
 * understands the handful of sequences an interactive shell actually leans on:
 * SGR colour, erase-line, erase-display, and horizontal cursor motion. That
 * covers a powerline prompt redrawing itself, `clear`, and progress bars that
 * rewrite with a carriage return.
 *
 * Everything it does not understand is swallowed rather than printed, which is
 * the important part -- a terminal that leaks raw escape bytes into the
 * viewport is unreadable, and this box's prompt emits a great many of them.
 */
class AnsiTerminal(private val maxLines: Int = 3000) {

    data class Cell(val ch: Char, val style: SpanStyle)

    private val lines = ArrayList<ArrayList<Cell>>().apply { add(ArrayList()) }
    private var col = 0
    private var style = SpanStyle(color = P.text)
    private var bold = false
    private var fg: Color? = null

    /** Bumped on every mutation so Compose can key a recomposition off it. */
    var revision = 0
        private set

    val lineCount get() = lines.size

    private val cur get() = lines[lines.size - 1]

    // ── writing ──────────────────────────────────────────────────────────────
    fun clear() {
        lines.clear()
        lines.add(ArrayList())
        col = 0
        revision++
    }

    fun feed(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\u001B' -> i = escape(text, i)
                c == '\n' -> { newLine(); i++ }
                c == '\r' -> { col = 0; i++ }
                c == '\b' -> { if (col > 0) col--; i++ }
                c == '\u0007' -> i++                       // bell
                c == '\t' -> { repeat(8 - (col % 8)) { put(' ') }; i++ }
                c.code < 32 -> i++                         // other control bytes
                else -> { put(c); i++ }
            }
        }
        revision++
    }

    private fun put(c: Char) {
        val line = cur
        while (line.size <= col) line.add(Cell(' ', style))
        line[col] = Cell(c, style)
        col++
    }

    private fun newLine() {
        lines.add(ArrayList())
        col = 0
        while (lines.size > maxLines) lines.removeAt(0)
    }

    // ── escape sequences ─────────────────────────────────────────────────────
    /** Returns the index just past the sequence starting at [start]. */
    private fun escape(t: String, start: Int): Int {
        var i = start + 1
        if (i >= t.length) return t.length
        return when (t[i]) {
            '[' -> csi(t, i + 1)
            // OSC: runs until BEL or ST (ESC \). Window titles live here.
            ']' -> {
                while (i < t.length && t[i] != '\u0007') {
                    if (t[i] == '\u001B' && i + 1 < t.length && t[i + 1] == '\\') return i + 2
                    i++
                }
                i + 1
            }
            // Two-byte sequences we simply skip.
            '(', ')', '#', '%' -> i + 2
            else -> i + 1
        }
    }

    private fun csi(t: String, start: Int): Int {
        var i = start
        val sb = StringBuilder()
        while (i < t.length && t[i].code in 0x20..0x3f) sb.append(t[i++])
        if (i >= t.length) return t.length
        val final = t[i]
        val params = sb.toString()
        val nums = params.trimStart('?', '>', '=')
            .split(';').map { it.toIntOrNull() ?: 0 }

        when (final) {
            'm' -> sgr(nums)
            'K' -> when (nums.firstOrNull() ?: 0) {          // erase in line
                0 -> while (cur.size > col) cur.removeAt(cur.size - 1)
                1 -> for (x in 0 until minOf(col, cur.size)) cur[x] = Cell(' ', style)
                2 -> cur.clear()
            }
            'J' -> if ((nums.firstOrNull() ?: 0) >= 2) clear()
            'C' -> col += maxOf(1, nums.firstOrNull() ?: 1)
            'D' -> col = maxOf(0, col - maxOf(1, nums.firstOrNull() ?: 1))
            'G' -> col = maxOf(0, (nums.firstOrNull() ?: 1) - 1)
            'H', 'f' -> if (params.isEmpty() || nums.all { it <= 1 }) col = 0
        }
        return i + 1
    }

    private fun sgr(nums: List<Int>) {
        var i = 0
        if (nums.isEmpty()) return reset()
        while (i < nums.size) {
            when (val n = nums[i]) {
                0 -> reset()
                1 -> { bold = true; restyle() }
                22 -> { bold = false; restyle() }
                in 30..37 -> { fg = basic(n - 30); restyle() }
                in 90..97 -> { fg = bright(n - 90); restyle() }
                39 -> { fg = null; restyle() }
                38 -> {
                    // 38;5;n (256-colour) and 38;2;r;g;b (truecolour)
                    when (nums.getOrNull(i + 1)) {
                        5 -> { fg = xterm256(nums.getOrNull(i + 2) ?: 7); i += 2 }
                        2 -> {
                            fg = Color(
                                (nums.getOrNull(i + 2) ?: 0),
                                (nums.getOrNull(i + 3) ?: 0),
                                (nums.getOrNull(i + 4) ?: 0),
                            )
                            i += 4
                        }
                        else -> {}
                    }
                    restyle()
                }
                // Background colours are dropped on purpose: the panel has one
                // ground colour, and honouring backgrounds would paint blocks
                // of terminal-theme colour over it.
                48 -> i += if (nums.getOrNull(i + 1) == 5) 2 else 4
            }
            i++
        }
    }

    private fun reset() {
        bold = false; fg = null; restyle()
    }

    private fun restyle() {
        style = SpanStyle(
            color = fg ?: P.text,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }

    // ── rendering ────────────────────────────────────────────────────────────
    /** Collapses the scrollback into one annotated string for a Text node. */
    fun render(): AnnotatedString = buildAnnotatedString {
        for ((idx, line) in lines.withIndex()) {
            if (idx > 0) append('\n')
            var i = 0
            while (i < line.size) {
                val s = line[i].style
                val sb = StringBuilder()
                while (i < line.size && line[i].style == s) sb.append(line[i++].ch)
                pushStyle(s)
                append(sb.toString())
                pop()
            }
        }
    }

    private companion object {
        fun basic(i: Int) = listOf(
            Color(0xFF2B3A4F), Color(0xFFF87171), Color(0xFF34D399), Color(0xFFFBBF24),
            Color(0xFF60A5FA), Color(0xFFA78BFA), Color(0xFF22D3EE), Color(0xFFC8DDEF),
        )[i]

        fun bright(i: Int) = listOf(
            Color(0xFF5C7FA3), Color(0xFFFCA5A5), Color(0xFF6EE7B7), Color(0xFFFDE68A),
            Color(0xFF93C5FD), Color(0xFFC4B5FD), Color(0xFF67E8F9), Color(0xFFFFFFFF),
        )[i]

        /** Standard xterm 256-colour cube + greyscale ramp. */
        fun xterm256(n: Int): Color = when {
            n < 8 -> basic(n)
            n < 16 -> bright(n - 8)
            n < 232 -> {
                val v = n - 16
                val steps = intArrayOf(0, 95, 135, 175, 215, 255)
                Color(steps[v / 36], steps[(v % 36) / 6], steps[v % 6])
            }
            else -> {
                val g = 8 + (n - 232) * 10
                Color(g, g, g)
            }
        }
    }
}
