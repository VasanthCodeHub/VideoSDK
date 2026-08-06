package dev.meshcall.sdk.internal.webrtc

/**
 * Pure SDP text rewriting. Deliberately free of `org.webrtc` types so it is unit-testable
 * on the JVM.
 */
internal object SdpTransform {

    private const val CRLF = "\r\n"
    private const val LF = "\n"

    /**
     * Cap the video bandwidth of [sdp] at [kbps].
     *
     * Per RFC 4566 a `b=` line is only honoured **inside** a media section, and it must
     * come directly after that section's `c=` line. Appending `b=AS:` to the end of the
     * whole description — the obvious-looking approach — is silently ignored by every
     * stack. So this walks each `m=video` section, drops any pre-existing bandwidth line,
     * and re-inserts the cap in the one position that actually works.
     *
     * Audio sections are left alone: capping them degrades intelligibility long before
     * video bitrate becomes the problem. Returns [sdp] unchanged when [kbps] <= 0.
     */
    fun applyVideoBitrateCap(sdp: String, kbps: Int): String {
        if (kbps <= 0 || sdp.isEmpty()) return sdp

        val eol = if (sdp.contains(CRLF)) CRLF else LF
        val lines = sdp.split(CRLF, LF)
        val out = ArrayList<String>(lines.size + 4)

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!line.startsWith("m=video")) {
                out += line
                index++
                continue
            }
            // Take the whole media section: the m= line up to (not including) the next m=.
            val section = ArrayList<String>()
            section += line
            index++
            while (index < lines.size && !lines[index].startsWith("m=")) {
                section += lines[index]
                index++
            }
            out += capSection(section, kbps)
        }
        return out.joinToString(eol)
    }

    private fun capSection(section: List<String>, kbps: Int): List<String> {
        val kept = section.filterNotTo(ArrayList()) {
            it.startsWith("b=AS:") || it.startsWith("b=TIAS:")
        }
        // Directly after c=; if the section inherits a session-level c=, right after m=.
        val connectionLine = kept.indexOfFirst { it.startsWith("c=") }
        kept.add(if (connectionLine >= 0) connectionLine + 1 else 1, "b=AS:$kbps")
        return kept
    }
}
