package org.bread_experts_group

import org.bread_experts_group.http.HTTPMethod
import org.bread_experts_group.http.html.DirectoryListing

fun main(args: Array<String>) {
	val (singleArgs, multipleArgs, serverSocket) = getSocket(
		args,
		standardFlags + listOf(
			Flag("directory_listing_color", default = "off"),
			Flag<String>("get_credential", repeatable = true),
			Flag<String>("store", repeatable = true)
		)
	)
	val getCredentialTable = multipleArgs["get_credential"]?.associate {
		val credential = (it as String).split(',', limit = 2)
		credential[0] to credential[1]
	}
	val color = (singleArgs["directory_listing_color"] as String).let { if (it == "off") null else it }
	DirectoryListing.css = "color:white;background-color:$color"
	val getHead: ServerHandle = { stores, request, sock ->
		httpServerGetHead(
			stores, request, sock.outputStream,
			getCredentialTable,
			color != null
		)
	}
	staticMain(multipleArgs, serverSocket, mapOf(HTTPMethod.GET to getHead, HTTPMethod.HEAD to getHead))
}