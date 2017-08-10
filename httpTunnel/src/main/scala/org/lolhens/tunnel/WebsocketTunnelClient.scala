package org.lolhens.tunnel

import akka.http.scaladsl.model.ws.WebSocketRequest
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
      val tcpServer = Tcp().bind("localhost", localPort).to(Sink.foreach { connection =>
        HttpHelper(Http()).singleWebSocketRequest(
          request = WebSocketRequest(s"wss://$tunnelServer/${socketAddress.getHostString}:${socketAddress.getPort}",
            extraHeaders = List(
              header("Proxy-Connection", "keep-alive")
            )),
          transport = proxyOption
            .map(proxy => proxyTransport(proxy.getHostString, proxy.getPort))
            .getOrElse(ClientTransport.TCP),
          clientFlow = messageToByteString
            .via(connection.flow)
            .via(byteStringToMessage)
        )
      }).run()

      println(s"Server online at tcp://localhost:${socketAddress.getPort}/\nPress RETURN to stop...")
      StdIn.readLine()

      tcpServer
        .flatMap(_.unbind())
        .onComplete(_ => system.terminate())
    }
  }
}
