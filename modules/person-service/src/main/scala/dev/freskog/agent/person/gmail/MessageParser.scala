package dev.freskog.agent.person.gmail

import zio.Chunk
import zio.json.ast.Json

/** Extract plain-text body from a Gmail API message JSON payload. */
object MessageParser {

  final case class Attachment(
    attachmentId: String,
    filename: String,
    mimeType: String,
    sizeBytes: Long
  )

  final case class ParsedMessage(
    id: String,
    threadId: Option[String],
    from: String,
    subject: String,
    bodyText: String,
    internalDateMillis: Long,
    attachments: List[Attachment] = Nil
  )

  def parseMessage(json: Json): Option[ParsedMessage] = json match {
    case Json.Obj(fields) =>
      val id        = str(fields, "id")
      val threadId  = optStr(fields, "threadId")
      val internal  = long(fields, "internalDate").getOrElse(0L)
      val headers   = headerMap(fields)
      val payload   = fields.collectFirst { case ("payload", p) => p }
      val body      = payloadText(payload)
      val attaches  = payload.map(extractAttachments).getOrElse(Nil)
      id.map(i =>
        ParsedMessage(
          id = i,
          threadId = threadId,
          from = headers.getOrElse("from", ""),
          subject = sanitize(headers.getOrElse("subject", "")),
          bodyText = sanitize(body.getOrElse("")),
          internalDateMillis = internal,
          attachments = attaches
        )
      )
    case _ => None
  }

  /** Walk the MIME tree collecting parts that carry a `body.attachmentId` (the
   *  downloadable handle). Real attachments always have a filename; inline parts
   *  without one are skipped. */
  private def extractAttachments(payload: Json): List[Attachment] = payload match {
    case Json.Obj(fields) =>
      val filename = str(fields, "filename").getOrElse("")
      val mime     = str(fields, "mimeType").getOrElse("application/octet-stream")
      val body     = fields.collectFirst { case ("body", Json.Obj(bf)) => bf }
      val attId    = body.flatMap(bf => optStr(bf, "attachmentId"))
      val size     = body.flatMap(bf => long(bf, "size")).getOrElse(0L)
      val here     =
        if (filename.nonEmpty && attId.isDefined) List(Attachment(attId.get, filename, mime, size))
        else Nil
      val parts    = fields.collectFirst { case ("parts", Json.Arr(arr)) => arr.toList }.getOrElse(Nil)
      here ++ parts.flatMap(extractAttachments)
    case _ => Nil
  }

  private def headerMap(fields: Chunk[(String, Json)]): Map[String, String] =
    fields.collectFirst { case ("payload", p) => headersFromPayload(p) }.getOrElse(Map.empty)

  private def headersFromPayload(payload: Json): Map[String, String] = payload match {
    case Json.Obj(pf) =>
      pf.collectFirst {
        case ("headers", Json.Arr(elems)) =>
          elems.collect {
            case Json.Obj(hf) =>
              for {
                n <- str(hf, "name")
                v <- str(hf, "value")
              } yield n.toLowerCase -> v
          }.flatten.toMap
      }.getOrElse(Map.empty)
    case _ => Map.empty
  }

  private def payloadText(payloadOpt: Option[Json]): Option[String] = payloadOpt.flatMap(extractText)

  private def extractText(payload: Json): Option[String] = payload match {
    case Json.Obj(fields) =>
      val mime = str(fields, "mimeType").getOrElse("")
      val data = fields.collectFirst { case ("body", Json.Obj(bf)) => optStr(bf, "data") }.flatten
      if (mime == "text/plain" && data.isDefined)
        Some(decodeBase64Url(data.get))
      else {
        val parts = fields.collectFirst { case ("parts", Json.Arr(arr)) => arr.toList }.getOrElse(Nil)
        val plain = parts.iterator.flatMap(p => extractText(p)).find(_.nonEmpty)
        plain.orElse {
          if (mime == "text/html" && data.isDefined) Some(stripHtml(decodeBase64Url(data.get)))
          else None
        }
      }
    case _ => None
  }

  private def decodeBase64Url(s: String): String = {
    val padded = s + ("=" * ((4 - s.length % 4) % 4))
    val bytes  = java.util.Base64.getUrlDecoder.decode(padded)
    new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
  }

  private def stripHtml(html: String): String =
    html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim

  /** Neutralise covert prompt-injection vectors in email text before it ever
   *  reaches the agent: drop every Unicode *format* char (Cf — zero-width spaces,
   *  joiners, bidi overrides/isolates, the U+E00xx tag block) and other control
   *  chars, keeping only ordinary whitespace. This kills "invisible instruction"
   *  tricks; it does NOT (and cannot) neutralise plainly-worded injection — that is
   *  the job of the untrusted-content framing and the human-approval gate. */
  private def sanitize(s: String): String = {
    val out = new java.lang.StringBuilder(s.length)
    s.codePoints().forEach { cp =>
      val keep =
        cp == '\n' || cp == '\r' || cp == '\t' ||
        (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT.toInt)
      if (keep) out.appendCodePoint(cp)
    }
    out.toString
  }

  private def str(fields: Chunk[(String, Json)], name: String): Option[String] =
    fields.collectFirst { case (k, Json.Str(v)) if k == name => v }

  private def optStr(fields: Chunk[(String, Json)], name: String): Option[String] = str(fields, name)

  private def long(fields: Chunk[(String, Json)], name: String): Option[Long] =
    fields.collectFirst {
      case (k, Json.Str(v)) if k == name => v.toLongOption
      case (k, Json.Num(n)) if k == name => Some(n.longValue())
    }.flatten
}
