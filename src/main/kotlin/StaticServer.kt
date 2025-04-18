package org.bread_experts_group

import org.bread_experts_group.http.HTTPMethod
import org.bread_experts_group.http.HTTPRequest
import org.bread_experts_group.http.HTTPResponse
import org.bread_experts_group.socket.failquick.FailQuickInputStream
import org.bread_experts_group.socket.failquick.FailQuickOutputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.logging.Logger

val standardFlags = listOf(
	Flag<String>("ip", default = "0.0.0.0"),
	Flag<Int>("port", default = 443, conv = ::stringToInt)
)

private val socketLogger = Logger.getLogger("Static Server Socket Retrieval")
fun getSocket(
	args: Array<String>,
	vararg flags: Flag<*>
) = getSocket(args, flags.toList())

fun getSocket(
	args: Array<String>,
	flags: List<Flag<*>>
): Triple<SingleArgs, MultipleArgs, ServerSocket> {
	socketLogger.fine("Argument read")
	val (singleArgs, multipleArgs) = readArgs(args, standardFlags + flags)
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

typealias ServerHandle = (stores: List<File>, storePath: String, request: HTTPRequest, sock: Socket) -> Unit

private val mainLogger = Logger.getLogger("Static Server Main")
fun staticMain(
	multipleArgs: MultipleArgs,
	serverSocket: ServerSocket,
	methods: Map<HTTPMethod, ServerHandle>
) {
	Thread.currentThread().name = "Static-Main"
	mainLogger.info("Server loop (${serverSocket.localSocketAddress})")
	val stores = multipleArgs["store"]?.map { File(it as String).absoluteFile.normalize() } ?: emptyList()
	while (true) {
		val sock = serverSocket.accept()
		val fqIn = FailQuickInputStream(sock.inputStream)
		val fqOut = FailQuickOutputStream(sock.outputStream)
		Thread.ofVirtual().name("Static-${sock.remoteSocketAddress}").start {
			try {
				while (true) {
					val request = HTTPRequest.read(fqIn)
					val storePath = request.path.substring(1)
					val method = methods[request.method]
					if (method != null) method.invoke(stores, storePath, request, sock)
					else HTTPResponse(405, request.version, emptyMap(), "")
						.write(fqOut)
				}
			} catch (_: EOFException) {
			}
			fqOut.close()
			fqIn.close()
			sock.close()
		}
	}
}