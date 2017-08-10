package org.lolhens.tunnel

import java.util.UUID

import akka.NotUsed
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Sink, Source, Tcp}
import akka.util.ByteString
import monix.execution.FutureUtils.extensions._

import scala.io.StdIn
import scala.util.{Failure, Success, Try}

object TunnelClient extends Tunnel {
  def main(args: Array[String]): Unit = {
    for {
      tunnelServer <- parseSocketAddress(args(0))
      targetSocket <- parseSocketAddress(args(1))
      localPort <- Try(args(2).toInt).toOption
      proxyOption = args.lift(3).flatMap(parseSocketAddress)
    } {
      val server = proxyOption.getOrElse(tunnelServer)

      val tcpServer = Tcp().bind("localhost", localPort).to(Sink.foreach { connection =>
        val uuid = UUID.randomUUID().toString

        val inbox: Source[ByteString, NotUsed] = {
          val (inlet, outlet) = coupling[Boolean]
          Source.single(true)
            .concat(outlet)
            .map { ack =>
              Http().singleRequest(HttpRequest(
                uri = Uri(s"http://${server.getHostString}:${server.getPort}/${targetSocket.getHostString}:${targetSocket.getPort}/recv/$uuid"),
                headers = List(headers.Host(tunnelServer.getHostString, tunnelServer.getPort)),
                entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString.fromString(if (ack) "ACK" else ""))
              ))
            }
            .flatMapConcat(f => Source.fromFuture(f.materialize))
            .map { e => println(e); e }
            .map {
              case Success(
              HttpResponse(StatusCodes.OK, _,
              HttpEntity.Strict(ContentTypes.`application/octet-stream`, bytes), _)) =>
                bytes

              case Failure(_) =>
                ByteString.empty
            }
            .alsoTo(Flow[ByteString].map(_.nonEmpty).to(inlet))
            .filter(_.nonEmpty)
        }

        val outbox: Sink[ByteString, NotUsed] =
          Flow[ByteString]
            .map { bytes =>
              Http().singleRequest(HttpRequest(
                uri = Uri(s"http://${server.getHostString}:${server.getPort}/${targetSocket.getHostString}:${targetSocket.getPort}/send/$uuid"),
                headers = List(headers.Host(tunnelServer.getHostString, tunnelServer.getPort)),
                entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, bytes)
              ))
            }
            .flatMapConcat(f => Source.fromFuture(f))
            .to(Sink.ignore)

        val tunnel: Flow[ByteString, ByteString, NotUsed] = Flow.fromSinkAndSource(outbox, inbox)

        connection.handleWith(Flow[ByteString].map { e => println("REQ: " + e); e }.via(tunnel).map { e => println("RES: " + e); e })
      }).run()

      println(s"Server online at tcp://localhost:$localPort/\nPress RETURN to stop...")
      StdIn.readLine()

      tcpServer
        .flatMap(_.unbind())
        .onComplete(_ => system.terminate())
    }
  }
}
