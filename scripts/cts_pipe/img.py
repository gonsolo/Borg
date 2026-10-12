#!/usr/bin/env python3
"""img.py result.qpa [v|diff]: decode the PNG images in a CTS result; v prints pixels, diff lists mismatches."""
import re,base64,zlib,struct,sys
t=open(sys.argv[1],errors="ignore").read()
def unf(raw,w,h,bpp):
    st=w*bpp; out=[]; prev=bytearray(st)
    p=0
    for y in range(h):
        f=raw[p]; row=bytearray(raw[p+1:p+1+st]); p+=1+st
        for i in range(st):
            a=row[i-bpp] if i>=bpp else 0; b=prev[i]; c=prev[i-bpp] if i>=bpp else 0
            if f==1: row[i]=(row[i]+a)&255
            elif f==2: row[i]=(row[i]+b)&255
            elif f==3: row[i]=(row[i]+((a+b)>>1))&255
            elif f==4:
                pa=abs(b-c); pb=abs(a-c); pc=abs(a+b-2*c)
                pr=a if pa<=pb and pa<=pc else (b if pb<=pc else c)
                row[i]=(row[i]+pr)&255
        out.append(row); prev=row
    return out
def decode(b):
    d=base64.b64decode(b); i=8; idat=b''
    while i<len(d):
        l,=struct.unpack('>I',d[i:i+4]); ty=d[i+4:i+8]
        if ty==b'IHDR': w,h,bd,ct=struct.unpack('>IIBB',d[i+8:i+18])
        if ty==b'IDAT': idat+=d[i+8:i+8+l]
        i+=12+l
    bpp=4 if ct==6 else 3
    return unf(zlib.decompress(idat),w,h,bpp),w,h,bpp
imgs={}
for m in re.finditer(r'<Image Name="(\w+)"[^>]*Width="(\d+)" Height="(\d+)"[^>]*>([^<]*)</Image>',t):
    n=m.group(1); imgs[n]=decode(m.group(4)); rows,w,h,bpp=imgs[n]
    cols=sorted(set(tuple(r[x*bpp:x*bpp+bpp]) for r in rows for x in range(w)))
    print(n,w,h,"colours:",cols[:6])
    if len(sys.argv)>2 and sys.argv[2]=='v' and n!="ErrorMask":
        for y in range(0,h,max(1,h//8)):
            print(''.join('%02x%02x%02x%02x '%tuple(rows[y][x*bpp:x*bpp+4]) for x in range(0,w,max(1,w//4))) if bpp==4 else '')
if len(sys.argv)>2 and sys.argv[2]=='diff':
    a,w,h,bpp=imgs['Result']; r=imgs['Reference'][0]
    bad=[(x,y,tuple(a[y][x*bpp:x*bpp+bpp]),tuple(r[y][x*bpp:x*bpp+bpp])) for y in range(h) for x in range(w) if a[y][x*bpp:x*bpp+bpp]!=r[y][x*bpp:x*bpp+bpp]]
    xs=[b[0] for b in bad]; ys=[b[1] for b in bad]
    print(len(bad),'bad;',bad[:6],'; x range',min(xs,default=''),max(xs,default=''),'y range',min(ys,default=''),max(ys,default=''))
