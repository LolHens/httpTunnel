package org.lolhens.tunnel

import akka.http.javadsl.model.RequestEntity
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Keep, Sink, Tcp}
import akka.util.ByteString
import monix.execution.atomic.Atomic

import scala.concurrent.Future
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
    private val httpOutBuffer = Atomic(ByteString.empty)
    private val httpInBuffer = Atomic(ByteString.empty)

    val (tcpOutSignalInlet, tcpOutSignalOutlet) = actorSource[Unit]

    def push(data: ByteString): Unit = {
      httpInBuffer.transform(_ ++ data)
      tcpOutSignalInlet ! ()
    }

    def pull(): ByteString = httpOutBuffer.transformAndExtract(data => (data.take(maxHttpPacketSize), data.drop(maxHttpPacketSize)))

    private val tcpStream = Tcp().outgoingConnection(target.host.address(), target.port)

    tcpOutSignalOutlet
      .map(_ => httpInBuffer.getAndSet(ByteString.empty))
      .filter(_.nonEmpty)
      .map { e => println("REQ " + time + " " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
      .via(tcpStream)
      .map { e => println("RES " + time + " " + id + " " + e.size + ":" + toBase64(e).utf8String); e }
      .map(data => httpOutBuffer.transform(_ ++ data))
      .to(Sink.ignore)
      .run()
  }

  def main(args: Array[String]): Unit = {
    val requestHandler: HttpRequest => Future[HttpResponse] = {
      case req@HttpRequest(HttpMethods.POST, Uri.Path(path), _, entity: RequestEntity, _) =>
        val pathParts = path.drop(1).split("/", -1).toList
        (for {
          id <- pathParts.headOption
          target <- pathParts.lift(1).flatMap(parseAuthority)
          connection = ConnectionManager.get(id, target)
        } yield
          entity.dataBytes
            .limit(maxHttpPacketSize)
            .toMat(Sink.fold(ByteString.empty)(_ ++ _))(Keep.right)
            .run()
            .map { data =>
              connection.push(data)
              val out = connection.pull()
              HttpResponse(entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, out))
            }
          ).getOrElse {
          Future.successful(unknownResource)
        }

      case e =>
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
