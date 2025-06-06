package org.bread_experts_group.static_microserver

import org.bread_experts_group.http.HTTPMethod
import org.bread_experts_group.http.HTTPRangeHeader
import org.bread_experts_group.http.HTTPRequest
import org.bread_experts_group.http.HTTPResponse
import org.bread_experts_group.http.html.DirectoryListing
import org.bread_experts_group.http.html.VirtualFileChannel
import org.bread_experts_group.logging.ColoredLogger
import java.io.File
import java.io.FileReader
import java.io.OutputStream
import java.lang.Exception
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.logging.Level
import kotlin.math.min
import kotlin.text.substringAfter

val unauthorizedHeadersGet = mapOf(
	"WWW-Authenticate" to "Basic realm=\"Access to file GET\", charset=\"UTF-8\"",
)

private val getLogger = ColoredLogger.newLogger("Static Server GET/HEAD")
private val base64Decoder = Base64.getDecoder()

fun checkAuthorization(
	request: HTTPRequest,
	credentials: Map<String, String>
): HTTPResponse? {
	val authorization = request.headers["Authorization"]
	if (authorization == null) {
		getLogger.warning { "No user provided, unauthorized for GET" }
		return HTTPResponse(401, request.version, unauthorizedHeadersGet)
	}
	val pair = base64Decoder.decode(authorization.substringAfter("Basic "))
		.decodeToString()
		.split(':')
	val password = credentials[pair[0]]
	if (password == null || password != pair[1]) {
		getLogger.warning { "\"${pair[0]}\" unauthorized for GET, not a user or wrong password" }
		return HTTPResponse(403, request.version, unauthorizedHeadersGet)
	}
	getLogger.info { "\"${pair[0]}\" authorized." }
	return null
}

fun getFile(
	request: HTTPRequest,
	size: Long,
	channel: SeekableByteChannel,
	out: OutputStream,
	fileName: String,
	addedHeaders: Map<String, String>,
	lastModified: Long = System.currentTimeMillis()
) {
	val modifiedSince = request.headers["If-Modified-Since"]
	val lastModifiedStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(
		ZonedDateTime.ofInstant(
			Instant.ofEpochMilli(lastModified),
			ZoneOffset.UTC
		)
	)
	var transferenceRegion = 0L to size
	val rangeHeader = request.headers["Range"]
	val totalSize = if (rangeHeader != null) {
		val parsed = HTTPRangeHeader.parse(rangeHeader, size)
		transferenceRegion = parsed.ranges.first()
		parsed.totalSize
	} else size
	val headers = buildMap {
		set("Accept-Ranges", "bytes")
		if (rangeHeader != null) {
			set(
				"Content-Range",
				"bytes ${transferenceRegion.first}-${transferenceRegion.second}/$size"
			)
		}
		set("Last-Modified", lastModifiedStr)
		val (mime, download) = mimeMap[fileName.substringAfterLast('.')]
			?: ("application/octet-stream" to DownloadFlag.DOWNLOAD)
		set("Content-Type", mime)
		set(
			"Content-Disposition",
			"${
				if (download == DownloadFlag.DOWNLOAD) "attachment"
				else "inline"
			}; filename=\"$fileName\""
		)
	} + addedHeaders
	if (modifiedSince == lastModifiedStr) {
		HTTPResponse(
			304, request.version, headers, totalSize
		).write(out)
	} else {
		HTTPResponse(
			if (rangeHeader != null) 206 else 200, request.version,
			headers, totalSize
		).write(out)
		if (request.method == HTTPMethod.GET) {
			channel.position(transferenceRegion.first)
			val buffer = ByteBuffer.allocateDirect(1000000)
			var remainder = totalSize
			try {
				while (remainder > 0) {
					buffer.limit(min(buffer.capacity(), min(remainder, Int.MAX_VALUE.toLong()).toInt()))
					val next = channel.read(buffer)
					if (next == -1) break
					buffer.flip()
					val readIn = ByteArray(next)
					buffer.get(readIn)
					out.write(readIn)
					buffer.flip()
					remainder -= next
				}
			} catch (_: SocketException) {
			} catch (e: Exception) {
				getLogger.log(Level.WARNING, e) { "Problem while sending data to client [channel]" }
			}
		}
	}
	channel.close()
}

fun httpServerGetHead(
	stores: List<File>,
	request: HTTPRequest,
	out: OutputStream,
	getCredentials: Map<String, String>? = null,
	directoryListing: Boolean = false
) {
	if (!getCredentials.isNullOrEmpty()) {
		val failResponse = checkAuthorization(request, getCredentials)
		if (failResponse != null) return failResponse.write(out)
	}

	if (request.path.path == '/' + DirectoryListing.directoryListingFile) {
		val style = DirectoryListing.directoryListingStyle.toByteArray()
		getFile(
			request,
			style.size.toLong(),
			VirtualFileChannel(style),
			out,
			DirectoryListing.directoryListingFile,
			emptyMap()
		)
		out.flush()
		return
	}

	val storePath = '.' + request.path.path
	stores.firstOrNull {
		val requestedPath = it.resolve(storePath).canonicalFile
		if (!(requestedPath.canRead() && requestedPath.startsWith(it))) return@firstOrNull false

		val modifierFile = requestedPath.parentFile.resolve("beg_sm_local_modifier.begsm")
		val addedHeaders = mutableMapOf<String, String>()
		if (modifierFile.exists() && modifierFile.canRead()) {
			val reader = FileReader(modifierFile)
			var thisIsSet = false
			for (line in reader.readLines()) {
				if (line.startsWith('#') || line.isBlank()) continue
				if (!thisIsSet) {
					val target = line.substringAfter('=', "<.not.>")
					if (target != requestedPath.name) continue
					thisIsSet = true
					continue
				}
				if (!line.contains(':')) break
				val (headerName, headerValue) = line.split(':', limit = 2)
				addedHeaders[headerName] = headerValue
			}
		}

		if (requestedPath.isFile) {
			getFile(
				request,
				requestedPath.length(),
				FileChannel.open(requestedPath.toPath()),
				out,
				requestedPath.name,
				addedHeaders
			)
			true
		} else if (directoryListing && requestedPath.isDirectory) {
			getLogger.fine { "Directory listing for \"${requestedPath.invariantSeparatorsPath}\"" }
			val locale = request.headers["Accept-Language"]?.let { languageTags ->
				Locale.lookup(
					Locale.LanguageRange.parse(languageTags),
					Locale.getAvailableLocales().toList()
				)
			} ?: Locale.getDefault()
			val (data, hash) = DirectoryListing.getDirectoryListingHTML(
				it, requestedPath,
				locale
			)
			val etag = request.headers["If-None-Match"]
			val wrappedHash = "\"$hash\""
			val headers = mapOf(
				"Content-Type" to "text/html;charset=UTF-8",
				"ETag" to wrappedHash
			) + addedHeaders
			if (etag == "*" || etag?.contains(wrappedHash) == true) {
				HTTPResponse(
					304, request.version, headers
				).write(out)
			} else {
				val encoded = data.encodeToByteArray()
				HTTPResponse(
					200, request.version, headers, encoded.size.toLong()
				).write(out)
				if (request.method == HTTPMethod.GET) out.write(encoded)
			}
			true
		} else false
	} ?: run {
		getLogger.warning { "No found file for \"$storePath\"" }
		HTTPResponse(404, request.version)
			.write(out)
	}
	out.flush()
}