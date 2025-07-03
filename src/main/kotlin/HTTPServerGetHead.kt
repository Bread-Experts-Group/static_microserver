package org.bread_experts_group.static_microserver

import org.bread_experts_group.http.HTTPMethod
import org.bread_experts_group.http.HTTPProtocolSelector
import org.bread_experts_group.http.HTTPRequest
import org.bread_experts_group.http.HTTPResponse
import org.bread_experts_group.http.header.HTTPAcceptHeader
import org.bread_experts_group.http.header.HTTPRangeHeader
import org.bread_experts_group.http.header.HTTPServerTimingHeader
import org.bread_experts_group.http.html.DirectoryListing
import org.bread_experts_group.http.html.VirtualFileChannel
import org.bread_experts_group.logging.ColoredHandler
import org.bread_experts_group.stream.BufferedByteChannelInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.io.path.*
import kotlin.time.DurationUnit

val unauthorizedHeadersGet = mapOf(
	"WWW-Authenticate" to "Basic realm=\"Access to file GET\", charset=\"UTF-8\"",
)

private val getLogger = ColoredHandler.newLogger("Static Server GET/HEAD")
private val base64Decoder = Base64.getDecoder()

fun checkAuthorization(
	request: HTTPRequest,
	credentials: Map<String, String>
): HTTPResponse? {
	val authorization = request.headers["authorization"]
	if (authorization == null) {
		getLogger.warning { "No user provided, unauthorized for GET" }
		return HTTPResponse(request, 401, unauthorizedHeadersGet)
	}
	val pair = base64Decoder.decode(authorization.substringAfter("Basic "))
		.decodeToString()
		.split(':')
	val password = credentials[pair[0]]
	if (password == null || password != pair[1]) {
		getLogger.warning { "\"${pair[0]}\" unauthorized for GET, not a user or wrong password" }
		return HTTPResponse(request, 403, unauthorizedHeadersGet)
	}
	getLogger.info { "\"${pair[0]}\" authorized." }
	return null
}

fun blankStream(n: Long) = object : InputStream() {
	override fun available(): Int = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
	override fun read(): Int = -1
}

fun getFile(
	selector: HTTPProtocolSelector,
	request: HTTPRequest,
	accept: HTTPAcceptHeader?,
	timings: HTTPServerTimingHeader,
	size: Long,
	channel: SeekableByteChannel,
	fileName: String,
	addedHeaders: Map<String, String>,
	lastModified: Long
): Boolean = channel.use {
	val (_, download, baseHeaders) = timings.time("mime", "MIME & Accept Check") {
		val (mime, download) = mimeMap[fileName.substringAfterLast('.')]
			?: ("application/octet-stream" to DownloadFlag.DOWNLOAD)
		val baseHeaders = addedHeaders + ("content-type" to mime)

		if (accept != null && accept.accepted(mime) == null) {
			selector.sendResponse(
				HTTPResponse(
					request, 406,
					baseHeaders + ("server-timing" to timings.asString())
				)
			)
			return false
		}
		Triple(mime, download, baseHeaders)
	}

	val modifiedSince: String?
	val lastModifiedStr: String
	var transferenceRegion: Pair<Long, Long>
	val rangeHeader: String?
	val totalSize: Long
	val headers: Map<String, String>
	timings.time("headers", "Compute headers for file req.") {
		modifiedSince = request.headers["if-modified-since"]
		lastModifiedStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(
			ZonedDateTime.ofInstant(
				Instant.ofEpochMilli(lastModified),
				ZoneOffset.UTC
			)
		)
		transferenceRegion = 0L to size - 1
		rangeHeader = request.headers["range"]
		totalSize = if (rangeHeader != null) {
			val parsed = HTTPRangeHeader.parse(rangeHeader, size)
			transferenceRegion = parsed.ranges.first()
			parsed.totalSize
		} else size
		headers = buildMap {
			set("accept-ranges", "bytes")
			if (rangeHeader != null) {
				set(
					"Content-Range",
					"bytes ${transferenceRegion.first}-${transferenceRegion.second}/$size"
				)
			}
			set("last-modified", lastModifiedStr)
			val asciiEncoder: CharsetEncoder = Charsets.US_ASCII.newEncoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE)
				.replaceWith(byteArrayOf(0x5F))
			val safeName = asciiEncoder.encode(CharBuffer.wrap(fileName)).let {
				val readInto = ByteArray(it.limit())
				it.get(readInto)
				readInto.toString(Charsets.US_ASCII)
			}
			set(
				"content-disposition",
				"${
					if (download == DownloadFlag.DOWNLOAD) "attachment"
					else "inline"
				}; filename=\"$safeName\"; filename*=UTF-8''${URLEncoder.encode(fileName, Charsets.UTF_8)}"
			)
		} + baseHeaders
	}

	val timedHeaders = headers + ("server-timing" to timings.asString())
	if (modifiedSince == lastModifiedStr) selector.sendResponse(
		HTTPResponse(request, 304, timedHeaders, blankStream(totalSize))
	) else {
		selector.sendResponse(
			HTTPResponse(
				request, if (rangeHeader != null) 206 else 200,
				timedHeaders,
				if (request.method == HTTPMethod.GET) BufferedByteChannelInputStream(
					channel,
					listOf(transferenceRegion)
				) else blankStream(totalSize)
			)
		)
	}

	true
}

