package org.bread_experts_group

import org.bread_experts_group.http.HTTPMethod
import org.bread_experts_group.http.html.DirectoryListing
import org.bread_experts_group.socket.failquick.FailQuickOutputStream

fun main(args: Array<String>) {
	val (singleArgs, multipleArgs, serverSocket) = getSocket(
		args,
		standardFlags + listOf(
			Flag<String>("get_credential", repeatable = true),
			Flag<String>("directory_listing_color", default = "off"),
			Flag<String>("store", repeatable = true)
		)
	)
	val getCredentialTable = multipleArgs["get_credential"]?.associate {
		val credential = (it as String).split(',')
		credential[0] to (credential[1] to credential[2].toBoolean())
	}
	val color = (singleArgs["directory_listing_color"] as String).let { if (it == "off") null else it }
	DirectoryListing.css = "color:white;background-color:$color"
	val getHead: ServerHandle = { stores, storePath, request, sock ->
		httpServerGetHead(
			stores, storePath, request, FailQuickOutputStream(sock.outputStream),
			getCredentialTable,
			color != null
		)
	}
	staticMain(multipleArgs, serverSocket, mapOf(HTTPMethod.GET to getHead, HTTPMethod.HEAD to getHead))
}