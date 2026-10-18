package com.getjobs.worker.manager;

import com.getjobs.application.service.CookieService;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaywrightManagerTest {
    PlaywrightManager manager;
    BrowserContext context;

    @BeforeEach void setUp() {
        manager = new PlaywrightManager();
        context = mock(BrowserContext.class);
        ReflectionTestUtils.setField(manager, "playwright", mock(Playwright.class));
        ReflectionTestUtils.setField(manager, "context", context);
        ReflectionTestUtils.setField(manager, "bossPage", mock(Page.class, RETURNS_DEEP_STUBS));
        ReflectionTestUtils.setField(manager, "cookieService", mock(CookieService.class));
    }

    @AfterEach void stop() { manager.destroy(); }

    @Test void bossDoesNotOpenUnusedPlatforms() {
        manager.ensurePlatformReady("boss");
        verify(context, never()).newPage();
        assertNull(manager.getLiepinPage());
        assertNull(manager.getJob51Page());
        assertNull(manager.getZhilianPage());
    }

    @Test void opensLiepinOnlyOnDemandAndReopensClosedTab() {
        Page first = mock(Page.class, RETURNS_DEEP_STUBS);
        Page second = mock(Page.class, RETURNS_DEEP_STUBS);
        when(context.newPage()).thenReturn(first, second);
        manager.ensurePlatformReady("liepin");
        manager.ensurePlatformReady("liepin");
        assertSame(first, manager.getLiepinPage());
        verify(context, times(1)).newPage();
        when(first.isClosed()).thenReturn(true);
        manager.ensurePlatformReady("liepin");
        assertSame(second, manager.getLiepinPage());
        verify(context, times(2)).newPage();
        assertNull(manager.getJob51Page());
        assertNull(manager.getZhilianPage());
    }

    @Test void unknownPlatformDoesNotLaunchBrowser() {
        assertThrows(IllegalArgumentException.class, () -> manager.ensurePlatformReady("other"));
        verifyNoInteractions(context);
    }

    @Test void loginMonitoringThrottlesAndResumesFastChecksWhenLoggedOut() {
        manager.getLoginStatus().put("liepin", true);
        assertTrue(manager.loginProbeDue("liepin"));
        assertFalse(manager.loginProbeDue("liepin"));
        manager.getLastLoginProbe().put("liepin", System.nanoTime() - 4_000_000_000L);
        assertFalse(manager.loginProbeDue("liepin"));
        manager.getLoginStatus().put("liepin", false);
        assertTrue(manager.loginProbeDue("liepin"));
    }

    @Test void backgroundChecksDoNotQueueBehindRunningDelivery() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        assertTrue(manager.tryRunOnPlaywright(() -> {
            started.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(manager.tryRunOnPlaywright(() -> fail("Should not be queued")));
        } finally { release.countDown(); }
        manager.runOnPlaywright(() -> {});
    }
}
