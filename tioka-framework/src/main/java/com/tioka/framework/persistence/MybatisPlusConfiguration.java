package com.tioka.framework.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackages = "com.tioka", annotationClass = Mapper.class)
public class MybatisPlusConfiguration {
}
