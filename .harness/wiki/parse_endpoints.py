# -*- coding: utf-8 -*-
"""解析 wheel 所有 @RestController，抽取端点契约（method/path/javadoc/@RequirePerm/参数/响应）。"""
import re, json

files = [l for l in open('/tmp/wheel_controllers.txt').read().split() if l.strip()]
BASE = "/Users/daishuaishuai/IdeaProjects/wheel/"

VERB = {'Get':'GET','Post':'POST','Put':'PUT','Delete':'DELETE','Patch':'PATCH'}
MAP_RE = re.compile(r'@(Get|Post|Put|Delete|Patch|Request)Mapping\b')
INFRA_TYPES = {'TenantContext','AuthContext','HttpServletRequest','HttpServletResponse',
    'HttpHeaders','Principal','Authentication','BindingResult','UriComponentsBuilder','Locale','Pageable'}

def grab_parens(s, start):
    """start 指向 '(' ；返回 (内部内容, 结束后位置)。无括号返回 ('', start)。"""
    if start >= len(s) or s[start] != '(':
        return '', start
    depth = 0; i = start
    while i < len(s):
        c = s[i]
        if c == '(': depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return s[start+1:i], i+1
        i += 1
    return s[start+1:], len(s)

def split_top(s, seps=','):
    """按顶层分隔符切分（忽略 <>、()、{}、字符串内）。"""
    out=[]; depth=0; buf=''; instr=None
    for c in s:
        if instr:
            buf+=c
            if c==instr: instr=None
            continue
        if c in '"\'': instr=c; buf+=c; continue
        if c in '<({[': depth+=1
        elif c in '>)}]': depth-=1
        if c in seps and depth==0:
            out.append(buf); buf=''
        else:
            buf+=c
    if buf.strip(): out.append(buf)
    return out

def str_lit(s):
    m = re.search(r'"([^"]*)"', s)
    return m.group(1) if m else None

def join_path(base, p):
    base = base or ''
    p = p or ''
    if not p: full = base
    elif not base: full = p
    else: full = base.rstrip('/') + '/' + p.lstrip('/')
    if not full.startswith('/'): full = '/'+full
    return re.sub(r'//+','/',full)

def parse_javadoc(block):
    """返回 (summary, {param: desc}, return_desc)。"""
    if not block: return '', {}, ''
    lines = block.split('\n')
    clean=[]
    for ln in lines:
        ln = ln.strip()
        ln = re.sub(r'^/\*\*?','',ln); ln = re.sub(r'\*/$','',ln)
        ln = re.sub(r'^\*\s?','',ln)
        clean.append(ln)
    text='\n'.join(clean)
    # summary = 第一段；之后继续扫 @param/@return（不能在空行 break，否则丢标签）
    summ=[]
    params={}; ret=''
    cur=None; summ_done=False
    for ln in clean:
        st=ln.strip()
        m=re.match(r'@param\s+(\w+)\s*(.*)', st)
        mr=re.match(r'@return\s*(.*)', st)
        if m:
            cur=('p',m.group(1)); params[m.group(1)]=m.group(2).strip(); summ_done=True; continue
        if mr:
            cur=('r',None); ret=mr.group(1).strip(); summ_done=True; continue
        if st.startswith('@'):  # 其它 tag
            cur='other'; summ_done=True; continue
        if cur is None:
            if st=='':
                if summ: summ_done=True
                continue
            if not summ_done:
                s2=re.sub(r'<[^>]+>','',st).strip()
                if s2: summ.append(s2)
        elif cur and cur[0]=='p':
            params[cur[1]]=(params[cur[1]]+' '+st).strip()
        elif cur and cur[0]=='r':
            ret=(ret+' '+st).strip()
    def clean_tags(s):
        s=re.sub(r'\{@(?:code|link|linkplain|literal|value)\s+([^}]*)\}', r'\1', s)
        return re.sub(r'\s+',' ',s).strip()
    summary=clean_tags(' '.join(summ))
    params={k:clean_tags(v) for k,v in params.items()}
    ret=clean_tags(ret)
    return summary, params, ret

def classify_param(p, pdoc):
    p=p.strip()
    if not p: return None
    ann = ' '.join(re.findall(r'@\w+(?:\([^)]*\))?', p))
    rest = re.sub(r'@\w+(?:\([^)]*\))?','',p).strip()
    toks = rest.split()
    if len(toks)<2: return None
    typ=' '.join(toks[:-1]); var=toks[-1]
    desc = pdoc.get(var,'')
    if '@PathVariable' in ann:
        nm = str_lit(ann) or var
        return {'kind':'path','name':nm,'type':typ,'desc':desc}
    if '@RequestParam' in ann:
        am = re.search(r'@RequestParam\(([^)]*)\)', ann)
        args = am.group(1) if am else ''
        nm = None
        mn=re.search(r'(?:name|value)\s*=\s*"([^"]+)"',args)
        if mn: nm=mn.group(1)
        elif '"' in args: nm=str_lit(args)
        nm = nm or var
        required = not re.search(r'required\s*=\s*false',args) and 'defaultValue' not in args
        dv=re.search(r'defaultValue\s*=\s*"([^"]*)"',args)
        return {'kind':'query','name':nm,'type':typ,'required':required,'default':dv.group(1) if dv else None,'desc':desc}
    if '@RequestBody' in ann:
        return {'kind':'body','name':var,'type':typ,'desc':desc}
    if '@RequestPart' in ann:
        nm=str_lit(ann) or var
        return {'kind':'part','name':nm,'type':typ,'desc':desc}
    # 无注解
    bt=typ.split('<')[0].split('.')[-1]
    if bt in INFRA_TYPES: return None
    if bt[:1].isupper():  # 复杂对象 -> 查询对象绑定
        return {'kind':'queryobj','name':var,'type':typ,'desc':desc}
    return None

