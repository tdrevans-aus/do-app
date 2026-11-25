package com.evansappstudio.doapp.backup

/**
 * Small JSON codec for the backup file. Unit tests run on the JVM, where the
 * Android org.json stub is not a real parser, so the backup file does not use it.
 */
internal sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Long) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

internal fun JsonValue.obj(): JsonValue.Obj = this as? JsonValue.Obj ?: error("expected object")

internal fun JsonValue.field(name: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(name)

internal class JsonParseException(message: String) : IllegalArgumentException(message)

internal fun parseJson(text: String): JsonValue = JsonParser(text).parse()

internal fun encodeJson(value: JsonValue): String = buildString { writeJson(value, 0) }

private fun StringBuilder.writeJson(value: JsonValue, indent: Int) {
    when (value) {
        is JsonValue.Null -> append("null")
        is JsonValue.Bool -> append(if (value.value) "true" else "false")
        is JsonValue.Num -> append(value.value.toString())
        is JsonValue.Str -> append(quote(value.value))
        is JsonValue.Arr -> {
            append('[')
            if (value.items.isEmpty()) {
                append(']')
                return
            }
            value.items.forEachIndexed { index, item ->
                append('\n')
                append("  ".repeat(indent + 1))
                writeJson(item, indent + 1)
                if (index != value.items.lastIndex) append(',')
            }
            append('\n')
            append("  ".repeat(indent))
            append(']')
        }
        is JsonValue.Obj -> {
            append('{')
            if (value.fields.isEmpty()) {
                append('}')
                return
            }
            val entries = value.fields.entries.toList()
            entries.forEachIndexed { index, (key, item) ->
                append('\n')
                append("  ".repeat(indent + 1))
                append(quote(key))
                append(": ")
                writeJson(item, indent + 1)
                if (index != entries.lastIndex) append(',')
            }
            append('\n')
            append("  ".repeat(indent))
            append('}')
        }
    }
}

private fun quote(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 0x20) {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue {
        val value = readValue()
        skip()
        if (index != text.length) throw JsonParseException("trailing data")
        return value
    }

    private fun readValue(): JsonValue {
        skip()
        if (index >= text.length) throw JsonParseException("truncated")
        return when (val ch = text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> JsonValue.Str(readString())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            '-', in '0'..'9' -> readNumber()
            else -> throw JsonParseException("unexpected '$ch'")
        }
    }

    private fun readObject(): JsonValue.Obj {
        expect('{')
        val fields = linkedMapOf<String, JsonValue>()
        skip()
        if (peek('}')) {
            index++
            return JsonValue.Obj(fields)
        }
        while (index < text.length) {
            skip()
            if (index >= text.length || text[index] != '"') throw JsonParseException("expected key")
            val key = readString()
            skip()
            expect(':')
            fields[key] = readValue()
            skip()
            when {
                peek(',') -> index++
                peek('}') -> {
                    index++
                    return JsonValue.Obj(fields)
                }
                else -> throw JsonParseException("expected comma or end of object")
            }
        }
        throw JsonParseException("truncated object")
    }

    private fun readArray(): JsonValue.Arr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skip()
        if (peek(']')) {
            index++
            return JsonValue.Arr(items)
        }
        while (index < text.length) {
            items += readValue()
            skip()
            when {
                peek(',') -> index++
                peek(']') -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> throw JsonParseException("expected comma or end of array")
            }
        }
        throw JsonParseException("truncated array")
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            val ch = text[index++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) throw JsonParseException("truncated escape")
                    when (val esc = text[index++]) {
                        '"', '\\', '/' -> out.append(esc)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) throw JsonParseException("truncated unicode")
                            val hex = text.substring(index, index + 4)
                            index += 4
                            val code = hex.toIntOrNull(16) ?: throw JsonParseException("bad unicode")
                            out.append(code.toChar())
                        }
                        else -> throw JsonParseException("bad escape")
                    }
                }
                else -> {
                    if (ch.code < 0x20) throw JsonParseException("raw control")
                    out.append(ch)
                }
            }
        }
        throw JsonParseException("truncated string")
    }

    private fun readNumber(): JsonValue.Num {
        val start = index
        if (peek('-')) index++
        if (index >= text.length || text[index] !in '0'..'9') throw JsonParseException("bad number")
        if (text[index] == '0') {
            index++
        } else {
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] in setOf('.', 'e', 'E')) {
            throw JsonParseException("non-integer")
        }
        val raw = text.substring(start, index)
        val value = raw.toLongOrNull() ?: throw JsonParseException("bad number")
        return JsonValue.Num(value)
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, index)) throw JsonParseException("expected $word")
        index += word.length
        return value
    }

    private fun expect(ch: Char) {
        skip()
        if (index >= text.length || text[index] != ch) throw JsonParseException("expected '$ch'")
        index++
    }

    private fun peek(ch: Char): Boolean = index < text.length && text[index] == ch

    private fun skip() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
