package org.lolhens.tunnel

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._

object StreamNumbers extends App {
  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()

  val route =
    path("numbers") {
      get {
        complete {
          HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, Source.repeat(1).scan(0) { (last, e) =>
            last + e
          }
            .map(item => ByteString.fromString(item.toString + "\n"))))
        }
      }
    }

  //val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

  val requestHandler: HttpRequest => HttpResponse = {
    case HttpRequest(GET, Uri.Path("/test"), _, entity: HttpEntity, _) =>
      HttpResponse(entity = HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, entity.dataBytes))
  }

//val bind = Http().bindAndHandleAsync()


  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  Console.readLine()

  import system.dispatcher // for the future transformations
  /*bindingFuture
    .flatMap(_.unbind()) // trigger unbinding from the port
    .onComplete(_ ⇒ system.shutdown()) // and shutdown when done
*/
}
