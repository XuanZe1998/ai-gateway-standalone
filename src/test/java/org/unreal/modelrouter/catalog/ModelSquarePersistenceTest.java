package org.unreal.modelrouter.catalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;
import org.unreal.modelrouter.persistence.jpa.entity.ModelSquareContentEntity;
import org.unreal.modelrouter.persistence.jpa.repository.ModelSquareContentRepository;
import java.time.LocalDateTime;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: URL must point at the isolated restore, never production. */
@EnabledIfEnvironmentVariable(named="MODEL_SQUARE_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/model_square_rehearsal")
class ModelSquarePersistenceTest {
    @Test void realPostgresqlJpaRoundTripUpdateAndReset(){
        var ds=new DriverManagerDataSource(System.getenv("MODEL_SQUARE_TEST_DB_URL"),"rehearsal",System.getenv("MODEL_SQUARE_TEST_DB_PASSWORD"));
        var factory=new LocalContainerEntityManagerFactoryBean();factory.setDataSource(ds);factory.setPackagesToScan("org.unreal.modelrouter.persistence.jpa.entity");factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none"));factory.afterPropertiesSet();
        var emf=factory.getObject(); assertNotNull(emf);
        var em=org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(emf);
        var repo=new JpaRepositoryFactory(em).getRepository(ModelSquareContentRepository.class);
        var tx=new TransactionTemplate(new JpaTransactionManager(emf));
        try {
            tx.executeWithoutResult(s->{var e=new ModelSquareContentEntity();e.setContentKey("chat:persistence-probe");e.setServiceType("chat");e.setModelId("persistence-probe");e.setDisplayName("测试展示名");e.setDescription("<b>纯文本</b>");e.setTags("[\"测试标签\"]");e.setUpdatedBy("rehearsal-only");e.setUpdatedAt(LocalDateTime.now());repo.saveAndFlush(e);});
            tx.executeWithoutResult(s->{var e=repo.findById("chat:persistence-probe").orElseThrow();assertEquals("测试展示名",e.getDisplayName());assertEquals("[\"测试标签\"]",e.getTags());assertEquals("<b>纯文本</b>",e.getDescription());e.setDisplayName("已更新");repo.saveAndFlush(e);});
            tx.executeWithoutResult(s->{assertEquals("已更新",repo.findById("chat:persistence-probe").orElseThrow().getDisplayName());repo.deleteById("chat:persistence-probe");repo.flush();});
            tx.executeWithoutResult(s->assertTrue(repo.findById("chat:persistence-probe").isEmpty()));
        } finally {factory.destroy();}
    }
}
