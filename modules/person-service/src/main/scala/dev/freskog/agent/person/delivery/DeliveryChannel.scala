package dev.freskog.agent.person.delivery

import dev.freskog.agent.common.AgentError

import zio.IO

/** How a briefing reaches an owner. Email is implemented now (to the owner's own
 *  Google account address); WhatsApp/Signal are future impls behind this trait.
 *  The destination is resolved by person-service from config — never agent-chosen,
 *  so this is not an egress route the agent controls. */
trait DeliveryChannel {
  /** Short tag recorded on the delivered briefing (e.g. "email"). */
  def name: String
  def deliver(subject: String, body: String): IO[AgentError, Unit]
}
