package org.lolhens.tunnel

import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, _}
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.{ClientTransport, ConnectionContext, Http}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, Tcp}
import akka.util.ByteString

import scala.concurrent.Future
import scala.io.StdIn
import scala.util.Try

object TunnelClient extends Tunnel {
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

  def toFlow[A, B](logic: Source[A, NotUsed] => Source[B, NotUsed]): Flow[A, B, NotUsed] = {
    val (dataOutInlet, dataOutOutlet) =
      Source.asSubscriber[A]
        .toMat(Sink.asPublisher(false))(Keep.both)
        .run()

    val dataIn = logic(Source.fromPublisher(dataOutOutlet))

    Flow.fromSinkAndSource(
      Sink.fromSubscriber(dataOutInlet),
      dataIn
    )
  }

  def httpStreamingRequest(host: String, port: Int,
                           request: HttpRequest,
                           transport: ClientTransport = ClientTransport.TCP,
                           connectionContext: ConnectionContext = Http().defaultServerHttpContext,
                           settings: ClientConnectionSettings = ClientConnectionSettings(system),
                           log: LoggingAdapter = system.log): Flow[ByteString, ByteString, NotUsed] = toFlow { source =>
    val streamingRequest = request.withEntity(
      HttpEntity.Chunked.fromData(
        ContentTypes.`application/octet-stream`,
        source
      )
    )

    Source.single(streamingRequest)
      .via(
        Http().outgoingConnectionUsingTransport(
          host, port,
          transport = transport,
          connectionContext = connectionContext,
          settings = settings,
          log = log
        )
      )
      .flatMapConcat(_.entity.dataBytes)
  }

  def main(args: Array[String]): Unit = {
    for {
      tunnelServer <- parseSocketAddress(args(0))
      socketAddress <- parseSocketAddress(args(1))
      localPort <- Try(args(2).toInt).toOption
      proxyOption = args.lift(3).flatMap(parseSocketAddress)
    } {
      val tcpServer = Tcp().bind("localhost", localPort).to(Sink.foreach { connection =>
        connection.handleWith(
          Flow[ByteString]
            .map { e => println("REQUEST: " + e); e }
            .via(httpStreamingRequest(
              tunnelServer.getHostString, tunnelServer.getPort,
              HttpRequest(GET, Uri(s"/${socketAddress.getHostString}:${socketAddress.getPort}")),
              proxyOption
                .map(proxy => proxyTransport(proxy.getHostString, proxy.getPort))
                .getOrElse(ClientTransport.TCP),
              connectionContext = http
            ))
            .map { e => println("RESPONSE: " + e); e }
        )
      }).run()

      println(s"Server online at tcp://localhost:$localPort/\nPress RETURN to stop...")
      StdIn.readLine()

      tcpServer
        .flatMap(_.unbind())
        .onComplete(_ => system.terminate())
    }
  }
}
