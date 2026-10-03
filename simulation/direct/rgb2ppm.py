import sys
d=open(sys.argv[1],'rb').read(); n=int(sys.argv[2])
with open(sys.argv[3],'w') as o:
    o.write("P3\n%d %d\n255\n"%(n,n))
    for y in range(n):
        o.write(" ".join(str(b) for b in d[y*n*3:(y+1)*n*3])+"\n")
