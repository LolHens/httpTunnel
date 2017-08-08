package org.lolhens.tunnel

import java.net.InetSocketAddress

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props, Stash}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, WebSocketRequest}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.util.ByteString

import scala.io.StdIn
import scala.util.Try

object Tunnel {

  object StreamSignal {

    trait Command

    trait Message

    case object Init extends Message

    case object Ack extends Command

    case object Complete extends Message

    case class Failure(e: Throwable) extends Message

    def SinkWithAck[T](ref: ActorRef): Sink[T, NotUsed] = Sink.actorRefWithAck[T](ref, Init, Ack, Complete, Failure)

    class SourceAckActor[T] extends ActorPublisher[T] {
      var bufferedElem: Option[T] = None
      var lastSender: ActorRef = _

      override def receive: Receive = {
        case Init =>
          sender() ! Ack

        case Complete =>
          onCompleteThenStop()

        case Failure(e) =>
          onErrorThenStop(e)

        case ActorPublisherMessage.Request(_) =>
          for (elem <- bufferedElem) {
            bufferedElem = None
            onNext(elem)
            lastSender ! Ack
          }

        case ActorPublisherMessage.Cancel =>
          context.stop(self)

        case msg: T@unchecked =>
          if (totalDemand > 0) {
            onNext(msg)
            sender() ! Ack
          } else {
            bufferedElem = Some(msg)
            lastSender = sender()
          }
      }
    }

    object SourceAckActor {
      def props[T]: Props = Props[SourceAckActor[T]]
    }

    def SourceWithAck[E]()(implicit materializer: ActorMaterializer): (ActorRef, Source[E, NotUsed]) = {
      val (actorRef, pub) = Source.actorPublisher(SourceAckActor.props[E]).toMat(Sink.asPublisher(false))(Keep.both).run()

      val source = Source.fromPublisher(pub)

      (actorRef, source)
    }
  }

  class WebSocketTcpTunnelServerActor(socketAddress: InetSocketAddress, receiver: ActorRef) extends Actor with Stash {
    override def receive: Receive = {
      case StreamSignal.Init => sender() ! StreamSignal.Ack
      case StreamSignal.Complete =>
      case StreamSignal.Failure(e: Throwable) =>
      case e: Message =>
        //println("TCP TUNNEL " + e)
        val msgSender = sender()

        receiver ! e

        context.become {
          case StreamSignal.Ack =>
            msgSender ! StreamSignal.Ack
            unstashAll()
            context.unbecome()
          case _ => stash()
        }
      // TODO: wait for ack
    }
  }

  object WebSocketTcpTunnelServerActor {
    def props(socketAddress: InetSocketAddress, receiver: ActorRef): Props = Props(new WebSocketTcpTunnelServerActor(socketAddress, receiver))

    def actorRef(socketAddress: InetSocketAddress, receiver: ActorRef)
                (implicit actorRefFactory: ActorRefFactory): ActorRef = actorRefFactory.actorOf(props(socketAddress, receiver))
  }

  val messageToByteString: Flow[Message, ByteString, NotUsed] = Flow[Message].collect {
    case binaryMessage: BinaryMessage =>
      binaryMessage.getStrictData
    /*binaryMessage.getStreamedData.runFold(ByteString.empty, { (last: ByteString, e: ByteString) =>
    last ++ e
  }, materializer)*/
  }

  val byteStringToMessage: Flow[ByteString, Message, NotUsed] = Flow[ByteString].collect {
    case byteString: ByteString => BinaryMessage(byteString)
  }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route: Route =
      get {
        path(Remaining) { p =>
          val hostString :: portString :: _ = (if (p.endsWith("/")) p.dropRight(1) else p).split(":").toList ++ List("")
          val hostOption = Some(hostString.trim).filter(_ != "")
          val portOption = Some(portString.trim).filter(_ != "").flatMap(e => Try(e.toInt).toOption)

          (for {
            host <- hostOption
            port <- portOption
            socketAddress = InetSocketAddress.createUnresolved(host, port)
          } yield {
            println(host + ":" + port)

            //complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
            val (receiver, source) = StreamSignal.SourceWithAck()
            Tcp().outgoingConnection(host, port)

            //handleWebSocketMessages(Flow.fromSinkAndSource(StreamSignal.SinkWithAck(WebSocketTcpTunnelServerActor.actorRef(socketAddress, receiver)), source))
            handleWebSocketMessages(
              messageToByteString
                .via(Tcp().outgoingConnection(host, port).mapError {
                  case e =>
                    println(e)
                    e
                })
                .via(byteStringToMessage)
            )
          }).getOrElse(reject)
        }
      }

    val bindingFuture = Http().bindAndHandle(route, "127.0.0.1", 8080)

    Tcp().bind("127.0.0.1", 1234).runForeach(connection =>
      Http().singleWebSocketRequest(
        WebSocketRequest("ws://localhost:8080/akka.io:80"),
        //Flow[Message]
        messageToByteString
          .via(connection.flow)
          .via(byteStringToMessage)
      )
    )
    /*Http().singleWebSocketRequest(
      WebSocketRequest("ws://localhost:8080/asdf:4"),
      //Flow[Message]
      Flow.fromSinkAndSource(Sink.ignore /*.foreach[Message](println)*/ , Source.repeat[Message](TextMessage("test2")))
    )*/

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return

    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}
