package dev.hardline.net

import android.content.Context
import dev.hardline.core.Keys
import dev.hardline.core.Settings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** TLS for the built-in servers: loads the configured PKCS#12 identity or creates a self-signed one. */
object Tls {
    fun certDir(context: Context) = File(context.filesDir, "certs").apply { mkdirs() }

    /** Creates a listening socket, encrypted when HTTPS is enabled in settings. */
    fun serverSocket(context: Context, settings: Settings, port: Int): ServerSocket {
        if (!settings[Keys.https]) return ServerSocket(port, 20)
        val file = File(certDir(context), settings[Keys.certificateFile].ifEmpty { generate(context, settings) })
        val password = settings[Keys.certificatePassword].toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return (ctx.serverSocketFactory.createServerSocket(port, 20) as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        }
    }

    /** Generates a self-signed RSA certificate, stores it as PKCS#12 and selects it. Returns the file name. */
    fun generate(context: Context, settings: Settings): String {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        fun time(ms: Long) = der(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)).toByteArray())
        val algorithm = der(0x30, der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)) + der(0x05, ByteArray(0)))
        val name = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)) + der(0x0C, "HardLine".toByteArray()))))
        val tbs = der(0x30,
            der(0xA0, der(0x02, byteArrayOf(2))) +
                der(0x02, BigInteger(63, SecureRandom()).toByteArray()) + algorithm + name +
                der(0x30, time(now - 86_400_000L) + time(now + 3650L * 86_400_000L)) + name + pair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs) }.sign()
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der(0x30, tbs + algorithm + der(0x03, byteArrayOf(0) + signature))))
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val password = (1..14).map { alphabet[SecureRandom().nextInt(alphabet.length)] }.joinToString("")
        val fileName = "self-signed-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".p12"
        KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("server", pair.private, password.toCharArray(), arrayOf(cert))
            File(certDir(context), fileName).outputStream().use { store(it, password.toCharArray()) }
        }
        settings[Keys.certificateFile] = fileName
        settings[Keys.certificatePassword] = password
        return fileName
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 6)
        out.write(tag)
        when {
            content.size < 0x80 -> out.write(content.size)
            content.size < 0x100 -> { out.write(0x81); out.write(content.size) }
            else -> { out.write(0x82); out.write(content.size shr 8); out.write(content.size) }
        }
        out.write(content)
        return out.toByteArray()
    }
}
