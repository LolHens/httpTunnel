package org.lolhens.tunnel

import java.net.InetSocketAddress

import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.{get, handleWebSocketMessages, path, reject, _}
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.Tcp

import scala.io.StdIn

object WebsocketTunnelServer extends Tunnel {
  val whitelist: Set[InetSocketAddress] = Set(
    InetSocketAddress.createUnresolved("lolhens.de", 3306)
  )

  def main(args: Array[String]): Unit = {
    val route: Route =
      get {
        path(Remaining) { p =>
          parseSocketAddress(p.split("/", -1)(0))
            .filter(whitelist.contains)
            .map { socketAddress =>
              println(socketAddress.getHostString + ":" + socketAddress.getPort)

              handleWebSocketMessages(
                messageToByteString
                  .via(Tcp().outgoingConnection(socketAddress.getHostString, socketAddress.getPort).recover {
                    case e =>
                      println(e)
                      throw e
                  })
                  .via(byteStringToMessage)
              )
            }.getOrElse {
            println("Rejected request to " + p)
            reject
          }
        }
      }

    //val httpsBindingFuture = Http().bindAndHandle(route, "0.0.0.0", 8443, connectionContext = https)
    val httpBindingFuture = Http().bindAndHandle(route, "0.0.0.0", 8080, connectionContext = http)

    println(s"Server online at http://0.0.0.0:8080/\nPress RETURN to stop...")
    StdIn.readLine()

    /*httpsBindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())*/

    httpBindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}
