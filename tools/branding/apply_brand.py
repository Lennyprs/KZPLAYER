import base64,io,re
from pathlib import Path
from xml.sax.saxutils import escape
from PIL import Image,ImageOps
Image.MAX_IMAGE_PIXELS=1048576

def read_png(text):
 if not text.startswith('data:image/png;base64,'):raise ValueError('PNG requis')
 raw=base64.b64decode(text.split(',',1)[1],validate=True)
 if len(raw)>130000:raise ValueError('Image trop volumineuse')
 im=Image.open(io.BytesIO(raw))
 if im.format!='PNG' or min(im.size)<32 or max(im.size)>2048 or im.width*im.height>1048576:raise ValueError('Image invalide')
 im.load();return im.convert('RGBA')

def apply(root,m):
 brand=m['brand_id'];package=m['package_id'];name=m['config']['app_name']
 if not re.fullmatch('b[a-f0-9]{24}',brand) or package!='com.kzplayer.brand.'+brand:raise ValueError('Package invalide')
 if not 2<=len(name)<=40 or re.search(r'[\x00-\x1f<>]',name) or name[0] in '=+@-':raise ValueError('Nom invalide')
 if not 1000000<=int(m['version_code'])<=2000000000:raise ValueError('Version invalide')
 icon=read_png(m['config']['icon_png']);logo=read_png(m['config']['logo_png']);res=root/'app/src/main/res'
 for dpi,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
  folder=res/('mipmap-'+dpi);folder.mkdir(exist_ok=True);canvas=Image.new('RGBA',(size,size));im=ImageOps.contain(icon,(size,size));canvas.alpha_composite(im,((size-im.width)//2,(size-im.height)//2));canvas.save(folder/'ic_launcher.png')
 (res/'drawable/app_logo.xml').unlink(missing_ok=True);folder=res/'drawable-nodpi';folder.mkdir(exist_ok=True)
 def fitted(image,size,background=(0,0,0,0)):
  canvas=Image.new('RGBA',size,background);image=ImageOps.contain(image,size,method=Image.Resampling.LANCZOS);canvas.alpha_composite(image,((size[0]-image.width)//2,(size[1]-image.height)//2));return canvas
 # Dimensions EXACTES des fichiers KZ Player d'origine. Icônes et logos ont chacun leur source.
 fitted(icon,(1024,1024)).save(folder/'kz_logo_icon.png')
 fitted(icon,(1024,1024)).save(folder/'app_logo.png')
 for n in ['kz_logo_wide.png','logo_kz_wide.png']:fitted(logo,(1400,420)).save(folder/n)
 banner=Image.new('RGBA',(1280,720),(0,0,0,255));wide=fitted(logo,(1120,336));banner.alpha_composite(wide,(80,192));banner.convert('RGB').save(folder/'app_banner_kz.png')
 # Balises d'habillage distant : ressources de marque seulement.
 for layout in res.glob('layout*/*.xml'):
  text=layout.read_text()
  def tag_view(match):
   block=match.group(0)
   if 'android:tag=' in block:return block
   tag=None
   if block.startswith('<ImageView'):
    if any(x in block for x in ['@drawable/kz_logo_wide','@drawable/logo_kz_wide']):tag='brand:wide'
    elif any(x in block for x in ['@drawable/kz_logo_icon','@drawable/app_logo']):tag='brand:icon'
   elif block.startswith('<TextView'):
    if 'android:text="KZ"' in block:
     block=block.replace('android:text="KZ"','android:text="@string/app_name"');tag='brand:name'
    elif 'android:text="PLAYER"' in block:block=block.replace('android:text="PLAYER"','android:text=""')
    elif 'android:text="KZ Player"' in block or 'android:text="@string/app_name"' in block:tag='brand:name'
    elif 'android:textColor="?attr/kzAccent"' in block:tag='brand:accent'
   if tag:block=block.replace('/>','android:tag="'+tag+'" />')
   return block
  text=re.sub(r'<(?:ImageView|TextView)\b[^>]*?/>',tag_view,text,flags=re.S)
  layout.write_text(text)
 literal=name.replace('\\','\\\\').replace("'","\\'").replace('"','\\"').replace('@','\\@').replace('?','\\?')
 for f in res.rglob('*.xml'):
  s=f.read_text();s=s.replace('KZ Player',escape(literal,{'"':'&quot;',"'":'&apos;'})).replace('KZPLAYER',escape(literal.upper(),{'"':'&quot;',"'":'&apos;'}));f.write_text(s)
 manifest=root/'app/src/main/AndroidManifest.xml';manifest.write_text(manifest.read_text().replace('android:label="KZ Player"','android:label="@string/app_name"'))
 return {'BRAND_ID':brand,'BRAND_PACKAGE':package,'VERSION_CODE':str(m['version_code'])}
