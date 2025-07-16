package org.bread_experts_group.static_microserver

import org.bread_experts_group.command_line.*
import org.bread_experts_group.logging.ColoredHandler
import org.bread_experts_group.protocol.http.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.net.URISyntaxException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
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
	),
	Flag(
		"orb", "Use the standard BEG security headers.",
		default = false, conv = ::stringToBoolean
	),
	Flag(
		"server_time", "Use the Server-Timing header (primarily for development).",
		default = false, conv = ::stringToBoolean
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
): Pair<ArgumentContainer, ServerSocketChannel> {
	socketLogger.fine("Argument read")
	val arguments = readArgs(
		args,
		standardFlags + flags,
		projectName,
		projectUsage
	)
	socketLogger.finer("Socket retrieval")
	val serverSocket = ServerSocketChannel.open()
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
	sock: SocketChannel,
	arguments: ArgumentContainer
) -> Unit

private val mainLogger = ColoredHandler.newLogger("Static Server Main")
fun staticMain(
	arguments: ArgumentContainer,
	serverSocket: ServerSocketChannel,
	methods: Map<HTTPMethod, ServerHandle>
) {
	Thread.currentThread().name = "Static Main"
	mainLogger.info("Server loop (${serverSocket.localAddress})")
	val stores = arguments.getsRequired<String>("store").map { Path(it).toRealPath() }
	val okayHeaders = methods.keys.joinToString(", ") { it.name }
	while (true) {
		val sock = serverSocket.accept()
		sock.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
		val selector = HTTPProtocolSelector(
			HTTPVersion.HTTP_1_1,
			sock,
			sock,
			true
		)
		Thread.ofVirtual().name("Static ${sock.remoteAddress}").start {
			val localLogger = ColoredHandler.newLogger("${sock.remoteAddress}")
			try {
				while (true) selector.nextRequest().onSuccess { request ->
					val method = methods[request.method]
					if (method != null) method.invoke(
						selector,
						stores,
						request,
						sock,
						arguments
					) else selector.sendResponse(
						HTTPResponse(
							request,
							405,
							mutableMapOf("allow" to okayHeaders)
						)
					)
				}.onFailure {
					if (
						it !is IOException &&
						it !is URISyntaxException
					) localLogger.log(Level.SEVERE, it) { "Error while reading request" }
					break
				}
			} catch (_: IOException) {
			} catch (e: Exception) {
				localLogger.warning { "General failure; [${e.javaClass.canonicalName}] ${e.localizedMessage}" }
			} finally {
				try {
					sock.shutdownOutput()
					sock.shutdownInput()
					sock.close()
				} catch (_: IOException) {
				}
			}
		}
	}
}