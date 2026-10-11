package app.scanforge.transfer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Parsed HTTP/1.1 request (strict subset: no chunked request bodies, bounded headers). */
class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String) = headers[name.lowercase()]
}

class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "application/json",
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Streams the body instead of [body] when set; [streamLength] must then be exact. */
    val stream: ((OutputStream) -> Unit)? = null,
    val streamLength: Long = 0,
)

/** [drainBytes]: request body bytes still unread that the server should discard before closing (lingering close). */
class HttpProtocolException(val status: Int, message: String, val drainBytes: Long = 0) : IOException(message)

/** Minimal, bounded HTTP/1.1 codec used by the transfer server. */
object MiniHttp {
    private const val MAX_LINE = 8 * 1024
    private const val MAX_HEADERS = 64

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else throw HttpProtocolException(400, "Unexpected end of stream")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > MAX_LINE) throw HttpProtocolException(431, "Line too long")
        }
        return out.toString(Charsets.ISO_8859_1)
    }

    /** Returns null on a clean end of stream between requests (keep-alive connection closed). */
    fun readRequest(input: BufferedInputStream, maxBody: Int): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpProtocolException(400, "Malformed request line")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw HttpProtocolException(400, "Unexpected end of headers")
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw HttpProtocolException(431, "Too many headers")
            val idx = line.indexOf(':')
            if (idx <= 0) throw HttpProtocolException(400, "Malformed header")
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw HttpProtocolException(501, "Transfer-Encoding not supported")
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length < 0) throw HttpProtocolException(400, "Invalid Content-Length")
        if (length > maxBody) throw HttpProtocolException(413, "Body too large", drainBytes = length)
        val body = ByteArray(length.toInt())
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) throw HttpProtocolException(400, "Truncated body")
            read += n
        }
        return HttpRequest(parts[0], parts[1], headers, body)
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 410 -> "Gone"; 413 -> "Payload Too Large"
        429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"; 501 -> "Not Implemented"; else -> "Error"
    }

    /**
     * Discards up to [max] bytes of an unread request body so the client can finish sending and read the error
     * response, instead of hitting a broken pipe / reset when the server closes early.
     */
    fun drain(input: InputStream, bytes: Long, max: Long = 4L shl 20) {
        var left = minOf(bytes, max)
        val buf = ByteArray(16 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    fun writeResponse(out: OutputStream, r: HttpResponse, keepAlive: Boolean) {
        val length = if (r.stream != null) r.streamLength else r.body.size.toLong()
        val head = buildString {
            append("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
            append("Content-Type: ${r.contentType}\r\n")
            append("Content-Length: $length\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
            r.extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (r.stream != null) r.stream.invoke(out) else out.write(r.body)
        out.flush()
    }
}
