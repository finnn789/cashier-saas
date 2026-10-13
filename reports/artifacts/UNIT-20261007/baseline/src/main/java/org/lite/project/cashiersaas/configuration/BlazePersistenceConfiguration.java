package org.lite.project.cashiersaas.configuration;

import com.blazebit.persistence.Criteria;
import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.spi.CriteriaBuilderConfiguration;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class BlazePersistenceConfiguration {

    @Bean
    @Lazy(false)
    public CriteriaBuilderFactory criteriaBuilderFactory(EntityManagerFactory entityManagerFactory) {
        CriteriaBuilderConfiguration configuration = Criteria.getDefault();
        return configuration.createCriteriaBuilderFactory(entityManagerFactory);
    }
}
