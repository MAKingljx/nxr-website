package com.nxr.platform.admin;

import static org.assertj.core.api.Assertions.*;
import com.nxr.platform.admin.storage.*;
import com.nxr.platform.commerce.OrderAccessScopeService.AccessScope;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class AdminMediaQueueSqlTest {
    @TempDir Path files;
    JdbcTemplate jdbc;
    DataSource source;
    Connection connection;
    final Set<String> createdTemps=new LinkedHashSet<>();
    AdminMediaService service;
    MediaStorageRegistry registry;
    final AtomicInteger fileChecks=new AtomicInteger();
    final AtomicInteger referenceChecks=new AtomicInteger();
    boolean injectFailure;
    final AccessScope all=new AccessScope(true,List.of(),List.of());
    protected Connection openConnection()throws Exception {
        return DriverManager.getConnection("jdbc:h2:mem:queue-sql-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
    }
    @BeforeEach void setup()throws Exception {
        connection=track(openConnection());source=new SingleConnectionDataSource(connection,true);jdbc=new JdbcTemplate(source);
        schema();
        Files.createDirectories(files.resolve("staged"));Files.createDirectories(files.resolve("published"));Files.createDirectories(files.resolve("legacy"));
        var local=new LocalMediaStorageProvider(files.toString(),"http://localhost:8090","local-media") {
            @Override public boolean referenceExists(StoredMediaLocation location) {fileChecks.incrementAndGet();if(injectFailure)throw new IllegalStateException("injected file failure");return super.referenceExists(location);}
        };
        var legacy=new LegacyPythonMediaStorageProvider(files.resolve("legacy").toString()) {
            @Override public boolean referenceExists(StoredMediaLocation location) {fileChecks.incrementAndGet();return super.referenceExists(location);}
        };
        registry=new MediaStorageRegistry(List.of(local,legacy,new Remote()),"local");
        service=new AdminMediaService(JdbcClient.create(jdbc),jdbc,null,registry,12,1024,2048,100000) {
            @Override boolean isReferenceAvailable(String stage,String provider,String bucket,String key,String version,String url) {
                referenceChecks.incrementAndGet();return super.isReferenceAvailable(stage,provider,bucket,key,version,url);
            }
        };
        seed();
    }
    @AfterEach void teardown()throws Exception {if(connection!=null)connection.close();}
    void schema() {
        for(String ddl:List.of(
            "CREATE TABLE grading_submission(id BIGINT PRIMARY KEY,cert_id VARCHAR(64),card_name VARCHAR(255),set_name VARCHAR(255),brand_name VARCHAR(128),language_code VARCHAR(32),status_code VARCHAR(32),product_type_code VARCHAR(32),card_category_code VARCHAR(32),vintage_classification_code VARCHAR(64),merch_description TEXT,approved_at TIMESTAMP NULL,published_at TIMESTAMP NULL,updated_at TIMESTAMP NULL,order_origin_code VARCHAR(32),business_line_id BIGINT,work_center_id BIGINT)",
            "CREATE TABLE grading_score(submission_id BIGINT PRIMARY KEY,final_grade_value DECIMAL(4,1),final_grade_label VARCHAR(64))",
            "CREATE TABLE submission_upload_state(submission_id BIGINT PRIMARY KEY,status_code VARCHAR(32),started_at TIMESTAMP NULL,completed_at TIMESTAMP NULL,error_message TEXT)",
            "CREATE TABLE submission_media(id BIGINT AUTO_INCREMENT PRIMARY KEY,submission_id BIGINT,media_stage_code VARCHAR(16),media_side_code VARCHAR(16),storage_provider_code VARCHAR(64),storage_bucket VARCHAR(128),storage_key VARCHAR(255),storage_object_version VARCHAR(128),checksum_sha256 VARCHAR(128),public_url VARCHAR(512),sort_order INT DEFAULT 1,is_active INT DEFAULT 1,UNIQUE(submission_id,media_stage_code,media_side_code,sort_order))",
            "CREATE TABLE grading_order(id BIGINT PRIMARY KEY,business_line_id BIGINT,work_center_id BIGINT)",
            "CREATE TABLE grading_order_item(id BIGINT PRIMARY KEY,order_id BIGINT,grading_submission_id BIGINT)",
            "CREATE TABLE commerce_business_line(id BIGINT PRIMARY KEY,is_active INT)",
            "CREATE TABLE commerce_work_center(id BIGINT PRIMARY KEY,is_active INT)",
            "CREATE TABLE transaction_sentinel(id INT PRIMARY KEY,marker VARCHAR(30))"
        ))jdbc.execute(ddl);
        jdbc.update("INSERT INTO commerce_business_line VALUES(1,1),(2,1)");jdbc.update("INSERT INTO commerce_work_center VALUES(1,1),(2,1)");
    }
    void seed()throws Exception {
        String[] names={"Café_100%","Cafe_100%","Straße","STRASSE","İstanbul","東京","Pikachu","Pikachu","Pikachu","Pikachu","Pikachu","Pikachu"};
        for(int i=1;i<=12;i++) {
            jdbc.update("INSERT INTO grading_submission(id,cert_id,card_name,set_name,brand_name,language_code,status_code,product_type_code,card_category_code,approved_at,updated_at,order_origin_code,business_line_id,work_center_id) VALUES(?,?,?,?,?,?,?,?,?,TIMESTAMP '2026-10-19 00:00:00',TIMESTAMP '2026-10-19 00:00:00','owned_inventory',?,?)",
                i,"CERT_"+i,names[i-1],i%2==0?"SET%":"SET_","Iİıi",i%2==0?"EN":"en","approved",i==2?"label\t product":"graded_card",i==3?null:"trading_card",i%2+1,i%2+1);
            jdbc.update("INSERT INTO grading_score VALUES(?,?,?)",i,"9.5",i==3?"Pristine 10":"9.50");
            jdbc.update("INSERT INTO submission_upload_state(submission_id,status_code) VALUES(?,?)",i,switch(i){case 3->"client_pushed";case 4->"uploading";case 5,6->"uploaded";default->"not_started";});
        }
        for(String side:List.of("front","back")) {
            local(1,"staged",side,true);local(2,"staged",side,"front".equals(side));
            local(3,"staged",side,true);local(4,"staged",side,true);
            media(5,"published",side,"r2","bucket","key-"+side,"https://cdn.invalid/"+side);
            local(5,"staged",side,true); // Uploaded cards can still have pending replacement images.
            media(6,"published",side,"legacy-python","python-public","CERT6/"+side,"https://legacy.invalid/"+side);
            media(7,"staged",side,"legacy-python","python-admin-uploads","legacy-"+side+".jpg","/media/staged/legacy-"+side+".jpg");
            Files.writeString(files.resolve("legacy/legacy-"+side+".jpg"),"fixture");
            media(8,"published",side,"unknown","bucket",null,"https://unknown.invalid/"+side);
            media(9,"published",side,"r2","bucket","\t\n",null);
            media(10,"published",side,"r2","bucket","key-"+side,null);
            media(11,"published",side,"ŕ2","bucket","key-"+side,null);
            media(12,"published",side,"local","wrong-bucket","bad-"+side,"https://cdn.invalid/bad");
        }
    }
    void local(long id,String stage,String side,boolean exists)throws Exception {
        String key=id+"-"+side+".webp";media(id,stage,side,"local","local-media",key,"/media/"+stage+"/"+key);
        if(exists)Files.writeString(files.resolve(stage).resolve(key),"fixture");
    }
    void media(long id,String stage,String side,String provider,String bucket,String key,String url) {
        jdbc.update("INSERT INTO submission_media(submission_id,media_stage_code,media_side_code,storage_provider_code,storage_bucket,storage_key,public_url,checksum_sha256) VALUES(?,?,?,?,?,?,?,?)",id,stage,side,provider,bucket,key,url,"sum-"+id+"-"+side);
    }
    AdminMediaService.MediaQueuePage page(String[] f,String upload,String image,int p,int size,AccessScope scope) {
        return service.loadQueuePage(f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],upload,image,true,p,size,scope);
    }
    AdminMediaService.MediaQueueResponse oracle(String[] f,String upload,String image,int p,int size,java.util.function.LongPredicate access) {
        return service.loadQueue(f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],upload,image,true,p,size,access);
    }
    @Test void allImageAndUploadFiltersMatchOriginalFilesystemOracle() {
        String[] empty=new String[8];
        for(String image:Arrays.asList(null,"ready","waiting","published","missing_any","missing_front","missing_back"))
            for(String upload:Arrays.asList(null,"not_started","uploaded","uploading","client_pushed","remaining_uploads","uploaded_to_server")) {
                var expected=oracle(empty,upload,image,99,3,id->true);var actual=page(empty,upload,image,99,3,all);
                assertThat(actual.items()).as("image=%s status=%s",image,upload).isEqualTo(expected.items());
                assertThat(actual.total()).isEqualTo(expected.total());assertThat(actual.page()).isEqualTo(expected.page());
            }
        assertThat(service.loadQueueSummary(all)).isEqualTo(oracle(empty,null,null,1,20,id->true).summary());
        assertNoTemps();
    }
    @Test void unicodeLiteralTextAliasesAndGradesMatchTheJavaOracle() {
        List<String[]> filters=new ArrayList<>();
        for(String term:List.of("cafe","café","_100%","strasse","İstanbul","東京","SET%","CERT_1")) {String[] f=new String[8];f[0]=term;filters.add(f);}
        for(int index:List.of(1,2,3,4,5,6,7)) {String[] f=new String[8];f[index]=switch(index){case 1->"_1";case 2->"café";case 3->"trading-card";case 4->"merch_product";case 5->"iiii";case 6->"EN";default->"9.500";};filters.add(f);}
        for(String[] f:filters) {
            var expected=oracle(f,null,null,1,20,id->true);var actual=page(f,null,null,1,20,all);
            assertThat(actual.total()).as(Arrays.toString(f)).isEqualTo(expected.total());assertThat(actual.items()).isEqualTo(expected.items());
        }
        assertNoTemps();
    }
    @Test void scopesExcludeLinkedForeignOrdersAndNeverBroadenSummary() {
        jdbc.update("INSERT INTO grading_order VALUES(1,1,1),(2,2,2)");
        jdbc.update("INSERT INTO grading_order_item VALUES(1,2,2),(2,1,3)");
        AccessScope scope=new AccessScope(false,List.of(1L),List.of(1L));
        Set<Long> allowed=Set.of(3L,4L,6L,8L,10L,12L);
        for(String image:Arrays.asList(null,"ready","published","waiting","missing_any")) {
            var expected=oracle(new String[8],null,image,1,20,allowed::contains);var actual=page(new String[8],null,image,1,20,scope);
            assertThat(actual.items()).isEqualTo(expected.items());assertThat(actual.total()).isEqualTo(expected.total());
        }
        assertThat(service.loadQueueSummary(scope)).isEqualTo(oracle(new String[8],null,null,1,20,allowed::contains).summary());
        assertThat(page(new String[8],null,"ready",1,20,new AccessScope(false,List.of(),List.of())).total()).isZero();
    }
    @Test void interruptedUploadsAreReadOnlyAndAgreeAcrossFiltersPageAndSummary() {
        var now=java.time.LocalDateTime.of(2026,10,19,12,0);
        var lease=new MediaUploadLease(java.time.Clock.fixed(now.toInstant(java.time.ZoneOffset.UTC),java.time.ZoneOffset.UTC));
        service=new AdminMediaService(JdbcClient.create(jdbc),jdbc,null,registry,12,1024,2048,100000,lease);
        jdbc.update("UPDATE submission_upload_state SET status_code='uploading',started_at=?,error_message='stored lease evidence' WHERE submission_id=1",now.minusMinutes(15));
        jdbc.update("UPDATE submission_upload_state SET status_code='uploading',started_at=? WHERE submission_id=2",now.minusMinutes(15).minusSeconds(1));
        jdbc.update("UPDATE submission_upload_state SET status_code='uploading',started_at=? WHERE submission_id=3",now.minusMinutes(15).plusSeconds(1));
        jdbc.update("UPDATE submission_upload_state SET status_code='uploading',started_at=NULL WHERE submission_id=4");
        jdbc.update("UPDATE submission_upload_state SET status_code='failed',started_at=?,error_message='ordinary failure' WHERE submission_id=5",now.minusMinutes(30));
        var before=businessSnapshot();
        var failed=page(new String[8],"failed",null,1,20,all);
        assertThat(failed.total()).isEqualTo(3);
        assertThat(failed.items()).extracting(AdminMediaService.MediaQueueItem::submissionId).containsExactly(5L,2L,1L);
        assertThat(failed.items().get(0).uploadInterrupted()).isFalse();
        assertThat(failed.items().get(0).uploadError()).isEqualTo("ordinary failure");
        assertThat(failed.items().get(1).uploadInterrupted()).isTrue();
        assertThat(failed.items().get(1).readyToPublish()).isFalse();
        assertThat(failed.items().get(2).uploadInterrupted()).isTrue();
        assertThat(failed.items().get(2).readyToPublish()).isTrue();
        assertThat(failed.items().get(2).uploadError()).isEqualTo(MediaUploadLease.INTERRUPTED_MESSAGE);
        var active=page(new String[8],"uploading",null,1,20,all);
        assertThat(active.items()).extracting(AdminMediaService.MediaQueueItem::submissionId).containsExactly(4L,3L);
        assertThat(active.items()).allMatch(item->!item.uploadInterrupted()&&!item.readyToPublish());
        var ready=page(new String[8],null,"ready",99,1,all);
        assertThat(ready.total()).isEqualTo(3);assertThat(ready.page()).isEqualTo(3);
        assertThat(ready.items()).singleElement().satisfies(item->{assertThat(item.submissionId()).isEqualTo(1);assertThat(item.uploadInterrupted()).isTrue();});
        var summary=service.loadQueueSummary(all);
        assertThat(summary.readyToPublish()).isEqualTo(3);assertThat(summary.statusCounts()).containsEntry("failed",3).containsEntry("uploading",2);
        assertThat(oracle(new String[8],null,null,1,20,id->true).summary()).isEqualTo(summary);
        assertThat(businessSnapshot()).isEqualTo(before);assertNoTemps();
    }
    @Test void oneRequestKeepsOneCutoffEvenWhenTheClockMovesDuringPagination() {
        var initial=java.time.Instant.parse("2026-10-19T10:00:00Z");
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        java.time.Clock moving=new java.time.Clock() {
            public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            public java.time.Clock withZone(java.time.ZoneId zone){return this;}
            public java.time.Instant instant(){return initial.plusSeconds(3600L*reads.getAndIncrement());}
        };
        jdbc.update("UPDATE submission_upload_state SET status_code='uploading',started_at=TIMESTAMP '2026-10-19 09:46:00' WHERE submission_id=1");
        var before=businessSnapshot();
        service=new AdminMediaService(JdbcClient.create(jdbc),jdbc,null,registry,12,1024,2048,100000,new MediaUploadLease(moving));
        var result=service.loadQueue(null,null,null,null,null,null,null,null,"uploading",null,true,1,20,all);
        assertThat(reads.get()).isOne();assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).allMatch(item->"uploading".equals(item.uploadStatus())&&!item.uploadInterrupted());
        assertThat(result.summary().statusCounts()).containsEntry("uploading",2);
        assertThat(businessSnapshot()).isEqualTo(before);assertNoTemps();
    }
    private Map<String,List<Map<String,Object>>> businessSnapshot() {
        Map<String,List<Map<String,Object>>> rows=new LinkedHashMap<>();
        for(String table:List.of("grading_submission","submission_media","submission_upload_state","grading_score"))
            rows.put(table,jdbc.queryForList("SELECT * FROM "+table+" ORDER BY 1"));
        return rows;
    }

    @Test void temporaryEvidenceIsCleanedOnFailureAndCannotCommitTheCallersTransaction() {
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(status->{
            jdbc.update("INSERT INTO transaction_sentinel VALUES(1,'uncommitted')");
            page(new String[8],null,"ready",1,20,all);if(isMySql())assertNoTemps();status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_sentinel",Integer.class)).isZero();assertNoTemps();
        injectFailure=true;
        assertThatThrownBy(()->page(new String[8],null,"ready",1,20,all)).hasMessageContaining("injected file failure");
        injectFailure=false;assertNoTemps();
        readOnlyCallerCheck();
    }
    void readOnlyCallerCheck() {
        var txRead=new TransactionTemplate(new DataSourceTransactionManager(source));txRead.setReadOnly(true);
        txRead.executeWithoutResult(status->{page(new String[8],null,"ready",1,20,all);service.loadQueueSummary(all);});assertNoTemps();
    }
    void assertNoTemps() {
        for(String table:createdTemps)assertThatThrownBy(()->jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class))
            .isInstanceOf(org.springframework.jdbc.BadSqlGrammarException.class);
        createdTemps.clear();
    }
    private Connection track(Connection actual) {
        return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
            try {
                if(method.getName().equals("prepareStatement")&&args[0] instanceof String text)recordSql(text);
                Object result=method.invoke(actual,args);
                if(method.getName().equals("createStatement"))return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(p,m,a)->{
                    if(a!=null&&a.length>0&&a[0] instanceof String text)recordSql(text);
                    try{return m.invoke(result,a);}catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}
                });
                return result;
            }catch(java.lang.reflect.InvocationTargetException ex){throw ex.getCause();}
        });
    }
    private void recordSql(String text) {
        var matcher=java.util.regex.Pattern.compile("(?i)CREATE (?:LOCAL )?TEMPORARY TABLE (tmp_media_queue_[a-z_0-9]+)").matcher(text);
        if(matcher.find())createdTemps.add(matcher.group(1));
    }
    boolean isMySql(){try{return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql");}catch(SQLException e){throw new IllegalStateException(e);}}
    static class Remote implements MediaStorageProvider {
        public String providerCode(){return "r2";}public boolean manages(String code){return code!=null&&"r2".equalsIgnoreCase(code.trim());}
        public StoredMediaObject store(String s,String c,String d,String e,MediaUpload u){throw new UnsupportedOperationException();}
        public StoredMediaObject copy(String s,String c,String d,StoredMediaSource u){throw new UnsupportedOperationException();}
        public ResolvedMediaAsset resolve(StoredMediaLocation l){throw new UnsupportedOperationException();}
        public void deleteIfPresent(StoredMediaLocation l){throw new UnsupportedOperationException();}
    }
}
