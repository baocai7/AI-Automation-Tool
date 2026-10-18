package com.getjobs.application.controller;

import com.getjobs.application.service.CookieService;
import com.getjobs.application.service.Job51Service;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.Job51JobService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobControllerTest {
    @Test void reconnectingDoesNotAccumulateLoginListeners() {
        PlaywrightManager manager = new PlaywrightManager();
        JobController controller = new JobController(mock(Job51Service.class), mock(Job51JobService.class), manager, mock(CookieService.class));
        controller.registerLoginStatusListener();
        for (int i = 0; i < 20; i++) controller.streamLoginStatus().complete();
        assertEquals(1, manager.getLoginStatusListeners().size());
        controller.unregisterLoginStatusListener();
        assertTrue(manager.getLoginStatusListeners().isEmpty());
        manager.destroy();
    }
}
