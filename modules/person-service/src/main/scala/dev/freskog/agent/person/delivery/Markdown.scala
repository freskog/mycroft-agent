package dev.freskog.agent.person.delivery

/** Tiny markdown → HTML converter for briefing emails (Gmail doesn't render
 *  markdown). Handles the subset the daily-briefing skill emits: `#`/`##`/`###`
 *  headings, `- `/`* ` bullet lists, `**bold**`, and paragraphs. Content is
 *  HTML-escaped first, so it's safe for arbitrary (incl. untrusted-derived) text. */
object Markdown {

  private val bold = "\\*\\*(.+?)\\*\\*".r

  def toHtml(md: String): String = {
    val sb     = new StringBuilder
    var inList = false
    def closeList(): Unit = if (inList) { sb.append("</ul>"); inList = false }

    md.split("\n", -1).foreach { raw =>
      val line = raw.trim
      if (line.startsWith("### "))       { closeList(); sb.append(s"<h4>${inline(line.drop(4))}</h4>") }
      else if (line.startsWith("## "))   { closeList(); sb.append(s"<h3>${inline(line.drop(3))}</h3>") }
      else if (line.startsWith("# "))    { closeList(); sb.append(s"<h2>${inline(line.drop(2))}</h2>") }
      else if (line.startsWith("- ") || line.startsWith("* ")) {
        if (!inList) { sb.append("<ul>"); inList = true }
        sb.append(s"<li>${inline(line.drop(2))}</li>")
      }
      else if (line.isEmpty)             { closeList() } // paragraph break
      else                               { closeList(); sb.append(s"<p>${inline(line)}</p>") }
    }
    closeList()

    "<html><body style=\"font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;" +
      "font-size:14px;line-height:1.5;color:#222\">" + sb.toString + "</body></html>"
  }

  /** Escape HTML, then render `**bold**`. */
  private def inline(s: String): String = {
    val esc = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    bold.replaceAllIn(esc, m => java.util.regex.Matcher.quoteReplacement(s"<b>${m.group(1)}</b>"))
  }
}
