package com.nxr.platform.admin;

import com.nxr.platform.admin.AdminMediaService.MediaQueueSummary;
import com.nxr.platform.admin.AdminMediaService.QueueFilters;
import com.nxr.platform.admin.storage.MediaStorageProvider;
import com.nxr.platform.admin.storage.MediaStorageRegistry;
import com.nxr.platform.commerce.OrderAccessScopeService;
import com.nxr.platform.commerce.OrderAccessScopeService.AccessScope;
import com.nxr.platform.shared.ProductTypePolicy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** SQL paging with exact, request-local filesystem evidence; never persists business data. */
final class AdminMediaQueueReader {
    private static final int CHUNK = 512;
    private static final String ORDER = " ORDER BY COALESCE(s.approved_at,s.published_at,s.updated_at) DESC,s.id DESC";
    private static final String BASE_FROM = " FROM grading_submission s LEFT JOIN submission_upload_state us ON us.submission_id=s.id LEFT JOIN grading_score g ON g.submission_id=s.id";
    private static final String MEDIA_JOINS = mediaJoin("sf", "staged", "front") + mediaJoin("sb", "staged", "back")
        + mediaJoin("pf", "published", "front") + mediaJoin("pb", "published", "back");
    private final JdbcTemplate template;
    private final MediaStorageRegistry registry;
    private final ReferenceCheck referenceCheck;
    private final MediaUploadLease.Snapshot lease;

