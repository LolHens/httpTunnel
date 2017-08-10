package org.lolhens.tunnel

import akka.actor.{Actor, ActorRef, ActorRefFactory, Props, Stash}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Flow, Sink, Tcp}
import akka.util.ByteString
import monix.execution.atomic.Atomic
import org.lolhens.tunnel.Tunnel.PublisherActor

import scala.concurrent.{Future, Promise}
import scala.io.StdIn

object WaitingTunnelServer extends Tunnel {


  object ConnectionManager {
    val connections = Atomic(Map.empty[(Authority, String), Connection])

    case object Ack

    class ConnectionActor(target: Authority, id: String, onRemove: () => Unit) extends Actor with Stash {
      val tcpStream: Flow[ByteString, ByteString, Any] =
        Flow[ByteString].map { e => println("REQ: " + time + " " + e); e }.via(Tcp().outgoingConnection(target.host.address(), target.port)).map { e => println("RES: " + time + " " + e); e }

      val (tcpInInlet, tcpInOutlet) = actorSource[ByteString]

      tcpInOutlet.via(tcpStream).to(Sink.actorRef(self, ConnectionActor.TcpComplete)).run()

      var lastBuffer: ByteString = ByteString.empty
      var buffer: ByteString = ByteString.empty
      var requestPromise: Option[Promise[ByteString]] = None
      var donePromise: Option[Promise[Unit]] = None

      override def receive: Receive = {
        case ConnectionActor.RequestData(resend, dataPromise) =>
          if (!resend) {
            lastBuffer = buffer
            buffer = ByteString.empty
          }

          if (lastBuffer.nonEmpty)
            dataPromise.success(lastBuffer)
          else
            requestPromise = Some(dataPromise)
          println(id + " " + requestPromise)
        case ConnectionActor.PutData(data, promise) =>
          tcpInInlet ! data
          donePromise = Some(promise)

        case PublisherActor.Ack =>
          donePromise match {
            case Some(promise) =>
              promise.success(())
              donePromise = None

            case None =>
          }

        case ConnectionActor.TcpComplete =>

        case data: ByteString =>
          println("RES akka " + time + " " + data + " " + id + " " + requestPromise)
          requestPromise match {
            case Some(promise) =>
              promise.success(data)
              requestPromise = None

            case None =>
              buffer = buffer ++ data
          }

      }

      override def postStop(): Unit = onRemove()
    }

    object ConnectionActor {
      def props(target: Authority, id: String, onRemove: () => Unit): Props =
        Props(new ConnectionActor(target, id, onRemove))

      def actor(target: Authority, id: String, onRemove: () => Unit)
               (implicit actorRefFactory: ActorRefFactory): ActorRef =
        actorRefFactory.actorOf(props(target, id, onRemove))

      case class RequestData(resend: Boolean, data: Promise[ByteString])

      case class PutData(data: ByteString, done: Promise[Unit])

      case object ResetTimeout

      case object TcpComplete

    }

    class Connection(val target: Authority, val id: String, onRemove: Connection => Unit) {
      val connectionActor: ActorRef = ConnectionActor.actor(target, id, () => onRemove(this))

      def requestData(resend: Boolean): Future[ByteString] = {
        val responsePromise = Promise[ByteString]
        connectionActor ! ConnectionActor.RequestData(resend, responsePromise)
        responsePromise.future
      }

      def putData(data: ByteString): Future[Unit] = {
        val donePromise = Promise[Unit]
        connectionActor ! ConnectionActor.PutData(data, donePromise)
        donePromise.future
      }

      def resetTimeout(): Unit = connectionActor ! ConnectionActor.ResetTimeout
    }

    def get(target: Authority, id: String): Connection = connections.transformAndExtract { connectionMap =>
      val connection: Connection = connectionMap.getOrElse((target, id), new Connection(target, id, { c =>
        connections.transform(connections => connections - ((c.target, c.id)))
      }))
      connection.resetTimeout()
      (connection, connectionMap + ((target, id) -> connection))
    }
  }

  def main(args: Array[String]): Unit = {
    val requestHandler: HttpRequest => Future[HttpResponse] = {
      case HttpRequest(GET, Uri.Path(path), _, HttpEntity.Strict(_, data), _) =>
        val pathParts = path.drop(1).split("/", -1).toList

        println(pathParts)
        (for {
          target <- parseAuthority(pathParts.head)
          direction <- pathParts.lift(1)
          id <- pathParts.lift(2)
          connection = ConnectionManager.get(target, id)
        } yield {
          direction match {
            case "recv" =>
              val AckBytes = ByteString.fromString("ACK")
              val ack: Boolean = data match {
                case AckBytes => true
                case _ => false
              }

              connection.requestData(!ack).map{data =>
                println("RES send " + time + " " + data)
                HttpResponse(entity = HttpEntity.Strict(ContentTypes.`application/octet-stream`, data))
              }
            case "send" => println("send")
              connection.putData(data).map(_ => HttpResponse())
          }
        }).getOrElse(Future.successful(unknownResource))

      case e =>
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
