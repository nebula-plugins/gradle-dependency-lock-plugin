package com.netflix.nebula.dependencylock

import com.netflix.nebula.dependencylocks.LockFileParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class LockFileParserTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `test readLocks locked version`() {
        val lockfile = tempDir.resolve("old.lock").apply {
            //language=json
            writeText(
                """
{
  "compileClasspath": {
    "group:artifact": {
      "locked": "1.591.1"
    }
  }
}
            """
            )
        }
        val result = LockFileParser.readLocks(lockfile)
        assertThat(result.dependenciesForConfiguration("compileClasspath").usedVersion("group:artifact"))
            .isEqualTo("1.591.1")

    }

    @Test
    fun `test readLocks project dependency`() {
        val lockfile = tempDir.resolve("old.lock").apply {
            //language=json
            writeText(
                """
{
  "compileClasspath": {
    "group:artifact": {
      "project": true
    }
  }
}
"""
            )
        }
        val result = LockFileParser.readLocks(lockfile)
        assertThat(result.dependenciesForConfiguration("compileClasspath").usedVersion("group:artifact"))
            .isNull()
    }
}