package com.dtc.transit.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.dtc.transit.common.paging.PagingParameterInterceptor;

/** Registers the cross-cutting MVC pieces that every API endpoint relies on. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final PagingParameterInterceptor pagingParameterInterceptor;

    public WebMvcConfig(PagingParameterInterceptor pagingParameterInterceptor) {
        this.pagingParameterInterceptor = pagingParameterInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Limited to the API. Actuator endpoints take no paging parameters, and a stray `page` on a
        // health probe should not fail the probe.
        registry.addInterceptor(pagingParameterInterceptor).addPathPatterns("/api/**");
    }
}
