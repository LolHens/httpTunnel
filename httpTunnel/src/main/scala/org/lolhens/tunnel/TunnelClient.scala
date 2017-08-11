package org.lolhens.tunnel

import java.util.{Base64, UUID}

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Sink, Source, Tcp}
import akka.util.ByteString
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.Atomic

import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.Try

object TunnelClient extends Tunnel {
  def main(args: Array[String]): Unit = {
    for {
      tunnelServer <- parseAuthority(args(0))
      targetSocket <- parseAuthority(args(1))
      localPort <- Try(args(2).toInt).toOption
      proxyOption = args.lift(3).flatMap(parseAuthority)
    } {
      val server = proxyOption.getOrElse(tunnelServer)
      println(server)
      println(server.host.address())
      println(server.host.toString())

      val tcpServer = Tcp().bind("localhost", localPort)
        .to(Sink.foreach { tcpConnection =>
          val id = UUID.randomUUID().toString

          val httpConnection = Flow[ByteString]
            .map(data => HttpRequest(
              method = HttpMethods.POST,
              uri = Uri(s"http://${tunnelServer.host}:${tunnelServer.port}/$id/${targetSocket.host}:${targetSocket.port}"),
              headers = List(headers.Host(tunnelServer)),
              entity = HttpEntity.Strict(ContentTypes.`text/plain(UTF-8)`, ByteString(Base64.getEncoder.encode(data.asByteBuffer)))
            ))
            .via(Http().outgoingConnection(server.host.toString(), server.port))
            .map {
              case HttpResponse(StatusCodes.OK, _, HttpEntity.Strict(_, data), _) => ByteString(Base64.getDecoder.decode(data.asByteBuffer))
              case _ => ByteString.empty
            }

          val httpOutBuffer = Atomic(ByteString.empty)
          val httpInBuffer = Atomic(ByteString.empty)

          val (httpResponseSignalInlet, httpResponseSignalOutlet) = coupling[Unit]
          val (tcpResponseSignalInlet, tcpResponseSignalOutlet) = coupling[Unit]

          httpResponseSignalOutlet
            .alsoTo {
              Flow[Unit]
                .map(_ => httpInBuffer.getAndSet(ByteString.empty))
                .map { e => println("RES " + e); e }
                .via(tcpConnection.flow)
                .map { e => println("REQ " + e); e }
                .alsoTo(
                  Flow[ByteString]
                    .filter(_.nonEmpty)
                    .map(_ => ())
                    .to(tcpResponseSignalInlet)
                )
                .map(data => httpOutBuffer.transform(_ ++ data))
                .to(Sink.ignore)
            }
            .to {
              Flow[Unit]
                .flatMapMerge(2, _ =>
                  Source.tick(100.millis, 100.millis, ()).take(40)
                    .merge(Source.tick(10.millis, 5.millis, ()).take(100))
                )
                .merge(tcpResponseSignalOutlet)
                .merge(Source.tick(0.millis, 1000.millis, ()))
                .map(_ => httpOutBuffer.transformAndExtract(data => (data.take(maxHttpPacketSize), data.drop(maxHttpPacketSize))))
                .via(httpConnection)
                .alsoTo(
                  Flow[ByteString]
                    .filter(_.nonEmpty)
                    .map(_ => ())
                    .to(httpResponseSignalInlet)
                )
                .map(data => httpInBuffer.transform(_ ++ data))
                .to(Sink.ignore)
            }
            .run()

        }).run()

      println(s"Server online at tcp://localhost:$localPort/\nPress RETURN to stop...")
      StdIn.readLine()

      tcpServer
        .flatMap(_.unbind())
        .onComplete(_ => system.terminate())
    }
  }
}
