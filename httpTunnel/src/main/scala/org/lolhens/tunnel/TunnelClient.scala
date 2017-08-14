package org.lolhens.tunnel

import java.util.UUID

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ThrottleMode
import akka.stream.scaladsl.{Flow, Sink, Source, Tcp}
import akka.util.ByteString
import monix.execution.Scheduler.Implicits.global

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

          //val lastReq = Atomic(None: Option[HttpRequest])

          val httpConnection = Flow[ByteString]
            .map(data => HttpRequest(
              method = HttpMethods.POST,
              uri = Uri(s"http://${tunnelServer.host}:${tunnelServer.port}/$id/${targetSocket.host}:${targetSocket.port}"),
              headers = List(headers.Host(tunnelServer)),
              entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, data)
            ))
            //.map { e => lastReq.set(Some(e)); e }
            .via(Http().outgoingConnection(server.host.toString(), server.port))
            .flatMapConcat {
              case HttpResponse(StatusCodes.OK, _, entity: ResponseEntity, _) => entity.dataBytes
              case r =>
                //println("ERR3: " + r + "   " + lastReq.get)
                Source.empty[ByteString]
            }

          val (httpResponseSignalInlet, httpResponseSignalOutlet) = coupling[Unit]

          Flow[ByteString]
            .map { e => println("signal1 " + id); e }
            .merge(httpResponseSignalOutlet.map(_ => ByteString.empty).map { e => println("signal2 " + id); e })
            .flatMapMerge(2, e => Source.single(e).concat(
              Source.tick(10.millis, 10.millis, ByteString.empty).take(20)
                .concat(Source.tick(0.millis, 100.millis, ByteString.empty).take(20))
                .concat(Source.tick(0.millis, 500.millis, ByteString.empty).take(10))
            ))
            .batch(Int.MaxValue, e => e)((last, e) => last ++ e)
            .throttle(1, 10.millis, 1, ThrottleMode.Shaping)
            .map { e => println("signal " + id); e }
            .keepAlive(2000.millis, () => ByteString.empty)
            //.map { e => println("signal " + id); e }
            .via(httpConnection)
            .filter(_.nonEmpty)
            .map { e => println("received " + e.size); e }
            .alsoTo(Flow[ByteString].map(_ => ()).to(httpResponseSignalInlet))
            .join {
              Flow[ByteString]
                .map { e => println("RES " + time + " " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
                .via(tcpConnection.flow)
                .filter(_.nonEmpty)
                .map { e => println("REQ " + time + " " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
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
