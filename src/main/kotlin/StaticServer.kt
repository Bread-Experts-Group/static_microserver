package org.bread_experts_group.static_microserver

import org.bread_experts_group.command_line.ArgumentContainer
import org.bread_experts_group.command_line.Flag
import org.bread_experts_group.command_line.readArgs
import org.bread_experts_group.command_line.stringToInt
import org.bread_experts_group.http.*
import org.bread_experts_group.logging.ColoredHandler
import org.bread_experts_group.stream.FailQuickInputStream
import java.io.IOException
import java.net.*
import java.nio.file.Path
import java.util.logging.Level
import kotlin.io.path.Path

val standardFlags = listOf(
	Flag(
		"ip", "The IP address on which to listen to for requests.",
		default = "0.0.0.0"
	),
	Flag(
		"port", "The TCP port on which to listen to for requests.",
		default = 80, conv = stringToInt(0..65535)
	)
)

private val socketLogger = ColoredHandler.newLogger("Static Server Socket Retrieval")
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
): Pair<ArgumentContainer, ServerSocket> {
	socketLogger.fine("Argument read")
	val arguments = readArgs(
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
			arguments.getRequired<String>("ip"),
			arguments.getRequired("port")
		)
	)
	socketLogger.finer("Return")
	return Pair(arguments, serverSocket)
}

typealias ServerHandle = (
	selector: HTTPProtocolSelector,
	stores: List<Path>,
	request: HTTPRequest,
	sock: Socket
) -> Unit

private val mainLogger = ColoredHandler.newLogger("Static Server Main")
fun staticMain(
	arguments: ArgumentContainer,
	serverSocket: ServerSocket,
	methods: Map<HTTPMethod, ServerHandle>
) {
	Thread.currentThread().name = "Static Main"
	mainLogger.info("Server loop (${serverSocket.localSocketAddress})")
	val stores = arguments.getsRequired<String>("store").map { Path(it).toRealPath() }
	while (true) {
		val sock = serverSocket.accept()
		sock.keepAlive = true
		val selector = HTTPProtocolSelector(
			HTTPVersion.HTTP_1_1,
			FailQuickInputStream(sock.inputStream),
			sock.outputStream,
			true
		)
		Thread.ofVirtual().name("Static ${sock.remoteSocketAddress}").start {
			val localLogger = ColoredHandler.newLogger("${sock.remoteSocketAddress}")
			try {
				while (true) selector.nextRequest().onSuccess { request ->
					val method = methods[request.method]
					if (method != null) method.invoke(
						selector,
						stores,
						request,
						sock
					) else selector.sendResponse(
						HTTPResponse(
							request,
							405
						)
					)
				}.onFailure {
					if (it !is FailQuickInputStream.EndOfStream)
						localLogger.log(Level.SEVERE, it) { "Error while reading request" }
					break
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