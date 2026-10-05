package com.storeops.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void mapsEachAppErrorToItsStatusAndCode() throws Exception {
        expectError("not-found", 404, "NOT_FOUND");
        expectError("validation", 400, "VALIDATION_ERROR");
        expectError("unauthorized", 401, "UNAUTHORIZED");
        expectError("forbidden", 403, "FORBIDDEN");
        expectError("conflict", 409, "CONFLICT");
    }

    @Test
    void mapsUnexpectedExceptionToInternalError() throws Exception {
        mockMvc.perform(get("/throw/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.statusCode").value(500));
    }

    @Test
    void mapsBeanValidationFailureToValidationError() throws Exception {
        mockMvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void mapsMalformedBodyToValidationError() throws Exception {
        mockMvc.perform(post("/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void mapsTypeMismatchToValidationError() throws Exception {
        mockMvc.perform(get("/number").param("n", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'n'"));
    }

    @Test
    void mapsMissingHeaderToValidationError() throws Exception {
        mockMvc.perform(get("/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private void expectError(String kind, int status, String code) throws Exception {
        mockMvc.perform(get("/throw/" + kind))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.statusCode").value(status));
    }

    record Body(@NotBlank String name) {
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/throw/{kind}")
        String doThrow(@PathVariable String kind) {
            switch (kind) {
                case "not-found" -> throw new NotFoundError("Thing", "1");
                case "validation" -> throw new ValidationError("bad");
                case "unauthorized" -> throw new UnauthorizedError("who?");
                case "forbidden" -> throw new ForbiddenError("no");
                case "conflict" -> throw new ConflictError("dup");
                default -> throw new IllegalStateException("boom");
            }
        }

        @PostMapping("/body")
        String body(@Valid @RequestBody Body body) {
            return body.name();
        }

        @GetMapping("/number")
        int number(@RequestParam int n) {
            return n;
        }

        @GetMapping("/header")
        String header(@RequestHeader("X-Required") String value) {
            return value;
        }
    }
}
