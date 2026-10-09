package dev.hardline.net

import dev.hardline.core.Keys
import dev.hardline.core.Settings
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.InputStream
import java.net.URI
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/** Uploads finished files to the FTP or FTPS server from settings. */
object FtpUploader {
    private fun connect(settings: Settings): Pair<FTPClient, String> {
        val uri = URI(settings[Keys.ftpUrl].trim())
        val secure = uri.scheme.equals("ftps", true)
        val client = if (secure) FTPSClient() else FTPClient()
        client.connectTimeout = 10_000
        client.connect(uri.host, if (uri.port > 0) uri.port else 21)
        client.soTimeout = 30_000
        val user = settings[Keys.ftpUser].ifEmpty { "anonymous" }
        check(client.login(user, settings[Keys.ftpPassword])) { "Login refused: ${client.replyString.trim()}" }
        if (client is FTPSClient) { client.execPBSZ(0); client.execPROT("P") }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
        return client to (uri.path ?: "")
    }

    /** Checks the connection; returns null when it works, otherwise the reason. */
    fun verify(settings: Settings): String? = runCatching {
        val (client, path) = connect(settings)
        try {
            if (path.length > 1) check(client.changeWorkingDirectory(path)) { "Folder $path not found" }
        } finally {
            runCatching { client.logout() }
            client.disconnect()
        }
    }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

    fun upload(settings: Settings, name: String, data: InputStream): String? = runCatching {
        val (client, path) = connect(settings)
        try {
            if (path.length > 1) {
                if (!client.changeWorkingDirectory(path)) { client.makeDirectory(path); client.changeWorkingDirectory(path) }
            }
            check(client.storeFile(name, data)) { "Upload refused: ${client.replyString.trim()}" }
        } finally {
            runCatching { client.logout() }
            client.disconnect()
        }
    }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
}

/** Sends notification e-mails through the SMTP server from settings. */
object Mailer {
    private fun session(settings: Settings): Session {
        val security = settings[Keys.smtpSecurity]
        val props = Properties().apply {
            put("mail.smtp.host", settings[Keys.smtpServer].trim())
            put("mail.smtp.port", settings[Keys.smtpPort].toString())
            put("mail.smtp.connectiontimeout", "10000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.auth", settings[Keys.smtpUser].isNotEmpty().toString())
            if (security == 1) put("mail.smtp.starttls.enable", "true")
            if (security == 2) put("mail.smtp.ssl.enable", "true")
        }
        val user = settings[Keys.smtpUser]
        val password = settings[Keys.smtpPassword]
        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(user, password)
        })
    }

    /** Sends a message with an optional JPEG attachment; returns null on success, otherwise the reason. */
    fun send(settings: Settings, subject: String, text: String, jpeg: ByteArray? = null, attachmentName: String = "snapshot.jpg"): String? = runCatching {
        val to = settings[Keys.mailTo].trim()
        check(to.isNotEmpty() && settings[Keys.smtpServer].isNotBlank()) { "Mail settings are incomplete" }
        val from = settings[Keys.smtpUser].takeIf { '@' in it } ?: to
        val message = MimeMessage(session(settings)).apply {
            setFrom(InternetAddress(from))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            val body = MimeMultipart()
            body.addBodyPart(MimeBodyPart().apply { setText(text, "UTF-8") })
            if (jpeg != null) body.addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(jpeg, "image/jpeg"))
                fileName = attachmentName
            })
            setContent(body)
        }
        Transport.send(message)
    }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
}
