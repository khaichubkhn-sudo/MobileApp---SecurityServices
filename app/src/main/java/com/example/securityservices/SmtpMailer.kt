package com.example.securityservices

import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tiny, dependency-free SMTP client used to email the photo and the voice recordings captured by the
 * Volume Down action. Large attachments are streamed rather than buffered, so a 16 MB recording part
 * can be sent without running out of memory.
 *
 * The app has no backend, so the email is sent straight from the phone through the user's own
 * email account (SMTP). Both common secure setups are supported:
 *  - implicit SSL/TLS (typically port 465), and
 *  - STARTTLS upgrade (typically port 587).
 * Authentication uses the AUTH LOGIN mechanism with the account address and an app password.
 *
 * Only the Java/Kotlin standard library and the Android framework are used, matching the rest of
 * the app (no third-party libraries).
 *
 * @author Chu Quang Khai (Khai Chu)
 */
object SmtpMailer {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 25_000
    private const val CRLF = "\r\n"

    /** Everything needed to authenticate and deliver one message. */
    data class Config(
        val host: String,
        val port: Int,
        val startTls: Boolean,
        val username: String,
        val password: String,
        val from: String,
        /** Semicolon-separated recipient addresses (up to 10); each gets its own RCPT TO. */
        val recipient: String,
        val subject: String,
        val body: String
    ) {
        /**
         * Individual recipient addresses parsed from [recipient]: split on ';',
         * trimmed, empties dropped, de-duplicated (case-insensitive), first
         * [Prefs.MAX_EMAIL_RECIPIENTS] kept. Accepts commas as well so a pasted
         * comma-separated list still works.
         */
        fun recipients(): List<String> {
            val seen = LinkedHashSet<String>()
            val out = ArrayList<String>()
            for (raw in recipient.split(';', ',')) {
                val addr = raw.trim()
                if (addr.isEmpty()) continue
                val key = addr.lowercase()
                if (!seen.add(key)) continue
                out.add(addr)
                if (out.size >= Prefs.MAX_EMAIL_RECIPIENTS) break
            }
            return out
        }
    }

