package org.lolhens.tunnel

import java.net.InetSocketAddress

import akka.event.LoggingAdapter
import akka.http.scaladsl.Http.OutgoingConnection
import akka.http.scaladsl._
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest, WebSocketUpgradeResponse}
import akka.http.scaladsl.settings.ClientConnectionSettings
import akka.stream.TLSProtocol._
import akka.stream._
import akka.stream.scaladsl._

import scala.concurrent._

case class HttpHelper(httpExt: HttpExt) {
  implicit val system = httpExt.system

  private def _outgoingTlsConnectionLayer(host: String, port: Int,
                                          settings: ClientConnectionSettings,
                                          connectionContext: ConnectionContext,
                                          transport: ClientTransport,
                                          log: LoggingAdapter): Flow[SslTlsOutbound, SslTlsInbound, Future[OutgoingConnection]] = {
    val tlsStage = sslTlsStage(connectionContext, Client, Some(host → port))

    tlsStage.joinMat(transport.connectTo(host, port, settings))(Keep.right)
  }

  /** Creates real or placebo SslTls stage based on if ConnectionContext is HTTPS or not. */
  private def sslTlsStage(connectionContext: ConnectionContext, role: TLSRole, hostInfo: Option[(String, Int)] = None) =
    connectionContext match {
      case hctx: HttpsConnectionContext ⇒ TLS(hctx.sslContext, connectionContext.sslConfig, hctx.firstSession, role, hostInfo = hostInfo)
      case other ⇒ TLSPlacebo() // if it's not HTTPS, we don't enable SSL/TLS
    }

  /**
    * Constructs a flow that once materialized establishes a WebSocket connection to the given Uri.
    *
    * The layer is not reusable and must only be materialized once.
    */
  def webSocketClientFlow(request: WebSocketRequest,
                          connectionContext: ConnectionContext = httpExt.defaultClientHttpsContext,
                          transport: ClientTransport = ClientTransport.TCP,
                          localAddress: Option[InetSocketAddress] = None,
                          settings: ClientConnectionSettings = ClientConnectionSettings(httpExt.system),
                          log: LoggingAdapter = httpExt.system.log): Flow[Message, Message, Future[WebSocketUpgradeResponse]] = {
    import request.uri
    require(uri.isAbsolute, s"WebSocket request URI must be absolute but was '$uri'")

    val ctx = uri.scheme match {
      case "ws" ⇒ ConnectionContext.noEncryption()
      case "wss" if connectionContext.isSecure ⇒ connectionContext
      case "wss" ⇒ throw new IllegalArgumentException("Provided connectionContext is not secure, yet request to secure `wss` endpoint detected!")
      case scheme ⇒
        throw new IllegalArgumentException(s"Illegal URI scheme '$scheme' in '$uri' for WebSocket request. " +
          s"WebSocket requests must use either 'ws' or 'wss'")
    }
    val host = uri.authority.host.address
    val port = uri.effectivePort

    httpExt.webSocketClientLayer(request, settings, log)
      .joinMat(_outgoingTlsConnectionLayer(host, port, settings.withLocalAddressOverride(localAddress), ctx, transport, log))(Keep.left)
  }

  /**
    * Runs a single WebSocket conversation given a Uri and a flow that represents the client side of the
    * WebSocket conversation.
    */
  def singleWebSocketRequest[T](request: WebSocketRequest,
                                clientFlow: Flow[Message, Message, T],
                                connectionContext: ConnectionContext = httpExt.defaultClientHttpsContext,
                                transport: ClientTransport = ClientTransport.TCP,
                                localAddress: Option[InetSocketAddress] = None,
                                settings: ClientConnectionSettings = ClientConnectionSettings(httpExt.system),
                                log: LoggingAdapter = httpExt.system.log)(implicit mat: Materializer): (Future[WebSocketUpgradeResponse], T) =
    webSocketClientFlow(request, connectionContext, transport, localAddress, settings, log)
      .joinMat(clientFlow)(Keep.both).run()
}
