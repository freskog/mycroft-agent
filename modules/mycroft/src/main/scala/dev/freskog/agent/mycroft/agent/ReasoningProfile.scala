package dev.freskog.agent.mycroft.agent

/** How a model call should reason. The verification of the oMLX server showed
 *  `enable_thinking` is honoured and that, without it, the model burns reasoning
 *  tokens on every call — so we make it explicit per call:
 *
 *  - `direct` — thinking off, a tight token budget. Fast/cheap for mechanical
 *    steps (routing, structured extraction, confirmations).
 *  - `reason` — thinking on (cleanly separated into `reasoning_content`), with
 *    generous headroom so the model doesn't truncate mid-thought.
 *
 *  A skill can pick its mode via the `reasoning:` frontmatter field; otherwise the
 *  configured default applies. (`deep` is intentionally not implemented yet.) */
final case class ReasoningProfile(name: String, enableThinking: Boolean, maxTokens: Int)

object ReasoningProfile {
  def reason(maxTokens: Int): ReasoningProfile = ReasoningProfile("reason", enableThinking = true, maxTokens)
  def direct(maxTokens: Int): ReasoningProfile = ReasoningProfile("direct", enableThinking = false, maxTokens)

  /** Resolve a mode name to a profile using the configured token budgets.
   *  Anything other than `direct` (including unknown values) is treated as
   *  `reason` — the safe default that preserves reasoning behaviour. */
  def resolve(name: String, reasonMaxTokens: Int, directMaxTokens: Int): ReasoningProfile =
    name.trim.toLowerCase match {
      case "direct" => direct(directMaxTokens)
      case _        => reason(reasonMaxTokens)
    }
}
