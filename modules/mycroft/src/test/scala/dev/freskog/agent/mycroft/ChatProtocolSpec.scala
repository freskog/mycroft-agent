package dev.freskog.agent.mycroft

import dev.freskog.agent.mycroft.llm._

import zio.test._
import zio.test.Assertion._

object ChatProtocolSpec extends ZIOSpecDefault {

  def spec = suite("ChatProtocol / StreamParser")(

    test("parses a content delta") {
      val line = """data: {"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}"""
      assert(StreamParser.parseLine(line))(isSome(hasField("content", (_: ChatChunk).contentDelta, isSome(equalTo("hello")))))
    },

    test("parses a reasoning delta into reasoning_content") {
      val line = """data: {"choices":[{"delta":{"reasoning_content":"thinking"}}]}"""
      assert(StreamParser.parseLine(line).flatMap(_.reasoningDelta))(isSome(equalTo("thinking")))
    },

    test("parses a tool_call delta with index/id/name and arg fragment") {
      val line = """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"shell_run","arguments":"{\"command\""}}]}}]}"""
      val deltas = StreamParser.parseLine(line).map(_.toolCallDeltas).getOrElse(Nil)
      assertTrue(
        deltas.size == 1,
        deltas.head.index == 0,
        deltas.head.id.contains("call-1"),
        deltas.head.name.contains("shell_run"),
        deltas.head.argsFragment.contains("{\"command\"")
      )
    },

    test("captures finish_reason tool_calls") {
      val line = """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""
      assert(StreamParser.parseLine(line).flatMap(_.finishReason))(isSome(equalTo("tool_calls")))
    },

    test("ignores [DONE] and non-data lines") {
      assertTrue(
        StreamParser.parseLine("data: [DONE]").isEmpty,
        StreamParser.parseLine("").isEmpty,
        StreamParser.parseLine(": comment").isEmpty
      )
    },

    test("encodeBody includes tools and renders tool_calls + tool role") {
      val msgs = List(
        ChatMessage.system("sys"),
        ChatMessage.user("hi"),
        ChatMessage("assistant", Some(""), toolCalls = List(ToolCallSpec("c1", "shell_run", """{"command":"ls"}"""))),
        ChatMessage("tool", Some("exit 0"), toolCallId = Some("c1"))
      )
      val body = ChatProtocol.encodeBody("m", msgs, 256, stream = true, Some(ToolRegistryToolsJson))
      assertTrue(
        body.contains("\"tools\""),
        body.contains("\"tool_calls\""),
        body.contains("\"tool_call_id\":\"c1\""),
        body.contains("\"role\":\"tool\""),
        body.contains("\"stream\":true")
      )
    }
  )

  // A minimal tools array so the test doesn't depend on ToolRegistry's constant.
  private val ToolRegistryToolsJson =
    """[{"type":"function","function":{"name":"shell_run","parameters":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}}}]"""
}
