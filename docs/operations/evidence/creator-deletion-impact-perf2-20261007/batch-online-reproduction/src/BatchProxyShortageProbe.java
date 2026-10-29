import com.armada.account.mapper.*;
import com.armada.account.model.entity.*;
import com.armada.account.model.vo.AccountBatchCommandResultVO;
import com.armada.account.service.*;
import com.armada.account.service.impl.*;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.kafka.config.*;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.platform.protocol.service.impl.ProtocolCommandOutboxServiceImpl;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.service.IpProxyService;
import com.armada.resource.service.impl.IpProxyServiceImpl;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskNormalLinkSchema;
import com.armada.testsupport.CreatorDeletionH2Schema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Isolated audit: production services + real Mapper XML + H2; no network or dispatch. */
public class BatchProxyShortageProbe {
  static int assertions;
  static boolean rawXml;
  static void check(boolean ok,String message){assertions++; if(!ok)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception{
    rawXml=args.length>0&&args[0].equals("raw");
    if(rawXml){
      run("raw-reserved-and-healthy-one-idle",List.of(1L,2L),1,0,2);
      run("raw-healthy-and-reserved-one-idle",List.of(2L,1L),1,0,2);
      System.out.println("RAW_XML_AUDIT_PASS assertions="+assertions+" snapshotSqlAdapted=false realProxyAllocator=true immediateDispatch=false");
      return;
    }
    run("healthy-alone-one-idle",List.of(2L),1,1,0);
    run("reserved-and-healthy-one-idle",List.of(1L,2L),1,0,2);
    run("healthy-and-reserved-one-idle",List.of(2L,1L),1,0,2);
    run("reserved-and-healthy-two-idle",List.of(1L,2L),2,1,1);
    System.out.println("AUDIT_PASS assertions="+assertions+" realMappers=true realProxyAllocator=true immediateDispatch=false");
  }
  static void run(String name,List<Long> ids,int proxyCount,int expectedAccepted,int expectedFailed)throws Exception{
    Fixture f=new Fixture(name,proxyCount);TenantContext.set(7L);
    try{
      AccountBatchCommandResultVO result=f.service.onlineByIds(ids);
      List<Long> outboxIds=f.db.queryForList("SELECT aggregate_id FROM protocol_command_outbox WHERE command_type='account.online.requested' ORDER BY aggregate_id",Long.class);
      List<Long> boundAccounts=f.db.queryForList("SELECT bound_account_id FROM ip_proxy WHERE bound_account_id IS NOT NULL ORDER BY bound_account_id",Long.class);
      int healthyLogin=f.db.queryForObject("SELECT login_state FROM account_state WHERE account_id=2",Integer.class);
      int healthyDesired=f.db.queryForObject("SELECT desired_login_state FROM account_state WHERE account_id=2",Integer.class);
      int reservedLogin=f.db.queryForObject("SELECT login_state FROM account_state WHERE account_id=1",Integer.class);
      int reservedDesired=f.db.queryForObject("SELECT desired_login_state FROM account_state WHERE account_id=1",Integer.class);
      int idle=f.db.queryForObject("SELECT COUNT(*) FROM ip_proxy WHERE status=1 AND bound_account_id IS NULL",Integer.class);
      System.out.println("CASE "+name+" requested="+result.requested()+" submitted="+result.submitted()+" accepted="+result.accepted()+" failed="+result.failed()+" errors="+result.batchErrors());
      System.out.println("DB outboxAccounts="+outboxIds+" boundAccounts="+boundAccounts+" idle="+idle+" healthyLogin="+healthyLogin+" healthyDesired="+healthyDesired+" reservedLogin="+reservedLogin+" reservedDesired="+reservedDesired);
      System.out.println("MAPPER_TRACE "+f.trace.calls);
      check(result.accepted()==expectedAccepted,name+" accepted");check(result.failed()==expectedFailed,name+" failed");
      check(reservedLogin==AccountLoginStateCode.OFFLINE,name+" reserved pending rollback");check(reservedDesired==AccountLoginStateCode.OFFLINE,name+" reserved desired rollback");
      check(f.db.queryForObject("SELECT COUNT(*) FROM account_creator_deletion WHERE account_id=1 AND lifecycle='RESERVED'",Integer.class)==1,name+" reservation changed");
      check(f.trace.calls.stream().anyMatch(s->s.contains("IpProxyMapper.selectIdleByRegionPriority")),name+" real proxy SELECT missing");
      check(f.trace.calls.stream().anyMatch(s->s.contains("IpProxyMapper.markUsingAndBind")),name+" real proxy CAS missing");
      if(expectedAccepted==1){
        check(outboxIds.equals(List.of(2L)),name+" healthy outbox");check(boundAccounts.equals(List.of(2L)),name+" healthy real binding");
        check(healthyLogin==AccountLoginStateCode.PENDING_ONLINE,name+" healthy pending");check(healthyDesired==AccountLoginStateCode.ONLINE,name+" healthy desired online");
      }else{
        check(outboxIds.isEmpty(),name+" no outbox on rollback");check(boundAccounts.isEmpty(),name+" proxy binding rollback");check(idle==1,name+" proxy returned idle by transaction rollback");
        check(healthyLogin==AccountLoginStateCode.OFFLINE,name+" healthy pending rollback");check(healthyDesired==AccountLoginStateCode.OFFLINE,name+" healthy desired rollback");
        check(result.batchErrors().stream().anyMatch(s->s.contains("暂无足够空闲代理")&&s.contains("requested=2")&&s.contains("allocated=1")),name+" true shortage reason");
        check(f.trace.calls.stream().noneMatch(s->s.contains("ProtocolCommandOutboxMapper.creatorDeletionCommandBlocked")),name+" should fail before deletion outbox gate");
        check(f.trace.calls.stream().noneMatch(s->s.contains("updateProxySnapshotsInternal")),name+" should fail before snapshot SQL");
        check(f.trace.calls.stream().anyMatch(s->s.equals("PROXY_CAS_TARGET accountId=1 proxyId=100")),name+" reserved consumed candidate before healthy");
      }
      check(f.db.queryForObject("SELECT COUNT(*) FROM account_state WHERE account_id=3 AND tenant_id=8 AND login_state=2 AND desired_login_state=2",Integer.class)==1,name+" other tenant untouched");
    }finally{TenantContext.clear();}
  }
  @Intercepts({@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class})})
  public static class Trace implements Interceptor{
    final List<String> calls=new ArrayList<>();
    public Object intercept(Invocation invocation)throws Throwable{
      String id=((MappedStatement)invocation.getArgs()[0]).getId();
      if(id.endsWith("IpProxyMapper.markUsingAndBind")){var params=(Map<?,?>)invocation.getArgs()[1];calls.add("PROXY_CAS_TARGET accountId="+params.get("accountId")+" proxyId="+params.get("id"));}
      if(id.contains("IpProxyMapper.")||id.contains("creatorDeletionCommandBlocked")||id.contains("claimPendingOnlineInternal")||id.contains("updateDesiredLoginStateInternal")||id.contains("updateProxySnapshotsInternal"))calls.add(id.substring(id.indexOf("mapper.")+7));
      return invocation.proceed();
    }
  }
  static class Fixture{
    final JdbcTemplate db;final AccountBatchLifecycleServiceImpl service;final Trace trace=new Trace();
    Fixture(String name,int proxyCount)throws Exception{
      DataSource source=PullTaskNormalLinkH2Support.dataSource("proxyAudit"+System.nanoTime());db=new JdbcTemplate(source);schema(source);
      MyBatisConfig config=new MyBatisConfig();
      var factory=PullTaskNormalLinkH2Support.sqlSessionFactory(source,config.mybatisPlusInterceptor(config.tenantLineHandler()),
        "mapper/account/AccountMapper.xml",rawXml?"mapper/account/AccountStateMapper.xml":"audit/AccountStateMapper.xml","mapper/account/AccountCredentialMapper.xml","mapper/resource/IpProxyMapper.xml","mapper/platform/protocol/ProtocolCommandOutboxMapper.xml");
      factory.getConfiguration().addInterceptor(trace);var session=new SqlSessionTemplate(factory);var tx=new DataSourceTransactionManager(source);
      ProtocolCommandDispatcherProperties dispatchProps=new ProtocolCommandDispatcherProperties();dispatchProps.setImmediateEnabled(false);
      var trigger=new ProtocolCommandDispatchTrigger(null,r->{throw new AssertionError("dispatch forbidden");},dispatchProps,null);
      ProtocolCommandOutboxService outbox=transactional(new ProtocolCommandOutboxServiceImpl(session.getMapper(ProtocolCommandOutboxMapper.class),new ObjectMapper(),trigger,new ProtocolAccountCommandProperties(),new ProtocolMasterCommandProperties(),new ProtocolAndroidCommandProperties(),new NormalGroupCreationKafkaProperties()),tx,ProtocolCommandOutboxService.class);
      // Unused detector/converter/country collaborators are null: accidental access fails instead of simulating allocation.
      IpProxyService ip=transactional(new IpProxyServiceImpl(session.getMapper(IpProxyMapper.class),null,null,null,r->{throw new AssertionError("proxy detector forbidden");}),tx,IpProxyService.class);
      AccountOnlineCommandService commands=transactional(new AccountOnlineCommandServiceImpl(session.getMapper(AccountMapper.class),session.getMapper(AccountCredentialMapper.class),session.getMapper(AccountStateMapper.class),ip,null,outbox,new OnlineAttemptIdGenerator(),null,new AccountTakeoverReonlineCooldown()),tx,AccountOnlineCommandService.class);
      service=new AccountBatchLifecycleServiceImpl(session.getMapper(AccountMapper.class),commands);
      for(long id=1;id<=3;id++){
        long tenant=id==3?8:7;String phone="1550000000"+id;
        db.update("INSERT INTO account(id,tenant_id,ws_phone,protocol_account_id,protocol_id) VALUES (?,?,?,?,?)",id,tenant,phone,"account-"+id,"ANDROID");
        db.update("INSERT INTO account_state(account_id,tenant_id,account_state,login_state,desired_login_state,updated_at) VALUES (?,?,?,?,?,1)",id,tenant,AccountStateCode.NORMAL,AccountLoginStateCode.OFFLINE,AccountLoginStateCode.OFFLINE);
        db.update("INSERT INTO account_credential(account_id,tenant_id,cred_format) VALUES (?,?,1)",id,tenant);
      }
      db.update("INSERT INTO account_creator_deletion(account_id,tenant_id,task_id,group_execution_id,identity_hash,creator_phone,protocol_account_id,lifecycle) VALUES (1,7,64,6401,'fixture-hash','15500000001','account-1','RESERVED')");
      for(int n=0;n<proxyCount;n++)db.update("INSERT INTO ip_proxy(id,tenant_id,host,port,protocol,status,region,source,created_at,updated_at) VALUES (?,7,'127.0.0.1',9,2,1,'fixture-region','audit',1,1)",100+n);
    }
    void schema(DataSource source){
      db.execute("CREATE TABLE account(id BIGINT PRIMARY KEY,tenant_id BIGINT,ws_phone VARCHAR(32),protocol_account_id VARCHAR(128),protocol_id VARCHAR(16),account_group_id BIGINT,deleted_at BIGINT)");
      db.execute("CREATE TABLE account_state(account_id BIGINT PRIMARY KEY,tenant_id BIGINT,account_state INT,login_state INT,desired_login_state INT,mute_status INT,last_state_sync_time BIGINT,state_source VARCHAR(128),truth_ip VARCHAR(100),proxy_country VARCHAR(100),proxy_source VARCHAR(100),updated_at BIGINT)");
      db.execute("CREATE TABLE account_group(id BIGINT,tenant_id BIGINT,deleted_at BIGINT)");
      db.execute("CREATE TABLE account_credential(account_id BIGINT,tenant_id BIGINT,cred_format INT,deleted_at BIGINT)");
      db.execute("CREATE TABLE account_import_detail(id BIGINT,account_id BIGINT,tenant_id BIGINT,online_phase INT,ws_phone VARCHAR(32),batch_id BIGINT,parse_result INT)");
      db.execute("CREATE TABLE account_import_batch(id BIGINT,tenant_id BIGINT,ip_region VARCHAR(100),ip_allocation_mode VARCHAR(32),deleted_at BIGINT)");
      db.execute("CREATE TABLE ip_proxy(id BIGINT PRIMARY KEY,tenant_id BIGINT,host VARCHAR(100),port INT,protocol INT,username VARCHAR(100),password VARCHAR(100),region VARCHAR(100),status INT,bound_account_id BIGINT,bound_at BIGINT,pairing_session_id BIGINT,last_sample_check_at BIGINT,detected_country_code VARCHAR(8),outbound_ip VARCHAR(100),detected_location VARCHAR(255),detected_isp VARCHAR(255),detected_latitude DECIMAL(10,7),detected_longitude DECIMAL(10,7),check_fail_count INT,last_check_error VARCHAR(512),check_status INT,whatsapp_check_status INT,whatsapp_http_status INT,whatsapp_check_error VARCHAR(512),source VARCHAR(100),ownership INT,allocation_mode VARCHAR(32),remark VARCHAR(255),created_at BIGINT,updated_at BIGINT,created_by BIGINT,deleted_at BIGINT)");
      db.execute(CreatorDeletionH2Schema.DDL);CreatorDeletionH2Schema.installIdentityIndex(source);db.execute(PullTaskNormalLinkSchema.protocolCommandOutbox());
    }
  }
  static <T>T transactional(Object target,DataSourceTransactionManager manager,Class<T> type){var p=new ProxyFactory(target);p.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));return type.cast(p.getProxy());}
}
