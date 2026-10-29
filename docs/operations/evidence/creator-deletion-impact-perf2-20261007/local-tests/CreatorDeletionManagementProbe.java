import com.armada.account.mapper.*;
import com.armada.account.model.dto.AccountQuery;
import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.file.*;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
public class CreatorDeletionManagementProbe {
  static void require(boolean b, String message) { if(!b) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    var ds=new JdbcDataSource(); ds.setURL("jdbc:h2:mem:creator_management_probe;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    var db=new JdbcTemplate(ds);
    db.execute(com.armada.testsupport.CreatorDeletionH2Schema.DDL);
    db.execute("CREATE TABLE account(id BIGINT PRIMARY KEY, tenant_id BIGINT, ws_phone VARCHAR(32), protocol_id VARCHAR(32), protocol_account_id VARCHAR(128), account_group_id BIGINT, owner_user_id BIGINT, dispatched_at BIGINT, deleted_at BIGINT, updated_at BIGINT)");
    com.armada.testsupport.CreatorDeletionH2Schema.installIdentityIndex(ds);
    db.execute("CREATE TABLE account_state(account_id BIGINT, tenant_id BIGINT, account_state INT,login_state INT, desired_login_state INT,state_source VARCHAR(40), risk_status INT, mute_status INT, updated_at BIGINT,last_state_sync_time BIGINT)");
    db.execute("CREATE TABLE account_group(id BIGINT,tenant_id BIGINT,marketing_occupancy_task_id BIGINT,deleted_at BIGINT)");
    db.execute("CREATE TABLE ip_proxy(bound_account_id BIGINT,tenant_id BIGINT,deleted_at BIGINT,status INT)");
    db.execute("CREATE TABLE account_credential(account_id BIGINT,tenant_id BIGINT,deleted_at BIGINT)");
    db.execute("CREATE TABLE marketing_account_occupancy(tenant_id BIGINT,account_id BIGINT)");
    db.execute("CREATE TABLE group_pull_marketing_task(id BIGINT,tenant_id BIGINT,builder_group_id BIGINT,resource_status INT)");
    db.update("INSERT INTO account(id,tenant_id,ws_phone,protocol_id,protocol_account_id,account_group_id,owner_user_id) VALUES(10,1,'15550000010','ANDROID','synthetic-10',1,7),(11,1,'15550000011','ANDROID','synthetic-11',1,7),(20,2,'15550000010','WEB','synthetic-web-20',2,8)");
    db.update("INSERT INTO account_state(account_id,tenant_id,account_state,login_state,desired_login_state) VALUES(10,1,2,2,2),(11,1,2,2,2),(20,2,2,2,2)");
    db.update("INSERT INTO account_group VALUES(1,1,NULL,NULL),(2,2,NULL,NULL)");
    db.update("INSERT INTO account_credential VALUES(10,1,NULL),(11,1,NULL),(20,2,NULL)");
    db.update("INSERT INTO account_creator_deletion(account_id,tenant_id,task_id,group_execution_id,identity_hash,creator_phone,protocol_account_id,lifecycle) VALUES(10,1,64,303,?,'15550000010','synthetic-10','RESERVED')", "a".repeat(64));
    var conf=new MybatisConfiguration(); conf.setMapUnderscoreToCamelCase(true);
    var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(conf);
    var cfg=new MyBatisConfig(); factory.setPlugins(cfg.mybatisPlusInterceptor(cfg.tenantLineHandler()));
    String src="/Users/daishuaishuai/IdeaProjects/armada/armada-api/src/main/resources/mapper/account/";
    factory.setMapperLocations(new FileSystemResource(src+"AccountMapper.xml"),new FileSystemResource(src+"AccountExportMapper.xml"));
    var session=new SqlSessionTemplate(factory.getObject());
    var accounts=session.getMapper(AccountMapper.class);var exports=session.getMapper(AccountExportMapper.class);
    TenantContext.set(1L);
    for(String phase:List.of("RESERVED","DELETING","DELETED")) {
      db.update("UPDATE account_creator_deletion SET lifecycle=?",phase);
      require(accounts.countPage(new AccountQuery())==2,"list count must include ledger protected row");
      var stats=accounts.statsSummary(); require(stats.getTotal()==2&&stats.getOffline()==2,"stats retain normal offline");
      var preview=accounts.previewBatchTargetsByIds(List.of(10L,11L)); require(preview.getMatched()==2&&preview.getMissingCredential()==0,"preview includes protected account as credential present");
      var visible=accounts.selectActiveByIds(List.of(10L,11L)); require(visible.size()==(phase.equals("RESERVED")?2:1),"lifecycle-dependent identity visibility");
      require(accounts.selectWsPhonesByIds(List.of(10L,11L)).size()==2,"local phone export remains available");
      db.update("UPDATE account_state SET login_state=1 WHERE account_id=10");
      var callable=new AccountQuery(); callable.setCallable(true);
      require(accounts.countPage(callable)==1,"protected stale-online account still counted callable");
      db.update("UPDATE account_state SET login_state=2 WHERE account_id=10");
      System.out.println("phase="+phase+" countPage=2 statsOffline=2 batchPreviewMatched=2 activeIdentityCount="+visible.size()+" phoneExportCount=2 staleOnlineCallableCount=1");
    }
    var exportRows=exports.candidates(List.of(10L));require(exportRows.size()==1,"deleted account still export candidate");
    int reserved=exports.reserve(exportRows, 99L);require(reserved==1,"deleted identity export reserve succeeds");
    require(db.queryForObject("SELECT account_state FROM account_state WHERE account_id=10",Integer.class)==4,"export overwrites old NORMAL");
    System.out.println("DELETED export candidates=1 export.reserve=1 account_state=EXPORTED (actual production Mapper + tenant plugin)");
    TenantContext.set(2L);
    require(accounts.countPage(new AccountQuery())==1,"foreign alias visible in tenant list");
    require(accounts.selectActiveByIds(List.of(20L)).isEmpty(),"foreign same-phone Web identity hidden by global deleted phone");
    System.out.println("samePhoneDifferentTenantDifferentProtocol listCount=1 activeIdentityCount=0");
    TenantContext.clear();System.out.println("PASS: synthetic in-memory H2; no live database or protocol accessed");
  }
}
