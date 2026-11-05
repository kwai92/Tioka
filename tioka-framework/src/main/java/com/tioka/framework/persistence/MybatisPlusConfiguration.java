package com.tioka.framework.persistence;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.tioka.**.infrastructure.mapper")
public class MybatisPlusConfiguration {
}
