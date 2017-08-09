package org.lolhens.tunnel

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.HttpMethods.GET
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, _}
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.http.scaladsl.{ClientTransport, ConnectionContext, Http}
import akka.stream.scaladsl.Flow.fromGraph
import akka.stream.scaladsl.{Flow, GraphDSL, Keep, Sink, Source, Tcp}
import akka.stream.{FlowShape, Graph, SinkShape, SourceShape}
import akka.util.ByteString
import org.reactivestreams.{Processor, Subscriber, Subscription}

import scala.concurrent.Future
import scala.io.StdIn
import scala.util.Try

object TunnelClient extends Tunnel {
  def proxyTransport(proxyHost: String, proxyPort: Int): ClientTransport = new ClientTransport {
    override def connectTo(host: String, port: Int, settings: ClientConnectionSettings)
                          (implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
      Flow[ByteString]
        .map { byteString =>
          val lines = byteString.utf8String.split("\r\n|\n", -1).toList
          val firstLine = lines.headOption.getOrElse("")

          if (firstLine.startsWith("GET ") && firstLine.endsWith(" HTTP/1.1")) {
            val newFirstLine = s"GET http://$host${firstLine.drop(4)}"
            val newLines = newFirstLine +: lines.drop(1)

            println(ByteString.fromString(newLines.mkString("\r\n")).utf8String)
            ByteString.fromString(newLines.mkString("\r\n"))
          } else
            byteString
        }
        .viaMat(
          ClientTransport.TCP.connectTo(proxyHost, proxyPort, settings)
        )(Keep.right)
  }

  def header(key: String, value: String): HttpHeader =
    HttpHeader.parse(key, value).asInstanceOf[ParsingResult.Ok].header


  def fromSinkAndSource[I, O](sink: Graph[SinkShape[I], _], source: Graph[SourceShape[O], _]): Flow[I, O, NotUsed] =
    fromSinkAndSourceMat(sink, source)(Keep.none)

  /**
    * Creates a `Flow` from a `Sink` and a `Source` where the Flow's input
    * will be sent to the Sink and the Flow's output will come from the Source.
    *
    * The `combine` function is used to compose the materialized values of the `sink` and `source`
    * into the materialized value of the resulting [[Flow]].
    */
  def fromSinkAndSourceMat[I, O, M1, M2, M](source1: Graph[SourceShape[I], M1], source2: Graph[SourceShape[O], M2])(combine: (M1, M2) ⇒ M): Flow[I, O, M] =
    fromGraph(GraphDSL.create(source1, source2)(combine) { implicit b ⇒ (in, out) ⇒ FlowShape(in.out, out.out) })


  def toFlow[A, B](logic: Source[A, NotUsed] => Source[B, NotUsed]): Flow[A, B, NotUsed] = {
    Flow.fromProcessor { () =>
      new Processor[A, B] {
        override def onError(t: Throwable) = ???
        override def onComplete() = ???
        override def onNext(t: A) = ???
        override def onSubscribe(s: Subscription) = ???
        override def subscribe(s: Subscriber[_ >: B]) = ???
      }
    }
    Sink.asPublisher(false).mapMaterializedValue(publisher => logic(Source.fromPublisher(publisher)))
    .
  }

  def httpStreamingRequest(host: String, port: Int,
                           request: HttpRequest,
                           transport: ClientTransport = ClientTransport.TCP,
                           connectionContext: ConnectionContext = Http().defaultServerHttpContext,
                           settings: ClientConnectionSettings = ClientConnectionSettings(system),
                           log: LoggingAdapter = system.log): Flow[ByteString, ByteString, NotUsed] = {

    val (dataOutInletActor, dataOutOutletPublisher): ActorRef =
      Source.actorPublisher[ByteString](Props[ACTOR])
        .toMat(Sink.asPublisher(false))(Keep.both)
        .run()

      val streamingRequest = request.withEntity(
        HttpEntity.Chunked.fromData(
          ContentTypes.`application/octet-stream`,
          Source.actorPublisher(dataOutOutletPublisher)
        )
      )

      val dataIn = Source.single(streamingRequest)
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

    Flow.fromSinkAndSource(
      Sink.actorRefWithAck(dataOutInletActor, (), (), (), e => e),
      dataIn
    )
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
          httpStreamingRequest(
            tunnelServer.getHostString, tunnelServer.getPort,
            HttpRequest(GET, Uri./.withPath(Uri.Path(s"${socketAddress.getHostString}:${socketAddress.getPort}"))),
            proxyOption
              .map(proxy => proxyTransport(proxy.getHostString, proxy.getPort))
              .getOrElse(ClientTransport.TCP),
            connectionContext = http
          )
        )
      }).run()

      println(s"Server online at tcp://localhost:${socketAddress.getPort}/\nPress RETURN to stop...")
      StdIn.readLine()

      tcpServer
        .flatMap(_.unbind())
        .onComplete(_ => system.terminate())
    }
  }
}
