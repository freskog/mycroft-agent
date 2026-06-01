package dev.freskog.agent.common

/** Single umbrella error type for repos, services, routes, and CLIs.
 *  Subtypes carry enough structure that boundaries can translate them
 *  without resorting to string-matching on messages. */
sealed trait AgentError extends Product with Serializable {
  def message: String
  def cause: Option[Throwable] = None
}

object AgentError {

  final case class NotFound(targetType: String, id: String) extends AgentError {
    val message: String = s"$targetType not found: $id"
  }

  final case class BadRequest(message: String) extends AgentError

  final case class Validation(message: String) extends AgentError

  final case class Persistence(
    message: String,
    override val cause: Option[Throwable] = None
  ) extends AgentError

  final case class HttpFailed(
    message: String,
    override val cause: Option[Throwable] = None
  ) extends AgentError

  final case class HttpBadStatus(
    message: String,
    status: Int,
    body: String
  ) extends AgentError

  final case class DecodeFailed(
    message: String,
    override val cause: Option[Throwable] = None
  ) extends AgentError

  final case class Bug(
    message: String,
    override val cause: Option[Throwable] = None
  ) extends AgentError

  def fromThrowable(context: String)(t: Throwable): AgentError =
    Persistence(
      s"$context: ${Option(t.getMessage).getOrElse(t.getClass.getSimpleName)}",
      Some(t)
    )
}
