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

    @Test
    fun aggregateStatusSummarizesMultipleServers() {
        assertEquals(
            "Monitoring 3 SSH servers",
            AgentMonitorService.monitoringTitle(3, null)
        )
        assertEquals(
            "Monitoring Build box",
            AgentMonitorService.monitoringTitle(1, "Build box")
        )
        assertEquals(
            "Monitoring SSH server",
            AgentMonitorService.monitoringTitle(1, null)
        )
        assertEquals(
            "2/3 connected · 1 reconnecting · 4 terminal alerts",
            AgentMonitorService.aggregateStatusLabel(
                serverCount = 3,
                connectedCount = 2,
                reconnectingCount = 1,
                alertCount = 4
            )
        )
    }
}
