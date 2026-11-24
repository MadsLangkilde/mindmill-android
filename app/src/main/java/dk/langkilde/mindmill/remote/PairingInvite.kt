package dk.langkilde.mindmill.remote

/** What the Mac's pairing QR code holds: `kb://pair?v=1&hosts=…&port=…&fp=…&code=…&name=…`. */
data class PairingInvite(
    val hosts: List<String>,
    val port: Int,
    val fingerprint: String,
    val code: String,
    val name: String,
    val version: Int = 1,
) {
    companion object {
        fun parse(raw: String): PairingInvite? {
            val text = raw.trim()
            val prefix = "kb://pair"
            if (!text.startsWith(prefix, ignoreCase = true)) return null
            val rest = text.substring(prefix.length).removePrefix("/")
            if (!rest.startsWith("?")) return null
            val query = rest.substring(1).substringBefore('#')
            val q = query.split('&').filter { it.isNotEmpty() }.associate {
                val k = percentDecode(it.substringBefore('='))
                val v = if ('=' in it) percentDecode(it.substringAfter('=')) else ""
                k to v
            }
            val hosts = q["hosts"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val port = q["port"]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val fp = normalizeFingerprint(q["fp"] ?: return null) ?: return null
            if (hosts.isEmpty()) return null
            return PairingInvite(
                hosts = hosts,
                port = port,
                fingerprint = fp,
                code = q["code"].orEmpty().filter { it.isDigit() },
                name = q["name"]?.takeIf { it.isNotBlank() } ?: "Mindmill",
                version = q["v"]?.toIntOrNull() ?: 1,
            )
        }

        fun normalizeFingerprint(raw: String): String? {
            val hex = raw.replace(":", "").replace(" ", "").lowercase()
            return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '%' && i + 2 < s.length) {
                    val b = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (b != null) {
                        out.write(b); i += 3; continue
                    }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }
}
