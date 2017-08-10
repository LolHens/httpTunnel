package org.lolhens.tunnel

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Tcp

import scala.concurrent.Future
import scala.io.StdIn

object ChunkedTunnelServer extends Tunnel {
  def main(args: Array[String]): Unit = {
    val unknownResource = HttpResponse(404, entity = "Unknown resource!")

    val requestHandler: HttpRequest => Future[HttpResponse] = {
      case HttpRequest(GET, Uri.Path(path), _, entity: HttpEntity, _) => Future {
        parseSocketAddress(path.drop(1).split("/", -1)(0)).map { socketAddress =>
          println(socketAddress.getHostString + ":" + socketAddress.getPort)

          HttpResponse(entity = HttpEntity.Chunked.fromData(
            ContentTypes.`application/octet-stream`,
            entity.dataBytes
              .map { e =>
                //val bytes = ByteString.fromByteBuffer(Base64.getDecoder.decode(e.asByteBuffer))
                system.log.debug("REQUEST: " + e)
                e
              }
              .via(Tcp().outgoingConnection(socketAddress.getHostString, socketAddress.getPort))
              //.prepend(Source.single(ByteString.fromString("asdfasdfasdf")))
              .map { e =>
              system.log.debug("RESPONSE: " + e)
              //val bytes = ByteString.fromByteBuffer(Base64.getEncoder.encode(e.asByteBuffer))
              e
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
