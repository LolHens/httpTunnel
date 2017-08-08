package org.lolhens.tunnel

import java.net.InetSocketAddress

import akka.NotUsed
import akka.http.scaladsl.model.ws.{BinaryMessage, Message}
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import scala.util.Try

class Tunnel {
  def parseSocketAddress(string: String): Option[InetSocketAddress] = {
    val hostString :: portString :: _ = string.split(":").toList ++ List("")
    val hostOption = Some(hostString.trim).filter(_ != "")
    val portOption = Some(portString.trim).filter(_ != "").flatMap(e => Try(e.toInt).toOption)

    for {
      host <- hostOption
      port <- portOption
    } yield InetSocketAddress.createUnresolved(host, port)
  }

  val messageToByteString: Flow[Message, ByteString, NotUsed] = Flow[Message].collect {
    case binaryMessage: BinaryMessage =>
      binaryMessage.getStrictData
    /*binaryMessage.getStreamedData.fold(ByteString.empty){ (last: ByteString, e: ByteString) =>
    last ++ e
  }.to(Sink.head)(Keep.left)*/
  }

  val byteStringToMessage: Flow[ByteString, Message, NotUsed] = Flow[ByteString].collect {
    case byteString: ByteString => BinaryMessage(byteString)
  }
}
