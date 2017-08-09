package org.lolhens.tunnel

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Tcp

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
            ContentTypes.`application/octet-stream`,
            entity.dataBytes
              .map { e => system.log.debug("REQUEST: " + e); e }
              .via(Tcp().outgoingConnection(socketAddress.getHostString, socketAddress.getPort))
              .map { e => system.log.debug("RESPONSE: " + e); e }
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
