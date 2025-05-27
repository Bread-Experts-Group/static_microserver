package org.bread_experts_group.static_microserver

import org.bread_experts_group.Flag
import org.bread_experts_group.MultipleArgs
import org.bread_experts_group.SingleArgs
import org.bread_experts_group.http.HTTPMethod
import org.bread_experts_group.http.HTTPRequest
import org.bread_experts_group.http.HTTPResponse
import org.bread_experts_group.http.HTTPVersion
import org.bread_experts_group.logging.ColoredLogger
import org.bread_experts_group.readArgs
import org.bread_experts_group.stringToInt
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URISyntaxException

val standardFlags = listOf(
	Flag(
		"ip", "The IP address on which to listen to for requests.",
		default = "0.0.0.0"
	),
	Flag(
		"port", "The TCP port on which to listen to for requests.",
		default = 80, conv = ::stringToInt
	)
)

private val socketLogger = ColoredLogger.newLogger("Static Server Socket Retrieval")
fun getSocket(
	args: Array<String>,
	projectName: String,
	projectUsage: String,
	vararg flags: Flag<*>
) = getSocket(args, projectName, projectUsage, flags.toList())

fun getSocket(
	args: Array<String>,
	projectName: String,
	projectUsage: String,
	flags: List<Flag<*>>
): Triple<SingleArgs, MultipleArgs, ServerSocket> {
	socketLogger.fine("Argument read")
	val (singleArgs, multipleArgs) = readArgs(
		args,
		standardFlags + flags,
		projectName,
		projectUsage
	)
	socketLogger.finer("Socket retrieval")
	val serverSocket = ServerSocket()
	socketLogger.fine("Socket bind")
	serverSocket.bind(
		InetSocketAddress(
			singleArgs["ip"] as String,
			singleArgs["port"] as Int
		)
	)
	socketLogger.finer("Return")
	return Triple(singleArgs, multipleArgs, serverSocket)
}

typealias ServerHandle = (stores: List<File>, request: HTTPRequest, sock: Socket) -> Unit

private val mainLogger = ColoredLogger.newLogger("Static Server Main")
fun staticMain(
	multipleArgs: MultipleArgs,
	serverSocket: ServerSocket,
	methods: Map<HTTPMethod, ServerHandle>
) {
	Thread.currentThread().name = "Static Main"
	mainLogger.info("Server loop (${serverSocket.localSocketAddress})")
	val stores = multipleArgs.getValue("store").map { File(it as String).absoluteFile.normalize() }
	while (true) {
		val sock = serverSocket.accept()
		sock.keepAlive = true
		sock.soTimeout = 60000
		sock.setSoLinger(true, 2)
		Thread.ofVirtual().name("Static ${sock.remoteSocketAddress}").start {
			val localLogger = ColoredLogger.newLogger("${sock.remoteSocketAddress}")
			try {
				while (true) {
					val request = try {
						HTTPRequest.read(sock.inputStream)
					} catch (_: URISyntaxException) {
						HTTPResponse(404, HTTPVersion.HTTP_1_1).write(sock.outputStream)
						break
					}
					val method = methods[request.method]
					if (method != null) method.invoke(
						stores,
						request,
						sock
					) else {
						HTTPResponse(405, request.version).write(sock.outputStream)
						break
					}
				}
			} catch (_: SocketTimeoutException) {
			} catch (_: SocketException) {
			} catch (e: IOException) {
				localLogger.warning { "IO failure encountered; [${e.javaClass.canonicalName}] ${e.localizedMessage}" }
			} finally {
				sock.close()
			}
		}
	}
}