package com.tioka.framework.persistence;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.tioka.framework.security.PrincipalHolder;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackages = "com.tioka", annotationClass = Mapper.class)
@EnableConfigurationProperties(PersistenceProperties.class)
public class MybatisPlusConfiguration {

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor(PrincipalHolder holder, ScopedTables tables) {
        MybatisPlusInterceptor i = new MybatisPlusInterceptor();
        // 仅 workspace 行级过滤；禁止 tenant_id 拦截器（ADR-029 第 36 条）
        i.addInnerInterceptor(new TenantLineInnerInterceptor(
                new ScopeLineHandler("workspace_id", tables, holder)));
        i.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        i.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return i;
    }
}
