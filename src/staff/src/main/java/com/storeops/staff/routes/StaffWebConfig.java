package com.storeops.staff.routes;

import com.storeops.staff.api.StaffDirectory;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the {@link CurrentUserArgumentResolver} so routes can require authentication. */
@Configuration
public class StaffWebConfig implements WebMvcConfigurer {

    private final StaffDirectory staffDirectory;

    public StaffWebConfig(StaffDirectory staffDirectory) {
        this.staffDirectory = staffDirectory;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver(staffDirectory));
    }
}
