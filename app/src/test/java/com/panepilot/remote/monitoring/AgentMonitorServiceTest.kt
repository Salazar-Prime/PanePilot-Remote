package com.panepilot.remote.monitoring

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentMonitorServiceTest {
    @Test
    fun reconnectDelayUsesBoundedExponentialBackoff() {
        assertEquals(5_000L, AgentMonitorService.reconnectDelay(1))
        assertEquals(10_000L, AgentMonitorService.reconnectDelay(2))
        assertEquals(20_000L, AgentMonitorService.reconnectDelay(3))
        assertEquals(40_000L, AgentMonitorService.reconnectDelay(4))
        assertEquals(60_000L, AgentMonitorService.reconnectDelay(5))
        assertEquals(60_000L, AgentMonitorService.reconnectDelay(50))
    }

    @Test
    fun statusLabelUsesTerminalCount() {
        assertEquals(
            "SSH connected · no terminal alerts enabled",
            AgentMonitorService.monitoredTerminalLabel(0)
        )
        assertEquals(
            "Watching 1 terminal for attention",
            AgentMonitorService.monitoredTerminalLabel(1)
        )
        assertEquals(
            "Watching 3 terminals for attention",
            AgentMonitorService.monitoredTerminalLabel(3)
        )
    }
}
