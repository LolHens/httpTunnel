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

object WTunnelClient extends Tunnel {
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

          val atomicTryReceive = Atomic(0)

          val atomicReceivedPackets = Atomic(0L)

          val averageInterval = 500
          val atomicLastAveraged = Atomic(System.currentTimeMillis())
          val atomicLastReceiveRate = Atomic(0D)

          val atomicLastSent = Atomic(System.currentTimeMillis())

          val Signal = Some(ByteString.empty)

          Flow[ByteString]
            .map { e => atomicTryReceive.set(5); e }
            .map(Some(_))
            .keepAlive(5.millis, { () =>
              if (atomicTryReceive.getAndTransform(e => if (e == 0) 0 else e - 1) > 0) Signal
              else None
            })
            .filter(_.nonEmpty)
            .keepAlive(5.millis, { () =>
              val time = System.currentTimeMillis()

              val lastAverageDelta = atomicLastAveraged.transformAndExtract { lastAveraged =>
                val delta = time - lastAveraged
                if (delta >= averageInterval) (delta, time)
                else (delta, lastAveraged)
              }

              val receiveRate: Double =
                if (lastAverageDelta >= averageInterval) {
                  val receiveRate = atomicReceivedPackets.getAndSet(0).toDouble / lastAverageDelta
                  atomicLastReceiveRate.set(receiveRate)
                  receiveRate
                } else {
                  val lastReceiveRate = atomicLastReceiveRate.get
                  val receiveRate = atomicReceivedPackets.get.toDouble / lastAverageDelta
                  (lastReceiveRate * (averageInterval - lastAverageDelta) +
                    receiveRate * lastAverageDelta) / averageInterval
                }

              val sendInterval = (1D / (receiveRate * 4)).toInt

              if (atomicLastSent.transformAndExtract(lastSent =>
                if (lastSent >= sendInterval) (true, time) else (false, lastSent)
              )) Signal
              else None
            })
            .filter(_.nonEmpty)
            .map(_.get)
            .keepAlive(1000.millis, () => ByteString.empty)
            .map { e => if (e.nonEmpty) system.log.debug("SEND"); e }
            .mapConcat(data => if (data.isEmpty) List(data) else data.grouped(maxHttpPacketSize).toList)
            .map(data =>
              if (data.isEmpty) data
              else LZ4Compressor.compress(data)
            )
            .via(httpConnection)
            .filter(_.nonEmpty)
            .map { e => atomicReceivedPackets.increment(); e }
            .join {
              Flow[ByteString]
                .map { e => system.log.debug("REC " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
                .via(tcpConnection.flow)
                .backpressureTimeout(10.second)
                .filter(_.nonEmpty)
                .map { e => system.log.debug("SND " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
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
