package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Opt-in localhost-only MySQL 8 acceptance, with a new test-owned database per case. */
@EnabledIfEnvironmentVariable(named="NXR_RUN_MEDIA_QUEUE_MYSQL",matches="1")
class AdminMediaQueueMySqlTest extends AdminMediaQueueSqlTest {
    private String database;
    private String url;
    private String user;
    private String password;
    @Override protected Connection openConnection()throws Exception {
        int port=Integer.parseInt(System.getenv().getOrDefault("NXR_MEDIA_QUEUE_MYSQL_PORT","13418"));
        url="jdbc:mysql://127.0.0.1:"+port+"/";
        user=System.getenv().getOrDefault("NXR_MEDIA_QUEUE_MYSQL_USER","root");password=System.getenv("NXR_MEDIA_QUEUE_MYSQL_PASSWORD");
        database="nxr_media_queue_test_"+UUID.randomUUID().toString().replace("-", "");
        try(var admin=DriverManager.getConnection(url+"?allowPublicKeyRetrieval=true&sslMode=DISABLED",user,password);var s=admin.createStatement()) {
            assertThat(admin.getMetaData().getDatabaseProductVersion()).startsWith("8.0.46");
            s.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        return DriverManager.getConnection(url+database+"?allowPublicKeyRetrieval=true&sslMode=DISABLED",user,password);
    }
    @AfterEach void dropTestDatabase()throws Exception {
        if(database!=null)try(var admin=DriverManager.getConnection(url+"?allowPublicKeyRetrieval=true&sslMode=DISABLED",user,password);var s=admin.createStatement()) {
            assertThat(database).matches("nxr_media_queue_test_[a-f0-9]{32}");s.execute("DROP DATABASE "+database);
        }
    }
    @Override void readOnlyCallerCheck() {
        var data=new org.springframework.jdbc.datasource.DriverManagerDataSource(url+database+"?allowPublicKeyRetrieval=true&sslMode=DISABLED",user,password);
        var template=new JdbcTemplate(data);
        var target=new AdminMediaService(org.springframework.jdbc.core.simple.JdbcClient.create(template),template,null,registry,12,1024,2048,100000);
        var manager=new DataSourceTransactionManager(data);
        var proxy=new org.springframework.aop.framework.ProxyFactory(target);proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        var transactional=(AdminMediaService)proxy.getProxy();
        var caller=new TransactionTemplate(manager);caller.setReadOnly(true);
        caller.executeWithoutResult(status->{
            try {assertThat(org.springframework.jdbc.datasource.DataSourceUtils.getConnection(data).isReadOnly()).isTrue();}catch(SQLException ex){throw new RuntimeException(ex);}
            assertThat(transactional.loadQueuePage(null,null,null,null,null,null,null,null,null,"ready",true,1,20,all).total()).isEqualTo(3);
            assertThat(transactional.loadQueueSummary(all).trackedEntries()).isEqualTo(12);
            assertThat(template.queryForObject("SELECT COUNT(*) FROM grading_submission",Integer.class)).isEqualTo(12);
        });
    }

    @Test void largeQueueUsesSqlPagingAndOnlyChecksPhysicalReferences() {
        jdbc.execute("SET SESSION cte_max_recursion_depth=225000");
        jdbc.execute("INSERT INTO grading_submission(id,cert_id,card_name,status_code,product_type_code,card_category_code,approved_at,updated_at,order_origin_code,business_line_id,work_center_id) WITH RECURSIVE n AS(SELECT 100 AS id UNION ALL SELECT id+1 FROM n WHERE id<224099) SELECT id,CONCAT('BULK',id),'Synthetic','published','graded_card','trading_card',TIMESTAMP '2026-10-19 01:00:00',TIMESTAMP '2026-10-19 01:00:00','owned_inventory',1,1 FROM n");
        jdbc.execute("INSERT INTO submission_upload_state(submission_id,status_code) SELECT id,'uploaded' FROM grading_submission WHERE id>=100");
        jdbc.execute("INSERT INTO submission_media(submission_id,media_stage_code,media_side_code,storage_provider_code,storage_bucket,storage_key,public_url) SELECT id,'published','front','r2','bucket',CONCAT('key-front-',id),CONCAT('https://cdn.invalid/front-',id) FROM grading_submission WHERE id>=100 AND id<200100");
        jdbc.execute("INSERT INTO submission_media(submission_id,media_stage_code,media_side_code,storage_provider_code,storage_bucket,storage_key,public_url) SELECT id,'published','back','r2','bucket',CONCAT('key-back-',id),CONCAT('https://cdn.invalid/back-',id) FROM grading_submission WHERE id>=100 AND id<200100");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        fileChecks.set(0);long start=System.nanoTime();
        var allPage=tx.execute(status->page(new String[8],null,null,1,12,all));long pageMs=(System.nanoTime()-start)/1_000_000;
        assertThat(allPage.total()).isEqualTo(224012);assertThat(allPage.items()).hasSize(12);assertThat(fileChecks.get()).isZero();
        start=System.nanoTime();var ready=tx.execute(status->page(new String[8],null,"ready",1,12,all));long readyMs=(System.nanoTime()-start)/1_000_000;
        assertThat(ready.items()).extracting(AdminMediaService.MediaQueueItem::submissionId).containsExactly(7L,5L,1L);assertThat(fileChecks.get()).isLessThan(30);
        int readyChecks=fileChecks.get();fileChecks.set(0);start=System.nanoTime();var summary=tx.execute(status->service.loadQueueSummary(all));long summaryMs=(System.nanoTime()-start)/1_000_000;
        assertThat(summary.trackedEntries()).isEqualTo(224012);assertThat(summary.readyToPublish()).isEqualTo(3);assertThat(summary.livePublished()).isEqualTo(200004);assertThat(fileChecks.get()).isLessThan(30);
        int optimizedReferences=referenceChecks.get();referenceChecks.set(0);start=System.nanoTime();
        var previous=oracle(new String[8],null,"ready",1,12,id->true);long legacyMs=(System.nanoTime()-start)/1_000_000;
        assertThat(ready.items()).isEqualTo(previous.items());assertThat(ready.total()).isEqualTo(previous.total());
        System.out.printf("QUEUE_MYSQL8_PERFORMANCE rows=%d remoteMedia=400000 pageMs=%d readyMs=%d summaryMs=%d legacyReadyAndSummaryMs=%d pageFileChecks=0 readyFileChecks=%d summaryFileChecks=%d optimizedReferenceChecks=%d legacyReferenceChecks=%d%n",allPage.total(),pageMs,readyMs,summaryMs,legacyMs,readyChecks,14,optimizedReferences,referenceChecks.get());
        assertNoTemps();
    }
}
