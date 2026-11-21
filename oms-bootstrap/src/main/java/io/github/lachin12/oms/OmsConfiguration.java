package io.github.lachin12.oms;

import io.github.lachin12.oms.application.OmsService;
import io.github.lachin12.oms.infrastructure.JdbcOmsStore;
import io.github.lachin12.oms.infrastructure.SpringUnitOfWork;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
public class OmsConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    JdbcOmsStore orderStore(JdbcTemplate jdbc) { return new JdbcOmsStore(jdbc); }

    @Bean
    SpringUnitOfWork unitOfWork(PlatformTransactionManager manager) { return new SpringUnitOfWork(manager); }

    @Bean
    OmsService omsService(JdbcOmsStore store, SpringUnitOfWork transactions, Clock clock) {
        return new OmsService(store, store, store, transactions, clock, UUID::randomUUID);
    }
}
