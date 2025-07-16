package org.bread_experts_group.static_microserver

import org.bread_experts_group.channel.*
import org.bread_experts_group.command_line.ArgumentContainer
import org.bread_experts_group.protocol.http.HTTPMethod
import org.bread_experts_group.protocol.http.HTTPProtocolSelector
import org.bread_experts_group.protocol.http.HTTPRequest
import org.bread_experts_group.protocol.http.HTTPResponse
import org.bread_experts_group.protocol.http.header.HTTPAcceptHeader
import org.bread_experts_group.protocol.http.header.HTTPRangeHeader
import org.bread_experts_group.protocol.http.header.HTTPServerTimingHeader
import org.bread_experts_group.protocol.http.html.DirectoryListing
import org.bread_experts_group.protocol.http.html.VirtualFileChannel
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
import java.util.logging.Logger
import kotlin.io.path.*
import kotlin.time.DurationUnit

val unauthorizedHeadersGet = mapOf(
	"WWW-Authenticate" to "Basic realm=\"Access to file GET\", charset=\"UTF-8\"",
)

private val base64Decoder = Base64.getDecoder()

fun checkAuthorization(
	logger: Logger,
	request: HTTPRequest,
	credentials: Map<String, String>
): HTTPResponse? {
	val authorization = request.headers["authorization"]
	if (authorization == null) {
		logger.warning { "No user provided, unauthorized for GET" }
		return HTTPResponse(request, 401, unauthorizedHeadersGet)
	}
	val pair = base64Decoder.decode(authorization.substringAfter("Basic "))
		.decodeToString()
		.split(':')
	val password = credentials[pair[0]]
	if (password == null || password != pair[1]) {
		logger.warning { "\"${pair[0]}\" unauthorized for GET, not a user or wrong password" }
		return HTTPResponse(request, 403, unauthorizedHeadersGet)
	}
	logger.info { "\"${pair[0]}\" authorized." }
	return null
}

fun getFile(
	mime: String,
	downloadFlag: DownloadFlag,
	selector: HTTPProtocolSelector,
	request: HTTPRequest,
	accept: HTTPAcceptHeader?,
	timings: HTTPServerTimingHeader,
	channel: SeekableByteChannel,
	fileName: String,
	headers: MutableMap<String, String>,
	lastModified: Long,
	arguments: ArgumentContainer
): Boolean = channel.use {
	headers["content-type"] = mime
	if (accept != null) timings.time("mime", "Accept Check") {
		if (accept.accepted(mime) == null) {
			if (arguments.getRequired("server_time")) headers["server-timing"] = timings.toString()
			selector.sendResponse(
				HTTPResponse(
					request, 406,
					headers
				)
			)
			return false
		}
	}

	val modifiedSince: String?
	val lastModifiedStr: String
	val transferenceRegions: List<SeekableByteChannel>
	val rangeHeader: String?
	timings.time("headers", "Compute headers for file req.") {
		modifiedSince = request.headers["if-modified-since"]
		lastModifiedStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(
			ZonedDateTime.ofInstant(
				Instant.ofEpochMilli(lastModified),
				ZoneOffset.UTC
			)
		)
		val vary = mutableSetOf<String>()
		val size = channel.size()
		rangeHeader = request.headers["range"]
		transferenceRegions = if (rangeHeader != null) {
			vary.add("range")
			val parsed = HTTPRangeHeader.parse(rangeHeader, size)
			if (parsed.ranges.size > 1) {
				// TODO: Multipart range requests
				headers["content-range"] = "bytes */$size"
				selector.sendResponse(
					HTTPResponse(
						request, 416,
						headers
					)
				)
				return false
			}
			headers["content-range"] = "bytes ${parsed.ranges.first().first}-${parsed.ranges.first().second}/$size"
			parsed.ranges.map { WindowedSeekableByteChannel(channel, it.first, it.second + 1) }
		} else listOf(channel)
		headers["accept-ranges"] = "bytes"
		headers["last-modified"] = lastModifiedStr
		val asciiEncoder: CharsetEncoder = Charsets.US_ASCII.newEncoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE)
			.replaceWith(byteArrayOf(0x5F))
		val safeName = asciiEncoder.encode(CharBuffer.wrap(fileName)).let {
			val readInto = ByteArray(it.limit())
			it.get(readInto)
			readInto.toString(Charsets.US_ASCII)
		}
		headers["content-disposition"] = "${
			if (downloadFlag == DownloadFlag.DOWNLOAD) "attachment"
			else "inline"
		}; filename=\"$safeName\"; filename*=UTF-8''${URLEncoder.encode(fileName, Charsets.UTF_8)}"
		request.headers["origin"].let {
			if (it != null) {
				vary.add("origin")
				headers["access-control-allow-origin"] = it
			} else headers["access-control-allow-origin"] = "*"
		}
		headers["access-control-allow-methods"] = "GET, HEAD"
		headers["cache-control"] = "max-age=3600, must-revalidate, no-transform"
		if (arguments.getRequired("orb")) {
			headers.putIfAbsent("x-frame-options", "DENY")
			headers.putIfAbsent("referrer-policy", "strict-origin-when-cross-origin")
			headers.putIfAbsent(
				"permissions-policy", "bluetooth=(), ambient-light-sensor=(), attribution-reporting=()" +
						", autoplay=(), browsing-topics=(), camera=(), compute-pressure=(), " +
						", deferred-fetch=(), deferred-fetch-minimal=(), display-capture=(), encrypted-media=()" +
						", fullscreen=(), geolocation=(), gyroscope=(), hid=(), identity-credentials-get=()" +
						", idle-detection=(), local-fonts=(), magnetometer=(), microphone=(), midi=()" +
						", payment=(), picture-in-picture=(), publickey-credentials-create=()" +
						", publickey-credentials-get=(), screen-wake-lock=(), serial=(), storage-access=()" +
						", usb=(), web-share=(), window-management=(), xr-spatial-tracking=(), accelerometer=()" +
						", cross-origin-isolated=(), otp-credentials=(), summarizer=()"
			)
			headers.putIfAbsent(
				"content-security-policy",
				"default-src 'self'; upgrade-insecure-requests; block-all-mixed-content"
			)
			headers.putIfAbsent("cross-origin-embedder-policy", "require-corp")
			headers.putIfAbsent("cross-origin-resource-policy", "same-origin")
			headers.putIfAbsent("cross-origin-opener-policy", "same-origin")
		}
		if (vary.isNotEmpty()) headers["vary"] = vary.joinToString(", ")
	}

	if (arguments.getRequired("server_time")) headers["server-timing"] = timings.toString()
	if (modifiedSince == lastModifiedStr) selector.sendResponse(
		HTTPResponse(request, 304, headers)
	) else selector.sendResponse(
		HTTPResponse(
			request, if (rangeHeader != null) 206 else 200,
			headers,
			if (request.method == HTTPMethod.GET) {
				if (rangeHeader == null) channel
				else SplitSeekableChannel(transferenceRegions)
			} else EmptyChannel
		)
	)

	true
}

