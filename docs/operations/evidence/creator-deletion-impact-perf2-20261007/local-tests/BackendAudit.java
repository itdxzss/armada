package com.armada.account.service;
import com.armada.account.mapper.*;
import com.armada.account.model.dto.*;
import com.armada.account.model.entity.*;
import com.armada.boot.config.MyBatisConfig;
import com.armada.feed.task.mapper.FeedTaskAccountCandidateMapper;
import com.armada.feed.task.model.dto.FeedTaskCandidateQuery;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.mapper.MarketingAccountOccupancyMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.lang.reflect.*;
import java.util.*;
public class BackendAudit {
 static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);System.out.println("PASS "+s);}
 static CreatorDeletionBinding binding(AccountCreatorDeletionService s){return new CreatorDeletionBinding(7,100,500,1,s.identityHash(new ProtocolAccountRef(1L,ProtocolBackend.ANDROID,"acc_12345678901","12345678901")),"create-500","delete-1");}
 static CreatorReservationRequest req(){return new CreatorReservationRequest(7,100,500,new ProtocolAccountRef(1L,ProtocolBackend.ANDROID,"acc_12345678901","12345678901"),"create-500",1);}
 public static void main(String[] args)throws Exception{
  var base=new AccountCreatorDeletionServiceInMemoryTest();base.setup();
  var db=(JdbcTemplate)field(base,"db");var tx=(TransactionTemplate)field(base,"tx");var svc=(AccountCreatorDeletionService)field(base,"service");var accounts=(AccountMapper)field(base,"accounts");
  db.execute("ALTER TABLE account ADD created_at BIGINT DEFAULT 1");
  db.execute("CREATE TABLE account_group(id BIGINT,tenant_id BIGINT,deleted_at BIGINT,marketing_occupancy_task_id BIGINT)");
  db.execute("CREATE TABLE ip_proxy(id BIGINT,tenant_id BIGINT,bound_account_id BIGINT,deleted_at BIGINT,status INT)");
  var cfg=new MyBatisConfig();var factory=PullTaskNormalLinkH2Support.sqlSessionFactory(db.getDataSource(),cfg.mybatisPlusInterceptor(cfg.tenantLineHandler()),"mapper/feed/FeedTaskAccountCandidateMapper.xml","mapper/account/AccountFilterSelectionMapper.xml","mapper/marketing/MarketingTaskMapper.xml","mapper/marketing/MarketingAccountOccupancyMapper.xml","mapper/marketing/GroupPullMarketingMapper.xml");
  var ss=new SqlSessionTemplate(factory);var feed=ss.getMapper(FeedTaskAccountCandidateMapper.class);
  check(tx.execute(s->svc.reserve(req())),"reservation succeeds with no dependencies");
  var q=new FeedTaskCandidateQuery();q.setTaskId(88L);q.setLimit(10);q.setCallable(true);
  check(feed.selectCandidates(q).size()==1,"feed callable candidate still includes RESERVED creator");
  var sendable=ss.getMapper(com.armada.account.selection.mapper.AccountFilterSelectionMapper.class);
  check(sendable.selectSendableByIds(List.of(1L),2,4).size()==1,"feed/contact sendability still includes RESERVED creator");
  check(accounts.creatorDeletionCommandBlocked(7L,"acc_12345678901",null,null),"same creator cannot enqueue ordinary business without owner binding");
  db.update("INSERT INTO feed_task VALUES(88,7,NULL,1)");db.update("INSERT INTO feed_task_account VALUES(1,7,88)");
  check(!tx.execute(s->svc.beginDeletion(binding(svc),10)),"new feed dependency now prevents original owner deletion");
  db.update("DELETE FROM feed_task_account");
  db.execute("CREATE TABLE account_group_sync_state(account_id BIGINT,tenant_id BIGINT,baseline_state INT)");db.update("INSERT INTO account_group_sync_state VALUES(1,7,2)");
  db.execute("ALTER TABLE account_group ADD marketing_occupancy_type INT");
  db.update("INSERT INTO account_group(id,tenant_id) VALUES(10,7)");
  db.execute("ALTER TABLE group_pull_marketing_execution ADD task_id BIGINT");db.execute("ALTER TABLE group_pull_marketing_execution ADD tenant_id BIGINT");db.execute("ALTER TABLE group_pull_marketing_execution ADD active_builder_account_id BIGINT");
  db.execute("CREATE TABLE group_pull_marketing_account_stat(task_id BIGINT,tenant_id BIGINT,account_id BIGINT,reserved_group_count INT,joined_group_count INT)");
  var groupPull=ss.getMapper(com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper.class);
  check(groupPull.selectBuilderCandidate(77L,10L)!=null,"group-pull marketing builder candidate includes RESERVED creator");
  db.update("UPDATE account_group SET marketing_occupancy_type=2,marketing_occupancy_task_id=77 WHERE id=10");
  check(groupPull.selectMarketerCandidate(77L,10L,10)!=null,"group-pull marketing marketer candidate includes RESERVED creator");
  db.update("UPDATE account_group SET marketing_occupancy_type=NULL,marketing_occupancy_task_id=NULL WHERE id=10");
  var marketing=ss.getMapper(MarketingTaskMapper.class);
  check(marketing.selectAccountTargetCandidate(10L,1L,List.of(2))!=null,"ordinary marketing target candidate includes RESERVED creator");
  db.execute("ALTER TABLE marketing_account_occupancy ADD occupied_at BIGINT");db.execute("ALTER TABLE marketing_account_occupancy ADD created_at BIGINT");db.execute("ALTER TABLE marketing_account_occupancy ADD updated_at BIGINT");
  db.execute("CREATE TABLE marketing_task_target(account_id BIGINT,tenant_id BIGINT,marketing_task_id BIGINT)");db.update("INSERT INTO marketing_task VALUES(99,7,1,2,NULL)");db.update("INSERT INTO marketing_task_target VALUES(1,7,99)");
  check(ss.getMapper(MarketingAccountOccupancyMapper.class).insertAvailableTaskAccounts(99L,10)==1,"marketing occupancy SQL acquires RESERVED creator");
  check(!tx.execute(s->svc.beginDeletion(binding(svc),11)),"new marketing occupancy now prevents original owner deletion");
  db.update("DELETE FROM marketing_account_occupancy");
  check(tx.execute(s->svc.beginDeletion(binding(svc),12)),"deletion can begin after removing competing test dependencies");
  check(sendable.selectSendableByIds(List.of(1L),2,4).size()==1,"feed/contact sendability includes DELETING creator with stale NORMAL state");
  tx.executeWithoutResult(s->svc.completeDeletion(binding(svc),13));
  check(feed.selectCandidates(q).isEmpty(),"feed callable filter excludes DELETED only because now offline");
  q.setCallable(null);check(feed.selectCandidates(q).size()==1,"feed unrestricted candidate still includes DELETED creator");
  check(sendable.selectSendableByIds(List.of(1L),2,4).size()==1,"feed/contact sendability includes DELETED creator because NORMAL retained");
  base.cleanup();System.out.println("AUDIT_COMPLETED: 15 assertions; real source Mapper XML + H2 + production tenant interceptor + Spring transactions; no network");
 }
}
