package dev.freskog.agent.mycroft.domain

import java.time.Instant

/** Typed events published by the agent loop and serialised onto the outbound
 *  SSE stream. Every event carries the channel + message id so clients can
 *  filter and correlate. SSE frames are ephemeral — durable delivery is keyed
 *  on persisted messages (see Replay & delivery in the plan). */
sealed trait AgentEvent extends Product with Serializable {
  def channel: String
  def messageId: String
}

object AgentEvent {

  /** The wire name used for the SSE `event:` field. */
  def name(e: AgentEvent): String = e match {
    case _: Started    => "started"
    case _: Reasoning  => "reasoning"
    case _: Content    => "content"
    case _: ToolCall   => "tool_call"
    case _: ToolResult => "tool_result"
    case _: Done       => "done"
    case _: Error      => "error"
  }

  final case class Started(channel: String, messageId: String, inReplyTo: Option[String], model: String, startedAt: Instant) extends AgentEvent
  final case class Reasoning(channel: String, messageId: String, delta: String) extends AgentEvent
  final case class Content(channel: String, messageId: String, delta: String) extends AgentEvent
  final case class ToolCall(channel: String, messageId: String, tool: String, args: String) extends AgentEvent
  final case class ToolResult(channel: String, messageId: String, tool: String, ok: Boolean, summary: String) extends AgentEvent
  final case class Done(channel: String, messageId: String, stopReason: String, tokensIn: Int, tokensOut: Int) extends AgentEvent
  final case class Error(channel: String, messageId: String, kind: String, message: String) extends AgentEvent
}
