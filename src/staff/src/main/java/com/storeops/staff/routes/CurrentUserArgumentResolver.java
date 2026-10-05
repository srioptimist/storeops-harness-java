package com.storeops.staff.routes;

import com.storeops.shared.error.UnauthorizedError;
import com.storeops.staff.api.AuthenticatedUser;
import com.storeops.staff.api.StaffDirectory;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link AuthenticatedUser} controller parameters from the bearer token, raising
 * {@link UnauthorizedError} when the token is missing or invalid.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final StaffDirectory staffDirectory;

    public CurrentUserArgumentResolver(StaffDirectory staffDirectory) {
        this.staffDirectory = staffDirectory;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedUser.class.equals(parameter.getParameterType());
    }

    @Override
    public AuthenticatedUser resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedError("Missing bearer token");
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return staffDirectory.authenticate(token)
                .orElseThrow(() -> new UnauthorizedError("Invalid or expired token"));
    }
}
