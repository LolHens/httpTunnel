package org.lolhens.tunnel

import akka.NotUsed
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.{HttpRequest, _}
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.{ClientTransport, ConnectionContext, Http}
import akka.stream.scaladsl.{Flow, Sink, Source, Tcp}
import akka.util.ByteString

import scala.io.StdIn
import scala.util.Try

object ChunkedTunnelClient extends Tunnel {
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
      .map { e =>
        log.debug("INCOMING ENTITY: " + e + " " + e.entity)
        e
      }
      .flatMapConcat(_.entity.dataBytes)
      .map { e =>
        log.debug("INCOMING: " + e)
        e
      }
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
            .map { e =>
              system.log.debug("REQUEST: " + e)
              //val bytes = ByteString.fromByteBuffer(Base64.getEncoder.encode(e.asByteBuffer))
              e
            }
            .via(httpStreamingRequest(
              tunnelServer.getHostString, tunnelServer.getPort,
              HttpRequest(GET, Uri(s"/${socketAddress.getHostString}:${socketAddress.getPort}")),
              transport = proxyOption
                .map(proxy => proxyTransport(proxy.getHostString, proxy.getPort))
                .getOrElse(ClientTransport.TCP),
              connectionContext = http
            ))
            .map { e =>
              //val bytes = ByteString.fromByteBuffer(Base64.getDecoder.decode(e.asByteBuffer))
              system.log.debug("RESPONSE: " + e)
              e
            }
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
