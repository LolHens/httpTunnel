package org.lolhens.tunnel

import java.net.InetSocketAddress

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
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
  lazy val sslConfig: AkkaSSLConfig = AkkaSSLConfig()

  def parseSocketAddress(string: String): Option[InetSocketAddress] = {
    val hostString :: portString :: _ = string.split(":").toList ++ List("")
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
    /*binaryMessage.getStreamedData.fold(ByteString.empty){ (last: ByteString, e: ByteString) =>
    last ++ e
  }.to(Sink.head)(Keep.left)*/
  }

  lazy val byteStringToMessage: Flow[ByteString, Message, NotUsed] = Flow[ByteString].collect {
    case byteString: ByteString => BinaryMessage(byteString)
  }
}
