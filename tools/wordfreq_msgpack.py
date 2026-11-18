import gzip, struct, sys
def unpack(b):
    pos=[0]
    def rd(n):
        p=pos[0]; pos[0]+=n; return b[p:p+n]
    def val():
        t=b[pos[0]]; pos[0]+=1
        if t<=0x7f: return t
        if 0x80<=t<=0x8f: return {val():val() for _ in range(t&0xf)}
        if 0x90<=t<=0x9f: return [val() for _ in range(t&0xf)]
        if 0xa0<=t<=0xbf: return rd(t&0x1f).decode()
        if t==0xc0: return None
        if t==0xc2: return False
        if t==0xc3: return True
        if t==0xd9: n=b[pos[0]]; pos[0]+=1; return rd(n).decode()
        if t==0xda: n=struct.unpack('>H',rd(2))[0]; return rd(n).decode()
        if t==0xdb: n=struct.unpack('>I',rd(4))[0]; return rd(n).decode()
        if t==0xdc: n=struct.unpack('>H',rd(2))[0]; return [val() for _ in range(n)]
        if t==0xdd: n=struct.unpack('>I',rd(4))[0]; return [val() for _ in range(n)]
        if t==0xde: n=struct.unpack('>H',rd(2))[0]; return {val():val() for _ in range(n)}
        if t==0xcc: return rd(1)[0]
        if t==0xcd: return struct.unpack('>H',rd(2))[0]
        if t==0xce: return struct.unpack('>I',rd(4))[0]
        if t>=0xe0: return t-256
        raise Exception(hex(t))
    return val()
def load(path):
    d=unpack(gzip.open(path).read())
    out={}
    for i,bucket in enumerate(d[1:]):
        for w in bucket: out[w]=10**(-i/100)
    return out
if __name__=='__main__':
    d=load(sys.argv[1]); print(len(d)); 
    for w in list(d)[:20]: print(w, d[w])
