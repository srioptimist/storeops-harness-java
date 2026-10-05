package com.storeops.staff.routes;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.storeops.shared.error.GlobalExceptionHandler;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import com.storeops.staff.model.StaffRole;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = CurrentUserArgumentResolverTest.WhoAmIController.class)
@Import({GlobalExceptionHandler.class, StaffWebConfig.class, CurrentUserArgumentResolverTest.WhoAmIController.class})
class CurrentUserArgumentResolverTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StaffDirectory staffDirectory;

    @Test
    void resolvesUserFromBearerToken() throws Exception {
        when(staffDirectory.authenticate("good")).thenReturn(Optional.of(
                new AuthenticatedUser("u1", "store-001", "region-north", StaffRole.ASSOCIATE)));

        mockMvc.perform(get("/whoami").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(content().string("u1"));
    }

    @Test
    void rejectsMissingHeader() throws Exception {
        mockMvc.perform(get("/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsNonBearerScheme() throws Exception {
        mockMvc.perform(get("/whoami").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidToken() throws Exception {
        when(staffDirectory.authenticate("bad")).thenReturn(Optional.empty());

        mockMvc.perform(get("/whoami").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));
    }

    @RestController
    static class WhoAmIController {

        @GetMapping("/whoami")
        String whoAmI(AuthenticatedUser user) {
            return user.userId();
        }
    }
}
