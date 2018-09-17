package org.lolhens.tunnel

import akka.http.scaladsl.model.ws.WebSocketRequest
import akka.http.scaladsl.settings.{ClientConnectionSettings, ConnectionPoolSettings}
import akka.http.scaladsl.{ClientTransport, Http}
import akka.stream.scaladsl.{Sink, Tcp}

import scala.io.StdIn
import scala.util.Try

object WebsocketTunnelClient extends Tunnel {
  def main(args: Array[String]): Unit = {
    for {
      tunnelServer <- Some(args(0))
      socketAddress <- parseSocketAddress(args(1))
      localPort <- Try(args(2).toInt).toOption
      proxyOption = args.lift(3).flatMap(parseSocketAddress)
    } {
      val settings = proxyOption match {
        case Some(proxy) =>
          val proxyTransport = ClientTransport.httpsProxy(proxy)
          ClientConnectionSettings(system).withTransport(proxyTransport)

        case None =>
          ClientConnectionSettings(system)
      }

      val tcpServer = Tcp().bind("localhost", localPort).to(Sink.foreach { connection =>
        //println(connection)
        val webSocket = Http().singleWebSocketRequest(
          settings = settings,
          request = WebSocketRequest(s"wss://$tunnelServer/${socketAddress.getHostString}:${socketAddress.getPort}",
            extraHeaders = List(
              header("Proxy-Connection", "keep-alive")
            )),
          clientFlow = messageToByteString
            //.map { bytes => println("recv " + bytes.utf8String); bytes }
            .via(connection.flow)
            //.map { bytes => println("send " + bytes.utf8String); bytes }
            .via(byteStringToMessage)
        )._1
        webSocket.foreach(println)
      }).run()

      println(s"Server online at tcp://localhost:${socketAddress.getPort}/\nPress RETURN to stop...")
      StdIn.readLine()

      tcpServer
        .flatMap(_.unbind())
        .onComplete(_ => system.terminate())
    }
  }
}
