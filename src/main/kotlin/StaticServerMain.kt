package org.bread_experts_group.static_microserver

import org.bread_experts_group.command_line.Flag
import org.bread_experts_group.http.HTTPMethod
import org.bread_experts_group.http.html.DirectoryListing

val staticFlags = listOf(
	Flag(
		"directory_listing_color",
		"The CSS background color the directory listing view will show. \"off\" disables the view.",
		default = "off"
	),
	Flag(
		"get_credential",
		"A credential required to access files or directory listings in a store.",
		repeatable = true,
		conv = {
			val (user, passphrase) = it.split(',', limit = 2)
			user to passphrase
		}
	),
	Flag(
		"store",
		"A folder which the server uses to search for files. The first stores are of higher precedence.",
		repeatable = true,
		required = 1
	)
)

fun main(args: Array<String>) {
	val (arguments, serverSocket) = getSocket(
		args,
		"static_microserver",
		"Distribution of software for Bread Experts Group static file servers.",
		staticFlags
	)
	val color = arguments.getRequired<String>("directory_listing_color").let { if (it == "off") null else it }
	DirectoryListing.css = "color:white;background-color:$color"
	val getHead: ServerHandle = { selector, stores, request, sock ->
		httpServerGetHead(
			selector, stores, request,
			arguments.gets<Pair<String, String>>("get_credential")?.toMap(),
			color != null
		)
	}
	staticMain(arguments, serverSocket, mapOf(HTTPMethod.GET to getHead, HTTPMethod.HEAD to getHead))
}