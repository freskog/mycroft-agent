package dev.freskog.agent.mycroft.domain

/** Environment-driven configuration, read once at startup. */
final case class MycroftConfig(
  port: Int,
  host: String,
  personServiceUrl: String,
  lmStudioUrl: String,
  defaultModel: String,
  maxToolIterations: Int,
  maxTurnSeconds: Int,
  maxOutputTokens: Int,
  contextWindowMsgs: Int,
  contextTokenBudget: Int
)

object MycroftConfig {

  private def env(key: String, default: String): String =
    sys.env.getOrElse(key, default)

  private def envInt(key: String, default: Int): Int =
    sys.env.get(key).flatMap(_.toIntOption).getOrElse(default)

  def fromEnv: MycroftConfig =
    MycroftConfig(
      port               = envInt("MYCROFT_PORT", 8090),
      host               = env("MYCROFT_HOST", "0.0.0.0"),
      personServiceUrl   = env("PERSON_SERVICE_URL", "http://person-service:8080"),
      lmStudioUrl        = env("MYCROFT_LM_STUDIO_URL", "http://fredriks-mac-mini.gledswood.org:1234"),
      defaultModel       = env("MYCROFT_DEFAULT_MODEL", "qwen3.6-35b-a3b-ud-mlx"),
      maxToolIterations  = envInt("MYCROFT_MAX_TOOL_ITERATIONS", 8),
      maxTurnSeconds     = envInt("MYCROFT_MAX_TURN_SECONDS", 90),
      maxOutputTokens    = envInt("MYCROFT_MAX_OUTPUT_TOKENS", 2048),
      contextWindowMsgs  = envInt("MYCROFT_CONTEXT_WINDOW_MSGS", 20),
      contextTokenBudget = envInt("MYCROFT_CONTEXT_TOKEN_BUDGET", 8000)
    )
}
