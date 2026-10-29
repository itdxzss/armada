package com.armada.account.service;
import com.armada.boot.config.MyBatisConfig;
import com.armada.platform.kafka.config.*;
import com.armada.platform.kafka.dispatch.ProtocolCommandDispatchTrigger;
import com.armada.platform.protocol.mapper.ProtocolCommandOutboxMapper;
import com.armada.platform.protocol.model.command.*;
import com.armada.platform.protocol.model.enums.*;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.platform.protocol.service.impl.ProtocolCommandOutboxServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.task.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
public final class OutboxMixedTransactionAudit {
 static ProtocolMessageOutboxCommand command(long id, String cmd) {
  String route=id==1 ? "acc_12345678901" : "synthetic_normal_route";
  var c=new MessageSendCommand(new ProtocolAccountRef(id,ProtocolBackend.ANDROID,route,"synthetic"),new MessageSendCommand.MessageTarget("synthetic-group@g.us"),new MessageSendCommand.MessagePayload(MessageType.TEXT,new MessageSendCommand.MessageContent("isolated local test",null,null,null),false,null),new MessageSendCommand.MessageCorrelation(7L,"marketing_task",new MessageSendCommand.MarketingCorrelation(99L,id,id,1L),null,null,null),cmd,0,0);
  return new ProtocolMessageOutboxCommand(c,ProtocolBackend.ANDROID,"local-test-never-dispatched",route,Map.of("tenantId",7L,"accountId",id,"commandId",cmd));
 }
 static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);System.out.println("PASS "+what);}
 public static void main(String[]args)throws Exception{
  var base=new AccountCreatorDeletionServiceInMemoryTest();base.setup();
  try{
   var db=(JdbcTemplate)BackendAudit.field(base,"db");var tx=(TransactionTemplate)BackendAudit.field(base,"tx");var creator=(AccountCreatorDeletionService)BackendAudit.field(base,"service");
   check(tx.execute(s->creator.reserve(BackendAudit.req())),"real creator reserve succeeds");
   db.update("INSERT INTO account(id,tenant_id,ws_phone,protocol_account_id,account_group_id,protocol_id,device_os) VALUES(2,7,'synthetic-normal-phone','synthetic_normal_route',10,'ANDROID',1)");
   db.execute("DROP TABLE protocol_command_outbox");db.execute(PullTaskNormalLinkSchema.protocolCommandOutbox());
   db.execute("CREATE TABLE isolated_business_marker(id BIGINT PRIMARY KEY,account_id BIGINT)");
   var cfg=new MyBatisConfig();var factory=PullTaskNormalLinkH2Support.sqlSessionFactory(db.getDataSource(),cfg.mybatisPlusInterceptor(cfg.tenantLineHandler()),"mapper/platform/protocol/ProtocolCommandOutboxMapper.xml");
   var mapper=new SqlSessionTemplate(factory).getMapper(ProtocolCommandOutboxMapper.class);
   var props=new ProtocolCommandDispatcherProperties();props.setImmediateEnabled(false);
   var trigger=new ProtocolCommandDispatchTrigger(null,null,props,null);
   var target=new ProtocolCommandOutboxServiceImpl(mapper,new ObjectMapper(),trigger,new ProtocolAccountCommandProperties(),new ProtocolMasterCommandProperties(),new ProtocolAndroidCommandProperties(),new NormalGroupCreationKafkaProperties());
   var proxy=new ProxyFactory(target);proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.getDataSource()),new AnnotationTransactionAttributeSource()));
   var outbox=(ProtocolCommandOutboxService)proxy.getProxy();
   tx.executeWithoutResult(s->{db.update("INSERT INTO isolated_business_marker VALUES(2,2)");outbox.enqueueMessageCommands(List.of(command(2,"normal-control")));});
   check(db.queryForObject("SELECT COUNT(*) FROM isolated_business_marker",Integer.class)==1,"normal-only caller business write commits");
   check(db.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox WHERE command_type='message.send.requested'",Integer.class)==1,"normal-only message is persisted by real source Mapper");
   db.update("DELETE FROM isolated_business_marker");db.update("DELETE FROM protocol_command_outbox");
   for(String phase:List.of("RESERVED","DELETING","DELETED"))for(boolean protectedFirst:List.of(false,true)){
    db.update("UPDATE account_creator_deletion SET lifecycle=?",phase);
    String caseName=phase+" protectedFirst="+protectedFirst;
    var commands=protectedFirst?List.of(command(1,"protected"),command(2,"normal")):List.of(command(2,"normal"),command(1,"protected"));
    try{
     tx.executeWithoutResult(s->{db.update("INSERT INTO isolated_business_marker VALUES(1,1),(2,2)");outbox.enqueueMessageCommands(commands);});
     throw new AssertionError("must reject: "+caseName);
    }catch(BusinessException expected){check(expected.getMessage().contains("永久注销"),caseName+" throws typed BusinessException from actual lifecycle gate");}
    check(db.queryForObject("SELECT COUNT(*) FROM isolated_business_marker",Integer.class)==0,caseName+" rolls back BOTH caller business markers");
    check(db.queryForObject("SELECT COUNT(*) FROM protocol_command_outbox",Integer.class)==0,caseName+" persists neither message");
   }
   System.out.println("AUDIT_COMPLETED: 21 assertions; real current service, source XML Mapper, production tenant interceptor, annotation transaction proxy + outer Spring transaction, H2. Immediate dispatch disabled; no network; no mock Mapper. Caller markers model the transaction boundary, not a full FeedTaskWorker or MarketingRoundWorker execution.");
  }finally{base.cleanup();}
 }
}
