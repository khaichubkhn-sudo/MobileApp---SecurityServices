package com.example.securityservices

import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Tiny, dependency-free SMTP client used to email the photo captured by the Volume Down action.
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
        val recipient: String,
        val subject: String,
        val body: String
    )

    /**
     * Sends [attachment] as a JPEG email attachment. Blocking: call it from a background thread.
     * Throws [IOException] (or a socket/SSL exception) with a readable message when the server
     * refuses any step, so the caller can surface it in Troubleshooting.
     */
    fun send(config: Config, attachment: File) {
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

            code = command(reader, writer, "RCPT TO:<${config.recipient}>")
            if (code != 250 && code != 251) throw IOException("RCPT TO was refused ($code)")

            code = command(reader, writer, "DATA")
            if (code != 354) throw IOException("DATA was refused ($code)")

            writer.write(buildMessage(config, attachment))
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
        val ssl = SSLSocketFactory.getDefault()
            .createSocket(plain, config.host, config.port, true) as SSLSocket
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

    private fun buildMessage(config: Config, attachment: File): String {
        val boundary = "SecurityServices_" + System.currentTimeMillis().toString(16)
        val body = config.body.replace("\r\n", "\n").replace("\n", CRLF)
        val sb = StringBuilder()
        sb.append("From: ").append(config.from).append(CRLF)
        sb.append("To: ").append(config.recipient).append(CRLF)
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
        // JPEG attachment part.
        sb.append("--").append(boundary).append(CRLF)
        sb.append("Content-Type: image/jpeg; name=\"photo.jpg\"").append(CRLF)
        sb.append("Content-Transfer-Encoding: base64").append(CRLF)
        sb.append("Content-Disposition: attachment; filename=\"photo.jpg\"").append(CRLF)
        sb.append(CRLF)
        sb.append(wrappedBase64(attachment.readBytes())).append(CRLF)
        sb.append("--").append(boundary).append("--")
        return dotStuff(sb.toString())
    }

    /** Base64-encodes [data] and wraps it at 76 characters, as required by MIME. */
    private fun wrappedBase64(data: ByteArray): String {
        val raw = Base64.encodeToString(data, Base64.NO_WRAP)
        val sb = StringBuilder(raw.length + raw.length / 76 * 2)
        var i = 0
        while (i < raw.length) {
            val end = minOf(i + 76, raw.length)
            sb.append(raw, i, end)
            if (end < raw.length) sb.append(CRLF)
            i = end
        }
        return sb.toString()
    }

    /** Escapes any line that starts with '.' (SMTP dot-stuffing) so DATA cannot be ended early. */
    private fun dotStuff(text: String): String =
        text.split(CRLF).joinToString(CRLF) { if (it.startsWith(".")) ".$it" else it }
}