private val styleTime = System.currentTimeMillis()

fun httpServerGetHead(
	selector: HTTPProtocolSelector,
	stores: List<Path>,
	request: HTTPRequest,
	getCredentials: Map<String, String>? = null,
	directoryListing: Boolean = false
) {
	val timings = HTTPServerTimingHeader(DurationUnit.MILLISECONDS)
	if (
		timings.time("auth", "GET Authorization") {
			if (!getCredentials.isNullOrEmpty()) {
				val failResponse = checkAuthorization(request, getCredentials)
				if (failResponse != null) {
					selector.sendResponse(failResponse)
					return@time true
				}
			}
			false
		}
	) return

	val accept = request.headers["accept"]?.let { HTTPAcceptHeader.parse(it) }
	if (request.path.path == '/' + DirectoryListing.directoryListingFile) {
		val style = DirectoryListing.directoryListingStyle.toByteArray()
		getFile(
			selector,
			request,
			accept,
			timings,
			style.size.toLong(),
			VirtualFileChannel(style),
			DirectoryListing.directoryListingFile,
			emptyMap(),
			styleTime
		)
		return
	}

	val storePath = Path('.' + request.path.path)
	stores.firstOrNull {
		val requestedPath = it.resolve(storePath).normalize()
		if (!(requestedPath.isReadable() && requestedPath.startsWith(it))) {
			selector.sendResponse(HTTPResponse(request, 404))
			return@firstOrNull false
		}

		val modifierFile = requestedPath.parent.resolve("beg_sm_local_modifier.begsm")
		val addedHeaders = mutableMapOf<String, String>()
		if (modifierFile.exists() && modifierFile.isReadable()) {
			val reader = modifierFile.reader()
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

		if (requestedPath.isRegularFile()) {
			getFile(
				selector,
				request,
				accept,
				timings,
				requestedPath.fileSize(),
				FileChannel.open(requestedPath),
				requestedPath.name,
				addedHeaders,
				requestedPath.getLastModifiedTime().toMillis()
			)
		} else if (directoryListing && requestedPath.isDirectory()) {
			val baseHeaders = mapOf("content-type" to "text/html;charset=UTF-8") + addedHeaders
			if (accept != null && accept.accepted("text/html") == null) {
				selector.sendResponse(HTTPResponse(request, 406, baseHeaders))
				return@firstOrNull false
			}
			val locale = timings.time("locale", "Locale Retrieval") {
				request.headers["accept-language"]?.let { languageTags ->
					Locale.lookup(
						Locale.LanguageRange.parse(languageTags),
						Locale.getAvailableLocales().toList()
					)
				} ?: Locale.getDefault()
			}
			val (data, hash) = timings.time("dir", "Directory Listing") {
				getLogger.fine { "Directory listing for \"${requestedPath.invariantSeparatorsPathString}\"" }
				DirectoryListing.getDirectoryListingHTML(
					it, requestedPath,
					locale
				)
			}
			val wrappedHash = "\"$hash\""
			val headers = baseHeaders + mapOf(
				"content-disposition" to "inline; filename=\"dirList-$hash.html\"",
				"server-timing" to timings.asString(),
				"etag" to wrappedHash
			)
			val etag = request.headers["if-none-match"]
			if (etag != null && (etag == "*" || etag.contains(wrappedHash))) selector.sendResponse(
				HTTPResponse(request, 304, headers)
			) else {
				val encoded = data.encodeToByteArray()
				selector.sendResponse(
					HTTPResponse(
						request, 200, headers,
						if (request.method == HTTPMethod.GET) encoded.inputStream()
						else blankStream(encoded.size.toLong())
					)
				)
			}
			true
		} else {
			selector.sendResponse(HTTPResponse(request, 404))
			false
		}
	} ?: run {
		getLogger.warning { "No found file for \"$storePath\" [${accept?.accepted}]" }
	}
}