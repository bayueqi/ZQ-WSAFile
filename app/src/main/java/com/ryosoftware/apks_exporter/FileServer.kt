package com.ryosoftware.apks_exporter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 轻量 HTTP 文件服务：在应用内监听一个本地端口，用浏览器即可浏览、下载、上传、删除
 * 共享存储中的文件。等价于原先在电脑上跑的 WSA-File 脚本，现在无需电脑端任何程序。
 */
object FileServer {

    const val DEFAULT_PORT = 8899
    const val ROOT_ALIAS = "/sdcard"

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    var port: Int = DEFAULT_PORT
        private set

    private var pool = Executors.newCachedThreadPool()

    @Volatile
    private var favicon: ByteArray? = null

    fun isRunning(): Boolean = running

    fun root(): File = Environment.getExternalStorageDirectory()

    @Synchronized
    fun start(context: Context, preferredPort: Int = DEFAULT_PORT): Int {
        if (running) return port

        favicon = buildFavicon(context)

        var candidate = preferredPort
        var bound: ServerSocket? = null
        while (candidate < preferredPort + 30) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(candidate), 50)
                bound = socket
                break
            } catch (t: Throwable) {
                candidate++
            }
        }
        val socket = bound ?: throw IllegalStateException("没有可用端口")

        serverSocket = socket
        port = candidate
        running = true
        pool = Executors.newCachedThreadPool()

        val appContext = context.applicationContext
        thread(name = "file-server", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (t: Throwable) {
                    break
                }
                try {
                    pool.execute { handle(appContext, client) }
                } catch (t: Throwable) {
                    try {
                        client.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return candidate
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        try {
            pool.shutdownNow()
        } catch (_: Throwable) {
        }
    }

    // ---------- 请求处理 ----------

    private fun handle(context: Context, socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 60000
                val input = client.getInputStream()
                val output = client.getOutputStream()

                val requestLine = readLine(input) ?: return
                var contentLength = 0L
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) {
                        contentLength = header.substring(header.indexOf(':') + 1).trim().toLongOrNull() ?: 0L
                    }
                }

                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    writeText(output, 400, "请求格式错误")
                    return
                }
                val method = parts[0].uppercase(Locale.ROOT)
                val url = parts[1]

                val queryIndex = url.indexOf('?')
                val path = if (queryIndex >= 0) url.substring(0, queryIndex) else url
                val query = if (queryIndex >= 0) url.substring(queryIndex + 1) else ""
                val params = parseQuery(query)

                when {
                    path == "/" || path == "/l" -> sendListing(output, params["p"])

                    path == "/d" -> sendDownload(output, params["p"])

                    path == "/up" && method == "POST" ->
                        receiveUpload(input, output, params["d"], params["n"], contentLength)

                    path == "/del" && method == "POST" -> deleteEntry(output, params["p"])

                    path == "/favicon.ico" -> {
                        val icon = favicon
                        if (icon == null) {
                            writeText(output, 404, "无图标")
                        } else {
                            writeHead(output, 200, "image/png", icon.size.toLong())
                            output.write(icon)
                            output.flush()
                        }
                    }

                    else -> writeText(output, 404, "页面不存在")
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun sendListing(output: OutputStream, rawPath: String?) {
        val target = resolve(rawPath)
        if (target == null) {
            writeText(output, 403, "无权访问该路径")
            return
        }
        if (!target.exists()) {
            writeText(output, 404, "路径不存在")
            return
        }
        if (target.isFile) {
            sendDownload(output, rawPath)
            return
        }

        val html = buildListingPage(target)
        val bytes = html.toByteArray(Charsets.UTF_8)
        writeHead(output, 200, "text/html; charset=utf-8", bytes.size.toLong())
        output.write(bytes)
        output.flush()
    }

    private fun sendDownload(output: OutputStream, rawPath: String?) {
        val target = resolve(rawPath)
        if (target == null || !target.isFile || !target.canRead()) {
            writeText(output, 404, "文件不存在")
            return
        }
        val length = target.length()
        val encoded = urlEncode(target.name)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Content-Disposition: attachment; filename=\"").append(asciiName(target.name)).append("\"; filename*=UTF-8''").append(encoded).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        FileInputStream(target).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
            }
        }
        output.flush()
    }

    private fun receiveUpload(
        input: InputStream,
        output: OutputStream,
        rawDir: String?,
        fileName: String?,
        contentLength: Long
    ) {
        val directory = resolve(rawDir)
        if (directory == null || !directory.isDirectory) {
            writeText(output, 400, "目标目录无效")
            return
        }
        val name = fileName?.trim()
        if (name.isNullOrEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
            writeText(output, 400, "文件名无效")
            return
        }
        val destination = File(directory, name)
        try {
            FileOutputStream(destination).use { stream ->
                val buffer = ByteArray(64 * 1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    stream.write(buffer, 0, read)
                    remaining -= read
                }
            }
            writeText(output, 200, "OK")
        } catch (t: Throwable) {
            writeText(output, 500, "写入失败")
        }
    }

    private fun deleteEntry(output: OutputStream, rawPath: String?) {
        val target = resolve(rawPath)
        if (target == null) {
            writeText(output, 403, "无权访问该路径")
            return
        }
        if (target == root() || target.absolutePath == root().absolutePath) {
            writeText(output, 400, "不允许删除根目录")
            return
        }
        if (!target.exists()) {
            writeText(output, 404, "路径不存在")
            return
        }
        val done = try {
            target.deleteRecursively()
        } catch (t: Throwable) {
            false
        }
        if (done) writeText(output, 200, "OK") else writeText(output, 500, "删除失败")
    }

    // ---------- 页面 ----------

    private fun buildListingPage(directory: File): String {
        val current = displayPath(directory)
        val children = directory.listFiles()?.sortedWith(
            compareBy<File>({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase(Locale.ROOT) })
        ) ?: emptyList()

        val parent = directory.parentFile
        val builder = StringBuilder()
        builder.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
        builder.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        builder.append("<title>").append(escapeHtml(displayPath(directory))).append("</title>")
        builder.append("<link rel=\"icon\" href=\"/favicon.ico\">")
        builder.append("<style>")
        builder.append("*{box-sizing:border-box}")
        builder.append("body{margin:0;font:14px/1.5 -apple-system,'Segoe UI','Microsoft YaHei',sans-serif;background:#f4f5f7;color:#20242a}")
        builder.append("header{position:sticky;top:0;background:#fff;border-bottom:1px solid #e3e6ea;padding:14px 18px;z-index:5}")
        builder.append(".path{font-size:15px;word-break:break-all;margin-bottom:10px}")
        builder.append(".path a{color:#2f6fd0;text-decoration:none}")
        builder.append(".path span{color:#8b939e;margin:0 4px}")
        builder.append(".bar{display:flex;align-items:center;gap:10px;flex-wrap:wrap}")
        builder.append(".btn{display:inline-flex;align-items:center;gap:6px;padding:7px 14px;border-radius:7px;border:1px solid #d3d8de;background:#fff;color:#20242a;cursor:pointer;font-size:13px;text-decoration:none}")
        builder.append(".btn.primary{background:#2f6fd0;border-color:#2f6fd0;color:#fff}")
        builder.append(".btn:hover{filter:brightness(0.96)}")
        builder.append("input[type=file]{display:none}")
        builder.append(".list{margin:16px 18px;background:#fff;border:1px solid #e3e6ea;border-radius:10px;overflow:hidden}")
        builder.append("table{width:100%;border-collapse:collapse}")
        builder.append("th,td{padding:10px 14px;text-align:left;border-bottom:1px solid #eef0f3;font-size:13px}")
        builder.append("th{background:#fafbfc;color:#6d7683;font-weight:500}")
        builder.append("tr:last-child td{border-bottom:none}")
        builder.append("td.n a{color:#20242a;text-decoration:none}")
        builder.append("td.n a:hover{color:#2f6fd0}")
        builder.append("td.s,td.t{color:#7a828e;white-space:nowrap}")
        builder.append("td.o{text-align:right;width:80px}")
        builder.append(".del{border:none;background:none;color:#c0392b;cursor:pointer;font-size:13px;padding:4px 6px}")
        builder.append(".empty{padding:40px;text-align:center;color:#9aa2ad}")
        builder.append(".status{margin-top:8px;font-size:13px;color:#2f6fd0;min-height:18px}")
        builder.append("@media(max-width:600px){td.t,th.t{display:none}}")
        builder.append("</style></head><body>")

        builder.append("<header><div class=\"path\">")
        builder.append("<a href=\"/l?p=").append(urlEncode(ROOT_ALIAS)).append("\">").append(escapeHtml(ROOT_ALIAS)).append("</a>")
        val relative = current.removePrefix(ROOT_ALIAS).trim('/')
        if (relative.isNotEmpty()) {
            val segments = relative.split('/')
            val accumulated = StringBuilder(ROOT_ALIAS)
            for (segment in segments) {
                if (segment.isEmpty()) continue
                accumulated.append('/').append(segment)
                builder.append("<span>/</span><a href=\"/l?p=").append(urlEncode(accumulated.toString())).append("\">")
                    .append(escapeHtml(segment)).append("</a>")
            }
        }
        builder.append("</div><div class=\"bar\">")
        if (parent != null && resolve(parent.absolutePath) != null) {
            builder.append("<a class=\"btn\" href=\"/l?p=").append(urlEncode(displayPath(parent))).append("\">返回上级</a>")
        }
        builder.append("<label class=\"btn primary\">上传文件<input type=\"file\" multiple onchange=\"upload(this)\"></label>")
        builder.append("<a class=\"btn\" href=\"/l?p=").append(urlEncode(ROOT_ALIAS)).append("\">根目录</a>")
        builder.append("</div><div class=\"status\" id=\"st\"></div></header>")

        builder.append("<div class=\"list\">")
        if (children.isEmpty()) {
            builder.append("<div class=\"empty\">这个目录是空的</div>")
        } else {
            builder.append("<table><thead><tr><th>名称</th><th class=\"s\">大小</th><th class=\"t\">修改时间</th><th></th></tr></thead><tbody>")
            for (child in children) {
                val full = displayPath(child)
                val isDir = child.isDirectory
                builder.append("<tr><td class=\"n\"><a href=\"/").append(if (isDir) "l" else "d").append("?p=")
                    .append(urlEncode(full)).append("\">").append(escapeHtml(child.name))
                    .append(if (isDir) "/" else "").append("</a></td>")
                builder.append("<td class=\"s\">").append(if (isDir) "-" else sizeText(child.length())).append("</td>")
                builder.append("<td class=\"t\">").append(timeText(child.lastModified())).append("</td>")
                builder.append("<td class=\"o\"><button class=\"del\" onclick=\"del('").append(jsEscape(full)).append("','")
                    .append(jsEscape(child.name)).append("')\">删除</button></td></tr>")
            }
            builder.append("</tbody></table>")
        }
        builder.append("</div>")

        builder.append("<script>")
        builder.append("const CUR=\"").append(jsEscape(current)).append("\";")
        builder.append("function del(p,n){if(!confirm('删除 '+n+' ？'))return;")
        builder.append("fetch('/del?p='+encodeURIComponent(p),{method:'POST'}).then(function(r){")
        builder.append("if(r.ok){location.reload()}else{alert('删除失败')}}).catch(function(){alert('删除失败')})}")
        builder.append("async function upload(input){const files=Array.from(input.files);if(!files.length)return;")
        builder.append("const st=document.getElementById('st');")
        builder.append("for(let i=0;i<files.length;i++){st.textContent='正在上传 '+(i+1)+'/'+files.length+'：'+files[i].name;")
        builder.append("try{const r=await fetch('/up?d='+encodeURIComponent(CUR)+'&n='+encodeURIComponent(files[i].name),{method:'POST',body:files[i]});")
        builder.append("if(!r.ok){st.textContent='上传失败：'+files[i].name;return}}catch(e){st.textContent='上传失败：'+files[i].name;return}}")
        builder.append("st.textContent='上传完成';location.reload()}")
        builder.append("</script></body></html>")
        return builder.toString()
    }

    // ---------- 工具 ----------

    private fun resolve(rawPath: String?): File? {
        val base = root()
        val basePath = try {
            base.canonicalPath
        } catch (t: Throwable) {
            base.absolutePath
        }
        if (rawPath.isNullOrBlank()) return base

        val candidate = when {
            rawPath == ROOT_ALIAS || rawPath == "$ROOT_ALIAS/" -> base
            rawPath.startsWith("$ROOT_ALIAS/") -> File(basePath + rawPath.substring(ROOT_ALIAS.length))
            rawPath.startsWith("/") -> File(rawPath)
            else -> File(base, rawPath)
        }
        val canonical = try {
            candidate.canonicalFile
        } catch (t: Throwable) {
            return null
        }
        val path = canonical.absolutePath
        return if (path == basePath || path.startsWith(basePath + File.separator)) canonical else null
    }

    private fun displayPath(file: File): String {
        val rootPath = root().absolutePath
        val absolute = file.absolutePath
        return when {
            absolute == rootPath -> ROOT_ALIAS
            absolute.startsWith("$rootPath/") -> ROOT_ALIAS + absolute.substring(rootPath.length)
            else -> absolute
        }
    }

    private fun buildFavicon(context: Context): ByteArray? = try {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        val buffer = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
        bitmap.recycle()
        buffer.toByteArray()
    } catch (t: Throwable) {
        null
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        var value = input.read()
        if (value == -1) return null
        while (value != -1 && value != '\n'.code) {
            if (value != '\r'.code) builder.append(value.toChar())
            value = input.read()
        }
        return builder.toString()
    }

    private fun parseQuery(query: String): Map<String, String> {
        val result = HashMap<String, String>()
        if (query.isEmpty()) return result
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            val key = if (index >= 0) pair.substring(0, index) else pair
            val value = if (index >= 0) pair.substring(index + 1) else ""
            result[urlDecode(key)] = urlDecode(value)
        }
        return result
    }

    private fun urlDecode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (t: Throwable) {
        value
    }

    private fun urlEncode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (t: Throwable) {
        value
    }

    private fun writeHead(output: OutputStream, code: Int, contentType: String, length: Long) {
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeText(output: OutputStream, code: Int, message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        writeHead(output, code, "text/plain; charset=utf-8", bytes.size.toLong())
        output.write(bytes)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun sizeText(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private fun timeText(timestamp: Long): String {
        if (timestamp <= 0) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
    }

    private fun asciiName(name: String): String {
        val builder = StringBuilder()
        for (character in name) {
            if (character.code in 32..126 && character != '"' && character != '\\') {
                builder.append(character)
            } else {
                builder.append('_')
            }
        }
        return builder.toString()
    }

    private fun escapeHtml(value: String): String {
        val builder = StringBuilder(value.length + 16)
        for (character in value) {
            when (character) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(character)
            }
        }
        return builder.toString()
    }

    private fun jsEscape(value: String): String {
        val builder = StringBuilder(value.length + 16)
        for (character in value) {
            when (character) {
                '\\' -> builder.append("\\\\")
                '\'' -> builder.append("\\'")
                '"' -> builder.append("\\\"")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '<' -> builder.append("\\u003c")
                '>' -> builder.append("\\u003e")
                '&' -> builder.append("\\u0026")
                else -> builder.append(character)
            }
        }
        return builder.toString()
    }
}
