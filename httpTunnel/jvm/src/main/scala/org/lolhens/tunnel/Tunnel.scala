package org.lolhens.tunnel

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}

import scala.io.StdIn

object Tunnel {

  object StreamSignal {

    trait Command

    trait Message

    case object Init extends Message

    case object Ack extends Command

    case object Complete extends Message

    case class Failure(e: Throwable) extends Message

    def SinkWithAck[T](ref: ActorRef): Sink[T, NotUsed] = Sink.actorRefWithAck[T](ref, Init, Ack, Complete, Failure)
  }

  class WebSocketActor extends Actor {
    override def receive: Receive = {
      case StreamSignal.Init => sender() ! StreamSignal.Ack
      case StreamSignal.Complete =>
      case StreamSignal.Failure(e: Throwable) =>
      case e =>
        println(e)
        sender() ! StreamSignal.Ack
    }
  }

  object WebSocketActor {
    def props: Props = Props[WebSocketActor]

    def actorRef(implicit actorRefFactory: ActorRefFactory): ActorRef = actorRefFactory.actorOf(props)
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route: Route =
      get {
        //complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
        handleWebSocketMessages(Flow.fromSinkAndSource(StreamSignal.SinkWithAck(WebSocketActor.actorRef), Source.single[Message](TextMessage("test"))))
      }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    Http().singleWebSocketRequest(
      WebSocketRequest("ws://localhost:8080"),
      Flow[Message]
      //Flow.fromSinkAndSource(Sink.foreach[Message](println), Source.single[Message](TextMessage("test2")))
    )

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return

    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}
