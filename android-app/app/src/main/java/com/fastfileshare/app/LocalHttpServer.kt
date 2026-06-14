package com.fastfileshare.app

import android.content.res.AssetManager
import android.os.Environment
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.*

class LocalHttpServer(private val port: Int, private val assetManager: AssetManager) : Thread() {
    private val tag = "LocalHttpServer"
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = true

    private val mimeMap = mapOf(
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "m4a" to "audio/mp4",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "vtt" to "text/vtt",
        "srt" to "text/vtt",
        "pdf" to "application/pdf"
    )

    init {
        name = "FastFileShareServerThread"
    }

    override fun run() {
        try {
            serverSocket = ServerSocket(port)
            Log.d(tag, "Server socket successfully bound to port $port")
            while (isRunning) {
                val client = serverSocket?.accept() ?: break
                // Process each request inside a separate background thread
                Thread { handleClient(client) }.start()
            }
        } catch (e: Exception) {
            Log.e(tag, "Server error: ${e.message}")
        }
    }

    fun stopServer() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket: ${e.message}")
        }
    }

    private fun handleClient(client: Socket) {
        val out = BufferedOutputStream(client.getOutputStream())

        try {
            val inputStream = client.getInputStream()
            val headerBytes = ByteArrayOutputStream()
            var state = 0
            while (true) {
                val b = inputStream.read()
                if (b == -1) break
                headerBytes.write(b)
                if (b == 13) {
                    if (state == 0 || state == 2) {
                        state++
                    } else {
                        state = 1
                    }
                } else if (b == 10) {
                    if (state == 1 || state == 3) {
                        state++
                        if (state == 4) break
                    } else {
                        state = 0
                    }
                } else {
                    state = 0
                }
            }

            val headersText = headerBytes.toString("UTF-8")
            val headerLines = headersText.split("\r\n")
            if (headerLines.isEmpty()) return
            
            val requestLine = headerLines[0]
            Log.d(tag, "Request: $requestLine")
            val tokens = StringTokenizer(requestLine)
            if (tokens.countTokens() < 2) return
            val method = tokens.nextToken()
            val rawUri = tokens.nextToken()

            // Read headers to capture 'Range' and 'Content-Length'
            var rangeHeader: String? = null
            var contentLength: Long = 0
            for (i in 1 until headerLines.size) {
                val line = headerLines[i]
                if (line.isEmpty()) continue
                val lowerLine = line.lowercase(Locale.ENGLISH)
                if (lowerLine.startsWith("range:")) {
                    rangeHeader = line.substring(6).trim()
                } else if (lowerLine.startsWith("content-length:")) {
                    contentLength = line.substring(15).trim().toLongOrNull() ?: 0
                }
            }

            if (method == "OPTIONS") {
                sendOptionsResponse(out)
                return
            }

            if (method != "GET" && method != "POST" && method != "DELETE") {
                sendErrorResponse(out, 501, "Not Implemented")
                return
            }

            // Route request by path (without decoding query parameters first)
            val queryIndex = rawUri.indexOf("?")
            val rawPath = if (queryIndex != -1) rawUri.substring(0, queryIndex) else rawUri
            val parsedPath = URLDecoder.decode(rawPath, "UTF-8")
            
            // Set base directory to /storage to enable both internal memory and USB OTG access
            val baseSharedFolder = File("/storage")

            when {
                parsedPath.startsWith("/api/files") -> {
                    handleApiRequest(out, rawUri, baseSharedFolder)
                }
                parsedPath.startsWith("/api/cast") -> {
                    handleCastRequest(out, rawUri)
                }
                parsedPath.startsWith("/stream") -> {
                    handleStreamRequest(out, rawUri, rangeHeader, baseSharedFolder)
                }
                parsedPath.startsWith("/client") -> {
                    handleAssetRequest(out, rawUri)
                }
                parsedPath.startsWith("/api/upload") && method == "POST" -> {
                    handleUploadRequest(inputStream, out, rawUri, contentLength, baseSharedFolder)
                }
                parsedPath.startsWith("/api/delete") && method == "DELETE" -> {
                    handleDeleteRequest(out, rawUri, baseSharedFolder)
                }
                parsedPath == "/" -> {
                    sendDashboardResponse(out)
                }
                else -> {
                    sendErrorResponse(out, 404, "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Request handling failed: ${e.message}")
            try {
                sendErrorResponse(out, 500, "Internal Server Error")
            } catch (ignored: Exception) {}
        } finally {
            try {
                out.close()
                client.close()
            } catch (ignored: Exception) {}
        }
    }

    // Endpoint: GET /client/... (Serves TV client files bundled inside the APK assets)
    private fun handleAssetRequest(out: BufferedOutputStream, uri: String) {
        // Remove query parameters if present
        val cleanUri = uri.split("?")[0].trimStart('/')
        try {
            val decodedUri = URLDecoder.decode(cleanUri, "UTF-8")
            val inputStream = assetManager.open(decodedUri)
            val ext = getExtension(cleanUri)
            val mime = when (ext) {
                "html" -> "text/html"
                "css" -> "text/css"
                "js" -> "application/javascript"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }

            val size = inputStream.available().toLong()
            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            out.write("Content-Type: $mime\r\n".toByteArray())
            out.write("Content-Length: $size\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())

            val buffer = ByteArray(8192)
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
            }
            out.flush()
            inputStream.close()
        } catch (e: Exception) {
            Log.e(tag, "Failed to load asset $cleanUri: ${e.message}")
            sendErrorResponse(out, 404, "Asset Not Found")
        }
    }

    // Endpoint: GET /api/files
    private fun handleApiRequest(out: BufferedOutputStream, uri: String, baseDir: File) {
        val queryParams = getQueryParams(uri)
        val relativePath = queryParams["path"] ?: ""
        val targetDirectory = File(baseDir, relativePath)

        // Path Traversal Security check
        if (!isPathSafe(targetDirectory, baseDir)) {
            sendErrorResponse(out, 403, "Access Denied")
            return
        }

        if (!targetDirectory.exists() || !targetDirectory.isDirectory) {
            sendErrorResponse(out, 404, "Directory Not Found")
            return
        }

        val foldersList = mutableListOf<String>()
        val filesList = mutableListOf<String>()

        // 1. Special case: If path is root (""), cleanly format internal memory and USB OTG
        if (relativePath.isEmpty()) {
            val internalStorageDir = Environment.getExternalStorageDirectory()
            val internalRelativePath = internalStorageDir.absolutePath.substring(baseDir.absolutePath.length).trimStart('/')
            
            // Add Internal Storage card
            foldersList.add(createItemJson("Internal Storage", "folder", internalRelativePath, 0, "--", "", "directory"))

            // Add connected USB drives
            val storageVolumes = baseDir.listFiles()
            if (storageVolumes != null) {
                for (volume in storageVolumes) {
                    if (volume.isDirectory && volume.name != "emulated" && volume.name != "self" && volume.name != "knox") {
                        val usbRelativePath = volume.absolutePath.substring(baseDir.absolutePath.length).trimStart('/')
                        try {
                            if (volume.canRead()) {
                                foldersList.add(createItemJson("USB Drive (${volume.name})", "folder", usbRelativePath, 0, "--", "", "directory"))
                            }
                        } catch (ignored: Exception) {}
                    }
                }
            }
        } else {
            // 2. Standard subdirectory browsing
            val files = targetDirectory.listFiles()
            if (files != null) {
                val supportedVideo = listOf("mp4", "mkv", "webm", "avi")
                val supportedAudio = listOf("mp3", "wav", "m4a")
                val supportedImage = listOf("jpg", "jpeg", "png", "webp")
                val supportedPdf = listOf("pdf")
                val allSupported = supportedVideo + supportedAudio + supportedImage + supportedPdf

                val detectedPosters = mutableSetOf<String>()
                val videoPosters = mutableMapOf<String, String>()

                // Pass 1: Scan for video files to find matching posters
                for (file in files) {
                    if (file.name.startsWith(".")) continue
                    if (file.isFile) {
                        val ext = getExtension(file.name)
                        if (supportedVideo.contains(ext)) {
                            val baseAbsolutePath = file.absolutePath.substring(0, file.absolutePath.length - ext.length - 1)
                            val relItemPath = file.absolutePath.substring(baseDir.absolutePath.length)
                                .replace('\\', '/')
                                .trimStart('/')
                            val baseRelPath = relItemPath.substring(0, relItemPath.length - ext.length - 1)

                            val jpgFile = File("$baseAbsolutePath.jpg")
                            val jpegFile = File("$baseAbsolutePath.jpeg")
                            val pngFile = File("$baseAbsolutePath.png")

                            if (jpgFile.exists() && jpgFile.isFile) {
                                videoPosters[relItemPath] = "$baseRelPath.jpg"
                                detectedPosters.add(jpgFile.canonicalPath)
                            } else if (jpegFile.exists() && jpegFile.isFile) {
                                videoPosters[relItemPath] = "$baseRelPath.jpeg"
                                detectedPosters.add(jpegFile.canonicalPath)
                            } else if (pngFile.exists() && pngFile.isFile) {
                                videoPosters[relItemPath] = "$baseRelPath.png"
                                detectedPosters.add(pngFile.canonicalPath)
                            }
                        }
                    }
                }

                // Pass 2: Process files and folders
                for (file in files) {
                    if (file.name.startsWith(".")) continue // Ignore hidden files

                    val relItemPath = file.absolutePath.substring(baseDir.absolutePath.length)
                        .replace('\\', '/')
                        .trimStart('/')

                    if (file.isDirectory) {
                        val folderJson = createItemJson(file.name, "folder", relItemPath, 0, "--", "", "directory")
                        foldersList.add(folderJson)
                    } else if (file.isFile) {
                        val ext = getExtension(file.name)
                        if (allSupported.contains(ext)) {
                            // Exclude raw images that are already used as posters
                            if (supportedImage.contains(ext) && detectedPosters.contains(file.canonicalPath)) {
                                continue
                            }

                            val type = when {
                                supportedVideo.contains(ext) -> "video"
                                supportedAudio.contains(ext) -> "audio"
                                supportedPdf.contains(ext) -> "pdf"
                                else -> "image"
                            }
                            
                            // Check if a subtitle file (.vtt or .srt) exists next to the video
                            var subtitlePath: String? = null
                            var posterPath: String? = null
                            if (type == "video") {
                                val baseName = file.absolutePath.substring(0, file.absolutePath.length - ext.length - 1)
                                val vttFile = File("$baseName.vtt")
                                val srtFile = File("$baseName.srt")
                                if (vttFile.exists() && vttFile.isFile) {
                                    subtitlePath = relItemPath.substring(0, relItemPath.length - ext.length - 1) + ".vtt"
                                } else if (srtFile.exists() && srtFile.isFile) {
                                    subtitlePath = relItemPath.substring(0, relItemPath.length - ext.length - 1) + ".srt"
                                }

                                posterPath = videoPosters[relItemPath]
                            }

                            val mime = mimeMap[ext] ?: "application/octet-stream"
                            val formattedSize = formatBytes(file.length())
                            val fileJson = createItemJson(file.name, type, relItemPath, file.length(), formattedSize, ".$ext", mime, subtitlePath, posterPath)
                            filesList.add(fileJson)
                        }
                    }
                }
            }
        }

        // Sort items alphabetically
        foldersList.sort()
        filesList.sort()

        val mergedList = foldersList + filesList
        val jsonArrayString = mergedList.joinToString(separator = ",", prefix = "[", postfix = "]")

        val responseBytes = jsonArrayString.toByteArray(Charsets.UTF_8)
        
        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
        out.write("Content-Type: application/json\r\n".toByteArray())
        out.write("Content-Length: ${responseBytes.size}\r\n".toByteArray())
        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(responseBytes)
        out.flush()
    }

    // Endpoint: GET /stream?path=...
    private fun handleStreamRequest(out: BufferedOutputStream, uri: String, rangeHeader: String?, baseDir: File) {
        val queryParams = getQueryParams(uri)
        val relativePath = queryParams["path"] ?: ""
        val targetFile = File(baseDir, relativePath)

        // Path Traversal Security check
        if (!isPathSafe(targetFile, baseDir)) {
            sendErrorResponse(out, 403, "Access Denied")
            return
        }

        if (!targetFile.exists() || !targetFile.isFile) {
            sendErrorResponse(out, 404, "File Not Found")
            return
        }

        // Convert SRT to WebVTT on-the-fly for Tizen player compatibility
        if (targetFile.name.lowercase(Locale.ENGLISH).endsWith(".srt")) {
            try {
                var content = targetFile.readText(Charsets.UTF_8)
                content = "WEBVTT\n\n" + content.replace(Regex("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})"), "$1.$2")
                val responseBytes = content.toByteArray(Charsets.UTF_8)
                
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                out.write("Content-Type: text/vtt\r\n".toByteArray())
                out.write("Content-Length: ${responseBytes.size}\r\n".toByteArray())
                out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(responseBytes)
                out.flush()
                return
            } catch (e: Exception) {
                Log.e(tag, "SRT subtitle conversion failed: ${e.message}")
                sendErrorResponse(out, 500, "Subtitle conversion failed")
                return
            }
        }

        val fileSize = targetFile.length()
        val ext = getExtension(targetFile.name)
        val mime = mimeMap[ext] ?: "application/octet-stream"

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            // Seek range parsing
            val rangeSpec = rangeHeader.substring(6)
            val parts = rangeSpec.split("-")
            val start: Long
            var end: Long

            try {
                start = parts[0].toLong()
                end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLong() else fileSize - 1
            } catch (e: NumberFormatException) {
                sendErrorResponse(out, 416, "Requested Range Not Satisfiable")
                return
            }

            if (start >= fileSize || end >= fileSize || start > end) {
                out.write("HTTP/1.1 416 Requested Range Not Satisfiable\r\n".toByteArray())
                out.write("Content-Range: bytes */$fileSize\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val chunkSize = (end - start) + 1
            val fileStream = FileInputStream(targetFile)
            fileStream.skip(start)

            val download = queryParams["download"] == "true"
            out.write("HTTP/1.1 206 Partial Content\r\n".toByteArray())
            out.write("Content-Range: bytes $start-$end/$fileSize\r\n".toByteArray())
            out.write("Accept-Ranges: bytes\r\n".toByteArray())
            out.write("Content-Length: $chunkSize\r\n".toByteArray())
            out.write("Content-Type: $mime\r\n".toByteArray())
            if (download) {
                val safeFilename = targetFile.name.replace("\"", "\\\"")
                val encodedFilename = java.net.URLEncoder.encode(targetFile.name, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~")
                out.write("Content-Disposition: attachment; filename=\"$safeFilename\"; filename*=UTF-8''$encodedFilename\r\n".toByteArray())
            }
            out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())

            val buffer = ByteArray(8192)
            var bytesRemaining = chunkSize
            while (bytesRemaining > 0) {
                val toRead = Math.min(buffer.size.toLong(), bytesRemaining).toInt()
                val bytesRead = fileStream.read(buffer, 0, toRead)
                if (bytesRead == -1) break
                out.write(buffer, 0, bytesRead)
                bytesRemaining -= bytesRead
            }
            out.flush()
            fileStream.close()
        } else {
            // Stream entire file
            val fileStream = FileInputStream(targetFile)
            val download = queryParams["download"] == "true"
            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            out.write("Accept-Ranges: bytes\r\n".toByteArray())
            out.write("Content-Length: $fileSize\r\n".toByteArray())
            out.write("Content-Type: $mime\r\n".toByteArray())
            if (download) {
                val safeFilename = targetFile.name.replace("\"", "\\\"")
                val encodedFilename = java.net.URLEncoder.encode(targetFile.name, "UTF-8")
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~")
                out.write("Content-Disposition: attachment; filename=\"$safeFilename\"; filename*=UTF-8''$encodedFilename\r\n".toByteArray())
            }
            out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())

            val buffer = ByteArray(8192)
            while (true) {
                val bytesRead = fileStream.read(buffer)
                if (bytesRead == -1) break
                out.write(buffer, 0, bytesRead)
            }
            out.flush()
            fileStream.close()
        }
    }

    // Server Web Dashboard response
    private fun sendDashboardResponse(out: BufferedOutputStream) {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>Fast File Share Mobile Server Active</title>
              <style>
                body { font-family: sans-serif; background: #0c101b; color: #e2e8f0; padding: 40px; text-align: center; }
                .card { background: #161f30; padding: 30px; border-radius: 12px; max-width: 500px; margin: 0 auto; border: 1px solid #233554; }
                h1 { color: #0084ff; }
                .badge { background: #10b981; color: white; padding: 4px 8px; border-radius: 4px; font-size: 0.8em; font-weight: bold; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>⚡ Fast File Share Mobile Server <span class="badge">ON</span></h1>
                <p>The Android file sharing server is active and running!</p>
                <p>Open the Browser Share Link on your PC or another device to start transferring files.</p>
              </div>
            </body>
            </html>
        """.trimIndent()

        val responseBytes = html.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
        out.write("Content-Type: text/html\r\n".toByteArray())
        out.write("Content-Length: ${responseBytes.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(responseBytes)
        out.flush()
    }

    private fun sendOptionsResponse(out: BufferedOutputStream) {
        val response = "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type, Authorization, Range\r\nAccess-Control-Max-Age: 86400\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray())
        out.flush()
    }

    private fun sendErrorResponse(out: BufferedOutputStream, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message\r\nContent-Length: ${message.length}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n$message"
        out.write(response.toByteArray())
        out.flush()
    }

    // Path Safety verification
    private fun isPathSafe(file: File, baseDir: File): Boolean {
        return try {
            file.canonicalPath.startsWith(baseDir.canonicalPath)
        } catch (e: Exception) {
            false
        }
    }

    private fun handleDeleteRequest(out: BufferedOutputStream, uri: String, baseDir: File) {
        val queryParams = getQueryParams(uri)
        val relativePath = queryParams["path"] ?: ""
        val targetFile = File(baseDir, relativePath)

        if (!isPathSafe(targetFile, baseDir)) {
            sendErrorResponse(out, 403, "Access Denied")
            return
        }

        if (!targetFile.exists()) {
            sendErrorResponse(out, 404, "File Not Found")
            return
        }

        try {
            val deleted = if (targetFile.isDirectory) {
                targetFile.deleteRecursively()
            } else {
                targetFile.delete()
            }
            if (deleted) {
                val responseBytes = "{\"success\":true,\"message\":\"Deleted successfully\"}".toByteArray(Charsets.UTF_8)
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                out.write("Content-Type: application/json\r\n".toByteArray())
                out.write("Content-Length: ${responseBytes.size}\r\n".toByteArray())
                out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(responseBytes)
                out.flush()
            } else {
                sendErrorResponse(out, 500, "Failed to delete file")
            }
        } catch (e: Exception) {
            Log.e(tag, "Delete failed: ${e.message}")
            sendErrorResponse(out, 500, "Delete failed: ${e.message}")
        }
    }

    private fun handleUploadRequest(inputStream: InputStream, out: BufferedOutputStream, uri: String, contentLength: Long, baseDir: File) {
        val queryParams = getQueryParams(uri)
        val fileName = queryParams["name"] ?: ""
        val relativeSubpath = queryParams["path"] ?: ""

        if (fileName.isEmpty()) {
            sendErrorResponse(out, 400, "Missing file name")
            return
        }

        val targetFile = File(File(baseDir, relativeSubpath), fileName)

        // Security check: Path traversal prevention
        if (!isPathSafe(targetFile, baseDir)) {
            sendErrorResponse(out, 403, "Access Denied")
            return
        }

        Log.d(tag, "Receiving upload: $fileName -> ${targetFile.absolutePath}")

        try {
            // Ensure parent directory exists
            targetFile.parentFile?.mkdirs()

            val fileOut = FileOutputStream(targetFile)
            val buffer = ByteArray(8192)
            var bytesRemaining = contentLength
            while (bytesRemaining > 0) {
                val toRead = Math.min(buffer.size.toLong(), bytesRemaining).toInt()
                val read = inputStream.read(buffer, 0, toRead)
                if (read == -1) break
                fileOut.write(buffer, 0, read)
                bytesRemaining -= read
            }
            fileOut.flush()
            fileOut.close()

            Log.d(tag, "Upload complete: $fileName")
            val response = "{\"success\":true,\"message\":\"File uploaded successfully\"}"
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            
            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            out.write("Content-Type: application/json\r\n".toByteArray())
            out.write("Content-Length: ${responseBytes.size}\r\n".toByteArray())
            out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(responseBytes)
            out.flush()
        } catch (e: Exception) {
            Log.e(tag, "Upload failed for $fileName: ${e.message}")
            sendErrorResponse(out, 500, "Internal Server Error: ${e.message}")
        }
    }

    private fun getQueryParams(uri: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val parts = uri.split("?")
        if (parts.size > 1) {
            val query = parts[1]
            val pairs = query.split("&")
            for (pair in pairs) {
                val index = pair.indexOf("=")
                if (index > 0) {
                    try {
                        val key = URLDecoder.decode(pair.substring(0, index), "UTF-8")
                        val value = URLDecoder.decode(pair.substring(index + 1), "UTF-8")
                        params[key] = value
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to decode query param: ${e.message}")
                    }
                }
            }
        }
        return params
    }

    private fun getExtension(filename: String): String {
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot > 0) filename.substring(lastDot + 1).lowercase(Locale.ENGLISH) else ""
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes == 0L) return "0 Bytes"
        val k = 1024L
        val sizes = arrayOf("Bytes", "KB", "MB", "GB")
        val i = Math.floor(Math.log(bytes.toDouble()) / Math.log(k.toDouble())).toInt()
        return String.format(Locale.ENGLISH, "%.2f %s", bytes / Math.pow(k.toDouble(), i.toDouble()), sizes[i])
    }

    private fun createItemJson(name: String, type: String, relPath: String, size: Long, formattedSize: String, ext: String, mime: String, subtitlePath: String? = null, posterPath: String? = null): String {
        val cleanName = escapeJsonString(name)
        val cleanPath = escapeJsonString(relPath)
        val subPathPart = if (subtitlePath != null) ",\"subtitlePath\":\"${escapeJsonString(subtitlePath)}\"" else ""
        val posterPathPart = if (posterPath != null) ",\"posterPath\":\"${escapeJsonString(posterPath)}\"" else ""
        return "{\"name\":\"$cleanName\",\"type\":\"$type\",\"relativePath\":\"$cleanPath\",\"size\":$size,\"sizeFormatted\":\"$formattedSize\",\"extension\":\"$ext\",\"mimeType\":\"$mime\"$subPathPart$posterPathPart}"
    }

    private fun escapeJsonString(str: String): String {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t")
    }

    private fun handleCastRequest(out: BufferedOutputStream, uri: String) {
        val queryIndex = uri.indexOf("?")
        val path = if (queryIndex != -1) uri.substring(0, queryIndex) else uri
        val queryParams = getQueryParams(uri)

        when {
            path.endsWith("/play") -> {
                CastState.activeVideoPath = queryParams["path"]
                CastState.command = "play"
                CastState.timestamp = System.currentTimeMillis()
                sendJsonStateResponse(out)
            }
            path.endsWith("/control") -> {
                CastState.command = queryParams["command"]
                CastState.timestamp = System.currentTimeMillis()
                sendJsonStateResponse(out)
            }
            path.endsWith("/seek") -> {
                CastState.command = "seek"
                CastState.seekTime = queryParams["time"]?.toDoubleOrNull() ?: 0.0
                CastState.timestamp = System.currentTimeMillis()
                sendJsonStateResponse(out)
            }
            path.endsWith("/change-audio") -> {
                CastState.command = "change-audio"
                CastState.audioTrackIndex = queryParams["index"]?.toIntOrNull() ?: 0
                CastState.timestamp = System.currentTimeMillis()
                sendJsonStateResponse(out)
            }
            path.endsWith("/report") -> {
                CastState.tvCurrentTime = queryParams["currentTime"]?.toDoubleOrNull() ?: 0.0
                CastState.tvDuration = queryParams["duration"]?.toDoubleOrNull() ?: 0.0
                CastState.tvIsPlaying = queryParams["isPlaying"] == "true"
                CastState.tvAudioTracks = queryParams["audioTracks"] ?: ""
                CastState.tvActiveAudioTrack = queryParams["activeAudioTrack"]?.toIntOrNull() ?: 0
                CastState.tvLastReported = System.currentTimeMillis()
                
                val reportResponse = "{\"success\":true}"
                out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                out.write("Content-Type: application/json\r\n".toByteArray())
                out.write("Content-Length: ${reportResponse.toByteArray().size}\r\n".toByteArray())
                out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
                out.write("Connection: close\r\n\r\n".toByteArray())
                out.write(reportResponse.toByteArray())
            }
            path.endsWith("/status") -> {
                sendJsonStateResponse(out)
            }
            else -> {
                sendErrorResponse(out, 404, "Not Found")
            }
        }
    }

    private fun sendJsonStateResponse(out: BufferedOutputStream) {
        val pathVal = if (CastState.activeVideoPath == null) "null" else "\"${escapeJsonString(CastState.activeVideoPath!!)}\""
        val cmdVal = if (CastState.command == null) "null" else "\"${CastState.command}\""
        
        // Build JSON representation of TV reported audio tracks list
        val tracksList = if (CastState.tvAudioTracks.isEmpty()) "[]" else {
            "[" + CastState.tvAudioTracks.split(",").joinToString(",") { "\"${escapeJsonString(it)}\"" } + "]"
        }

        val json = """
            {
                "activeVideoPath": $pathVal,
                "command": $cmdVal,
                "seekTime": ${CastState.seekTime},
                "audioTrackIndex": ${CastState.audioTrackIndex},
                "timestamp": ${CastState.timestamp},
                "tvCurrentTime": ${CastState.tvCurrentTime},
                "tvDuration": ${CastState.tvDuration},
                "tvIsPlaying": ${CastState.tvIsPlaying},
                "tvAudioTracks": $tracksList,
                "tvActiveAudioTrack": ${CastState.tvActiveAudioTrack},
                "tvLastReported": ${CastState.tvLastReported}
            }
        """.trimIndent()

        out.write("HTTP/1.1 200 OK\r\n".toByteArray())
        out.write("Content-Type: application/json\r\n".toByteArray())
        out.write("Content-Length: ${json.toByteArray().size}\r\n".toByteArray())
        out.write("Access-Control-Allow-Origin: *\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(json.toByteArray())
    }



    companion object CastState {
        @Volatile var activeVideoPath: String? = null
        @Volatile var command: String? = null
        @Volatile var seekTime: Double = 0.0
        @Volatile var audioTrackIndex: Int = 0
        @Volatile var timestamp: Long = 0
        
        // TV reported status properties
        @Volatile var tvCurrentTime: Double = 0.0
        @Volatile var tvDuration: Double = 0.0
        @Volatile var tvIsPlaying: Boolean = false
        @Volatile var tvAudioTracks: String = "" // comma separated
        @Volatile var tvActiveAudioTrack: Int = 0
        @Volatile var tvLastReported: Long = 0
    }
}
