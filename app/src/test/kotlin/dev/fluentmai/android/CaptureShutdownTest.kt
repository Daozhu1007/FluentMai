package dev.fluentmai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.ParcelFileDescriptor
import dev.fluentmai.android.vpn.core.LocalVpnService
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CaptureShutdownTest {
    @Test fun disconnectClosesTunnelEvenWhenServiceRemainsBound() {
        val controller = Robolectric.buildService(LocalVpnService::class.java).create()
        val service = controller.get()
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("fluentmai_hook", "Hook", NotificationManager.IMPORTANCE_LOW))
        service.startForeground(8285, Notification.Builder(service, "fluentmai_hook").setSmallIcon(android.R.drawable.stat_sys_download).build())
        val pipe = ParcelFileDescriptor.createPipe()
        val descriptor = LocalVpnService::class.java.getDeclaredField("m_VPNInterface").apply { isAccessible = true }
        descriptor.set(service, pipe[0])
        LocalVpnService.IsRunning = true
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().setAction(LocalVpnService.DISCONNECT_INTENT), 0, 1))
            assertFalse(LocalVpnService.IsRunning)
            assertNull(descriptor.get(service))
            assertNull(shadowOf(manager).getNotification(8285))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertFalse("Worker cannot own the cleanup monitor for its lifetime", Modifier.isSynchronized(LocalVpnService::class.java.getMethod("run").modifiers))
            // Repeated cleanup is harmless, including when Android delays onDestroy for its binding.
            service.onStartCommand(Intent().setAction(LocalVpnService.DISCONNECT_INTENT), 0, 2)
        } finally {
            pipe[1].close()
            controller.destroy()
        }
    }

    @Test fun revokedVpnRemovesNotificationAndStopsService() {
        val controller = Robolectric.buildService(LocalVpnService::class.java).create()
        LocalVpnService.IsRunning = true
        controller.get().onRevoke()
        assertFalse(LocalVpnService.IsRunning)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
        assertNull(LocalVpnService.Instance)
    }

    @Test fun nullRestartCannotResurrectCaptureServices() {
        val vpn = Robolectric.buildService(LocalVpnService::class.java).create()
        val hook = Robolectric.buildService(WahlapHookHttpService::class.java).create()
        assertEquals(Service.START_NOT_STICKY, vpn.get().onStartCommand(null, 0, 1))
        assertEquals(Service.START_NOT_STICKY, hook.get().onStartCommand(null, 0, 1))
        assertFalse(LocalVpnService.IsRunning)
        assertTrue(shadowOf(vpn.get()).isStoppedBySelf)
        assertTrue(shadowOf(hook.get()).isStoppedBySelf)
        vpn.destroy()
        hook.destroy()
    }
}
