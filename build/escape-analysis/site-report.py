import sys,re,collections
tab=sys.argv[1]; hits=sys.argv[2]; N=int(sys.argv[3]) if len(sys.argv)>3 else 30
h={}
for l in open(hits):
    a,b=l.split(); h[int(a)]=int(b)
def short(s):
    s=s.replace('/Users/lorenzo/scala/scalino/vendor/scala3/compiler/src/','').replace('dotty.tools.dotc.','').replace('scala.collection.','sc.').replace('@"','').replace('scala.scalanative.runtime.','rt.')
    s=re.sub(r'M\d+','M',s); s=re.sub(r'T\d+','T',s)
    return s
def bucket(reason,esc,loop,stack):
    if stack: return "STACK"
    if not esc: return "NONESC-HEAP(loop/budget)"
    r=reason
    if r.startswith('anc:'): r=r[4:]; pre='anc:'
    else: pre=''
    if r.startswith('deep:'): r=r[5:]; pre+='deep:'
    m=re.match(r'callee-escapes(-deep)?:(.*?)#(\d+)',r)
    if m:
        callee=short(m.group(2)); 
        # keep owner.method
        mm=re.match(r'([^D]*?)D(\d+)(.*)',callee)
        return pre+'callee:'+callee[:60]+'#'+m.group(3)
    if r.startswith('unknown-call:virtual'): return pre+'unknown-virtual:'+short(r.split(':',2)[2])[:60]
    if r.startswith('unknown-call'): return pre+'unknown-extern:'+short(r)[:60]
    r=r.split(' @')[0]
    return pre+short(r)[:70]
tot=0; byb=collections.Counter(); bykind=collections.defaultdict(collections.Counter); byfn=collections.Counter()
rows=[]
for l in open(tab):
    p=l.rstrip('\n').split('\t')
    if len(p)<7: continue
    i=int(p[0]); c=h.get(i,0)
    if c==0: continue
    kind,esc,loop,stack,reason,fn=p[1],p[2]=='true',p[3]=='true',p[4]=='true',p[5],p[6]
    b=bucket(reason,esc,loop,stack)
    tot+=c; byb[b]+=c; bykind[b][kind]+=c
    if b!="STACK": byfn[(short(fn)[:90],kind)]+=c
print("total site hits",tot)
print("== by bucket")
for b,c in byb.most_common(N):
    top=', '.join(f"{short(k)[:28]}:{v}" for k,v in bykind[b].most_common(4))
    print(f"{c:>10} {100*c/tot:5.1f}% {b}   [{top}]")
print("== hottest heap allocating functions")
for (fn,kind),c in byfn.most_common(N):
    print(f"{c:>10} {short(kind)[:34]:34} {fn}")
