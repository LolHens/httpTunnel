package org.lolhens.tunnel

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.http.scaladsl.{ConnectionContext, HttpConnectionContext, HttpsConnectionContext}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.typesafe.sslconfig.akka.AkkaSSLConfig

import scala.concurrent.ExecutionContext
import scala.util.Try

class Tunnel {
  lazy implicit val system: ActorSystem = ActorSystem("actorsystem")
  lazy implicit val materializer: ActorMaterializer = ActorMaterializer()
  lazy implicit val executionContext: ExecutionContext = system.dispatcher

  lazy val http: HttpConnectionContext = ConnectionContext.noEncryption()
  lazy val https: HttpsConnectionContext = ConnectionContext.https(SSLContext.getDefault, Some(AkkaSSLConfig()))

  def parseSocketAddress(string: String): Option[InetSocketAddress] = {
    val hostString :: portString :: _ = string.split(":", -1).toList ++ List("")
    val hostOption = Some(hostString.trim).filter(_ != "")
    val portOption = Some(portString.trim).filter(_ != "").flatMap(e => Try(e.toInt).toOption)

    for {
      host <- hostOption
      port <- portOption
    } yield InetSocketAddress.createUnresolved(host, port)
  }

  lazy val messageToByteString: Flow[Message, ByteString, NotUsed] = Flow[Message].collect {
    case binaryMessage: BinaryMessage =>
      binaryMessage.getStrictData
  }

  lazy val byteStringToMessage: Flow[ByteString, Message, NotUsed] = Flow[ByteString].collect {
    case byteString: ByteString => BinaryMessage(byteString)
  }
}
