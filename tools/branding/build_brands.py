import os,json,urllib.request,subprocess,base64,hashlib,re,tempfile,sys,traceback
from pathlib import Path
from apply_brand import apply
from brand_config import load_config
ROOT=Path.cwd();REPO='Lennyprs/KZPLAYER'
GAS,SECRET=load_config(report=True)
def call(action,**kwargs):
 request=urllib.request.Request(GAS,data=json.dumps(dict(action=action,secret=SECRET,**kwargs)).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(request,timeout=60) as response:data=json.load(response)
 if not data.get('ok'):raise RuntimeError(data.get('error','Erreur backend'))
 return data

def cmd(args,**kwargs):return subprocess.run(args,check=True,**kwargs)
def build(job):
 m=call('brandCiFetch',job_id=job);brand=m['brand_id'];version=int(m['version_code']);env=os.environ.copy();env.update(apply(ROOT,m))
 bt=sorted((Path(env['ANDROID_SDK_ROOT'])/'build-tools').glob('*'),key=lambda p:[int(x) for x in p.name.split('.') if x.isdigit()])[-1]
 cmd(['./gradlew','clean',':app:assembleRelease','--no-daemon','--stacktrace'],env=env)
 apk=ROOT/f'app-{brand}-{version}.apk';aligned=ROOT/'brand-aligned.apk';unsigned=ROOT/'app/build/outputs/apk/release/app-release-unsigned.apk'
 cmd([str(bt/'zipalign'),'-p','-f','4',str(unsigned),str(aligned)])
 with tempfile.TemporaryDirectory() as tmp:
  ks=Path(tmp)/'release.jks';ks.write_bytes(base64.b64decode(env['KEYSTORE_BASE64'],validate=True));ks.chmod(0o600)
  cmd([str(bt/'apksigner'),'sign','--ks',str(ks),'--ks-pass','env:KEYSTORE_PASSWORD','--ks-key-alias',env['KEY_ALIAS'],'--key-pass','env:KEY_PASSWORD','--out',str(apk),str(aligned)],env=env)
 verification=subprocess.check_output([str(bt/'apksigner'),'verify','--print-certs',str(apk)],text=True)
 cert=re.search(r'Signer #1 certificate SHA-256 digest:\s*([a-fA-F0-9]+)',verification)
 if not cert or cert.group(1).upper()!='E4288AF20EF2962943F4A7A0056CCB2661DE9D2838A311CDF4FA39BC6197BE6C':raise RuntimeError('Signature non conforme')
 metadata=subprocess.check_output([str(bt/'aapt'),'dump','badging',str(apk)],text=True)
 if f"name='{m['package_id']}'" not in metadata or f"versionCode='{version}'" not in metadata:raise RuntimeError('Identité APK non conforme')
 tag='brand-'+brand
 if subprocess.run(['gh','release','view',tag,'--repo',REPO],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
  cmd(['gh','release','create',tag,'--repo',REPO,'--target',env['GITHUB_SHA'],'--title','APK personnalisée '+brand,'--notes','Canal de marque signé. APK publiques, aucun secret dans ces fichiers.','--prerelease','--latest=false'])
 cmd(['gh','release','upload',tag,str(apk),'--repo',REPO,'--clobber'])
 name=re.search(r'versionName "([0-9.]+)"',(ROOT/'app/build.gradle').read_text()).group(1)
 call('brandCiComplete',job_id=job,success=True,version_name=name,sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
 mapping=ROOT/'app/build/outputs/mapping/release/mapping.txt'
 if mapping.exists():
  folder=ROOT/'brand-mappings';folder.mkdir(exist_ok=True);(folder/f'{brand}-{version}.txt').write_bytes(mapping.read_bytes())

if __name__=='__main__':
 job=os.environ.get('BRAND_JOB_ID','').strip();jobs=[job] if job else call('brandCiQueueAll')['jobs'];failed=False
 for job in jobs:
  if not re.fullmatch(r'job_[a-f0-9]{64}',job):raise RuntimeError('Job invalide')
  cmd(['git','restore','--source=HEAD','--worktree','--','app']);cmd(['git','clean','-fd','--','app/src/main/res'])
  try:build(job)
  except Exception:
   failed=True;traceback.print_exc()
   try:call('brandCiComplete',job_id=job,success=False)
   except Exception:pass
 if failed:raise SystemExit(1)
