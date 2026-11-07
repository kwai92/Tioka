package com.tioka.framework.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.tioka.framework.security.PrincipalHolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
@MapperScan(basePackages = "com.tioka", annotationClass = Mapper.class)
@EnableConfigurationProperties(PersistenceProperties.class)
public class MybatisPlusConfiguration {

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor(PrincipalHolder holder, ScopedTables tables) {
        MybatisPlusInterceptor i = new MybatisPlusInterceptor();
        // 仅 workspace 行级过滤；禁止 tenant_id 拦截器（ADR-029 第 60 条）
        i.addInnerInterceptor(new TenantLineInnerInterceptor(
                new ScopeLineHandler("workspace_id", tables, holder)));
        i.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        i.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return i;
    }

    /** M0-P2：TIMESTAMPTZ 列按 UTC LocalDateTime 读写（ADR-029 / ENG-11）。 */
    @Bean
    ConfigurationCustomizer utcLocalDateTimeTypeHandler() {
        return configuration -> {
            UtcLocalDateTimeTypeHandler handler = new UtcLocalDateTimeTypeHandler();
            configuration.getTypeHandlerRegistry().register(LocalDateTime.class, handler);
            configuration.getTypeHandlerRegistry().register(LocalDateTime.class, JdbcType.TIMESTAMP, handler);
            configuration.getTypeHandlerRegistry().register(LocalDateTime.class, JdbcType.TIMESTAMP_WITH_TIMEZONE, handler);
        };
    }
}
