import subprocess,sys,re,os
env=os.environ.copy();env['BSK_AUTO_UPDATE']='off'
p=subprocess.run(['bsk',*sys.argv[1:]],capture_output=True,text=True,env=env)
def clean(s):
 s=re.sub(r'(?<![a-zA-Z0-9])\+?\d{8,16}(?![a-zA-Z0-9])',lambda m:'[digits-redacted:'+m.group()[-4:]+']',s)
 s=re.sub(r'https://chat\.whatsapp\.com/[A-Za-z0-9]+','[invite-redacted]',s)
 s=re.sub(r'Bearer\s+[A-Za-z0-9._-]+','Bearer [redacted]',s)
 return s
print(clean(p.stdout))
if p.stderr:print(clean(p.stderr),file=sys.stderr)
sys.exit(p.returncode)
