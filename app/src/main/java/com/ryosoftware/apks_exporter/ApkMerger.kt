package com.ryosoftware.apks_exporter

import android.content.Context
import com.android.apksig.ApkSigner
import com.ryosoftware.utilities.LogUtilities
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal object ApkMerger {
    private const val KEYSTORE_ASSET = "sign.p12"
    private const val KEYSTORE_PASSWORD = "wsafile"
    private const val KEY_ALIAS = "wsafile"
    private const val PAGE_ALIGNMENT = 4096
    private const val RESOURCES_ALIGNMENT = 4

    fun merge(context: Context, apks: Map<String, File>, output: File): Boolean {
        val temporary = File(output.parentFile, "${output.name}.merge")
        return try {
            writeMergedZip(apks, temporary)
            sign(context, temporary, output)
            true
        } catch (e: Exception) {
            LogUtilities.show(ApkMerger::class.java, e)
            output.delete()
            false
        } finally {
            temporary.delete()
        }
    }

    private fun writeMergedZip(apks: Map<String, File>, output: File) {
        val baseFile = apks["base.apk"] ?: apks.values.first()
        val opened = ArrayList<ZipFile>()
        val counting = CountingOutputStream(FileOutputStream(output))
        try {
            val baseZip = ZipFile(baseFile)
            opened.add(baseZip)
            val sources = LinkedHashMap<String, Pair<ZipFile, ZipEntry>>()

            for (entry in baseZip.entries()) {
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                sources[entry.name] = baseZip to entry
            }

            for (file in apks.values) {
                if (file == baseFile) continue
                val zip = ZipFile(file)
                opened.add(zip)
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (name.contains("/") && !name.startsWith("lib/") && !name.startsWith("assets/")) continue
                    if (!name.startsWith("lib/") && !name.startsWith("assets/") && !name.endsWith(".so")) continue
                    if (!sources.containsKey(name)) sources[name] = zip to entry
                }
            }

            val zipOutput = ZipOutputStream(counting)

            sources["resources.arsc"]?.let { (zip, entry) ->
                writeStored(zipOutput, counting, zip, entry, RESOURCES_ALIGNMENT)
                sources.remove("resources.arsc")
            }

            for ((name, pair) in sources) {
                val (zip, entry) = pair
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    writeStored(zipOutput, counting, zip, entry, PAGE_ALIGNMENT)
                } else if (entry.method == ZipEntry.STORED) {
                    writeStored(zipOutput, counting, zip, entry, 0)
                } else {
                    val copy = ZipEntry(name)
                    zipOutput.putNextEntry(copy)
                    zip.getInputStream(entry).use { it.copyTo(zipOutput) }
                    zipOutput.closeEntry()
                }
            }

            zipOutput.finish()
            zipOutput.flush()
        } finally {
            counting.close()
            for (zip in opened) {
                try {
                    zip.close()
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun writeStored(
        zipOutput: ZipOutputStream,
        counting: CountingOutputStream,
        source: ZipFile,
        sourceEntry: ZipEntry,
        alignment: Int
    ) {
        val crc = CRC32()
        source.getInputStream(sourceEntry).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }

        val name = sourceEntry.name
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = sourceEntry.size
        entry.crc = crc.value
        if (alignment > 1) {
            val position = counting.count
            var extraLength = (alignment - ((position + 30 + name.toByteArray(Charsets.UTF_8).size) % alignment)) % alignment
            if (extraLength in 1..3) extraLength += alignment
            if (extraLength >= 4) {
                val extra = ByteArray(extraLength)
                extra[0] = 0xfe.toByte()
                extra[1] = 0xca.toByte()
                val payload = extraLength - 4
                extra[2] = (payload and 0xff).toByte()
                extra[3] = ((payload ushr 8) and 0xff).toByte()
                entry.extra = extra
            }
        }

        zipOutput.putNextEntry(entry)
        source.getInputStream(sourceEntry).use { it.copyTo(zipOutput) }
        zipOutput.closeEntry()
    }

    private fun sign(context: Context, input: File, output: File) {
        val keyStore = KeyStore.getInstance("PKCS12")
        context.assets.open(KEYSTORE_ASSET).use { keyStore.load(it, KEYSTORE_PASSWORD.toCharArray()) }
        val privateKey = keyStore.getKey(KEY_ALIAS, KEYSTORE_PASSWORD.toCharArray()) as PrivateKey
        val certificate = keyStore.getCertificate(KEY_ALIAS) as X509Certificate
        val signerConfig = ApkSigner.SignerConfig.Builder(KEY_ALIAS, privateKey, listOf(certificate)).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setMinSdkVersion(33)
            .build()
            .sign()
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }
}
