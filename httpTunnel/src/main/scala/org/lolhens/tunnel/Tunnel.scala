package org.lolhens.tunnel

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorRefFactory, ActorSystem, Props, Stash}
import akka.event.LoggingAdapter
import akka.http.scaladsl._
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.ActorMaterializer
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import com.typesafe.sslconfig.akka.AkkaSSLConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class Tunnel {
  lazy implicit val system: ActorSystem = ActorSystem("actorsystem")
  lazy implicit val materializer: ActorMaterializer = ActorMaterializer()
  lazy implicit val executionContext: ExecutionContext = system.dispatcher

  lazy val http: HttpConnectionContext = ConnectionContext.noEncryption()
  lazy val https: HttpsConnectionContext = ConnectionContext.https(SSLContext.getDefault, Some(AkkaSSLConfig()))

  val unknownResource = HttpResponse(404, entity = "Unknown resource!")

  def parseAuthority(string: String): Option[Authority] = {
    val hostString :: portString :: _ = string.split(":", -1).toList ++ List("")
    val hostOption = Some(hostString.trim).filter(_ != "")
    val portOption = Some(portString.trim).filter(_ != "").flatMap(e => Try(e.toInt).toOption)

    for {
      host <- hostOption
      port <- portOption
    } yield Authority(Uri.Host(host), port)
  }

  def parseSocketAddress(string: String): Option[InetSocketAddress] =
    parseAuthority(string).map(authority => InetSocketAddress.createUnresolved(authority.host.address(), authority.port))

  lazy val messageToByteString: Flow[Message, ByteString, NotUsed] = Flow[Message].collect {
    case binaryMessage: BinaryMessage =>
      binaryMessage.getStrictData
  }

  lazy val byteStringToMessage: Flow[ByteString, Message, NotUsed] = Flow[ByteString].collect {
    case byteString: ByteString => BinaryMessage(byteString)
  }

  def proxyTransport(proxyHost: String, proxyPort: Int): ClientTransport = new ClientTransport {
    override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)
                          (implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
      Flow[ByteString]
        /*.map { byteString =>
          val lines = byteString.utf8String.split("\r\n|\n", -1).toList
          val firstLine = lines.headOption.getOrElse("")

          if (firstLine.startsWith("GET ") && firstLine.endsWith(" HTTP/1.1")) {
            val newFirstLine = s"GET http://$host${firstLine.drop(4)}"
            val newLines = newFirstLine +: lines.drop(1)

            println(ByteString.fromString(newLines.mkString("\r\n")).utf8String)
            ByteString.fromString(newLines.mkString("\r\n"))
          } else
            byteString
        }*/
        .viaMat(
        ClientTransport.TCP.connectTo(proxyHost, proxyPort, settings)
      )(Keep.right)
  }

  def header(key: String, value: String): HttpHeader =
    HttpHeader.parse(key, value).asInstanceOf[ParsingResult.Ok].header

  def coupling[T]: (Sink[T, NotUsed], Source[T, NotUsed]) = {
    val (inlet, outlet) =
      Source.asSubscriber[T]
        .toMat(Sink.asPublisher(false))(Keep.both)
        .run()

    (Sink.fromSubscriber(inlet), Source.fromPublisher(outlet))
  }

  /*case class MutableConnector[A, B, ACK](flow: Flow[A, B, NotUsed]) {
    private val connectorActor: ActorRef = MutableConnectorActor.actor(flow)

    def appendSource_=(source: Source[A, NotUsed]): Unit = {
      _source = Some(source)
    }

    def setSink_=(sink: (Sink[B, NotUsed], Source[ACK, NotUsed])): Unit = {
      _sink = Some(sink)
    }
  }

  object MutableConnector {

    class MutableConnectorActor[A, B, ACK](flow: Flow[A, B, NotUsed]) extends Actor with Stash {
      val (flowSink, flowSource) = toSinkAndSource(flow)
      val (flowInInlet, flowInOutlet) = actorSource[A]
      flowInOutlet
        .via(flow)
        .to(Sink.actorRefWithAck[B](
          self,
          MutableConnectorActor.FlowInit,
          MutableConnectorActor.FlowAck,
          MutableConnectorActor.FlowComplete,
          MutableConnectorActor.FlowFailure(_)))
        .run()

      var sourceActor: Option[ActorRef] = None

      var sink: Option[(Sink[T, NotUsed], Source[ACK, NotUsed])] = None
      var sinkActor: Option[ActorRef] = None

      var buffer: Option[B] = None

      override def receive: Receive = {
        case appendSource: MutableConnectorActor.AppendSource[T] =>
          val selfSink = Sink.actorRefWithAck[T](self,
            MutableConnectorActor.SourceInit,
            MutableConnectorActor.SourceAck,
            MutableConnectorActor.SourceComplete,
            MutableConnectorActor.SourceFailure(_))

          appendSource.source.to(selfSink).run()

        case setSink: MutableConnectorActor.SetSink[T, ACK] =>
          sink = Some(setSink.sink)

          val (inlet, outlet) = actorSource[T]
          outlet.to(setSink.sink._1).run()
          sinkActor = Some(inlet)

          setSink.sink._2
            .map(_ => MutableConnectorActor.FeedbackAck(setSink.sink))
            .to(Sink.actorRef(self, MutableConnectorActor.Complete))

          sourceActor.foreach { ref =>
            ref ! MutableConnectorActor.Ack
            sourceActor = None
          }

          buffer.foreach { elem =>
            sinkActor.get ! elem
          }

        case MutableConnectorActor.Init =>
          if (sink.nonEmpty)
            sender() ! MutableConnectorActor.Ack
          else
            sourceActor = Some(sender())

        case MutableConnectorActor.Complete =>
          sinkActor.foreach(_ ! PublisherActor.Complete)

        case MutableConnectorActor.Failure(throwable) =>
          sinkActor.foreach(_ ! PublisherActor.Failure(throwable))

        case sourceElem: MutableConnectorActor.SourceElem[A] =>
          buffer = Some(sourceElem.elem)
          sinkActor.get ! sourceElem.elem

        case MutableConnectorActor.FeedbackAck(sinkRef) if sink.contains(sinkRef) =>
          buffer = None
          sourceActor.foreach(_ ! MutableConnectorActor.SourceAck)
      }
    }

    object MutableConnectorActor {
      def props[A, B, ACK](flow: Flow[A, B, NotUsed]): Props = Props[MutableConnectorActor[A, B, ACK](flow)
      ]

      def actor[A, B, ACK](flow: Flow[A, B, NotUsed])
                          (implicit actorRefFactory: ActorRefFactory) = actorRefFactory.actorOf(props[A, B, ACK](flow))

      case class AppendSource[T](source: Source[T, NotUsed])

      case class SetSink[T, ACK](sink: (Sink[T, NotUsed], Source[ACK, NotUsed]))

      case object FlowInit

      case class FlowElem[T](elem: T)

      case object FlowAck

      case object FlowComplete

      case class FlowFailure(throwable: Throwable)

      case object SourceInit

      case class SourceElem[T](elem: T)

      case object SourceAck

      case object SourceComplete

      case class SourceFailure(throwable: Throwable)

      case class FeedbackAck(any: Any)

    }

  }*/

  def toSinkAndSource[A, B](flow: Flow[A, B, NotUsed]): (Sink[A, NotUsed], Source[B, NotUsed]) = {
    val (inlet, outlet) =
      Source.asSubscriber[A]
        .viaMat(flow)(Keep.left)
        .toMat(Sink.asPublisher(false))(Keep.both)
        .run()

    (Sink.fromSubscriber(inlet), Source.fromPublisher(outlet))
  }

  def toFlow[A, B](logic: Source[A, NotUsed] => Source[B, NotUsed]): Flow[A, B, NotUsed] = {
    val (inlet, outlet) = coupling[A]

    val dataIn = logic(outlet)

    Flow.fromSinkAndSource(
      inlet,
      dataIn
    )
  }

  @deprecated
  def singleHttpRequest(host: String, port: Int,
                        request: HttpRequest,
                        transport: ClientTransport = ClientTransport.TCP,
                        connectionContext: ConnectionContext = Http().defaultServerHttpContext,
                        settings: ClientConnectionSettings = ClientConnectionSettings(system),
                        log: LoggingAdapter = system.log): Future[HttpResponse] =
    Source.single(request)
      .via(
        Http().outgoingConnectionUsingTransport(
          host, port,
          transport = transport,
          connectionContext = connectionContext,
          settings = settings,
          log = log
        )
      )
      .toMat(Sink.head)(Keep.right)
      .run()


  class PublisherActor[T] extends ActorPublisher[T] {
    var buffer: Option[(T, ActorRef)] = None

    override def receive: Receive = {
      case ActorPublisherMessage.Cancel =>
        context.stop(self)

      case ActorPublisherMessage.Request(_) =>
        for ((elem, lastSender) <- buffer) {
          onNext(elem)
          lastSender ! PublisherActor.Ack
          buffer = None
        }

      case PublisherActor.Complete =>
        onCompleteThenStop()

      case PublisherActor.Failure(throwable) =>
        onErrorThenStop(throwable)

      case value: T@unchecked =>
        if (totalDemand > 0) {
          onNext(value)
          sender() ! PublisherActor.Ack
        } else
          buffer = Some((value, sender()))
    }
  }

  object PublisherActor {
    def props[T]: Props = Props[PublisherActor[T]]

    trait Message

    trait Command

    case object Ack extends Message

    case object Complete extends Command

    case class Failure(throwable: Throwable) extends Command

  }

  def actorSource[T]: (ActorRef, Source[T, NotUsed]) = {
    val (actorRef, publisher) =
      Source.actorPublisher[T](PublisherActor.props[T])
        .toMat(Sink.asPublisher(false))(Keep.both)
        .run()

    (actorRef, Source.fromPublisher(publisher))
  }
}