def resp_type(ret):
    ret=ret.strip()
    m=re.match(r'ApiResponse<(.+)>$', ret)
    if m:
        inner=m.group(1).strip()
        mp=re.match(r'PageResult<(.+)>$', inner) or re.match(r'PageResponse<(.+)>$', inner)
        if mp: return f"分页（{mp.group(1)}）"
        if inner in ('Void','java.lang.Void'): return '无'
        return inner
    return ret  # 非包装（如 ResponseEntity / byte[] / void）

result=[]
for f in files:
    path=f if f.startswith('/') else BASE+f
    text=open(path,encoding='utf-8').read()
    cls_m=re.search(r'\b(?:public\s+)?(?:final\s+)?class\s+(\w+)', text)
    cname=cls_m.group(1) if cls_m else f.split('/')[-1][:-5]
    cls_pos=cls_m.start() if cls_m else 0
    module=re.sub(r'.*?(wheel-api-[a-z]+)/.*', r'\1', f)
    # 类级注解（class 声明前 ~600 字符）
    head=text[max(0,cls_pos-800):cls_pos]
    cb=None
    rm=re.search(r'@RequestMapping\(([^)]*)\)', head)
    if rm: cb=str_lit(rm.group(1))
    cperm_m=re.search(r'@RequirePerm\("([^"]+)"\)', head)
    cperm=cperm_m.group(1) if cperm_m else None

    eps=[]
    for m in MAP_RE.finditer(text):
        if m.start() < cls_pos:   # 类级 mapping，跳过
            continue
        kind=m.group(1)
        # 注解参数
        pstart=m.end()
        while pstart<len(text) and text[pstart] in ' \t': pstart+=1
        args, after = grab_parens(text, pstart) if pstart<len(text) and text[pstart]=='(' else ('', m.end())
        # 该 mapping 若是 @RequestMapping 且在类级位置(已过滤)；方法级 @RequestMapping 也可能存在
        verb = VERB.get(kind)
        if verb is None:  # RequestMapping
            mv=re.search(r'RequestMethod\.(\w+)', args)
            verb = mv.group(1) if mv else 'ANY'
        mpath = str_lit(args)
        full = join_path(cb, mpath)
        # 方法体起点 = after 之后第一个顶层 '{'
        b=text.find('{', after)
        if b<0: continue
        headspan=text[after:b]
        perm_m=re.search(r'@RequirePerm\("([^"]+)"\)', headspan)
        perm=perm_m.group(1) if perm_m else cperm
        # javadoc：m.start 之前最近的 /** */
        pre=text[:m.start()]
        je=pre.rfind('*/')
        javadoc=''
        if je!=-1:
            js=pre.rfind('/**', 0, je)
            gap=pre[je+2:]
            if js!=-1 and '}' not in gap and ';' not in gap:  # 中间只有注解/空白
                javadoc=pre[js:je+2]
        summary,pdoc,retdoc=parse_javadoc(javadoc)
        # 签名：剥掉 headspan 前面的注解，剩下 [修饰符] 返回类型 方法名(参数)
        sig=headspan
        while True:
            sig=sig.lstrip()
            if sig.startswith('@'):
                am2=re.match(r'@\w+', sig); sig=sig[am2.end():].lstrip()
                if sig.startswith('('):
                    _,e2=grab_parens(sig,0); sig=sig[e2:]
                continue
            break
        ret_type=''; params=[]; mname=''
        po=sig.find('(')
        if po>0:
            left=sig[:po].split()
            MODS={'public','protected','private','static','final','synchronized','default','abstract'}
            if left:
                mname=left[-1]
                rt=[t for t in left[:-1] if t not in MODS]
                ret_type=' '.join(rt)
            pinner,_=grab_parens(sig, po)
            for praw in split_top(pinner):
                cp=classify_param(praw, pdoc)
                if cp: params.append(cp)
        eps.append({'method':verb,'path':full,'name':mname,'summary':summary,'perm':perm,
                    'params':params,'response':resp_type(ret_type),'ret_raw':ret_type,'retdoc':retdoc})
    if eps:
        result.append({'controller':cname,'module':module,'base':cb,'class_perm':cperm,'endpoints':eps})

json.dump(result, open('/tmp/wheel_endpoints.json','w',encoding='utf-8'), ensure_ascii=False, indent=1)
tot=sum(len(c['endpoints']) for c in result)
print(f"controllers={len(result)} endpoints={tot}")
# 抽样自检
for c in result:
    if c['controller']=='TenantAccountController':
        for e in c['endpoints'][:3]:
            print(json.dumps(e,ensure_ascii=False))
