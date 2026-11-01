package com.clearcap.util

/**
 * Tiny JSON reader/writer in pure Kotlin so import/export logic can be unit-tested on the JVM
 * (android.jar's org.json is only a stub in local unit tests). Numbers parse to Long when integral, else Double.
 */
object MiniJson {
    class JsonException(message: String) : Exception(message)

    // ---- writing ----

    fun write(value: Any?): String = StringBuilder().also { append(it, value, 0, false) }.toString()

    fun writePretty(value: Any?): String = StringBuilder().also { append(it, value, 0, true) }.toString()

    private fun append(sb: StringBuilder, v: Any?, depth: Int, pretty: Boolean) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> sb.append(if (v.isFinite()) v.toString() else "null")
            is Double -> sb.append(if (v.isFinite()) v.toString() else "null")
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    quote(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    append(sb, x, depth + 1, pretty)
                }
                if (pretty) { sb.append('\n'); indent(sb, depth) }
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (x in items) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    append(sb, x, depth + 1, pretty)
                }
                if (pretty) { sb.append('\n'); indent(sb, depth) }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun indent(sb: StringBuilder, d: Int) { repeat(d) { sb.append("  ") } }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ---- reading ----

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw JsonException("Unexpected trailing data at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?> ?: throw JsonException("Not a JSON object")

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun value(): Any? {
            if (atEnd()) throw JsonException("Unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Bad literal at $pos")
            pos += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            skipWs()
            if (s.getOrNull(pos) == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (s.getOrNull(pos) != '"') throw JsonException("Expected key at $pos")
                val k = str()
                skipWs()
                if (s.getOrNull(pos) != ':') throw JsonException("Expected ':' at $pos")
                pos++
                skipWs()
                m[k] = value()
                skipWs()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw JsonException("Expected ',' or '}' at $pos")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            skipWs()
            if (s.getOrNull(pos) == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                when (s.getOrNull(pos)) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> throw JsonException("Expected ',' or ']' at $pos")
                }
            }
        }

        fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: throw JsonException("Bad unicode escape"))
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any {
            val st = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(st, pos)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw JsonException("Bad number '$t'")
        }
    }
}
