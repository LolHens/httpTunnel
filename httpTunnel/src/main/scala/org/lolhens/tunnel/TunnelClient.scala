package org.lolhens.tunnel

import java.util.UUID

import akka.NotUsed
import akka.http.scaladsl.{ClientTransport, Http}
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.util.ByteString
import org.lolhens.tunnel.ChunkedTunnelClient.{http, httpStreamingRequest, parseSocketAddress, proxyTransport, system}

import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.{Failure, Success, Try}
import monix.execution.FutureUtils.extensions._

import scala.concurrent.Future

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
            Source.single(false)
              .concat(outlet)
              .map { ack =>
                Http().singleRequest(HttpRequest(
                  uri = Uri(s"http://${server.getHostString}:${server.getPort}/${targetSocket.getHostString}:${targetSocket.getPort}/recv/$uuid"),
                  headers = List(headers.Host(tunnelServer.getHostString, tunnelServer.getPort)),
                  entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString.fromString(if (ack) "ACK" else ""))
                ))
              }
              .flatMapConcat(f => Source.fromFuture(f.materialize))
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
                  headers = List(headers.Host(tunnelServer.getHostString, tunnelServer.getPort))
                ))
              }
              .flatMapConcat(f => Source.fromFuture(f))
              .to(Sink.ignore)

          val tunnel: Flow[ByteString, ByteString, NotUsed] = Flow.fromSinkAndSource(outbox, inbox)

          connection.handleWith(tunnel)
        }).run()
          /*val signals = Source.tick(0.millis, 200.millis, ())
          signals.map{_ =>
            Http().singleRequest(HttpRequest(
              headers = List(headers.Host(tunnelServer.getHostString, tunnelServer.getPort))
            ))
          }

          connection.handleWith(
            Flow[ByteString]
              .map { e =>
                system.log.debug("REQUEST: " + e)
                //val bytes = ByteString.fromByteBuffer(Base64.getEncoder.encode(e.asByteBuffer))
                e
              }
              .via(httpStreamingRequest(
                tunnelServer.getHostString, tunnelServer.getPort,
                HttpRequest(GET, Uri(s"/${socketAddress.getHostString}:${socketAddress.getPort}")),
                transport = proxyOption
                  .map(proxy => proxyTransport(proxy.getHostString, proxy.getPort))
                  .getOrElse(ClientTransport.TCP),
                connectionContext = http
              ))
              .map { e =>
                //val bytes = ByteString.fromByteBuffer(Base64.getDecoder.decode(e.asByteBuffer))
                system.log.debug("RESPONSE: " + e)
                e
              }
          )*/

        println(s"Server online at tcp://localhost:$localPort/\nPress RETURN to stop...")
        StdIn.readLine()

        tcpServer
          .flatMap(_.unbind())
          .onComplete(_ => system.terminate())
      }
    }
}
