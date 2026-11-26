package com.filespro.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

class SafeItem(val id: String, val name: String, val size: Long)

/**
 * Safe Folder. The password and the 6-digit emergency code are stored only as salted PBKDF2 hashes.
 * Files are encrypted with AES-256-GCM using a key kept in the Android Keystore.
 */
object Safe {
    private const val ALIAS = "filespro_safe_key"
    private const val CHUNK = 1 shl 20

    private fun prefs(c: Context) = c.getSharedPreferences("safe", Context.MODE_PRIVATE)
    private fun dir(c: Context) = File(c.filesDir, "safe").also { it.mkdirs() }
    private fun metaFile(c: Context) = File(c.filesDir, "safe_index.properties")
    private fun meta(c: Context) = Properties().also { p -> metaFile(c).takeIf { it.exists() }?.inputStream()?.use { p.load(it) } }
    private fun saveMeta(c: Context, p: Properties) { metaFile(c).outputStream().use { p.store(it, null) } }

    fun isSet(c: Context) = prefs(c).contains("hash")
    fun hasEmergency(c: Context) = prefs(c).contains("ehash")

    private fun hash(secret: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(secret.toCharArray(), salt, 100_000, 256)).encoded

    // prefix "" = password, prefix "e" = emergency code
    private fun store(c: Context, prefix: String, secret: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(c).edit()
            .putString(prefix + "salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(prefix + "hash", Base64.encodeToString(hash(secret, salt), Base64.NO_WRAP))
            .putInt(prefix + "fails", 0).putLong(prefix + "until", 0L).apply()
    }

    private fun verify(c: Context, prefix: String, secret: String): Boolean {
        val p = prefs(c)
        val salt = Base64.decode(p.getString(prefix + "salt", "") ?: "", Base64.NO_WRAP)
        val want = Base64.decode(p.getString(prefix + "hash", "") ?: "", Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(hash(secret, salt), want)
        if (ok) {
            p.edit().putInt(prefix + "fails", 0).putLong(prefix + "until", 0L).apply()
        } else {
            val f = p.getInt(prefix + "fails", 0) + 1
            val e = p.edit().putInt(prefix + "fails", f)
            if (f % 5 == 0) e.putLong(prefix + "until", System.currentTimeMillis() + 30_000L * (f / 5))
            e.apply()
        }
        return ok
    }

    private fun left(c: Context, prefix: String): Long = maxOf(0L, prefs(c).getLong(prefix + "until", 0L) - System.currentTimeMillis())

    fun setPassword(c: Context, pw: String) = store(c, "", pw)
    fun setEmergency(c: Context, code: String) = store(c, "e", code)
    fun check(c: Context, pw: String) = verify(c, "", pw)
    fun checkEmergency(c: Context, code: String) = verify(c, "e", code)
    fun lockLeft(c: Context) = left(c, "")
    fun emergencyLockLeft(c: Context) = left(c, "e")

    fun change(c: Context, old: String, new: String): Boolean {
        if (!check(c, old)) return false
        setPassword(c, new)
        return true
    }

    /** Erase everything: all safe files, the password, the emergency code and the encryption key. */
    fun reset(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
        metaFile(c).delete()
        prefs(c).edit().clear().apply()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (e: Exception) { }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return g.generateKey()
    }

    private fun fill(i: InputStream, b: ByteArray): Int {
        var t = 0
        while (t < b.size) { val n = i.read(b, t, b.size - t); if (n < 0) break; t += n }
        return t
    }

    /** Encrypts [src] into the safe and deletes the original. */
    fun encrypt(c: Context, src: File) {
        val id = UUID.randomUUID().toString()
        val out = File(dir(c), "$id.enc")
        val sk = key()
        try {
            DataOutputStream(out.outputStream().buffered()).use { o ->
                src.inputStream().use { i ->
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        val n = fill(i, buf)
                        if (n <= 0) break
                        val ci = Cipher.getInstance("AES/GCM/NoPadding")
                        ci.init(Cipher.ENCRYPT_MODE, sk)
                        val enc = ci.doFinal(buf, 0, n)
                        o.writeByte(ci.iv.size); o.write(ci.iv); o.writeInt(enc.size); o.write(enc)
                    }
                }
            }
        } catch (e: Exception) { out.delete(); throw e }
        val p = meta(c); p.setProperty(id, "${src.name}|${src.length()}"); saveMeta(c, p)
        val path = src.absolutePath
        src.delete()
        Fx.scan(c, listOf(path))
    }

    fun decrypt(c: Context, id: String, dst: File) {
        val sk = key()
        DataInputStream(File(dir(c), "$id.enc").inputStream().buffered()).use { i ->
            dst.outputStream().buffered().use { o ->
                while (true) {
                    val ivLen = i.read()
                    if (ivLen < 0) break
                    val iv = ByteArray(ivLen); i.readFully(iv)
                    val enc = ByteArray(i.readInt()); i.readFully(enc)
                    val ci = Cipher.getInstance("AES/GCM/NoPadding")
                    ci.init(Cipher.DECRYPT_MODE, sk, GCMParameterSpec(128, iv))
                    o.write(ci.doFinal(enc))
                }
            }
        }
    }

    fun items(c: Context): List<SafeItem> {
        val p = meta(c)
        return p.stringPropertyNames().mapNotNull { id ->
            if (!File(dir(c), "$id.enc").exists()) return@mapNotNull null
            val v = p.getProperty(id)
            SafeItem(id, v.substringBeforeLast('|'), v.substringAfterLast('|').toLongOrNull() ?: 0L)
        }.sortedBy { it.name.lowercase() }
    }

    fun remove(c: Context, id: String) {
        File(dir(c), "$id.enc").delete()
        val p = meta(c); p.remove(id); saveMeta(c, p)
    }
}
