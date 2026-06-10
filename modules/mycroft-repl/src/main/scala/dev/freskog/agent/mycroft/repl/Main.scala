package dev.freskog.agent.mycroft.repl

import dev.freskog.agent.common.ApprovalEvent
import dev.freskog.agent.common.JsonCodecs._

import zio._
import zio.json._
import zio.json.ast.Json

import org.jline.reader.{EndOfFileException, LineReader, UserInterruptException}
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.{Attributes, Terminal, TerminalBuilder}
import org.jline.utils.NonBlockingReader

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

  final case class Cfg(channel: String, as: String, mycroftUrl: String, personServiceUrl: String, personServicePrivateUrl: String, register: Boolean)
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
  private val defaultPersonUrl: String = sys.env.getOrElse("PERSON_SERVICE_URL", "http://127.0.0.1:8080")
  // The private decision endpoint. Falls back to the public URL for local dev
  // (where person-service serves decide on the public interface).
  private val defaultPersonPrivateUrl: String = sys.env.getOrElse("PERSON_SERVICE_PRIVATE_URL", defaultPersonUrl)
  private val maxTurnSeconds: Int = sys.env.getOrElse("MYCROFT_MAX_TURN_SECONDS", "240").toIntOption.getOrElse(240)

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
    def go(rem: List[String], channel: Option[String], as: String, url: String, personUrl: String, register: Boolean): Cfg =
      rem match {
        case "--channel" :: v :: t           => go(t, Some(v), as, url, personUrl, register)
        case "--as" :: v :: t                => go(t, channel, v, url, personUrl, register)
        case "--mycroft-url" :: v :: t       => go(t, channel, as, v, personUrl, register)
        case "--person-service-url" :: v :: t => go(t, channel, as, url, v, register)
        case "--register" :: t             => go(t, channel, as, url, personUrl, register = true)
        case _ :: t                         => go(t, channel, as, url, personUrl, register)
        case Nil                             => Cfg(channel.getOrElse("repl"), as, url, personUrl, defaultPersonPrivateUrl, register)
      }
    go(args, None, "fred", defaultUrl, defaultPersonUrl, register = false)
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
      _        <- streamApprovals(cfg, terminal).forkScoped
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
        else if (trimmed == "/triage" || trimmed.startsWith("/triage "))
          runTriage(cfg, terminal, reader, queue, trimmed) *> loop(cfg, terminal, reader, queue, lastWasEof = false)
        else if (trimmed == "/approvals")
          listApprovals(cfg, terminal) *> loop(cfg, terminal, reader, queue, lastWasEof = false)
        else if (trimmed.startsWith("/approve "))
          decideApproval(cfg, terminal, queue, trimmed.stripPrefix("/approve ").trim, approve = true) *>
            loop(cfg, terminal, reader, queue, lastWasEof = false)
        else if (trimmed.startsWith("/reject "))
          decideApproval(cfg, terminal, queue, trimmed.stripPrefix("/reject ").trim, approve = false) *>
            loop(cfg, terminal, reader, queue, lastWasEof = false)
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
    val render = go.timeout((maxTurnSeconds + 30).seconds).flatMap {
      case Some(_) => ZIO.unit
      case None    => printLine(terminal, s"[timed out after ${maxTurnSeconds + 30}s waiting for a response]")
    }
    // On a real terminal, let ESC abort the turn (cancels it server-side and
    // hands the prompt back instead of waiting for the timeout). Dumb terminals
    // can't deliver a bare keypress, so they just render.
    if (!ansiSupported(terminal)) render
    else
      withCbreak(terminal)(render.as(false).raceFirst(watchEsc(terminal).as(true))).flatMap { aborted =>
        ZIO.when(aborted)(cancelTurn(cfg, turnId) *> printLine(terminal, "\n[aborted]")).unit
      }
  }

  /** Put the terminal in cbreak mode (no line buffering, no echo) for the
   *  duration of `io`, restoring the saved attributes afterwards. Output flags
   *  are left untouched so the live rendering still translates newlines. */
  private def withCbreak[A](terminal: Terminal)(io: Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt {
        val saved = terminal.getAttributes
        val attrs = new Attributes(saved)
        attrs.setLocalFlag(Attributes.LocalFlag.ICANON, false)
        attrs.setLocalFlag(Attributes.LocalFlag.ECHO, false)
        terminal.setAttributes(attrs)
        saved
      }
    )(saved => ZIO.attempt(terminal.setAttributes(saved)).ignore)(_ => io)

  /** Completes when the user presses a bare ESC. Polls with a short timeout so
   *  it stays responsive to interruption (when the turn finishes first), and
   *  distinguishes a real ESC from the start of an escape sequence (arrow keys)
   *  by peeking for an immediately-following byte. */
  private def watchEsc(terminal: Terminal): Task[Unit] = {
    val reader = terminal.reader()
    def loop: Task[Unit] =
      ZIO.attemptBlocking(reader.read(150L)).flatMap {
        case 27 =>
          ZIO.attemptBlocking(reader.read(40L)).flatMap { next =>
            if (next == NonBlockingReader.READ_EXPIRED) ZIO.unit // bare ESC -> abort
            else loop                                            // escape sequence -> ignore
          }
        case -1 => ZIO.never                                     // input closed; let render win
        case _  => loop                                          // timeout (-2) or any other key
      }
    loop
  }

  private def cancelTurn(cfg: Cfg, turnId: String): Task[Unit] =
    post(cfg.mycroftUrl + s"/turns/$turnId/cancel", "{}").unit.catchAll(_ => ZIO.unit)

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
    private var turnStart = 0L

    def on(ev: Ev): Unit = if (ansi) onAnsi(ev) else onPlain(ev)

    private def onAnsi(ev: Ev): Unit = ev.event match {
      case "started" => reasoning.setLength(0); tools.clear(); answering = false; turnStart = java.lang.System.nanoTime()
      case "reasoning" =>
        // Once the answer is streaming, ignore trailing chain-of-thought.
        if (!answering) { reasoning.append(delta(ev)); draw() }
      case "tool_call" =>
        // After the answer starts (e.g. a nested skill's tool_result arrives
        // late), never redraw the live box — that would clobber the printed
        // answer. Append the tool line inline instead.
        if (answering) { w.print("\n" + dim(s"· ${toolLine(ev)}")); terminal.flush() }
        else { tools += s"· ${toolLine(ev)}"; draw() }
      case "tool_result" =>
        if (answering) { w.print("\n" + dim(s"  ${resultLine(ev)}")); terminal.flush() }
        else { tools += s"  ${resultLine(ev)}"; draw() }
      case "content" =>
        if (!answering) { clearLive(); answering = true }
        w.print(delta(ev)); terminal.flush()
      case "done" =>
        // Only clear the live box if we never started answering; otherwise the
        // box is already gone and clearing would erase the answer.
        if (!answering) clearLive()
        w.print("\n" + dim("─" * 40) + "\n" + dim(doneLine(ev)) + "\n"); terminal.flush()
      case "error" =>
        if (!answering) clearLive()
        w.print(s"\n[error] ${errLine(ev)}\n"); terminal.flush()
      case _ => ()
    }

    private def onPlain(ev: Ev): Unit = {
      ev.event match {
        case "started"     => turnStart = java.lang.System.nanoTime(); w.println(); w.println("[mycroft] thinking…")
        case "reasoning"   => w.print(delta(ev))
        case "content"     => w.print(delta(ev))
        case "tool_call"   => w.println(); w.println(s"  · ${toolLine(ev)}")
        case "tool_result" => w.println(s"  · ${resultLine(ev)}")
        case "done"        => w.println(); w.println("─" * 40); w.println(doneLine(ev))
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

    private def doneLine(ev: Ev): String = {
      val elapsed = ev.json.flatMap(longField("elapsed_ms")).getOrElse {
        if (turnStart == 0L) 0L else (java.lang.System.nanoTime() - turnStart) / 1_000_000
      }
      val tokIn  = ev.json.flatMap(intField("tokens_in")).getOrElse(0)
      val tokOut = ev.json.flatMap(intField("tokens_out")).getOrElse(0)
      val ttft   = ev.json.flatMap(longField("ttft_ms"))
      val genTps = ev.json.flatMap(doubleField("gen_tps"))
      val ppTps  = ev.json.flatMap(doubleField("pp_tps"))

      val tokens =
        if (tokIn > 0 || tokOut > 0) List(s"${tokIn} ctx → ${tokOut} gen tok") else Nil
      val parts =
        List(s"${elapsed}ms") ++
          tokens ++
          ttft.map(t => s"TTFT ${t}ms").toList ++
          ppTps.map(t => s"PP ${fmtTps(t)} tok/s").toList ++
          genTps.map(t => s"TG ${fmtTps(t)} tok/s").toList
      parts.mkString(" · ")
    }

    private def fmtTps(v: Double): String =
      if (v >= 100) f"$v%.0f" else f"$v%.1f"
  }

  // --- inbox triage (thin trigger) ---

  /** The REPL no longer orchestrates triage — it just triggers the
   *  `inbox-triage` skill. All the steps (Gmail sync, oldest-pending fetch,
   *  classification, propose/skip, mark-triaged) live in the skill playbook, and
   *  dedup is a server-side guarantee (propose-by-source). */
  private def runTriage(cfg: Cfg, terminal: Terminal, reader: PastingLineReader, queue: Queue[Ev], cmd: String): Task[Unit] = {
    val limit = cmd.stripPrefix("/triage").trim match {
      case ""     => 5
      case digits => digits.toIntOption.getOrElse(5)
    }
    val params = Json.Obj("limit" -> Json.Num(limit), "owner" -> Json.Str(cfg.as)).toJson
    val task   =
      s"Triage the $limit oldest pending inbox messages for ${cfg.as}. Follow the inbox-triage playbook and finish with a short markdown summary of the action taken per message."
    for {
      _      <- printLine(terminal, s"Triggering inbox-triage skill (limit $limit)…")
      turnId <- sendSkill(cfg, "inbox-triage", task, Some(params)).catchAll(t => printLine(terminal, s"triage trigger failed: ${describe(t)}").as(""))
      _      <- ZIO.when(turnId.nonEmpty)(renderTurn(cfg, terminal, queue, turnId))
    } yield ()
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
        case t if isAlreadyExists(t)   => printLine(terminal, s"Channel '${cfg.channel}' already registered.")
        case t if isUnknownPerson(t)   =>
          printLine(terminal, s"Channel register failed: no person '${cfg.as}'. Pass --as <person-id> (a lowercase slug like 'fred', not a display name).")
        case t                         => printLine(terminal, s"channel register skipped: ${describe(t)}")
      }
  }

  /** A channel_members insert that hit a FK violation — the `--as` person id
   *  doesn't exist (usually a display name was passed instead of the slug). */
  private def isUnknownPerson(t: Throwable): Boolean = {
    val m = Option(t.getMessage).getOrElse("")
    m.contains("FOREIGN KEY") || m.contains("channel_members")
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

  /** Trigger a named skill at the top level — the REPL only names the skill and
   *  the task/params; the harness runs it in a skill executor. */
  private def sendSkill(cfg: Cfg, skill: String, task: String, params: Option[String]): Task[String] = {
    val body = Json.Obj(
      "channel" -> Json.Str(cfg.channel),
      "from"    -> Json.Str(cfg.as),
      "content" -> Json.Str(task),
      "skill"   -> Json.Str(skill),
      "params"  -> params.map(Json.Str(_)).getOrElse(Json.Null)
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
      if (resp.statusCode() >= 400) {
        val hint = if (resp.statusCode() == 413) " (payload too large — try /triage with a smaller number)" else ""
        throw new RuntimeException(s"${resp.statusCode()}$hint: ${resp.body()}")
      }
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

  // --- approvals (person-service) ---

  /** Subscribe to person-service's approval stream and surface `requested`
   *  prompts to the user. `executed` arrives separately as a normal continuation
   *  turn over the mycroft SSE, so it isn't printed here. */
  private def streamApprovals(cfg: Cfg, terminal: Terminal): Task[Unit] =
    ZIO.acquireReleaseWith(openApprovalStream(cfg))(closeQuietly)(consumeApprovals(_, terminal))
      .retry(Schedule.spaced(2.seconds) && Schedule.recurs(30))
      .unit

  private def openApprovalStream(cfg: Cfg): Task[InputStream] =
    ZIO.attemptBlocking {
      val req = HttpRequest.newBuilder()
        .uri(URI.create(cfg.personServiceUrl + "/approvals/stream?person=" + enc(cfg.as)))
        .header("Accept", "text/event-stream")
        .build()
      client.send(req, HttpResponse.BodyHandlers.ofInputStream()).body()
    }

  private def consumeApprovals(is: InputStream, terminal: Terminal): Task[Unit] =
    ZIO.attemptBlockingCancelable {
      val reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
      var line   = reader.readLine()
      while (line != null) {
        if (line.startsWith("data:")) {
          val data = line.drop("data:".length).trim
          data.fromJson[ApprovalEvent].toOption.foreach { ev =>
            val a = ev.approval
            val msg = ev.kind match {
              case "requested" =>
                val prov = a.source.map(s => s"  (from $s)").getOrElse("")
                Some(s"\n⚖  Approval needed [${a.id.value}]: ${a.actionType}$prov  ${a.payloadJson}\n" +
                  s"   /approve ${a.id.value}   |   /reject ${a.id.value} [reason]")
              case "rejected" => Some(s"\n✗  Approval ${a.id.value} rejected.")
              case _          => None // `executed` shows up as a continuation turn
            }
            msg.foreach { m => terminal.writer().println(m); terminal.flush() }
          }
        }
        line = reader.readLine()
      }
    }(closeQuietly(is))

  /** The human's decision goes straight to person-service. On approve, the action
   *  executes server-side and mycroft fires the continuation — which we render by
   *  adopting the next turn that starts on this channel. */
  private def decideApproval(cfg: Cfg, terminal: Terminal, queue: Queue[Ev], rest: String, approve: Boolean): Task[Unit] = {
    val parts  = rest.split("\\s+", 2)
    val id     = parts.headOption.getOrElse("")
    val reason = if (parts.length > 1 && parts(1).trim.nonEmpty) Some(parts(1).trim) else None
    if (id.isEmpty) printLine(terminal, "usage: /approve <id>  |  /reject <id> [reason]")
    else {
      // Fetch the one-time code over the private interface (the agent can't reach
      // this), then echo it back on decide. The human just types /approve <id>.
      val flow = for {
        codeResp <- httpGet(cfg.personServicePrivateUrl + s"/approvals/$id/code")
        code      = codeResp.fromJson[Json].toOption.flatMap(strField("code")).getOrElse("")
        body      = Json.Obj(
                      "code"      -> Json.Str(code),
                      "decidedBy" -> Json.Str(cfg.as),
                      "approve"   -> Json.Bool(approve),
                      "reason"    -> reason.map(Json.Str(_)).getOrElse(Json.Null)
                    ).toJson
        _        <- post(cfg.personServicePrivateUrl + s"/approvals/$id/decide", body)
      } yield ()
      flow.foldZIO(
        t => printLine(terminal, s"decision failed: ${describe(t)}"),
        _ =>
          if (approve) printLine(terminal, s"approved $id — running continuation…") *> renderNextTurn(cfg, terminal, queue)
          else printLine(terminal, s"rejected $id")
      )
    }
  }

  private def listApprovals(cfg: Cfg, terminal: Terminal): Task[Unit] =
    httpGet(cfg.personServiceUrl + "/approvals?status=requested").foldZIO(
      t    => printLine(terminal, s"list failed: ${describe(t)}"),
      body => printLine(terminal, body)
    )

  /** Render the next turn that starts on this channel, whatever its id — used for
   *  mycroft-initiated continuation/notification turns whose turnId we don't know. */
  private def renderNextTurn(cfg: Cfg, terminal: Terminal, queue: Queue[Ev]): Task[Unit] = {
    val r = new Renderer(terminal, ansiSupported(terminal))
    def go(adopted: Option[String]): Task[Unit] =
      queue.take.flatMap { ev =>
        val ch  = ev.json.flatMap(strField("channel"))
        val mid = ev.json.flatMap(strField("message_id"))
        if (!ch.contains(cfg.channel)) go(adopted)
        else adopted match {
          case None      => if (ev.event == "started") ZIO.attempt(r.on(ev)).ignore *> go(mid) else go(None)
          case Some(tid) =>
            if (!mid.contains(tid)) go(adopted)
            else ZIO.attempt(r.on(ev)).ignore *> (if (ev.event == "done" || ev.event == "error") ZIO.unit else go(adopted))
        }
      }
    go(None).timeout((maxTurnSeconds + 30).seconds).flatMap {
      case Some(_) => ZIO.unit
      case None    => printLine(terminal, s"[timed out waiting for the continuation]")
    }
  }

  private def httpGet(url: String): Task[String] =
    ZIO.attemptBlocking {
      val req  = HttpRequest.newBuilder().uri(URI.create(url)).GET().header("Accept", "application/json").build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
      if (resp.statusCode() >= 400) throw new RuntimeException(s"${resp.statusCode()}: ${resp.body()}")
      resp.body()
    }

  private def enc(v: String): String =
    java.net.URLEncoder.encode(v, StandardCharsets.UTF_8)

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

  private def intField(name: String)(json: Json): Option[Int] = json match {
    case Json.Obj(fields) =>
      fields.collectFirst {
        case (k, Json.Num(n)) if k == name => n.intValue
      }
    case _ => None
  }

  private def longField(name: String)(json: Json): Option[Long] = json match {
    case Json.Obj(fields) =>
      fields.collectFirst {
        case (k, Json.Num(n)) if k == name => n.longValue
      }
    case _ => None
  }

  private def doubleField(name: String)(json: Json): Option[Double] = json match {
    case Json.Obj(fields) =>
      fields.collectFirst {
        case (k, Json.Num(n)) if k == name => n.doubleValue
      }
    case _ => None
  }
}
