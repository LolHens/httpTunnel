package org.lolhens.tunnel

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.ws.WebSocketRequest
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.{ClientTransport, Http}
import akka.stream.scaladsl.{Flow, Keep, Sink, Tcp}
import akka.util.ByteString

import scala.concurrent.Future
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
              HttpHeader.parse("Proxy-Connection", "keep-alive").asInstanceOf[ParsingResult.Ok].header
              //HttpHeader.parse("Cache-Control", "no-cache").asInstanceOf[ParsingResult.Ok].header,
              //HttpHeader.parse("Pragma", "no-cache").asInstanceOf[ParsingResult.Ok].header,
              //HttpHeader.parse("Accept", "*/*").asInstanceOf[ParsingResult.Ok].header,
              //HttpHeader.parse("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:52.0) Gecko/20100101 Firefox/52.0").asInstanceOf[ParsingResult.Ok].header
            )),
          transport = proxyOption.map(proxy => new ClientTransport {
            override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)
                                  (implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
              Flow[ByteString].map { byteString =>
                val lines = byteString.utf8String.split("\r\n|\n", -1).toList
                val firstLine = lines.headOption.getOrElse("")
                if (firstLine.startsWith("GET ") && firstLine.endsWith(" HTTP/1.1")) {
                  val newFirstLine = s"GET http://$host${firstLine.drop(4)}"
                  val newLines = newFirstLine +: lines.drop(1)
                  println(ByteString.fromString(newLines.mkString("\r\n")).utf8String)
                  ByteString.fromString(newLines.mkString("\r\n"))
                } else
                  byteString
              }.viaMat(ClientTransport.TCP.connectTo(proxy.getHostString, proxy.getPort, settings))(Keep.right)
          }).getOrElse(ClientTransport.TCP),
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
