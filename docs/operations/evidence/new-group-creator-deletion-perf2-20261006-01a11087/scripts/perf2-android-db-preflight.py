import subprocess,os,json,pathlib,tomllib,socket,time,datetime
root=pathlib.Path('/Users/daishuaishuai/IdeaProjects')
ssh=['ssh','-i',str(root/'测试pem/android-protocol.pem'),'-o','BatchMode=yes','-o','ConnectTimeout=8']
host='ec2-user@3.111.245.182'
cfg=tomllib.loads(subprocess.check_output(ssh+[host,'cat /home/ec2-user/whatsapp-android-zhuan/deploy/configs/prod_configs.toml'],text=True))
m=cfg['mysql'];assert m['name']=='whatsapp_android_zhuan_perf'
dbhost,dbport=m['addr'].rsplit(':',1)
s=socket.socket();s.bind(('127.0.0.1',0));port=s.getsockname()[1];s.close()
p=subprocess.Popen(ssh+['-o','ExitOnForwardFailure=yes','-N','-L',f'127.0.0.1:{port}:{dbhost}:{dbport}',host],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
try:
 for _ in range(40):
  if p.poll() is not None:raise RuntimeError('tunnel failed')
  try:
   c=socket.create_connection(('127.0.0.1',port),.2);c.close();break
  except OSError:time.sleep(.25)
 env=os.environ.copy();env['MYSQL_PWD']=m['pass']
 q=subprocess.run(['/opt/homebrew/bin/mysql','--protocol=TCP','-h','127.0.0.1','-P',str(port),'-u',m['user'],'--connect-timeout=8','--batch',m['name'],'-e',"SET SESSION max_execution_time=5000; START TRANSACTION READ ONLY; SELECT NOW(3) observed_at,DATABASE() database_schema; SELECT COUNT(*) table_count FROM information_schema.tables WHERE table_schema=DATABASE(); ROLLBACK;"],env=env,capture_output=True,text=True,timeout=25)
 if q.returncode:raise RuntimeError('read-only database query failed code '+str(q.returncode))
 out={'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'databaseSchema':m['name'],'redisPrefix':cfg['redis'].get('keyprefix'),'normalGroupTopic':cfg['kafka'].get('normalgroupcommandtopic'),'queryResult':q.stdout}
 f=root/'armada/docs/operations/evidence/new-group-creator-deletion-perf2-20261006-01a11087/runtime/android-database.json';f.write_text(json.dumps(out,indent=2));print(json.dumps(out,indent=2))
finally:p.terminate();p.wait(timeout=5)
