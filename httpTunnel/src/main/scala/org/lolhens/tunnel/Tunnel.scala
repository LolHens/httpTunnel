package org.lolhens.tunnel

import java.net.InetSocketAddress
import java.util.Base64
import javax.net.ssl.SSLContext

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl._
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.ActorMaterializer
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

  val maxHttpPacketSize = 12000

  def toBase64(data: ByteString): ByteString = ByteString(Base64.getEncoder.encode(data.asByteBuffer))
  def fromBase64(data: ByteString): ByteString = ByteString(Base64.getDecoder.decode(data.asByteBuffer))

  def time: String = (((System.currentTimeMillis() - Tunnel.firstTime) / 10L).toDouble / 100D).toString

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
      Flow[ByteString].viaMat(ClientTransport.TCP.connectTo(proxyHost, proxyPort, settings))(Keep.right)
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
}

object Tunnel {
  lazy val firstTime: Long = System.currentTimeMillis()
}
