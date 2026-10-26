package app.voidmusic
import android.app.*
import android.os.Bundle
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.*
import android.text.TextUtils

class MainActivity : Activity() {
 private val bg=Color.rgb(8,10,16); private val surface=Color.rgb(17,21,31); private val surface2=Color.rgb(24,29,42)
 private val cyan=Color.rgb(80,225,255); private val primary=Color.rgb(242,245,250); private val muted=Color.rgb(143,153,172)
 private val io=Executors.newFixedThreadPool(2); private val imageIo=Executors.newFixedThreadPool(2); private lateinit var covers:LruCache<String,Bitmap>; private lateinit var library:LocalLibrary; private lateinit var content:LinearLayout; private lateinit var status:TextView; private lateinit var query:EditText; private lateinit var sourcePicker:Spinner; private lateinit var api:CatalogApi; private lateinit var soundCloudCredentials:SoundCloudCredentialStore
 private var page="Accueil"; private var selectedSource="Toutes les sources"; private var current:List<Track> = emptyList(); private var recents:List<Track> = emptyList(); private var search:Future<*>?=null; private var generation=0
 private var exportItems:List<Pair<String,List<Track>>> = emptyList()
 companion object { private const val ZIP=41 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState); library=LocalLibrary(this); api=CatalogApi(); soundCloudCredentials=SoundCloudCredentialStore(this); val cacheKb=getSharedPreferences("void_music",MODE_PRIVATE).getInt("image_cache_kb",4096); covers=object : LruCache<String,Bitmap>(cacheKb){ override fun sizeOf(key:String,value:Bitmap)=value.byteCount/1024 }; window.statusBarColor=bg; window.navigationBarColor=bg
  if(android.os.Build.VERSION.SDK_INT>=29){window.isStatusBarContrastEnforced=false;window.isNavigationBarContrastEnforced=false}
  val root=LinearLayout(this).apply{orientation=1;setPadding(dp(20),dp(14),dp(20),dp(8));setBackgroundColor(bg)}
  val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL};val brand=LinearLayout(this).apply{orientation=1}
  brand.addView(label("VOID",26f,cyan,true));brand.addView(label("MUSIC / CATALOGUE",10f,muted,true));header.addView(brand,LinearLayout.LayoutParams(0,-2,1f));header.addView(label("DEEZER",10f,muted,true));root.addView(header)
  status=label("Catalogues publics",10f,muted,false).apply{setPadding(0,dp(10),0,dp(8))};root.addView(status)
  val scroll=ScrollView(this).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER};content=LinearLayout(this).apply{orientation=1};scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  val nav=LinearLayout(this).apply{gravity=Gravity.CENTER;background=rounded(surface,dp(18))}
  listOf("Accueil","Recherche","Bibliothèque","Paramètres").forEach { name -> nav.addView(TextView(this).apply{text=name;textSize=10f;gravity=Gravity.CENTER;setTextColor(if(name==page)cyan else muted);setPadding(dp(4),dp(14),dp(4),dp(14));setOnClickListener{page=name;render()}},LinearLayout.LayoutParams(0,-2,1f)) }
  root.addView(nav,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)});setContentView(root);render()
 }
 private fun render(){content.removeAllViews();when(page){
  "Accueil"->{content.addView(label("Ta musique, sans le bruit.",23f,primary,true).apply{setPadding(0,dp(18),0,dp(5))});content.addView(label("Explore le catalogue et organise tes sélections.",13f,muted,false));searchField()
   section("Récents");if(recents.isEmpty())empty("Tes recherches apparaîtront ici.")else tracks(recents.take(5))
   section("Favoris");val f=library.favorites();if(f.isEmpty())empty("Ajoute un titre en favori depuis Recherche.")else tracks(f.take(5))
   section("Playlists");library.playlists().forEach{(n,t)->row(n,t.size.toString()+" titre(s)"){playlist(n)}}}
  "Recherche"->{content.addView(label("Recherche",24f,primary,true).apply{setPadding(0,dp(18),0,dp(14))});searchField();if(current.isEmpty())empty("Recherche des titres du catalogue public.")else tracks(current)}
  "Bibliothèque"->libraryScreen()
  else->settings()
 }}
 private fun searchField(){val box=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;background=rounded(surface,dp(15),Color.rgb(37,45,61),dp(1));setPadding(dp(10),0,dp(5),0)}
  query=EditText(this).apply{hint="Titre, artiste, album...";setHintTextColor(muted);setTextColor(primary);textSize=14f;singleLine=true;imeOptions=EditorInfo.IME_ACTION_SEARCH;background=null;setOnEditorActionListener{_,a,_->if(a==EditorInfo.IME_ACTION_SEARCH){searchTracks();true}else false}}
  box.addView(query,LinearLayout.LayoutParams(0,dp(50),1f));box.addView(button("CHERCHER",cyan,bg){searchTracks()},LinearLayout.LayoutParams(-2,dp(40)));content.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(5)});sourcePicker=Spinner(this);val providers=arrayOf("Toutes les sources","Deezer","Audius","SoundCloud");sourcePicker.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,providers);sourcePicker.setSelection(providers.indexOf(selectedSource).coerceAtLeast(0));sourcePicker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(parent:AdapterView<*>?){};override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){selectedSource=providers[position]}};content.addView(sourcePicker,LinearLayout.LayoutParams(-1,dp(44)))
 }
 private fun searchTracks(){
  val term=query.text.toString().trim()
  if(term.isEmpty()){setStatus("Saisis un titre, un artiste ou un album.");return}
  val selected=sourcePicker.selectedItem?.toString() ?: "Toutes les sources"
  val requested=when(selected){"Deezer"->listOf("Deezer");"Audius"->listOf("Audius");"SoundCloud"->listOf("SoundCloud");else->listOf("Deezer","Audius","SoundCloud")}
  val token=++generation
  search?.cancel(true)
  setStatus("Recherche sur "+selected+" · résultats précédents conservés")
  search=io.submit{
   val found=mutableListOf<Track>();val errors=mutableListOf<String>()
   requested.forEach{provider->
    if(Thread.currentThread().isInterrupted)return@forEach
    try{
     val tracks=when(provider){
      "Deezer"->api.searchDeezer(term)
      "Audius"->api.searchAudius(term)
      else->{val id=soundCloudCredentials.clientId();val secret=soundCloudCredentials.secret()
       if(id.isBlank()||secret.isBlank())throw IllegalStateException("SoundCloud : configure les identifiants dans Paramètres.")
       api.searchSoundCloud(term,id,secret,soundCloudCredentials)}
     }
     found.addAll(tracks)
    }catch(e:Exception){errors.add(e.localizedMessage ?: (provider+" indisponible"))}
   }
   runOnUiThread{
    if(token!=generation||isFinishing||isDestroyed)return@runOnUiThread
    if(found.isEmpty()&&errors.isNotEmpty()){
     val message=errors.distinct().joinToString("\n")
     setStatus(message);content.removeAllViews()
     content.addView(label("Recherche impossible",16f,primary,true))
     content.addView(label(message,13f,muted,false))
     content.addView(button("RÉESSAYER",cyan,bg){searchTracks()})
    }else{
     current=found.distinctBy{it.libraryKey}
     recents=(current+recents).distinctBy{it.libraryKey}.take(10)
     page="Recherche";render()
     val partial=if(errors.isEmpty())"" else " · "+errors.distinct().joinToString(" / ")
     setStatus(current.size.toString()+" résultat(s)"+partial)
    }
   }
  }
 } private fun tracks(items:List<Track>, onLongClick:((Track)->Unit)?=null){items.forEach{t->val card=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;background=rounded(surface,dp(15));setPadding(dp(12),dp(12),dp(8),dp(12))}
  val cover=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP;background=rounded(surface2,dp(11));contentDescription="Pochette de "+t.album};card.addView(cover,LinearLayout.LayoutParams(dp(48),dp(48)));loadCover(t.cover,cover)
  val info=LinearLayout(this).apply{orientation=1;setPadding(dp(11),0,dp(5),0)};info.addView(label(t.title,14f,primary,true).ellip());info.addView(label(t.artist+" · "+t.album+" · "+t.provider,11f,muted,false).ellip());info.addView(label(duration(t.duration),10f,muted,false));card.addView(info,LinearLayout.LayoutParams(0,-2,1f))
  val actions=LinearLayout(this).apply{orientation=1;gravity=Gravity.CENTER};val fav=TextView(this).apply{gravity=Gravity.CENTER;textSize=21f}
  fun update(){val yes=library.favorites().any{it.libraryKey==t.libraryKey};fav.text=if(yes)"★" else "☆";fav.setTextColor(if(yes)cyan else muted)}
  update();fav.setOnClickListener{library.toggleFavorite(t);update();setStatus("Favoris enregistrés sur cet appareil.")};actions.addView(fav,LinearLayout.LayoutParams(dp(34),dp(34)))
  actions.addView(TextView(this).apply{text="OUVRIR";textSize=9f;gravity=Gravity.CENTER;setTextColor(cyan);setOnClickListener{open(t)}},LinearLayout.LayoutParams(dp(42),dp(26)));card.addView(actions)
  card.setOnLongClickListener{(onLongClick?:{track->addToPlaylist(track)}).invoke(t);true};content.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})}}
 private fun loadCover(url:String,target:ImageView){
  if(!url.startsWith("https://"))return
  target.tag=url;covers.get(url)?.let{target.setImageBitmap(it);return}
  imageIo.submit{
   var connection:HttpURLConnection?=null
   try{
    connection=URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout=5000;connection.readTimeout=5000
    val bitmap=connection.inputStream.use{BitmapFactory.decodeStream(it)}
    if(bitmap!=null){covers.put(url,bitmap);runOnUiThread{if(!isFinishing&&target.tag==url)target.setImageBitmap(bitmap)}}
   }catch(_:Exception){}finally{connection?.disconnect()}
  }
 } private fun libraryScreen(){content.addView(label("Bibliothèque",24f,primary,true).apply{setPadding(0,dp(18),0,dp(10))});content.addView(button("NOUVELLE PLAYLIST",cyan,bg){createPlaylist()})
  section("Favoris · "+library.favorites().size);val f=library.favorites();if(f.isEmpty())empty("Les favoris restent sur cet appareil.")else tracks(f)
  section("Playlists locales");library.playlists().forEach{(n,t)->val r=row(n,t.size.toString()+" titre(s) · appui long pour supprimer"){playlist(n)};r.setOnLongClickListener{confirmDelete(n);true}}
  content.addView(button("EXPORTER LES PLAYLISTS EN ZIP",surface2,primary){beginExport()})}
 private fun playlist(name:String){page="Bibliothèque";content.removeAllViews();content.addView(label(name,22f,primary,true).apply{setPadding(0,dp(18),0,dp(12))});val list=library.playlists()[name].orEmpty();if(list.isEmpty())empty("Playlist vide. Maintiens un titre dans Recherche pour l’ajouter.")else tracks(list){track->AlertDialog.Builder(this).setMessage("Retirer « "+track.title+" » de cette playlist ?").setNegativeButton("Annuler",null).setPositiveButton("Retirer"){_,_->library.savePlaylist(name,list.filterNot{it.libraryKey==track.libraryKey});playlist(name)}.show()};content.addView(button("SUPPRIMER LA PLAYLIST",surface2,primary){confirmDelete(name)})}
 private fun confirmDelete(n:String){AlertDialog.Builder(this).setMessage("Supprimer « "+n+" » ?").setNegativeButton("Annuler",null).setPositiveButton("Supprimer"){_,_->library.deletePlaylist(n);render()}.show()}
 private fun createPlaylist(){val f=EditText(this).apply{hint="Nom de playlist";setTextColor(primary)};AlertDialog.Builder(this).setTitle("Nouvelle playlist").setView(f).setNegativeButton("Annuler",null).setPositiveButton("Créer"){_,_->val n=f.text.toString().trim();if(n.isNotEmpty()&&n.length<=60&&!library.playlists().containsKey(n)){library.savePlaylist(n,emptyList());render()}else setStatus("Nom invalide ou déjà utilisé.")}.show()}
 private fun addToPlaylist(t:Track){val lists=library.playlists();if(lists.isEmpty()){setStatus("Crée une playlist dans Bibliothèque.");return};val names=lists.keys.toTypedArray();AlertDialog.Builder(this).setTitle("Ajouter à une playlist").setItems(names){_,i->library.savePlaylist(names[i],(lists[names[i]].orEmpty()+t).distinctBy{it.libraryKey});setStatus("Ajouté à « "+names[i]+" ».")}.show()}
 private fun settings(){
  content.addView(label("Paramètres",24f,primary,true).apply{setPadding(0,dp(18),0,dp(12))})
  section("Audius")
  content.addView(label("La recherche et le streaming de pistes publiques sont en lecture seule et fonctionnent sans clé. La recherche publique reste disponible sans identifiant.",13f,muted,false))
  content.addView(button("TESTER AUDIUS",surface2,primary){io.submit{val message=try{"Audius accessible · "+api.searchAudius("VOID").size+" résultat(s)"}catch(e:Exception){e.localizedMessage?:"Audius indisponible"};runOnUiThread{if(!isFinishing&&!isDestroyed)setStatus(message)}}})
  section("SoundCloud · accès API")
  content.addView(label("SoundCloud exige une application enregistrée ; les inscriptions demandent actuellement Artist Pro. Saisis les identifiants de ta propre application. L’application demande un jeton Client Credentials pour la recherche publique.",13f,muted,false))
  val idField=EditText(this).apply{hint="SoundCloud Client ID";setTextColor(primary);setHintTextColor(muted);singleLine=true;setText(soundCloudCredentials.clientId())}
  val secretField=EditText(this).apply{hint="SoundCloud Client Secret";setTextColor(primary);setHintTextColor(muted);singleLine=true;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;setText(soundCloudCredentials.secret())}
  content.addView(idField);content.addView(secretField)
  content.addView(label("Le secret est chiffré avec Android Keystore sur cet appareil. Une application mobile ne peut toutefois pas le protéger d’un appareil compromis pendant son utilisation. Ne partage pas ce secret.",11f,muted,false).apply{setPadding(0,dp(6),0,dp(7))})
  content.addView(button("ENREGISTRER LES IDENTIFIANTS",surface2,primary){
   val id=idField.text.toString().trim();val secret=secretField.text.toString()
   if(id.isBlank()||secret.isBlank())setStatus("Client ID et secret sont requis.")
   else try{soundCloudCredentials.save(id,secret);api.clearSoundCloudToken();setStatus("Identifiants SoundCloud chiffrés et enregistrés sur cet appareil.")}
   catch(e:Exception){setStatus("Échec du chiffrement : "+(e.localizedMessage?:"erreur de stockage"))}
  })
  content.addView(button("TESTER SOUNDCLOUD",surface2,primary){
   val id=idField.text.toString().trim();val secret=secretField.text.toString()
   if(id.isBlank()||secret.isBlank())setStatus("Client ID et secret sont requis.")
   else try{
    soundCloudCredentials.save(id,secret);api.clearSoundCloudToken()
    io.submit{
     val message=try{api.testSoundCloud(id,secret,soundCloudCredentials);"Authentification de l’application SoundCloud réussie."}
     catch(e:Exception){e.localizedMessage?:"Connexion SoundCloud impossible"}
     runOnUiThread{if(!isFinishing&&!isDestroyed)setStatus(message)}
    }
   }catch(e:Exception){setStatus("Échec du chiffrement : "+(e.localizedMessage?:"erreur de stockage"))}
  })
  content.addView(button("EFFACER LES IDENTIFIANTS SOUNDCLOUD",surface2,primary){AlertDialog.Builder(this).setMessage("Effacer les identifiants et jetons SoundCloud de cet appareil ?").setNegativeButton("Annuler",null).setPositiveButton("Effacer"){_,_->soundCloudCredentials.clear();api.clearSoundCloudToken();setStatus("Identifiants SoundCloud effacés.");settings()}.show()})
  section("Deezer")
  content.addView(label("La recherche publique du catalogue ne nécessite pas de jeton. Les titres s’ouvrent sur Deezer.",13f,muted,false))
  content.addView(button("TESTER DEEZER",surface2,primary){io.submit{val message=try{"Deezer accessible · "+api.searchDeezer("VOID").size+" résultat(s)"}catch(e:Exception){e.localizedMessage?:"Deezer indisponible"};runOnUiThread{if(!isFinishing&&!isDestroyed)setStatus(message)}}})
  section("Lecture")
  content.addView(label("VOID Music n’intègre pas encore de lecteur. SoundCloud et Audius exposent des pistes publiques streamables selon leurs conditions et restrictions; les ouvreurs officiels restent l’action disponible ici.",13f,muted,false))
  section("Cache pochettes · "+covers.size()+" Ko / "+covers.maxSize()+" Ko")
  val sizes=arrayOf("1 Mo","4 Mo","8 Mo","16 Mo");val values=intArrayOf(1024,4096,8192,16384)
  val spinner=Spinner(this);spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,sizes)
  spinner.setSelection(values.indexOf(covers.maxSize()).coerceAtLeast(0))
  spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
   override fun onNothingSelected(parent:AdapterView<*>?){}
   override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
    getSharedPreferences("void_music",MODE_PRIVATE).edit().putInt("image_cache_kb",values[position]).apply();covers.resize(values[position])
   }
  }
  content.addView(spinner)
  content.addView(button("VIDER LE CACHE MÉMOIRE",surface2,primary){covers.evictAll();setStatus("Cache des pochettes vidé.")})
  section("Données")
  content.addView(label("Favoris et playlists sont locaux. Les recherches sont transmises aux fournisseurs sélectionnés.",12f,muted,false))
 } private fun beginExport(){val lists=library.playlists();if(lists.isEmpty()){setStatus("Aucune playlist à exporter.");return};exportItems=lists.entries.map{it.key to it.value};startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="application/zip";putExtra(Intent.EXTRA_TITLE,"void-music-playlists.zip")},ZIP)}
 @Deprecated("Platform document picker for API 23 compatibility")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=ZIP||resultCode!=RESULT_OK)return;val uri=data?.data?:return;io.submit{try{contentResolver.openOutputStream(uri)?.use{writeZip(it)}?:error("Fichier inaccessible");runOnUiThread{if(!isFinishing)setStatus("Export terminé · métadonnées uniquement.")}}catch(e:Exception){runOnUiThread{if(!isFinishing)setStatus("Échec export : "+(e.localizedMessage?:"écriture impossible"))}}}}
 private fun writeZip(out:OutputStream){ZipOutputStream(BufferedOutputStream(out)).use{z->
  z.putNextEntry(ZipEntry("playlists.csv"));val csvWriter=BufferedWriter(OutputStreamWriter(z,Charsets.UTF_8))
  csvWriter.write("playlist,provider,title,artist,album,duration_seconds,source_url\n")
  exportItems.forEach{(n,ls)->ls.forEach{t->csvWriter.write(listOf(n,t.provider,t.title,t.artist,t.album,t.duration.toString(),t.link).joinToString(","){csv(it)});csvWriter.write("\n")}}
  csvWriter.flush();z.closeEntry()
  z.putNextEntry(ZipEntry("playlists.json"));val jsonWriter=BufferedWriter(OutputStreamWriter(z,Charsets.UTF_8))
  jsonWriter.write("{\n")
  exportItems.forEachIndexed{index,pair->
   if(index>0)jsonWriter.write(",\n")
   jsonWriter.write(JSONObject.quote(pair.first));jsonWriter.write(": [")
   pair.second.forEachIndexed{trackIndex,track->if(trackIndex>0)jsonWriter.write(",");jsonWriter.write(track.toJson().toString())}
   jsonWriter.write("]")
  }
  jsonWriter.write("\n}\n");jsonWriter.flush();z.closeEntry()
  z.putNextEntry(ZipEntry("README.txt"));z.write("Métadonnées des playlists VOID Music. Aucun audio inclus.\n".toByteArray(Charsets.UTF_8));z.closeEntry()
 }} private fun csv(v:String)=MetadataExport.csv(v)
 private fun open(t:Track){
  try{
   if(t.link.startsWith("https://"))startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(t.link)))
   else setStatus("Lien "+t.provider+" indisponible.")
  }catch(_:Exception){setStatus("Impossible d’ouvrir le lien du fournisseur.")}
 } private fun row(title:String,sub:String,action:()->Unit):LinearLayout{val r=LinearLayout(this).apply{orientation=1;background=rounded(surface,dp(14));setPadding(dp(14),dp(13),dp(14),dp(13));setOnClickListener{action()}};r.addView(label(title,14f,primary,true));r.addView(label(sub,11f,muted,false).apply{setPadding(0,dp(4),0,0)});content.addView(r,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)});return r}
 private fun section(s:String){content.addView(label(s.uppercase(),10f,cyan,true).apply{setPadding(0,dp(19),0,dp(8))})}
 private fun empty(s:String){content.addView(label(s,12f,muted,false).apply{setPadding(dp(3),dp(7),dp(3),dp(12))})}
 private fun button(s:String,c:Int,fg:Int,action:()->Unit)=TextView(this).apply{text=s;textSize=10f;typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;setTextColor(fg);background=rounded(c,dp(11));setPadding(dp(12),dp(13),dp(12),dp(13));setOnClickListener{action()}}
 private fun label(s:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply{text=s;textSize=size;setTextColor(color);if(bold)typeface=Typeface.create("sans-serif",Typeface.BOLD)}
 private fun TextView.ellip()=apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(0,dp(4),0,0)}
 private fun rounded(c:Int,r:Int,stroke:Int?=null,width:Int=0)=GradientDrawable().apply{setColor(c);cornerRadius=r.toFloat();if(stroke!=null)setStroke(width,stroke)}
 private fun setStatus(s:String){status.text=s};private fun duration(s:Int)=if(s<=0)"--:--" else "%d:%02d".format(s/60,s%60);private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 override fun onDestroy(){generation++;search?.cancel(true);api.cancelActiveRequests();io.shutdownNow();imageIo.shutdownNow();covers.evictAll();super.onDestroy()}
}























