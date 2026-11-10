package com.dronits.wotvpn.vpn

import android.content.pm.PackageManager
import android.net.VpnService
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.Answers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

class GameTunnelPolicyTest {
    private val builder = mock(VpnService.Builder::class.java, Answers.RETURNS_SELF)

    @Test
    fun allowsExactlyTheWargamingPackageWithoutBypassOrGlobalFallback() {
        val configured = GameTunnelPolicy.configure(builder, "WoT Blitz VPN")

        assertSame(builder, configured)
        verify(builder).addAllowedApplication("com.wargaming.wot.blitz")
        verify(builder, times(1)).addAllowedApplication(anyString())
        verify(builder, never()).addDisallowedApplication(anyString())
        verify(builder, never()).allowBypass()
        verify(builder, never()).allowFamily(anyInt())
        verify(builder, never()).establish()
    }

    @Test
    fun configuresBothIpFamiliesForAllGameTraffic() {
        GameTunnelPolicy.configure(builder, "WoT Blitz VPN")

        verify(builder).addAddress("10.77.0.1", 30)
        verify(builder).addAddress("fd42:776f:7400::1", 126)
        verify(builder).addRoute("0.0.0.0", 0)
        verify(builder).addRoute("::", 0)
        verify(builder).addDnsServer("1.1.1.1")
        verify(builder).addDnsServer("2606:4700:4700::1111")
        verify(builder).setMtu(1400)
    }

    @Test
    fun missingGameAbortsBeforeAnyOtherTunnelConfiguration() {
        val failure = mock(PackageManager.NameNotFoundException::class.java)
        doThrow(failure).`when`(builder).addAllowedApplication(WotGame.PACKAGE_NAME)

        val thrown = assertThrows(PackageManager.NameNotFoundException::class.java) {
            GameTunnelPolicy.configure(builder, "WoT Blitz VPN")
        }

        assertSame(failure, thrown)
        verify(builder).addAllowedApplication(WotGame.PACKAGE_NAME)
        verifyNoMoreInteractions(builder)
    }
}
