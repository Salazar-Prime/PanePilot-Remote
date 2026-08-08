package com.panepilot.remote.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectActionGatewayTest {
    @Test
    fun parsesDesktopSharedActionDefinitions() {
        val actions = ProjectActionGateway.parse(
            """{
                "version": 1,
                "actions": [{
                    "id": "9af2aa17-6a84-4215-91fe-7dbddf6b348f",
                    "name": "Run tests",
                    "command": "npm test"
                }]
            }""".trimIndent()
        )

        assertEquals(1, actions.size)
        assertEquals("Run tests", actions.single().name)
        assertEquals("npm test", actions.single().command)
    }

    @Test
    fun rejectsDuplicateActionIds() {
        val duplicated = """{
            "version": 1,
            "actions": [
                {
                    "id": "9af2aa17-6a84-4215-91fe-7dbddf6b348f",
                    "name": "One",
                    "command": "true"
                },
                {
                    "id": "9af2aa17-6a84-4215-91fe-7dbddf6b348f",
                    "name": "Two",
                    "command": "true"
                }
            ]
        }""".trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            ProjectActionGateway.parse(duplicated)
        }
    }
}
