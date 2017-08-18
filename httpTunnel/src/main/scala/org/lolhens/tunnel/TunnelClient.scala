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
    (for {
      tunnelServer <- parseAuthority(args(0))
      targetSocket <- parseAuthority(args(1))
      localPort <- Try(args(2).toInt).toOption
      proxyOption = args.lift(3).flatMap(parseAuthority)
    } yield {
      val server = proxyOption.getOrElse(tunnelServer)
      println(server)

      val tcpServer = Tcp().bind("localhost", localPort)
        .to(Sink.foreach { tcpConnection =>
          val id = UUID.randomUUID().toString

          system.log.info("NEW CONNECTION: " + id)
          val httpConnection = Flow[ByteString]
            .map(data => HttpRequest(
              method = HttpMethods.POST,
              uri = Uri(s"http://${tunnelServer.host}:${tunnelServer.port}/$id/${targetSocket.host}:${targetSocket.port}"),
              headers = List(headers.Host(tunnelServer)),
              entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, data)
            ))
            .via(Http().outgoingConnection(server.host.toString(), server.port))
            .flatMapConcat {
              case HttpResponse(StatusCodes.OK, _, entity: ResponseEntity, _) => entity.dataBytes
              case _ => Source.empty[ByteString]
            }

          val signalHttpResponse = Atomic(false)
          val connectionVeryActive = Atomic(0)
          val connectionActive = Atomic(0)

          Flow[ByteString]
            .map(Some(_))
            .keepAlive(5.millis, { () =>
              if (signalHttpResponse.getAndSet(false)) Some(ByteString.empty)
              else None
            })
            .map { e =>
              connectionVeryActive.set(50)
              connectionActive.set(50)
              e
            }
            .keepAlive(10.millis, { () =>
              val s = connectionVeryActive.transformAndExtract(e => if (e == 0) (0, 0) else (e, e - 1))
              if (s > 0) Some(ByteString.empty) else None
            })
            .keepAlive(100.millis, { () =>
              val s = connectionActive.transformAndExtract(e => if (e == 0) (0, 0) else (e, e - 1))
              if (s > 0) Some(ByteString.empty) else None
            })
            .mapConcat[ByteString](_.toList)
            .keepAlive(1000.millis, () => ByteString.empty)
            .map { e => if (e.nonEmpty) system.log.info("SEND"); e }
            .mapConcat(data => if (data.isEmpty) List(data) else data.grouped(maxHttpPacketSize).toList)
            .map(data =>
              if (data.isEmpty) data
              else LZ4Compressor.compress(data)
            )
            .via(httpConnection)
            .filter(_.nonEmpty)
            .map { e => signalHttpResponse.set(true); e }
            .join {
              Flow[ByteString]
                .map { e => system.log.info("REC " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
                .via(tcpConnection.flow)
                .backpressureTimeout(10.second)
                .filter(_.nonEmpty)
                .map { e => system.log.info("SND " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
            }
            .run()

        }).run().map { e => println(e); e }

      println(s"Server online at tcp://localhost:$localPort/\nPress RETURN to stop...")
      StdIn.readLine()

      tcpServer
        .flatMap(_.unbind())
        .onComplete(_ => system.terminate())
    }).getOrElse {
      println(
        """Parameters:
          |  host:port   tunnelServer
          |  host:port   target
          |  port        localPort
          |  [host:port] proxy""".stripMargin)
    }
  }
}
