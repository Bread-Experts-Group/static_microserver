package org.bread_experts_group

import org.bread_experts_group.http.HTTPMethod
import org.bread_experts_group.http.HTTPRangeHeader
import org.bread_experts_group.http.HTTPRequest
import org.bread_experts_group.http.HTTPResponse
import org.bread_experts_group.http.html.DirectoryListing
import org.bread_experts_group.socket.failquick.FailQuickOutputStream
import org.bread_experts_group.socket.writeString
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.logging.Logger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.min

val unauthorizedHeadersGet = mapOf(
	"WWW-Authenticate" to "Basic realm=\"Access to file GET\", charset=\"UTF-8\"",
)
val unauthorizedHeadersList = mapOf(
	"WWW-Authenticate" to "Basic realm=\"Access to directory listing\", charset=\"UTF-8\"",
)

private val getLogger = Logger.getLogger("Static Server GET/HEAD")

@OptIn(ExperimentalEncodingApi::class)
fun httpServerGetHead(
	stores: List<File>,
	storePath: String,
	request: HTTPRequest,
	nOut: FailQuickOutputStream,
	getCredentials: Map<String, Pair<String, Boolean>>? = null,
	directoryListing: Boolean = false
) {
	val canDirectoryList = if (!getCredentials.isNullOrEmpty()) {
		val authorization = request.headers["Authorization"]
		if (authorization == null) {
			getLogger.warning { "No user provided, unauthorized for GET" }
			HTTPResponse(401, request.version, unauthorizedHeadersGet, "")
				.write(nOut)
			return
		}
		val pair = Base64.decode(authorization.substringAfter("Basic "))
			.decodeToString()
			.split(':')
		val password = getCredentials[pair[0]]
		if (password == null || password.first != pair[1]) {
			getLogger.warning { "\"${pair[0]}\" unauthorized for GET, not a user or wrong password" }
			HTTPResponse(403, request.version, unauthorizedHeadersGet, "")
				.write(nOut)
			return
		}
		getLogger.info { "\"${pair[0]}\" authorized." }
		password.second
	} else true

	stores.firstOrNull {
		val requestedPath = it.resolve(storePath).absoluteFile.normalize()
		if (requestedPath.exists()) {
			if (requestedPath.isFile) {
				getLogger.fine { "Found file for \"$storePath\" at \"${requestedPath.canonicalPath}\"" }
				val modifiedSince = request.headers["If-Modified-Since"]
				val lastModifiedStr = DateTimeFormatter.RFC_1123_DATE_TIME.format(
					ZonedDateTime.ofInstant(
						Instant.ofEpochMilli(requestedPath.lastModified()),
						ZoneOffset.UTC
					)
				)
				var transferenceRegion = 0L to requestedPath.length()
				val rangeHeader = request.headers["Range"]
				val size = if (rangeHeader != null) {
					val parsed = HTTPRangeHeader.parse(rangeHeader, requestedPath)
					transferenceRegion = parsed.ranges.first()
					parsed.totalSize
				} else {
					requestedPath.length()
				}
				val headers = buildMap {
					set("Accept-Ranges", "bytes")
					if (rangeHeader != null) {
						set(
							"Content-Range",
							"bytes ${transferenceRegion.first}-${transferenceRegion.second}/${requestedPath.length()}"
						)
					}
					set("Last-Modified", lastModifiedStr)
					val (mime, download) = mimeMap[requestedPath.extension] ?: ("application/octet-stream" to true)
					set("Content-Type", mime)
					set("Content-Disposition", "${if (download) "attachment" else "inline"}; filename=\"${requestedPath.name}\"")
				}
				if (modifiedSince == lastModifiedStr) {
					HTTPResponse(
						304, request.version, headers, size
					).write(nOut)
				} else {
					HTTPResponse(
						if (rangeHeader != null) 206 else 200, request.version,
						headers, size
					).write(nOut)
					if (request.method == HTTPMethod.GET) {
						val stream = FileInputStream(requestedPath)
						stream.channel.position(transferenceRegion.first)
						var length = (transferenceRegion.second - transferenceRegion.first) + 1
						while (length > 0) {
							val truncated = min(length, 1048576).toInt()
							nOut.write(stream.readNBytes(truncated))
							length -= truncated
						}
						stream.close()
					}
				}
				true
			} else if (directoryListing && requestedPath.isDirectory) {
				if (!canDirectoryList) {
					HTTPResponse(403, request.version, unauthorizedHeadersList, "")
						.write(nOut)
					return
				}
				getLogger.fine { "Directory listing for \"${requestedPath.invariantSeparatorsPath}\"" }
				val (data, hash) = DirectoryListing.getDirectoryListingHTML(it, requestedPath)
				val etag = request.headers["If-None-Match"]
				val wrappedHash = "\"$hash\""
				val headers = mapOf(
					"Content-Type" to "text/html;charset=UTF-8",
					"ETag" to wrappedHash
				)
				if (etag == "*" || etag?.contains(wrappedHash) == true) {
					HTTPResponse(
						304, request.version, headers, data.length
					).write(nOut)
				} else {
					HTTPResponse(
						200, request.version, headers, data.length
					).write(nOut)
					if (request.method == HTTPMethod.GET) nOut.writeString(data)
				}
				true
			} else false
		} else false
	} ?: run {
		getLogger.warning { "No found file for \"$storePath\"" }
		HTTPResponse(404, request.version, emptyMap(), "")
			.write(nOut)
	}
}