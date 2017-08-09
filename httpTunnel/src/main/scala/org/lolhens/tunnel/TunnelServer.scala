package org.lolhens.tunnel

import java.util.Base64

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Tcp
import akka.util.ByteString

import scala.concurrent.Future
import scala.io.StdIn

object TunnelServer extends Tunnel {
  def main(args: Array[String]): Unit = {
    val unknownResource = HttpResponse(404, entity = "Unknown resource!")

    val requestHandler: HttpRequest => Future[HttpResponse] = {
      case HttpRequest(GET, Uri.Path(path), _, entity: HttpEntity, _) => Future {
        parseSocketAddress((if (path.endsWith("/")) path.dropRight(1) else path).drop(1)).map { socketAddress =>
          println(socketAddress.getHostString + ":" + socketAddress.getPort)

          HttpResponse(entity = HttpEntity.Chunked.fromData(
            ContentTypes.`text/plain(UTF-8)`,
            entity.dataBytes
              .map { e =>
                val bytes = ByteString.fromByteBuffer(Base64.getDecoder.decode(e.asByteBuffer))
                system.log.debug("REQUEST: " + bytes)
                bytes
              }
              .via(Tcp().outgoingConnection(socketAddress.getHostString, socketAddress.getPort))
              .map { e =>
                system.log.debug("RESPONSE: " + e)
                ByteString.fromByteBuffer(Base64.getEncoder.encode(e.asByteBuffer))
              }
          ))
        }.getOrElse(unknownResource)
      }

      case _ =>
        Future.successful(unknownResource)
    }

    val httpBindingFuture = Http().bindAndHandleAsync(requestHandler, "0.0.0.0", 8080, connectionContext = http)

    println(s"Server online at http://0.0.0.0:8080/\nPress RETURN to stop...")
    StdIn.readLine()

    httpBindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}
