import subprocess,time,pathlib,threading,queue,json,re
BASE=pathlib.Path(__file__).resolve().parent
CLI=['docker','exec','-i','member-gap-repro-20260919','mysql','--no-defaults','-uroot','--batch','--raw','--unbuffered','--skip-column-names']
def run(sql):
 p=subprocess.run(CLI,input=sql,text=True,capture_output=True,timeout=25)
 if p.returncode:raise RuntimeError(p.stderr)
 return p.stdout
class Client:
 def __init__(self,db,iso='REPEATABLE READ'):
  self.p=subprocess.Popen(CLI,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1);self.out=[];self.err=[]
  def read(stream,dest):
   for s in stream:dest.append(s)
  threading.Thread(target=read,args=(self.p.stdout,self.out),daemon=True).start();threading.Thread(target=read,args=(self.p.stderr,self.err),daemon=True).start()
  self.send('USE '+db+';SET SESSION TRANSACTION ISOLATION LEVEL '+iso+';SET SESSION innodb_lock_wait_timeout=8;BEGIN;')
 def send(self,s):self.p.stdin.write(s+'\n');self.p.stdin.flush()
 def close(self,sql='COMMIT;'):
  if self.p.poll() is None:
   self.send(sql);self.p.stdin.close()
 def collect(self):
  self.p.wait(timeout=15);time.sleep(.03)
  return {'exit':self.p.returncode,'out':''.join(self.out),'err':''.join(self.err)}
 def finish(self,sql='COMMIT;'):
  if self.p.poll() is None:
   self.send(sql);self.p.stdin.close()
  self.p.wait(timeout=15);time.sleep(.03)
  return {'exit':self.p.returncode,'out':''.join(self.out),'err':''.join(self.err)}
DDL=(BASE/'schema.sql').read_text();UPSERT=(BASE/'upsert.sql').read_text()
SEED="INSERT INTO wa_group_participant(id,tenant_id,group_id,pn_jid,lid_jid,phone,created_at,updated_at) VALUES(10,1,20,'10002@s.whatsapp.net','214@lid','10002',1,1);"
PN="INSERT INTO wa_group_participant(id,tenant_id,group_id,pn_jid,lid_jid,phone,created_at,updated_at) VALUES(11,1,20,'10001@s.whatsapp.net',NULL,'10001',1,1);"
TARGET="INSERT INTO wa_group_participant(id,tenant_id,group_id,pn_jid,lid_jid,phone,created_at,updated_at) VALUES(12,1,20,NULL,'213@lid','10001',1,1);"
def locks(db):return run("SELECT ENGINE_TRANSACTION_ID,INDEX_NAME,LOCK_MODE,LOCK_STATUS,LOCK_DATA FROM performance_schema.data_locks WHERE OBJECT_SCHEMA='"+db+"' ORDER BY ENGINE_TRANSACTION_ID,INDEX_NAME;SELECT REQUESTING_ENGINE_TRANSACTION_ID,BLOCKING_ENGINE_TRANSACTION_ID FROM performance_schema.data_lock_waits;")
def scenario(mode,iso='REPEATABLE READ'):
 db='probe_'+mode+'_'+iso.split()[0].lower();run('DROP DATABASE IF EXISTS '+db+';CREATE DATABASE '+db+';USE '+db+';'+DDL+SEED+(PN if mode=='pn_update' else TARGET if mode in ['delete_commit','existing'] else ''))
 c=None
 if mode in ['pn_update','insert_rollback','insert_commit','delete_commit']:
  c=Client(db,iso)
  c.send({'pn_update':"UPDATE wa_group_participant SET updated_at=2 WHERE id=11;",'insert_rollback':TARGET,'insert_commit':TARGET,'delete_commit':"DELETE FROM wa_group_participant WHERE id=12;"}[mode]+"SELECT 'C_READY';")
  until=time.time()+4
  while not any('C_READY' in x for x in c.out) and time.time()<until:time.sleep(.02)
 a,b=Client(db,iso),Client(db,iso)
 a.send(UPSERT+"SELECT 'A_DONE';")
 b.send(UPSERT+"SELECT 'B_DONE';")
 time.sleep(.5)
 before=locks(db)
 if c:cresult=c.finish('ROLLBACK;' if mode=='insert_rollback' else 'COMMIT;')
 else:cresult=None
 # A/B commits queued while blocked; they execute after UPSERT finishes.
 a.close();b.close();ra=a.collect();rb=b.collect();status=run('SHOW ENGINE INNODB STATUS;')
 dead=any('1213' in z['err'] for z in [ra,rb])
 out={'mode':mode,'isolation':iso,'deadlock':dead,'A':ra,'B':rb,'C':cresult,'before':before}
 (BASE/(db+'.json')).write_text(json.dumps(out,indent=2))
 if dead:(BASE/(db+'-innodb.txt')).write_text(status)
 print(json.dumps(out),flush=True)

if __name__ == '__main__':
 for mode in ['empty','existing','pn_update','insert_commit','insert_rollback','delete_commit']:
  scenario(mode)
 for mode in ['insert_rollback','delete_commit','pn_update']:
  scenario(mode,'READ COMMITTED')
