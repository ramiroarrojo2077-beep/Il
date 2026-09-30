import os, re, subprocess, urllib.request, sys
BASE="https://raw.githubusercontent.com/android/android-test/main/"
RAICES=["runner/monitor/java","espresso/idling_resource/java","core/java","runner/android_junit_runner/java","annotation/java","services/storage/java","opentest4j/java"]
SRC="src"
ANDROID=sys.argv[1]
pedidas=set()
def bajar(fqn):
    fqn=fqn.split('$')[0]
    if fqn in pedidas: return True
    pedidas.add(fqn)
    rel=fqn.replace('.','/')+".java"
    if fqn.startswith("androidx.annotation."):
        nombre=fqn.split('.')[-1]; os.makedirs(SRC+"/androidx/annotation",exist_ok=True)
        open(SRC+"/androidx/annotation/"+nombre+".java","w").write(
          "package androidx.annotation;\nimport java.lang.annotation.*;\n@Retention(RetentionPolicy.CLASS)\npublic @interface %s { %s }\n" % (nombre,
          {"RestrictTo":"Scope[] value(); enum Scope { LIBRARY, LIBRARY_GROUP, LIBRARY_GROUP_PREFIX, GROUP_ID, TESTS, SUBCLASSES }",
           "VisibleForTesting":"int otherwise() default 2; int PRIVATE=2; int PACKAGE_PRIVATE=3; int PROTECTED=4; int NONE=5;",
           "IntDef":"int[] value() default {}; boolean flag() default false; boolean open() default false;",
           "StringDef":"String[] value() default {}; boolean open() default false;",
           "GuardedBy":"String value();","IntRange":"long from() default Long.MIN_VALUE; long to() default Long.MAX_VALUE;",
           "RequiresApi":"int value() default 1; int api() default 1;","ChecksSdkIntAtLeast":"int api() default -1; String codename() default \"\"; int parameter() default -1; int lambda() default -1;"}.get(nombre,"")))
        return True
    for r in RAICES:
        try:
            datos=urllib.request.urlopen(BASE+r+"/"+rel, timeout=20).read()
        except Exception:
            continue
        os.makedirs(os.path.dirname(SRC+"/"+rel),exist_ok=True)
        open(SRC+"/"+rel,"wb").write(datos)
        return True
    print("NO ENCONTRADA", fqn); return False
for c in open("clases.txt").read().split():
    bajar(c.replace('/','.'))
for vuelta in range(40):
    archivos=[os.path.join(d,f) for d,_,fs in os.walk(SRC) for f in fs if f.endswith(".java")]
    r=subprocess.run(["javac","-nowarn","-proc:none","-d","out","-cp",ANDROID,"-encoding","UTF-8"]+archivos,capture_output=True,text=True)
    if r.returncode==0: print("compiló en vuelta",vuelta,len(archivos),"archivos"); break
    faltan=set()
    errores=r.stderr
    for m in re.finditer(r"import (androidx\.[\w.]+);", errores): faltan.add(m.group(1))
    for m in re.finditer(r"package (androidx\.[\w.]+) does not exist", errores): pass
    # símbolos: buscar en cada archivo con error los imports y clases del mismo paquete
    for m in re.finditer(r"(\S+\.java):\d+: error: cannot find symbol\n.*\n.*\n\s+symbol:\s+(?:class|variable) (\w+)\n\s+location: (?:class|interface|package) ([\w.]+)", errores):
        archivo, simbolo, lugar = m.groups()
        texto=open(archivo).read()
        imp=re.search(r"import ([\w.]+\.%s);"%simbolo, texto)
        if imp: faltan.add(imp.group(1))
        else:
            paquete=re.search(r"package ([\w.]+);",texto).group(1)
            if simbolo[0].isupper(): faltan.add(paquete+"."+simbolo)
    for m in re.finditer(r"error: package ([\w.]+) does not exist\n(.*)\n", errores):
        linea=m.group(2)
        mm=re.search(r"import ([\w.]+);", linea)
        if mm: faltan.add(mm.group(1))
    for a in archivos:
        t=open(a).read()
        for m in re.finditer(r"import static (androidx\.[\w.]+)\.\w+;", t): faltan.add(m.group(1))
        for m in re.finditer(r"import (androidx\.[\w.]+);", t): faltan.add(m.group(1))
    nuevas=[f for f in faltan if f not in pedidas and f.startswith("androidx")]
    if not nuevas:
        print(errores[:6000]); break
    for f in nuevas: bajar(f)
