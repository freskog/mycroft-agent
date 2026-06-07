package dev.freskog.agent.person.cli

import dev.freskog.agent.common.AgentError

import zio._

import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI, URLDecoder}
import java.nio.charset.StandardCharsets

/** Minimal localhost callback server for Gmail OAuth (one-shot). */
object OAuthCallback {

  def waitForCode(port: Int, path: String, timeoutSeconds: Int = 120): IO[AgentError, String] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking {
        val socket = new ServerSocket()
        socket.setReuseAddress(true)
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        socket.setSoTimeout(timeoutSeconds * 1000)
        socket
      }.mapError(t => AgentError.HttpFailed(
        s"Could not bind OAuth callback port $port on 127.0.0.1: ${t.getMessage}. " +
          s"Kill any stale 'person gmail auth' process or choose another port via GMAIL_REDIRECT_URI.",
        Some(t)
      ))
    )(s => ZIO.attempt(s.close()).ignore) { socket =>
      ZIO.attemptBlocking {
        val conn   = socket.accept()
        val req    = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()))
        val line   = req.readLine()
        val query  = Option(line).flatMap(l => l.split(" ").lift(1)).getOrElse("")
        val params = parseQuery(query)
        val code   = params.getOrElse("code", "")
        val err    = params.get("error")
        val body   =
          if (code.nonEmpty) "<html><body><h2>Gmail connected.</h2><p>You can close this tab.</p></body></html>"
          else s"<html><body><h2>Authorization failed</h2><p>${err.getOrElse("missing code")}</p></body></html>"
        val resp =
          s"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.getBytes(StandardCharsets.UTF_8).length}\r\nConnection: close\r\n\r\n$body"
        conn.getOutputStream.write(resp.getBytes(StandardCharsets.UTF_8))
        conn.close()
        socket.close()
        if (code.nonEmpty) Right(code)
        else Left(err.getOrElse("OAuth callback missing code"))
      }.mapError(t => AgentError.HttpFailed(s"OAuth callback: ${t.getMessage}", Some(t)))
        .flatMap {
          case Right(code) => ZIO.succeed(code)
          case Left(msg)   => ZIO.fail(AgentError.Validation(msg))
        }
    }

  def openBrowser(url: String): UIO[Unit] =
    ZIO.attempt {
      val desktop = java.awt.Desktop.getDesktop
      if (desktop.isSupported(java.awt.Desktop.Action.BROWSE))
        desktop.browse(URI.create(url))
    }.ignore

  private def parseQuery(raw: String): Map[String, String] = {
    val q = raw.indexOf('?')
    if (q < 0) Map.empty
    else
      raw.drop(q + 1).split("&").flatMap { pair =>
        pair.split("=", 2) match {
          case Array(k, v) => Some(decode(k) -> decode(v))
          case _           => None
        }
      }.toMap
  }

  private def decode(s: String): String =
    URLDecoder.decode(s, StandardCharsets.UTF_8)
}