    /**
     * Sends [attachment] as an email attachment (its file name and MIME type are given by the caller).
     * Blocking: call it from a background thread.
     * Throws [IOException] (or a socket/SSL exception) with a readable message when the server
     * refuses any step, so the caller can surface it in Troubleshooting.
     */
    fun send(
        config: Config,
        attachment: File,
        attachmentName: String = "photo.jpg",
        attachmentMime: String = "image/jpeg"
    ) {
        var socket: Socket = openSocket(config)
        try {
            var reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            var writer = OutputStreamWriter(socket.getOutputStream(), Charsets.US_ASCII)

            expect(reader, 220, "server greeting")

            var code = command(reader, writer, "EHLO ${clientName()}")
            if (code != 250) throw IOException("EHLO was refused ($code)")

            if (config.startTls) {
                code = command(reader, writer, "STARTTLS")
                if (code != 220) throw IOException("STARTTLS was refused ($code)")
                val tls = upgradeToTls(socket, config)
                socket = tls
                reader = BufferedReader(InputStreamReader(tls.getInputStream(), Charsets.US_ASCII))
                writer = OutputStreamWriter(tls.getOutputStream(), Charsets.US_ASCII)
                code = command(reader, writer, "EHLO ${clientName()}")
                if (code != 250) throw IOException("EHLO after STARTTLS was refused ($code)")
            }

            code = command(reader, writer, "AUTH LOGIN")
            if (code != 334) throw IOException("AUTH LOGIN was refused ($code)")
            code = command(reader, writer, base64(config.username))
            if (code != 334) throw IOException("SMTP user name was refused ($code)")
            code = command(reader, writer, base64(config.password))
            if (code != 235) throw IOException("SMTP login was refused ($code) - check the address and app password")

            code = command(reader, writer, "MAIL FROM:<${config.from}>")
            if (code != 250) throw IOException("MAIL FROM was refused ($code)")

            val recipients = config.recipients()
            if (recipients.isEmpty()) throw IOException("No recipient address was set")
            for (addr in recipients) {
                code = command(reader, writer, "RCPT TO:<$addr>")
                if (code != 250 && code != 251) throw IOException("RCPT TO <$addr> was refused ($code)")
            }

            code = command(reader, writer, "DATA")
            if (code != 354) throw IOException("DATA was refused ($code)")

            writeMessage(writer, config, attachment, attachmentName, attachmentMime)
            writer.write("$CRLF.$CRLF")
            writer.flush()

            expect(reader, 250, "message delivery")
            command(reader, writer, "QUIT")
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------ socket helpers

    private fun openSocket(config: Config): Socket {
        return if (config.startTls) {
            // Plain socket first; STARTTLS upgrades it after the EHLO exchange.
            val plain = Socket()
            plain.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
            plain.soTimeout = READ_TIMEOUT_MS
            plain
        } else {
            // Implicit TLS: the socket is encrypted from the first byte.
            val ssl = SSLSocketFactory.getDefault().createSocket() as SSLSocket
            ssl.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
            ssl.soTimeout = READ_TIMEOUT_MS
            ssl.startHandshake()
            ssl
        }
    }

    private fun upgradeToTls(plain: Socket, config: Config): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(plain, config.host, config.port, true) as SSLSocket
        ssl.soTimeout = READ_TIMEOUT_MS
        ssl.startHandshake()
        return ssl
    }

    // ------------------------------------------------------------------ protocol helpers

    /** Sends one command line and returns the numeric SMTP reply code. */
    private fun command(reader: BufferedReader, writer: OutputStreamWriter, line: String): Int {
        writer.write(line + CRLF)
        writer.flush()
        return readReply(reader)
    }

    private fun expect(reader: BufferedReader, expected: Int, what: String) {
        val code = readReply(reader)
        if (code != expected) throw IOException("$what failed ($code)")
    }

    /** Reads a (possibly multi-line) SMTP reply and returns its final numeric code. */
    private fun readReply(reader: BufferedReader): Int {
        while (true) {
            val line = reader.readLine() ?: throw IOException("The mail server closed the connection")
            if (line.length < 3) throw IOException("Unexpected mail server reply: $line")
            val code = line.substring(0, 3).toIntOrNull()
                ?: throw IOException("Unexpected mail server reply: $line")
            // A line whose 4th character is '-' means the reply continues on the next line.
            if (line.length > 3 && line[3] == '-') continue
            return code
        }
    }

    private fun base64(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** A non-empty EHLO name; some servers reject a bare IP literal. */
    private fun clientName(): String {
        val name = try {
            InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            null
        }
        return if (name.isNullOrBlank()) "localhost" else name
    }

    // ------------------------------------------------------------------ message building

    /**
     * Writes one multipart message to [writer]: the small header/text part first, then the attachment
     * streamed from disk as base64 in chunks. A large attachment is therefore never held in memory as
     * one big string, so a 16 MB part can be emailed without running out of memory.
     */
    private fun writeMessage(
        writer: OutputStreamWriter,
        config: Config,
        attachment: File,
        attachmentName: String,
        attachmentMime: String
    ) {
        val boundary = "SecurityServices_" + System.currentTimeMillis().toString(16)
        val body = dotStuff(config.body.replace("\r\n", "\n").replace("\n", CRLF))
        val sb = StringBuilder()
        sb.append("From: ").append(config.from).append(CRLF)
        sb.append("To: ").append(config.recipients().joinToString(", ")).append(CRLF)
        sb.append("Subject: ").append(config.subject).append(CRLF)
        sb.append("MIME-Version: 1.0").append(CRLF)
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(boundary).append("\"").append(CRLF)
        sb.append(CRLF)
        // Plain-text part.
        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Type: text/plain; charset=UTF-8").append(CRLF)
        sb.append("Content-Transfer-Encoding: 7bit").append(CRLF)
        sb.append(CRLF)
        sb.append(body).append(CRLF)
        sb.append(CRLF)
        // Attachment part headers; the base64 body is streamed right after them.
        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Type: ").append(attachmentMime).append("; name=\"").append(attachmentName).append("\"").append(CRLF)
        sb.append("Content-Transfer-Encoding: base64").append(CRLF)
        sb.append("Content-Disposition: attachment; filename=\"").append(attachmentName).append("\"").append(CRLF)
        sb.append(CRLF)
        writer.write(sb.toString())
        writeAttachmentBase64(writer, attachment)
        writer.write("--")
        writer.write(boundary)
        writer.write("--")
    }

    /**
     * Streams the attachment as MIME base64, wrapped at 76 characters per line. Each chunk is a
     * multiple of 3 bytes, so the base64 stays aligned across chunk boundaries, and only one small
     * chunk is held in memory at a time. Base64 lines never start with '.', so no dot-stuffing is needed.
     */
    private fun writeAttachmentBase64(writer: OutputStreamWriter, attachment: File) {
        FileInputStream(attachment).use { input ->
            val chunk = ByteArray(3 * 76 * 64)
            while (true) {
                val read = readFully(input, chunk)
                if (read <= 0) break
                val encoded = Base64.encodeToString(chunk, 0, read, Base64.NO_WRAP)
                var i = 0
                while (i < encoded.length) {
                    val end = minOf(i + 76, encoded.length)
                    writer.write(encoded, i, end - i)
                    writer.write(CRLF)
                    i = end
                }
            }
        }
    }

    /** Fills [buffer] from [input] (a stream may return fewer bytes than asked); returns bytes read. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Escapes any line that starts with '.' (SMTP dot-stuffing) so DATA cannot be ended early. */
    private fun dotStuff(text: String): String =
        text.split(CRLF).joinToString(CRLF) { if (it.startsWith(".")) ".$it" else it }
}