    @FunctionalInterface interface ReferenceCheck {
        boolean available(String stage,String provider,String bucket,String key,String version,String url);
    }
    record PageIds(List<Long> ids,int page,int pageSize,int total) { }
    AdminMediaQueueReader(JdbcTemplate template,MediaStorageRegistry registry,ReferenceCheck check,MediaUploadLease.Snapshot lease) {
        this.template=template;this.registry=registry;this.referenceCheck=check;this.lease=lease;
    }
    PageIds page(QueueFilters filters,int page,int size,AccessScope scope) {
        return template.execute((ConnectionCallback<PageIds>) connection -> {
            try (Read read=new Read(connection,scope)) { return read.page(filters,page,size); }
        });
    }
    MediaQueueSummary summary(AccessScope scope) {
        return template.execute((ConnectionCallback<MediaQueueSummary>) connection -> {
            try (Read read=new Read(connection,scope)) { return read.summary(); }
        });
    }
    private static String mediaJoin(String alias,String stage,String side) {
        return " LEFT JOIN submission_media "+alias+" ON "+alias+".submission_id=s.id AND "+alias+".media_stage_code='"+stage
            +"' AND "+alias+".media_side_code='"+side+"' AND "+alias+".sort_order=1 AND "+alias+".is_active=1";
    }
    private final class Read implements AutoCloseable {
        final NamedParameterJdbcTemplate sql;
        final JdbcTemplate direct;
        final boolean mysql;
        final Map<String,Object> params=new LinkedHashMap<>();
        final List<String> tables=new ArrayList<>();
        final String suffix=UUID.randomUUID().toString().replace("-", "");
        final String scopeWhere;
        String textJoin="", availabilityJoin="", availableTable;
        List<String> optimistic=List.of(), physical=List.of();
        Read(Connection connection,AccessScope scope)throws SQLException {
            var dataSource=new SingleConnectionDataSource(connection,true);
            this.sql=new NamedParameterJdbcTemplate(dataSource);this.direct=new JdbcTemplate(dataSource);
            mysql=connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
            params.put("nonblank", "[^\\x{0}-\\x{20}]");
            params.put("leaseCutoff",lease.cutoff());
            if(scope.unrestricted())scopeWhere="";
            else if(scope.denied())scopeWhere=" AND 1=0";
            else {
                scopeWhere=OrderAccessScopeService.submissionSqlPredicate("s");
                params.put("scopeLineIds",scope.safeBusinessLineIds());params.put("scopeCenterIds",scope.safeWorkCenterIds());
            }
        }
        String binary(String value) { return mysql?"CAST("+value+" AS BINARY)":"CAST("+value+" AS VARBINARY)"; }
        String base() { return " WHERE s.status_code IN ('approved','published')"+scopeWhere; }
        String upload() {
            String raw="COALESCE(us.status_code,'not_started')";
            return "CASE WHEN "+equal(raw,"'uploading'")+" AND us.started_at IS NOT NULL AND us.started_at<=:leaseCutoff"
                +" THEN 'failed' ELSE "+raw+" END";
        }
        String equal(String left,String right) { return binary(left)+"="+binary(right); }
        String exactStatus(String value) { return equal(upload(),"'"+value+"'"); }
        String uploadWhere(QueueFilters f) {
            String where=f.showClientPushed()?"":" AND NOT ("+exactStatus("client_pushed")+")";
            if(f.uploadStatus()==null)return where;
            return where+" AND "+switch(f.uploadStatus()) {
                case "remaining_uploads" -> "NOT ("+exactStatus("uploaded")+" OR "+exactStatus("client_pushed")+")";
                case "uploaded_to_server" -> "("+exactStatus("uploaded")+" OR "+exactStatus("client_pushed")+")";
                default -> exactStatus(f.uploadStatus());
            };
        }
        PageIds page(QueueFilters f,int requestedPage,int requestedSize) {
            int size=Math.max(1,Math.min(200,requestedSize));
            String where=base()+uploadWhere(f);
            refineText(f,where);
            if("ready".equals(f.imageStatus()))narrowReady(where);
            String from=BASE_FROM+textJoin;
            if(f.imageStatus()!=null) {
                prepareAvailability(where,f.imageStatus());
                from+=MEDIA_JOINS+availabilityJoin;
                where+=" AND "+imageCondition(f.imageStatus());
            }
            int total=sql.queryForObject("SELECT COUNT(*)"+from+where,params,Integer.class);
            int pages=Math.max(1,(int)Math.ceil((double)total/size)),page=Math.min(Math.max(1,requestedPage),pages);
            params.put("limit",size);params.put("offset",(long)(page-1)*size);
            List<Long> ids=sql.queryForList("SELECT s.id"+from+where+ORDER+" LIMIT :limit OFFSET :offset",params,Long.class);
            return new PageIds(ids,page,size,total);
        }
        /** Java case folding is retained for Unicode instead of relying on an accent-insensitive DB collation. */
        void refineText(QueueFilters f,String where) {
            if(f.query()==null && f.certId()==null && f.cardName()==null && f.cardCategory()==null && f.productType()==null
                && f.brand()==null && f.language()==null && f.finalGrade()==null)return;
            String table=create("text","id BIGINT PRIMARY KEY");
            String candidates=where+coarseText(f);
            long after=0;
            while(true) {
                params.put("after",after);params.put("chunk",CHUNK);
                List<TextRow> rows=sql.query("SELECT s.id,s.cert_id,s.card_name,s.set_name,COALESCE(NULLIF(s.card_category_code,''),'trading_card'),s.product_type_code,s.brand_name,s.language_code,g.final_grade_value,g.final_grade_label"
                    +BASE_FROM+candidates+" AND s.id>:after ORDER BY s.id LIMIT :chunk",params,
                    (rs,n)->new TextRow(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getBigDecimal(9),rs.getString(10)));
                if(rows.isEmpty())break;
                List<MapSqlParameterSource> matches=new ArrayList<>();
                for(TextRow row:rows)if(row.matches(f))matches.add(new MapSqlParameterSource("id",row.id()));
                if(!matches.isEmpty())sql.batchUpdate("INSERT INTO "+table+"(id) VALUES(:id)",matches.toArray(MapSqlParameterSource[]::new));
                after=rows.get(rows.size()-1).id();
                if(rows.size()<CHUNK)break;
            }
            textJoin=" JOIN "+table+" qt ON qt.id=s.id";
        }
        String nonAscii(String expression) {
            // An ASCII coarse filter is safe; all non-ASCII candidates still pass
            // through Java's original Unicode rules (including expanding upper case).
            return mysql?"REGEXP_LIKE("+expression+",'[^ -~]','c')":"REGEXP_LIKE("+expression+",'[^ -~]')";
        }
        String coarseContains(String col,String value,String key) {
            if(value==null)return "1=1";
            if(!value.chars().allMatch(c->c>=32&&c<=126))return "1=1";
            params.put(key,"%"+value.toUpperCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");
            return "(UPPER("+col+") LIKE :"+key+" ESCAPE '!' OR "+nonAscii(col)+")";
        }
        String coarseText(QueueFilters f) {
            String result="";
            if(f.query()!=null)result+=" AND ("+coarseContains("s.cert_id",f.query(),"query")+" OR "+coarseContains("s.card_name",f.query(),"query")+" OR "+coarseContains("s.set_name",f.query(),"query")+")";
            if(f.certId()!=null)result+=" AND "+coarseContains("s.cert_id",f.certId(),"cert");
            if(f.cardName()!=null)result+=" AND "+coarseContains("s.card_name",f.cardName(),"name");
            for(var pair:List.of(new String[]{"s.brand_name",f.brand(),"brand"},new String[]{"s.language_code",f.language(),"language"},new String[]{"COALESCE(NULLIF(s.card_category_code,''),'trading_card')",f.cardCategory(),"category"})) {
                if(pair[1]!=null && pair[1].chars().allMatch(c->c>=32&&c<=126)) {
                    params.put(pair[2],pair[1]);result+=" AND (LOWER("+pair[0]+")=LOWER(:"+pair[2]+") OR "+nonAscii(pair[0])+")";
                }
            }
            // Product aliases and grade labels are checked by their existing Java policies.
            return result;
        }
        String create(String label,String definition) {
            String name="tmp_media_queue_"+label+"_"+suffix;
            direct.execute((mysql?"CREATE TEMPORARY TABLE ":"CREATE LOCAL TEMPORARY TABLE ")+name+" ("+definition+")"+(mysql?" ENGINE=InnoDB":" TRANSACTIONAL"));
            tables.add(name);return name;
        }
        void narrowReady(String where) {
            String table=create("ready","id BIGINT PRIMARY KEY");
            // Start with staged fronts rather than probing four media joins for
            // every historical public certificate. This needs no new index.
            String join=mysql?" STRAIGHT_JOIN ":" JOIN ";
            sql.update("INSERT INTO "+table+"(id) SELECT s.id FROM submission_media sf"+join+"grading_submission s ON s.id=sf.submission_id"
                +" LEFT JOIN submission_upload_state us ON us.submission_id=s.id LEFT JOIN grading_score g ON g.submission_id=s.id"
                +textJoin+" JOIN submission_media sb ON sb.submission_id=s.id AND sb.media_stage_code='staged' AND sb.media_side_code='back' AND sb.sort_order=1 AND sb.is_active=1"
                +where+" AND sf.media_stage_code='staged' AND sf.media_side_code='front' AND sf.sort_order=1 AND sf.is_active=1"
                +" AND "+recorded("sf")+" AND "+recorded("sb")+" AND NOT ("+exactStatus("uploading")+" OR "+exactStatus("client_pushed")+")",params);
            textJoin+=" JOIN "+table+" qr ON qr.id=s.id";
        }
        void providerPolicies() {
            List<String> defaults=new ArrayList<>(),checks=new ArrayList<>();
            // Only provider names cross JDBC here, not every published reference.
            for(String code:direct.query("SELECT DISTINCT "+binary("storage_provider_code")+" FROM submission_media WHERE is_active=1 AND sort_order=1",(rs,n)->{byte[] value=rs.getBytes(1);return value==null?null:new String(value,java.nio.charset.StandardCharsets.UTF_8);})) {
                if(code==null || code.trim().isEmpty() || registry==null || !registry.supports(code))continue;
                MediaStorageProvider provider=registry.providerFor(code);
                try {
                    if(provider.getClass().getMethod("referenceExists",MediaStorageProvider.StoredMediaLocation.class).getDeclaringClass()==MediaStorageProvider.class)defaults.add(code);
                    else checks.add(code);
                } catch(NoSuchMethodException impossible) { throw new IllegalStateException(impossible); }
            }
            optimistic=defaults;physical=checks;
            params.put("optimistic",defaults.isEmpty()?List.of("\u0000"):defaults);
            params.put("physical",checks.isEmpty()?List.of("\u0000"):checks);
        }
        String recorded(String alias) {
            return "(COALESCE(REGEXP_LIKE("+alias+".storage_key,:nonblank),FALSE) OR COALESCE(REGEXP_LIKE("+alias+".public_url,:nonblank),FALSE))";
        }
        String remote(String alias) {
            return "("+equal("LEFT("+alias+".public_url,8)","'https://'")+" OR "+equal("LEFT("+alias+".public_url,7)","'http://'")+")";
        }
        String legacyPublic(String alias) {
            return "("+equal(alias+".media_stage_code","'published'")+" AND "+equal("LOWER("+alias+".storage_provider_code)","'legacy-python'")+" AND "+equal(alias+".storage_bucket","'python-public'")+" AND "+remote(alias)+")";
        }
        String known(String alias,String list) {
            if(("physical".equals(list)?physical:optimistic).isEmpty())return "1=0";
            return binary(alias+".storage_provider_code")+" IN (:"+list+")";
        }
        String autoAvailable(String alias) {
            return "("+legacyPublic(alias)+" OR "+known(alias,"optimistic")+" OR (("+alias+".storage_provider_code IS NULL OR NOT ("+known(alias,"physical")+" OR "+known(alias,"optimistic")+")) AND "+remote(alias)+"))";
        }
        void prepareAvailability(String where,String imageStatus) {
            providerPolicies();
            availableTable=create("files","submission_id BIGINT PRIMARY KEY,sf INT NOT NULL,sb INT NOT NULL,pf INT NOT NULL,pb INT NOT NULL");
            availabilityJoin=" LEFT JOIN "+availableTable+" qa ON qa.submission_id=s.id";
            if(physical.isEmpty())return;
            String relevant=switch(imageStatus==null?"all":imageStatus) {
                case "ready" -> " AND m.media_stage_code='staged'";
                case "published" -> " AND m.media_stage_code='published'";
                case "missing_front" -> " AND m.media_side_code='front'";
                case "missing_back" -> " AND m.media_side_code='back'";
                default -> "";
            };
            String potential=switch(imageStatus==null?"all":imageStatus) {
                case "ready" -> " AND "+recorded("sf")+" AND "+recorded("sb")+" AND NOT ("+exactStatus("uploading")+" OR "+exactStatus("client_pushed")+")";
                case "published" -> " AND "+recorded("pf")+" AND "+recorded("pb");
                default -> "";
            };
            long after=0;
            while(true) {
                params.put("mediaAfter",after);params.put("chunk",CHUNK);
                List<FileReference> references=sql.query("SELECT m.id,m.submission_id,m.media_stage_code,m.media_side_code,m.storage_provider_code,m.storage_bucket,m.storage_key,m.storage_object_version,m.public_url"
                    +BASE_FROM+textJoin+MEDIA_JOINS+" JOIN submission_media m ON m.submission_id=s.id"
                    +where+potential+" AND m.id>:mediaAfter AND m.sort_order=1 AND m.is_active=1"
                    +" AND m.media_stage_code IN ('staged','published') AND m.media_side_code IN ('front','back')"
                    +relevant+" AND "+known("m","physical")+" AND "+recorded("m")+" AND NOT "+legacyPublic("m")
                    +" ORDER BY m.id LIMIT :chunk",params,(rs,n)->new FileReference(rs.getLong(1),rs.getLong(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)));
                if(references.isEmpty())break;
                List<MapSqlParameterSource> available=new ArrayList<>();
                for(FileReference ref:references)if(referenceCheck.available(ref.stage(),ref.provider(),ref.bucket(),ref.key(),ref.version(),ref.url())) {
                    String slot=("staged".equals(ref.stage())?"s":"p")+("front".equals(ref.side())?"f":"b");
                    var values=new MapSqlParameterSource("id",ref.submissionId());
                    for(String name:List.of("sf","sb","pf","pb"))values.addValue(name,name.equals(slot)?1:0);
                    available.add(values);
                }
                // Close each SELECT before writing: MySQL cannot interleave writes with a streaming result set.
                if(!available.isEmpty())sql.batchUpdate("INSERT INTO "+availableTable+"(submission_id,sf,sb,pf,pb) VALUES(:id,:sf,:sb,:pf,:pb) ON DUPLICATE KEY UPDATE sf=GREATEST(sf,VALUES(sf)),sb=GREATEST(sb,VALUES(sb)),pf=GREATEST(pf,VALUES(pf)),pb=GREATEST(pb,VALUES(pb))",available.toArray(MapSqlParameterSource[]::new));
                after=references.get(references.size()-1).id();if(references.size()<CHUNK)break;
            }
        }
        String available(String alias) { return "COALESCE(("+recorded(alias)+" AND ("+autoAvailable(alias)+" OR COALESCE(qa."+alias+",0)=1)),FALSE)"; }
        String imageCondition(String image) {
            String sf=available("sf"),sb=available("sb"),pf=available("pf"),pb=available("pb");
            return switch(image) {
                case "ready" -> "("+sf+" AND "+sb+" AND NOT ("+exactStatus("uploading")+" OR "+exactStatus("client_pushed")+"))";
                case "published" -> "("+pf+" AND "+pb+")";
                case "waiting" -> "(NOT ("+sf+" AND "+sb+") AND NOT ("+pf+" AND "+pb+"))";
                case "missing_front" -> "NOT ("+sf+" OR "+pf+")";
                case "missing_back" -> "NOT ("+sb+" OR "+pb+")";
                case "missing_any" -> "(NOT ("+sf+" OR "+pf+") OR NOT ("+sb+" OR "+pb+"))";
                default -> "1=1";
            };
        }
        MediaQueueSummary summary() {
            prepareAvailability(base(),null);
            String select="SELECT "+upload()+" AS upload_status,";
            String inner=select+"CASE WHEN "+available("sf")+" THEN 1 ELSE 0 END AS sf,CASE WHEN "+available("sb")+" THEN 1 ELSE 0 END AS sb,CASE WHEN "+available("pf")+" THEN 1 ELSE 0 END AS pf,CASE WHEN "+available("pb")+" THEN 1 ELSE 0 END AS pb"
                +BASE_FROM+MEDIA_JOINS+availabilityJoin+base();
            List<Map<String,Object>> groups=sql.queryForList("SELECT upload_status,COUNT(*) AS total,SUM(CASE WHEN sf=1 AND sb=1 THEN 1 ELSE 0 END) AS staged,SUM(CASE WHEN pf=1 AND pb=1 THEN 1 ELSE 0 END) AS published,SUM(CASE WHEN (sf=0 AND pf=0) OR (sb=0 AND pb=0) THEN 1 ELSE 0 END) AS missing,SUM(sf) AS front_count,SUM(sb) AS back_count,SUM(CASE WHEN (sf=0 OR sb=0) AND (pf=0 OR pb=0) THEN 1 ELSE 0 END) AS waiting FROM ("+inner+") q GROUP BY "+binary("upload_status")+",upload_status",params);
            int count=0,ready=0,live=0,missing=0,pushed=0,front=0,back=0,waiting=0,uploaded=0;
            Map<String,Integer> statuses=new LinkedHashMap<>();
            for(var g:groups) {
                String status=(String)g.get("upload_status");int total=number(g,"total");count+=total;statuses.put(status,total);
                if(!"uploading".equals(status)&&!"client_pushed".equals(status))ready+=number(g,"staged");
                if("client_pushed".equals(status))pushed+=total;
                if("uploaded".equals(status)||"client_pushed".equals(status))uploaded+=total;
                live+=number(g,"published");missing+=number(g,"missing");front+=number(g,"front_count");back+=number(g,"back_count");waiting+=number(g,"waiting");
            }
            return new MediaQueueSummary(count,ready,live,missing,count,pushed,front,back,waiting,uploaded,count-uploaded,statuses);
        }
        int number(Map<String,Object> row,String key) { return ((Number)row.get(key)).intValue(); }
        @Override public void close() {
            // H2 DROP TABLE commits even transactional local temp tables. Its
            // isolated tests defer only DDL cleanup until the caller has ended;
            // MySQL DROP TEMPORARY TABLE is immediate and never commits.
            if(!mysql && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) { dropTables(); }
                    });
                return;
            }
            dropTables();
        }
        void dropTables() {
            RuntimeException failure=null;
            for(int i=tables.size()-1;i>=0;i--)try { direct.execute((mysql?"DROP TEMPORARY TABLE ":"DROP TABLE ")+tables.get(i)); }catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(failure!=null)throw failure;
        }
    }
    private record FileReference(long id,long submissionId,String stage,String side,String provider,String bucket,String key,String version,String url) { }
    private record TextRow(long id,String cert,String name,String set,String category,String product,String brand,String language,BigDecimal grade,String label) {
        boolean matches(QueueFilters f) {
            return (f.query()==null||contains(cert,f.query())||contains(name,f.query())||contains(set,f.query()))
                &&contains(cert,f.certId())&&contains(name,f.cardName())
                &&equal(category==null||category.isEmpty()?"trading_card":category,f.cardCategory())
                &&equal(ProductTypePolicy.normalizeStored(product),f.productType())&&equal(brand,f.brand())&&equal(language,f.language())
                &&grade(f.finalGrade());
        }
        boolean grade(String filter) {if(filter==null||equal(label,filter))return true;try{return grade!=null&&grade.compareTo(new BigDecimal(filter))==0;}catch(NumberFormatException e){return false;}}
        static boolean contains(String value,String filter){return filter==null||(value!=null&&value.toUpperCase(Locale.ROOT).contains(filter.toUpperCase(Locale.ROOT)));}
        static boolean equal(String value,String filter){return filter==null||(value!=null&&value.equalsIgnoreCase(filter));}
    }
}
