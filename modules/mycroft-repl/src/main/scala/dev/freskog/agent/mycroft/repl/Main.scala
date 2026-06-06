package dev.freskog.agent.mycroft.repl

import zio._
import zio.json._
import zio.json.ast.Json

import org.jline.reader.{EndOfFileException, LineReader, UserInterruptException}
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.{Terminal, TerminalBuilder}

import java.io.{BufferedReader, InputStream, InputStreamReader, PrintWriter}
import java.net.URI
import java.net.http.{HttpClient => JHttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Interactive channel adapter for mycroft, running on a JVM so it can use JLine
 *  for a real terminal UI. It POSTs typed/pasted input to `/inbound` and renders
 *  the `/outbound/stream` SSE events for the current turn.
 *
 *  Rendering is *turn-synchronous*: JLine owns the terminal while reading a line
 *  (so editing, history and bracketed paste work), and only after the line is
 *  submitted do we draw the live "thinking" / "tool" boxes and stream the answer.
 *  The two phases never overlap, so there is a single terminal writer at a time. */
object Main extends ZIOAppDefault {

  final case class Cfg(channel: String, as: String, mycroftUrl: String, register: Boolean)
  final case class Ev(event: String, json: Option[Json])

  /** A LineReader that summarises bracketed pastes. While editing, a multi-line
   *  or long paste is shown as a single-line placeholder (so there is no wall of
   *  text and no `>` continuation prompts); on submit the placeholders are
   *  expanded back to the real pasted text. Small single-line pastes are kept
   *  verbatim. */
  private final class PastingLineReader(terminal: Terminal) extends LineReaderImpl(terminal, "mycroft-repl") {
    private val pastes = scala.collection.mutable.LinkedHashMap.empty[String, String]

    override def beginPaste(): Boolean = {
      val str = doReadStringUntil(LineReaderImpl.BRACKETED_PASTE_END).replace("\r\n", "\n").replace('\r', '\n')
      if (str.contains('\n') || str.length > 200) {
        val n     = pastes.size + 1
        val lines = str.split("\n", -1).length
        val words = str.split("\\s+").count(_.nonEmpty)
        val ph    = s"[paste #$n: $lines lines, $words words]"
        pastes.put(ph, str)
        putString(ph)
      } else putString(str)
      true
    }

    /** Replace any placeholders with their real pasted text and reset for the
     *  next line. */
    def expand(line: String): String = {
      var out = Option(line).getOrElse("")
      pastes.foreach { case (ph, txt) => out = out.replace(ph, txt) }
      pastes.clear()
      out
    }
  }

  private val defaultUrl: String = sys.env.getOrElse("MYCROFT_URL", "http://127.0.0.1:8090")

  // Daemon-threaded HTTP/1.1 client: daemon threads can't keep the JVM alive
  // after we quit; HTTP/1.1 for clean chunked SSE.
  private val client: JHttpClient = JHttpClient.newBuilder()
    .version(JHttpClient.Version.HTTP_1_1)
    .executor(Executors.newCachedThreadPool { (r: Runnable) =>
      val t = new Thread(r, "mycroft-repl-http"); t.setDaemon(true); t
    })
    .build()

  override def run: ZIO[ZIOAppArgs with Scope, Any, Any] =
    for {
      argv <- getArgs
      cfg   = parseArgs(argv.toList)
      rt   <- ZIO.runtime[Any]
      _    <- session(cfg, rt)
    } yield ()

  private def parseArgs(args: List[String]): Cfg = {
    @annotation.tailrec
    def go(rem: List[String], channel: Option[String], as: String, url: String, register: Boolean): Cfg =
      rem match {
        case "--channel" :: v :: t     => go(t, Some(v), as, url, register)
        case "--as" :: v :: t          => go(t, channel, v, url, register)
        case "--mycroft-url" :: v :: t => go(t, channel, as, v, register)
        case "--register" :: t         => go(t, channel, as, url, register = true)
        case _ :: t                    => go(t, channel, as, url, register)
        case Nil                       => Cfg(channel.getOrElse("repl"), as, url, register)
      }
    go(args, None, "fred", defaultUrl, register = false)
  }

  private def session(cfg: Cfg, rt: Runtime[Any]): ZIO[Scope, Throwable, Unit] =
    for {
      terminal <- ZIO.acquireRelease(ZIO.attemptBlocking(buildTerminal))(t => ZIO.attempt(t.close()).ignore)
      reader   <- ZIO.attemptBlocking {
                    val r = new PastingLineReader(terminal)
                    r.option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    r
                  }
      queue    <- Queue.unbounded[Ev]
      _        <- ZIO.when(cfg.register)(registerChannel(cfg, terminal))
      _        <- streamEvents(cfg, queue, rt).forkScoped
      _        <- printLine(terminal, s"Connected to ${cfg.mycroftUrl} on channel '${cfg.channel}' as '${cfg.as}'. Ctrl-D twice to quit.")
      _        <- loop(cfg, terminal, reader, queue, lastWasEof = false)
    } yield ()

  private def buildTerminal: Terminal =
    TerminalBuilder.builder().system(true).dumb(true).build()

  private def ansiSupported(t: Terminal): Boolean =
    !Option(t.getType).getOrElse("dumb").toLowerCase.contains("dumb")

  // --- main loop ---

  private val prompt = "\n> "

  private def loop(cfg: Cfg, terminal: Terminal, reader: PastingLineReader, queue: Queue[Ev], lastWasEof: Boolean): Task[Unit] =
    readLineZ(reader).foldZIO(
      {
        case _: EndOfFileException =>
          if (lastWasEof) printLine(terminal, "bye")
          else printLine(terminal, "(press Ctrl-D again to quit)") *> loop(cfg, terminal, reader, queue, lastWasEof = true)
        case _: UserInterruptException =>
          loop(cfg, terminal, reader, queue, lastWasEof = false) // Ctrl-C abandons the current line
        case t => ZIO.fail(t)
      },
      line => {
        val input   = reader.expand(line)
        val trimmed = input.trim
        if (trimmed.isEmpty) loop(cfg, terminal, reader, queue, lastWasEof = false)
        else if (trimmed == "exit" || trimmed == "quit") printLine(terminal, "bye")
        else
          for {
            turnId <- sendInbound(cfg, input).catchAll(t => printLine(terminal, s"send failed: ${describe(t)}").as(""))
            _      <- ZIO.when(turnId.nonEmpty)(renderTurn(cfg, terminal, queue, turnId))
            _      <- loop(cfg, terminal, reader, queue, lastWasEof = false)
          } yield ()
      }
    )

  private def readLineZ(reader: PastingLineReader): Task[String] =
    ZIO.attemptBlocking(reader.readLine(prompt))

  private def printLine(terminal: Terminal, s: String): UIO[Unit] =
    ZIO.attempt { terminal.writer().println(s); terminal.flush() }.ignore

  // --- turn rendering ---

  private def renderTurn(cfg: Cfg, terminal: Terminal, queue: Queue[Ev], turnId: String): Task[Unit] = {
    val r = new Renderer(terminal, ansiSupported(terminal))
    def go: Task[Unit] =
      queue.take.flatMap { ev =>
        val ch  = ev.json.flatMap(strField("channel"))
        val mid = ev.json.flatMap(strField("message_id"))
        if (!ch.contains(cfg.channel) || !mid.contains(turnId)) go
        else ZIO.attempt(r.on(ev)).ignore *> (if (ev.event == "done" || ev.event == "error") ZIO.unit else go)
      }
    go.timeout(180.seconds).flatMap {
      case Some(_) => ZIO.unit
      case None    => printLine(terminal, "[timed out waiting for a response]")
    }
  }

  /** Owns all terminal writes during a turn. Not thread-safe by design: only the
   *  turn loop (single fiber) calls it, and JLine is idle while it runs. */
  private final class Renderer(terminal: Terminal, ansi: Boolean) {
    private val w: PrintWriter = terminal.writer()
    private val width: Int     = { val x = terminal.getWidth; if (x > 20) math.min(x, 120) else 80 }
    private val thinkingHeight = 6
    private val toolHeight     = 4

    private val reasoning = new StringBuilder
    private val tools     = scala.collection.mutable.ArrayBuffer.empty[String]
    private var liveLines = 0
    private var answering = false

    def on(ev: Ev): Unit = if (ansi) onAnsi(ev) else onPlain(ev)

    private def onAnsi(ev: Ev): Unit = ev.event match {
      case "started" => reasoning.setLength(0); tools.clear(); answering = false
      case "reasoning" =>
        if (!answering) { reasoning.append(delta(ev)); draw() }
      case "tool_call" =>
        tools += s"· ${toolLine(ev)}"; draw()
      case "tool_result" =>
        tools += s"  ${resultLine(ev)}"; draw()
      case "content" =>
        if (!answering) { clearLive(); answering = true }
        w.print(delta(ev)); terminal.flush()
      case "done" =>
        clearLive(); w.print("\n" + dim("─" * 40) + "\n"); terminal.flush()
      case "error" =>
        clearLive(); w.print(s"\n[error] ${errLine(ev)}\n"); terminal.flush()
      case _ => ()
    }

    private def onPlain(ev: Ev): Unit = {
      ev.event match {
        case "started"     => w.println(); w.println("[mycroft] thinking…")
        case "reasoning"   => w.print(delta(ev))
        case "content"     => w.print(delta(ev))
        case "tool_call"   => w.println(); w.println(s"  · ${toolLine(ev)}")
        case "tool_result" => w.println(s"  · ${resultLine(ev)}")
        case "done"        => w.println(); w.println("─" * 40)
        case "error"       => w.println(); w.println(s"[error] ${errLine(ev)}")
        case _             => ()
      }
      terminal.flush()
    }

    /** Redraw the stacked thinking + tool boxes in place. */
    private def draw(): Unit = {
      val tb  = boxLines(reasoning.toString, thinkingHeight)
      val kb  = if (tools.isEmpty) Nil else boxLines(tools.mkString("\n"), toolHeight)
      val all = (tb ++ kb).map(dim)
      clearLive()
      if (all.nonEmpty) { w.print(all.mkString("\r\n")); liveLines = all.size }
      terminal.flush()
    }

    /** Erase the live region so the next write starts on a clean line. */
    private def clearLive(): Unit =
      if (liveLines > 0) {
        w.print("\r")
        if (liveLines > 1) w.print(s"\u001b[${liveLines - 1}A")
        w.print("\u001b[0J")
        liveLines = 0
      }

    private def boxLines(text: String, h: Int): List[String] = {
      val shown = wrap(text, width).takeRight(h)
      shown ++ List.fill(math.max(0, h - shown.size))("")
    }

    private def wrap(text: String, n: Int): List[String] =
      text.split("\n", -1).toList.flatMap(p => if (p.isEmpty) List("") else p.grouped(n).toList)

    private def dim(s: String): String = if (ansi) s"\u001b[2m$s\u001b[0m" else s

    private def delta(ev: Ev): String      = ev.json.flatMap(strField("delta")).getOrElse("")
    private def toolLine(ev: Ev): String    = s"${ev.json.flatMap(strField("tool")).getOrElse("tool")} ${ev.json.flatMap(strField("args")).getOrElse("")}"
    private def resultLine(ev: Ev): String  = {
      val ok = ev.json.flatMap(boolField("ok")).getOrElse(false)
      s"${if (ok) "ok" else "err"}: ${ev.json.flatMap(strField("summary")).getOrElse("")}"
    }
    private def errLine(ev: Ev): String =
      s"${ev.json.flatMap(strField("kind")).getOrElse("")}: ${ev.json.flatMap(strField("message")).getOrElse("")}"
  }

  // --- mycroft HTTP ---

  private def registerChannel(cfg: Cfg, terminal: Terminal): Task[Unit] = {
    val body = Json.Obj(
      "id"           -> Json.Str(cfg.channel),
      "defaultModel" -> Json.Null,
      "members"      -> Json.Arr(Json.Str(cfg.as))
    ).toJson
    post(cfg.mycroftUrl + "/channels", body).unit
      .flatMap(_ => printLine(terminal, s"Registered channel '${cfg.channel}' (member=${cfg.as})."))
      .catchAll {
        case t if isAlreadyExists(t) => printLine(terminal, s"Channel '${cfg.channel}' already registered.")
        case t                       => printLine(terminal, s"channel register skipped: ${describe(t)}")
      }
  }

  private def sendInbound(cfg: Cfg, content: String): Task[String] = {
    val body = Json.Obj(
      "channel" -> Json.Str(cfg.channel),
      "from"    -> Json.Str(cfg.as),
      "content" -> Json.Str(content)
    ).toJson
    post(cfg.mycroftUrl + "/inbound", body).map(resp =>
      resp.fromJson[Json].toOption.flatMap(strField("message_id")).getOrElse("")
    )
  }

  private def post(url: String, body: String): Task[String] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
        .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 400) throw new RuntimeException(s"${resp.statusCode()}: ${resp.body()}")
      resp.body()
    }

  // --- SSE: read frames on a blocking fiber, enqueue for the turn loop ---

  private def streamEvents(cfg: Cfg, queue: Queue[Ev], rt: Runtime[Any]): Task[Unit] =
    ZIO.acquireReleaseWith(openStream(cfg))(closeQuietly)(consume(_, cfg, queue, rt))
      .retry(Schedule.spaced(2.seconds) && Schedule.recurs(30))
      .unit

  private def openStream(cfg: Cfg): Task[InputStream] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder()
        .uri(URI.create(cfg.mycroftUrl + "/outbound/stream"))
        .header("Accept", "text/event-stream")
        .build()
      client.send(req, HttpResponse.BodyHandlers.ofInputStream()).body()
    }

  private def consume(is: InputStream, cfg: Cfg, queue: Queue[Ev], rt: Runtime[Any]): Task[Unit] =
    ZIO.attemptBlockingCancelable {
      val reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
      var event  = ""
      var line   = reader.readLine()
      while (line != null) {
        if (line.startsWith("event:")) event = line.drop("event:".length).trim
        else if (line.startsWith("data:")) {
          val data = line.drop("data:".length).trim
          val ev   = Ev(event, data.fromJson[Json].toOption)
          Unsafe.unsafe { implicit u => rt.unsafe.run(queue.offer(ev).unit).getOrThrowFiberFailure() }
        }
        line = reader.readLine()
      }
    }(closeQuietly(is))

  private def closeQuietly(is: InputStream): UIO[Unit] = ZIO.attempt(is.close()).ignore

  // --- helpers ---

  private def isAlreadyExists(t: Throwable): Boolean = {
    val m = Option(t.getMessage).getOrElse("")
    m.contains("UNIQUE constraint") || m.contains("PRIMARY KEY")
  }

  private def describe(t: Throwable): String =
    Option(t.getMessage).filter(_.nonEmpty).getOrElse(t.getClass.getSimpleName)

  private def strField(name: String)(json: Json): Option[String] = json match {
    case Json.Obj(fields) => fields.collectFirst { case (k, Json.Str(s)) if k == name => s }
    case _                => None
  }

  private def boolField(name: String)(json: Json): Option[Boolean] = json match {
    case Json.Obj(fields) => fields.collectFirst { case (k, Json.Bool(b)) if k == name => b }
    case _                => None
  }
}
