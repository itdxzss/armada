from probe import *

def await_ready(c,mark):
 end=time.time()+5
 while not any(mark in l for l in c.out) and time.time()<end:time.sleep(.002)
 assert any(mark in l for l in c.out),(c.out,c.err)

def two(mode,iso):
 db=globals().get('DB_PREFIX','stress_')+mode+'_'+iso.split()[0].lower()
 run('DROP DATABASE IF EXISTS '+db+';CREATE DATABASE '+db+';USE '+db+';'+DDL+SEED)
 clients=[Client(db,iso),Client(db,iso)]
 for i,c in enumerate(clients):c.send("COMMIT;SELECT 'READY"+str(i)+"';")
 for i,c in enumerate(clients):await_ready(c,'READY'+str(i))
 counts={};examples=[]
 for n in range(100):
  run('USE '+db+';DELETE FROM wa_group_participant WHERE phone=\'10001\';'+(PN if mode=='pn' else TARGET if mode=='existing' else ''))
  go=time.time()+.07;barrier=threading.Barrier(3)
  for c in clients:c.out.clear();c.err.clear()
  def send(c,i):
   barrier.wait();c.send('BEGIN;DO SLEEP(GREATEST(0,'+str(go)+'-UNIX_TIMESTAMP(SYSDATE(6))));'+UPSERT+"COMMIT;SELECT 'DONE"+str(i)+"';")
  ts=[threading.Thread(target=send,args=(c,i)) for i,c in enumerate(clients)]
  for t in ts:t.start()
  barrier.wait()
  for t in ts:t.join()
  until=time.time()+9
  while time.time()<until and not all(any('DONE'+str(i) in l for l in c.out) or c.p.poll() is not None for i,c in enumerate(clients)):time.sleep(.003)
  errs=''.join(''.join(c.err) for c in clients)
  if '1213' in errs:
   status=run('SHOW ENGINE INNODB STATUS;');a=status.find('LATEST DETECTED DEADLOCK');b=status.find('\nTRANSACTIONS',a);report=status[a:b]
   exact=report.count('lock_mode X locks gap before rec\n')==2 and report.count('locks gap before rec insert intention waiting')==2
   kind='matching_gap_cycle' if exact else 'other_deadlock';counts[kind]=counts.get(kind,0)+1
   if len(examples)<3 or exact:
    name=db+'-'+str(n)+'-innodb.txt';(BASE/name).write_text(status);examples.append(name)
   # error exits mysql CLI: recreate failed clients; any remaining transaction completed before next iteration
   for i,c in enumerate(clients):
    if c.p.poll() is not None:
     clients[i]=Client(db,iso);clients[i].send("COMMIT;SELECT 'RESET';");await_ready(clients[i],'RESET')
  elif errs:raise RuntimeError('unexpected SQL error '+errs)
  else:counts['success']=counts.get('success',0)+1
  if (n+1)%25==0:print(db,n+1,counts,flush=True)
 for c in clients:c.finish()
 return {'mode':mode,'iso':iso,'counts':counts,'examples':examples}
if __name__ == '__main__':
 results=[]
 for iso in ['REPEATABLE READ','READ COMMITTED']:
  for mode in ['pn','empty','existing']:
   results.append(two(mode,iso));(BASE/'stress-summary.json').write_text(json.dumps(results,indent=2))
