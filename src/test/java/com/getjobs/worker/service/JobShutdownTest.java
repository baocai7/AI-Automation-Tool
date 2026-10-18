package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.manager.PlaywrightManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobShutdownTest {
    @Test void contextShutdownStopsAllPlatformsBeforeBrowserCleanup() {
        PlaywrightManager manager = mock(PlaywrightManager.class);
        ConfigService config = mock(ConfigService.class);
        BossJobService boss = new BossJobService(manager, config, mock(ObjectProvider.class));
        LiepinJobService liepin = new LiepinJobService(manager, config, mock(ObjectProvider.class));
        Job51JobService job51 = new Job51JobService(manager, mock(ObjectProvider.class), config);
        ZhilianJobService zhilian = new ZhilianJobService(manager, mock(ObjectProvider.class), config);
        var services = java.util.List.of(boss, liepin, job51, zhilian);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(BossJobService.class, () -> boss);
            context.registerBean(LiepinJobService.class, () -> liepin);
            context.registerBean(Job51JobService.class, () -> job51);
            context.registerBean(ZhilianJobService.class, () -> zhilian);
            context.refresh();
            services.forEach(service -> ReflectionTestUtils.setField(service, "isRunning", true));
            context.close();
            assertTrue(boss.shouldStop());
            assertTrue(liepin.shouldStop());
            assertTrue(job51.shouldStop());
            assertTrue(zhilian.shouldStop());
            services.forEach(service -> service.executeDelivery(message -> fail("Shutdown must not start delivery")));
            verifyNoInteractions(manager);
        } finally {
            // Let Boss's existing stop watchdog exit immediately.
            services.forEach(service -> ReflectionTestUtils.setField(service, "isRunning", false));
        }
    }
}
