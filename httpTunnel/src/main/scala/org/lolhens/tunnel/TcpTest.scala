package org.lolhens.tunnel

import akka.stream.scaladsl.{Flow, Sink, Tcp}
import akka.util.ByteString

object TcpTest extends Tunnel {
  def main(args: Array[String]): Unit = {
    for {
      socketAddress <- parseSocketAddress(args(0))
    } {
      Tcp().bind("0.0.0.0", 1234).to(Sink.foreach { connection =>
        connection.handleWith(
          Flow[ByteString]
            .map { e =>
              println("REQUEST")
              println(e.utf8String)
              e
            }
            .via(Tcp().outgoingConnection(socketAddress.getHostString, socketAddress.getPort))
            .map { e =>
              println("RESPONSE")
              println(e.utf8String)
              e
            }
        )
      }).run()
    }
  }
}
