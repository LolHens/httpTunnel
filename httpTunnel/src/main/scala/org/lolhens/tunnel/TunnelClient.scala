package org.lolhens.tunnel

import java.util.UUID

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

          val lastReq = Atomic(None: Option[HttpRequest])

          val httpConnection = Flow[ByteString]
            .map(data => HttpRequest(
              method = HttpMethods.POST,
              uri = Uri(s"http://${tunnelServer.host}:${tunnelServer.port}/$id/${targetSocket.host}:${targetSocket.port}"),
              headers = List(headers.Host(tunnelServer)),
              entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, data)
            ))
            .map { e => lastReq.set(Some(e)); e }
            .via(Http().outgoingConnection(server.host.toString(), server.port))
            .flatMapConcat {
              case HttpResponse(StatusCodes.OK, _, entity: ResponseEntity, _) => entity.dataBytes
              case r =>
                println("ERR3: " + r + "   " + lastReq.get)
                Source.empty[ByteString]
            }

          val httpOutBuffer = Atomic(ByteString.empty)
          val httpInBuffer = Atomic(ByteString.empty)

          val (httpResponseSignalInlet, httpResponseSignalOutlet) = coupling[Unit]
          val (tcpResponseSignalInlet, tcpResponseSignalOutlet) = coupling[Unit]

          httpResponseSignalOutlet
            .alsoTo {
              Flow[Unit]
                .map(_ => httpInBuffer.getAndSet(ByteString.empty))
                .map { e => println("RES " + time + " " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
                .via(tcpConnection.flow)
                .map { e => println("REQ " + time + " " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
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
                //.map(_ => println("sig1"))
                .flatMapMerge(2, _ =>
                Source.tick(250.millis, 50.millis, ()).take(50)
                  .merge(Source.tick(10.millis, 5.millis, ()).take(50))
              )
                .merge(tcpResponseSignalOutlet /*.map(_ => println("sig2"))*/)
                .merge(Source.tick(0.millis, 200.millis, ()) /*.map(_ => println("sig3"))*/)
                .map(_ => httpOutBuffer.transformAndExtract(data => (data.take(maxHttpPacketSize), data.drop(maxHttpPacketSize))))
                .via(httpConnection)
                .map{data => httpInBuffer.transform(_ ++ data); data}
                .to(
                  Flow[ByteString]
                    .filter(_.nonEmpty)
                      .map{e => println("received length: " + e.size)}
                    .map(_ => ())
                    .to(httpResponseSignalInlet)
                )
            }
            .run()

        }).run().map { e => println(e); e }

      println(s"Server online at tcp://localhost:$localPort/\nPress RETURN to stop...")
      StdIn.readLine()

      tcpServer
        .flatMap(_.unbind())
        .onComplete(_ => system.terminate())
    }
  }
}
