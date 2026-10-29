import subprocess,sys,re,os,pathlib
code=pathlib.Path(sys.argv[1]).read_text()
assert '3.110.124.52' in code,'Missing explicit origin guard'
env=os.environ.copy();env['BSK_AUTO_UPDATE']='off'
r=subprocess.run(['bsk','evaluate',code,'--session','voqx'],capture_output=True,text=True,env=env)
def clean(s):
 s=re.sub(r'(?<![a-zA-Z0-9])\+?\d{8,16}(?![a-zA-Z0-9])',lambda m:'[digits-redacted:'+m.group()[-4:]+']',s)
 s=re.sub(r'https://chat\.whatsapp\.com/[A-Za-z0-9]+','[invite-redacted]',s)
 s=re.sub(r'Bearer\s+[A-Za-z0-9._-]+','Bearer [redacted]',s)
 return s
out=clean(r.stdout)
if len(sys.argv)>2:pathlib.Path(sys.argv[2]).write_text(out)
print(out)
if r.stderr:print(clean(r.stderr),file=sys.stderr)
sys.exit(r.returncode)
