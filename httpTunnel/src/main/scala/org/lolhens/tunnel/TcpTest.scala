package org.lolhens.tunnel

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Tcp}
import akka.util.ByteString

object TcpTest {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("actorsystem")
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    Tcp().bind("0.0.0.0", 1234).to(Sink.foreach { connection =>
      connection.handleWith(
        Flow[ByteString]
          .map { e =>
            println("REQUEST")
            println(e.utf8String)
            e
          }
          .via(Tcp().outgoingConnection("...", 8080))
          .map { e =>
            println("RESPONSE")
            println(e.utf8String)
            e
          }
      )
    }).run()
  }
}
