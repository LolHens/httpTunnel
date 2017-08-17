package org.lolhens.tunnel

import akka.http.javadsl.model.RequestEntity
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, Source, Tcp}
import akka.util.ByteString
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.Atomic

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.io.StdIn

object TunnelServer extends Tunnel {

  object ConnectionManager {
    private val connections = Atomic(Map.empty[(String, Authority), Connection])

    def get(id: String, authority: Authority): Connection = connections.transformAndExtract { connections =>
      val key = (id, authority)

      connections.get(key)
        .zip(Some(connections)).headOption
        .getOrElse {
          val newConnection = new Connection(id, authority)
          (newConnection, connections + (key -> newConnection))
        }
    }
  }

  class Connection(id: String, target: Authority) {
    private val tcpStream = Tcp().outgoingConnection(target.host.address(), target.port)

    private val httpOutBuffer = Atomic(ByteString.empty)
    private val httpInBuffer =
      Source.queue[ByteString](2, OverflowStrategy.backpressure)
        .filter(_.nonEmpty)
        .map { e => system.log.info("REC " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
        .backpressureTimeout(10.second)
        .via(tcpStream)
        .filter(_.nonEmpty)
        .backpressureTimeout(10.second)
        .map { e => system.log.info("SND " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
        .to(Sink.foreach(data => httpOutBuffer.transform(_ ++ data)))
        .run()

    def push(data: ByteString): Future[Unit] =
      httpInBuffer.offer(data).map(_ => ())

    def pull(): ByteString =
      httpOutBuffer.transformAndExtract(buffer => (buffer.take(maxHttpPacketSize), buffer.drop(maxHttpPacketSize)))
  }

  def main(args: Array[String]): Unit = {
    val requestHandler: HttpRequest => Future[HttpResponse] = {
      case req@HttpRequest(HttpMethods.POST, Uri.Path(path), _, entity: RequestEntity, _) =>
        val pathParts = path.drop(1).split("/", -1).toList
        (for {
          id <- pathParts.headOption
          target <- pathParts.lift(1).flatMap(parseAuthority)
          connection = ConnectionManager.get(id, target)
        } yield for {
          compressedData <- entity.dataBytes
            .limit(maxHttpPacketSize)
            .toMat(Sink.fold(ByteString.empty)(_ ++ _))(Keep.right)
            .run()
          data = {
            if (compressedData.isEmpty) data
            else LZ4Compressor.decompress(compressedData)
          }
          _ <- connection.push(data)
          out = connection.pull()
        } yield {
          HttpResponse(entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, out))
        }).getOrElse {
          system.log.error("ERR1: " + req)
          Future.successful(unknownResource)
        }

      case req =>
        system.log.error("ERR2: " + req)
        Future.successful(unknownResource)
    }

    val httpBindingFuture = Http().bindAndHandleAsync(requestHandler, "0.0.0.0", 8080, connectionContext = http, parallelism = 4)

    println(s"Server online at http://0.0.0.0:8080/\nPress RETURN to stop...")
    StdIn.readLine()

    httpBindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}
