/**
 * Example demonstrating executable actions.
 *
 * Actions:
 * - send: Send data to a registered action handler (HTTP, messaging, events)
 * - execute: Run a command by ID (from commands.yaml)
 *
 * Security: Execute commands are NOT defined in the workflow.
 * They are referenced by ID and resolved from commands.yaml at runtime.
 */

fun actionsTestWorkflow() = workflow("executable-actions-test") {
    description = "Demonstrates node actions"
    version = "1.0.0"

    agents {
        agent("writer") {
            role = "A helpful assistant that generates short messages"
            model = Models.GEMINI_3_1_FLASH_LITE
            temperature = 0.0
        }
    }

    state {
        variable("message", VarType.STRING, "the generated weather message")
    }

    graph {
        start at "generate"

        node("generate") {
            agent = "writer"
            prompt = """
                Generate a short message about the weather today.
                Keep it to one sentence.
            """.trimIndent()
            writes("message")
            onSuccess goto "notify"
        }

        action("notify") {
            // Execute command by ID (from commands.yaml)
            execute("echo-result")

            onSuccess goto "exit"
            onFailure retry 0 otherwise "exit"
        }

        end("exit")
    }
}