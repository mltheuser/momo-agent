package codes.momo.agent.tool

import codes.momo.agent.Budgets
import codes.momo.agent.environment.ExecResult
import codes.momo.agent.environment.LocalExecutionEnvironment
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Reads [path], resolved under [tempDir], back byte-exact via a plain exec
 * `cat` — read_file would truncate large content.
 */
internal fun catBack(tempDir: Path, path: String): String = runBlocking {
    val result = LocalExecutionEnvironment(tempDir).exec(
        listOf("cat", tempDir.resolve(path).toString()),
        timeout = Budgets.TOOL_TIMEOUT,
    )
    val completed = assertIs<ExecResult.Completed>(result)
    assertEquals(0, completed.exitCode, "read-back cat failed: ${completed.stderr}")
    assertFalse(completed.stdoutTruncated, "read-back hit the exec capture cap; use smaller test content")
    completed.stdout
}
