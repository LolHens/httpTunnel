package org.lolhens.tunnel

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.WebSocketRequest
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Tcp}

import scala.concurrent.ExecutionContext
import scala.io.StdIn

object TunnelClient extends Tunnel {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("actorsystem")
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContext = system.dispatcher

    parseSocketAddress(args.mkString(" ")) match {
      case Some(socketAddress) =>
        val tcpServer = Tcp().bind("127.0.0.1", socketAddress.getPort).to(Sink.foreach(connection =>
          Http().singleWebSocketRequest(
            WebSocketRequest(s"ws://localhost:8080/${socketAddress.getHostString}:${socketAddress.getPort}"),
            messageToByteString
              .via(connection.flow)
              .via(byteStringToMessage)
          )
        )).run()

        println(s"Server online at tcp://localhost:${socketAddress.getPort}/\nPress RETURN to stop...")
        StdIn.readLine()

        tcpServer
          .flatMap(_.unbind())
          .onComplete(_ => system.terminate())

      case None =>
    }
  }
}
