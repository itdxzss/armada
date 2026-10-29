import subprocess,json,os,re,socket,time,sys,pathlib
ssh=['ssh','-F','/dev/null','-o','ControlMaster=auto','-o','ControlPersist=60','-o','ControlPath=/private/tmp/perf2-task37489533-ssh','-i','/Users/daishuaishuai/IdeaProjects/测试pem/armada-perf.pem','-o','BatchMode=yes','-o','ConnectTimeout=15','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=2','-o','StrictHostKeyChecking=accept-new','-o','UserKnownHostsFile=/private/tmp/codex_known_hosts']
host='ec2-user@3.110.124.52'
r=subprocess.run(ssh+[host,'docker inspect armada-backend --format "{{json .Config.Env}}"'],capture_output=True,text=True,check=True)
e=dict(x.split('=',1) for x in json.loads(r.stdout) if '=' in x)
u=e.get('DB_URL') or e.get('SPRING_DATASOURCE_URL')
m=re.search(r'mysql://([^/:]+)(?::(\d+))?/([^?]+)',u or '')
assert m and m[3]=='armada_perf','Unexpected database target'
user=e.get('DB_USER') or e.get('SPRING_DATASOURCE_USERNAME')
password=e.get('DB_PASSWORD') or e.get('SPRING_DATASOURCE_PASSWORD')
assert user and password,'Missing database auth'
sql=pathlib.Path(sys.argv[1]).read_text()
assert not re.search(r'\b(INSERT|UPDATE|DELETE|DROP|ALTER|TRUNCATE|REPLACE|CREATE|CALL|GRANT|INTO\s+OUTFILE)\b',sql,re.I),'Only read-only diagnostic SQL allowed'
s=socket.socket();s.bind(('127.0.0.1',0));port=s.getsockname()[1];s.close()
p=subprocess.Popen(ssh+['-o','ExitOnForwardFailure=yes','-N','-L',f'127.0.0.1:{port}:{m[1]}:{m[2] or 3306}',host],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
try:
 for _ in range(80):
  if p.poll() is not None: raise RuntimeError('Tunnel failed: '+p.stderr.read().decode())
  try:
   c=socket.create_connection(('127.0.0.1',port),.2);c.close();break
  except OSError: time.sleep(.25)
 env=os.environ.copy();env['MYSQL_PWD']=password
 query="SET time_zone='+08:00'; SET SESSION max_execution_time=5000; START TRANSACTION READ ONLY;\n"+sql+"\nROLLBACK;"
 q=subprocess.run(['/opt/homebrew/bin/mysql','--protocol=TCP','-h','127.0.0.1','-P',str(port),'-u',user,'--connect-timeout=10','--batch',m[3],'-e',query],env=env,capture_output=True,text=True,timeout=120)
 print(q.stdout)
 if q.returncode: print(q.stderr,file=sys.stderr)
 sys.exit(q.returncode)
finally:
 p.terminate();p.wait(timeout=5)