private val styleTime = System.currentTimeMillis()

fun httpServerGetHead(
	logger: Logger,
	selector: HTTPProtocolSelector,
	stores: List<Path>,
	request: HTTPRequest,
	getCredentials: Map<String, String>? = null,
	directoryListing: Boolean = false,
	arguments: ArgumentContainer
) {
	request.data.skip()
	val timings = HTTPServerTimingHeader(DurationUnit.MILLISECONDS)
	if (
		timings.time("auth", "GET Authorization") {
			if (!getCredentials.isNullOrEmpty()) {
				val failResponse = checkAuthorization(logger, request, getCredentials)
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
			"text/css;charset=UTF-8",
			DownloadFlag.DISPLAY,
			selector,
			request,
			accept,
			timings,
			VirtualFileChannel(style),
			DirectoryListing.directoryListingFile,
			mutableMapOf(),
			styleTime,
			arguments
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
		val headers = mutableMapOf<String, String>()
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
				headers[headerName] = headerValue
			}
		}

		headers["x-content-type-options"] = "nosniff"
		if (requestedPath.isRegularFile()) {
			val (mime, downloadFlag) = mimeMap[requestedPath.name.substringAfterLast('.')]
				?: ("application/octet-stream" to DownloadFlag.DOWNLOAD)
			getFile(
				mime,
				downloadFlag,
				selector,
				request,
				accept,
				timings,
				FileChannel.open(requestedPath),
				requestedPath.name,
				headers,
				requestedPath.getLastModifiedTime().toMillis(),
				arguments
			)
		} else if (directoryListing && requestedPath.isDirectory()) {
			headers["content-type"] = "text/html;charset=UTF-8"
			if (accept != null && accept.accepted("text/html") == null) {
				selector.sendResponse(HTTPResponse(request, 406, headers))
				return@firstOrNull false
			}
			val locale = timings.time("locale", "Locale Retrieval") {
				request.headers["accept-language"].let { languageTags ->
					if (languageTags != null) {
						headers["vary"] = "accept-language"
						Locale.lookup(
							Locale.LanguageRange.parse(languageTags),
							Locale.getAvailableLocales().toList()
						)
					} else Locale.getDefault()
				}
			}
			val (data, hash) = timings.time("dir", "Directory Listing") {
				logger.fine { "Directory listing for \"${requestedPath.invariantSeparatorsPathString}\"" }
				DirectoryListing.getDirectoryListingHTML(
					it, requestedPath,
					locale
				)
			}
			val wrappedHash = "\"$hash\""
			headers["content-disposition"] = "inline; filename=\"dirList-$hash.html\""
			if (arguments.getRequired("server_time")) headers["server-timing"] = timings.toString()
			headers["etag"] = wrappedHash
			val etag = request.headers["if-none-match"]
			if (etag != null && (etag == "*" || etag.contains(wrappedHash))) selector.sendResponse(
				HTTPResponse(request, 304, headers)
			) else {
				val encoded = data.encodeToByteArray()
				selector.sendResponse(
					HTTPResponse(
						request, 200, headers,
						if (request.method == HTTPMethod.GET) ByteArrayChannel(encoded)
						else EmptyChannel
					)
				)
			}
			true
		} else {
			selector.sendResponse(HTTPResponse(request, 404))
			false
		}
	} ?: run {
		logger.warning { "No found file for \"$storePath\" [${accept?.accepted}]" }
	}
}